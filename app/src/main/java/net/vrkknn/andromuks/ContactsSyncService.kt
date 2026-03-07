package net.vrkknn.andromuks

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.Context
import android.os.Build
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import android.provider.ContactsContract.RawContacts.DefaultAccount
import android.provider.ContactsContract.RawContacts.DefaultAccount.DefaultAccountAndState
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.vrkknn.andromuks.BuildConfig
import net.vrkknn.andromuks.utils.AvatarUtils
import net.vrkknn.andromuks.utils.IntelligentMediaCache
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Service to sync Matrix users to Android Contacts
 * 
 * This allows Matrix users to appear in the system contacts app and enables
 * "Send Matrix message" actions from other apps (like WhatsApp does).
 * 
 * Features:
 * - Syncs Matrix users from direct messages and rooms
 * - Creates contacts with Matrix user ID as custom MIME type
 * - Handles avatar syncing
 * - Supports incremental updates
 */
class ContactsSyncService(
    private val context: Context,
    private val accountName: String,
    private val accountType: String = "net.vrkknn.andromuks.matrix"
) {
    companion object {
        private const val TAG = "ContactsSyncService"
        
        // Custom MIME type for Matrix user IDs
        // This allows Android to recognize Matrix contacts and show "Send Matrix message" option
        private const val MIME_TYPE_MATRIX_USER = "vnd.android.cursor.item/vnd.net.vrkknn.andromuks.matrix.user"
        
        // Authority for Matrix contacts (matches account type)
        private const val AUTHORITY = "net.vrkknn.andromuks.contacts"
        
        /**
         * Get the default contact account for creating new contacts
         * 
         * Android 14+ requires using the default account when a cloud account is set.
         * This function detects the default account and falls back gracefully.
         * 
         * @return Pair of (accountName, accountType) or (null, null) if no account available
         */
        fun getDefaultContactAccount(context: Context): Pair<String?, String?> {
            // Android 15+ (API 35) provides direct API to get default account
            if (Build.VERSION.SDK_INT >= 35) {
                try {
                    val defaultAccountAndState: DefaultAccountAndState =
                        DefaultAccount.getDefaultAccountForNewContacts(context.contentResolver)
                    
                    // .account is only non-null for STATE_CLOUD or STATE_SIM
                    val account = defaultAccountAndState.account
                    if (account != null) {
                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "Using default contact account: ${account.name} (${account.type})")
                        }
                        return Pair(account.name, account.type)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error getting default contact account", e)
                }
            }
            
            // Fallback: first Google account
            val accountManager = AccountManager.get(context)
            val googleAccount = accountManager.getAccountsByType("com.google").firstOrNull()
            if (googleAccount != null) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Using Google account: ${googleAccount.name}")
                }
                return Pair(googleAccount.name, "com.google")
            }
            
            // Last resort: any non-local syncing account
            val anyAccount = accountManager.accounts.firstOrNull { it.type != "local" }
            if (anyAccount != null) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Using any available account: ${anyAccount.name} (${anyAccount.type})")
                }
                return Pair(anyAccount.name, anyAccount.type)
            }
            
            // No account available - return null (local account)
            // Note: This may fail on Android 14+ if a cloud account is set as default
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "No account available, will attempt to use local account (may fail on Android 14+)")
            }
            return Pair(null, null)
        }
    }

    suspend fun nukeAllMatrixContacts() = withContext(Dispatchers.IO) {
        val deleteUri = ContactsContract.RawContacts.CONTENT_URI.buildUpon()
            .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
            .build()

        val count = context.contentResolver.delete(
            deleteUri,
            "${RawContacts.SYNC1} LIKE ?",
            arrayOf("@%:%")
        )
        Log.d(TAG, "Nuked $count Matrix raw contacts")
    }
    
    /**
     * Sync Matrix users to Android contacts
     * 
     * @param users List of Matrix users to sync (from DMs or room members)
     * @param syncAvatars Whether to sync avatars (can be disabled for performance)
     */
    suspend fun syncContacts(
        users: List<MatrixUser>,
        syncAvatars: Boolean = true
    ) = withContext(Dispatchers.IO) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Starting contact sync for ${users.size} Matrix users")
        }

        try {
            val account = Account(accountName, accountType)
            ensureAccountExists(account)

            var addedCount = 0
            var updatedCount = 0

            for (user in users) {
                try {
                    val rawContactId = getRawContactId(user.userId)

                    if (rawContactId == null) {
                        val existingContactId = findExistingContactByPhoneOrEmail(user.phoneNumber, user.email)

                        if (existingContactId != null) {
                            mergeWithExistingContact(existingContactId, user, syncAvatars)
                            updatedCount++
                            if (BuildConfig.DEBUG) {
                                Log.d(TAG, "Merged Matrix user ${user.userId} with existing contact")
                            }
                        } else {
                            // Each contact gets its own fresh operations list
                            // so rawContactInsertIndex is always 0 inside addContact
                            val ops = mutableListOf<ContentProviderOperation>()
                            addContact(ops, account, user, syncAvatars)
                            if (BuildConfig.DEBUG) {
                                ops.forEachIndexed { index, op ->
                                    Log.d(TAG, "op[$index]: $op")
                                }
                            }
                            if (ops.isNotEmpty()) {
                                context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ArrayList(ops))
                            }
                            addedCount++
                            if (BuildConfig.DEBUG) {
                                Log.d(TAG, "Added contact for ${user.userId}")
                            }
                        }
                    } else {
                        val ops = mutableListOf<ContentProviderOperation>()
                        updateContact(ops, rawContactId, user, syncAvatars)
                        if (ops.isNotEmpty()) {
                            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ArrayList(ops))
                        }
                        updatedCount++
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error syncing contact for user: ${user.userId}", e)
                }
            }

            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Contact sync completed: $addedCount added, $updatedCount updated")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during contact sync", e)
        }
    }
    
    /**
     * Add a new Matrix user as a contact
     */
    private suspend fun addContact(
        operations: MutableList<ContentProviderOperation>,
        account: Account,
        user: MatrixUser,
        syncAvatars: Boolean
    ) {
        val displayName = user.displayName ?: extractUsername(user.userId)
        
        
        // Create raw contact with TYPE_KEEP_TOGETHER
        // This prevents Android from auto-merging with existing contacts
        val rawContactInsertIndex = operations.size
        operations.add(
            ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
                .withValue(RawContacts.ACCOUNT_NAME, account.name)
                .withValue(RawContacts.ACCOUNT_TYPE, account.type)
                .withValue(RawContacts.SOURCE_ID, user.userId) // Store Matrix user ID as source ID
                .withValue(RawContacts.SYNC1, user.userId) // Also store in SYNC1 for backward compatibility    
                .build()
        )
        
        // Add display name
        operations.add(
            ContentProviderOperation.newInsert(Data.CONTENT_URI)
                .withValueBackReference(Data.RAW_CONTACT_ID, rawContactInsertIndex)
                .withValue(Data.MIMETYPE, CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(CommonDataKinds.StructuredName.DISPLAY_NAME, displayName)
                .build()
        )
        
        // Add phone number if provided (enables automatic contact matching)
        if (user.phoneNumber != null && user.phoneNumber.isNotBlank()) {
            operations.add(
                ContentProviderOperation.newInsert(Data.CONTENT_URI)
                    .withValueBackReference(Data.RAW_CONTACT_ID, rawContactInsertIndex)
                    .withValue(Data.MIMETYPE, CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(CommonDataKinds.Phone.NUMBER, user.phoneNumber)
                    .withValue(CommonDataKinds.Phone.TYPE, CommonDataKinds.Phone.TYPE_MOBILE)
                    .build()
            )
        }
        
        // Add email if provided (enables automatic contact matching)
        if (user.email != null && user.email.isNotBlank()) {
            operations.add(
                ContentProviderOperation.newInsert(Data.CONTENT_URI)
                    .withValueBackReference(Data.RAW_CONTACT_ID, rawContactInsertIndex)
                    .withValue(Data.MIMETYPE, CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                    .withValue(CommonDataKinds.Email.DATA, user.email)
                    .withValue(CommonDataKinds.Email.TYPE, CommonDataKinds.Email.TYPE_OTHER)
                    .build()
            )
        }
        
        // Add Matrix user ID as custom MIME type
        // This is what allows Android to recognize this as a Matrix contact
        // Use matrix:u/ URI format so MainActivity can handle it automatically
        val matrixUri = "matrix:u/${user.userId.removePrefix("@")}"
        operations.add(
            ContentProviderOperation.newInsert(Data.CONTENT_URI)
                .withValueBackReference(Data.RAW_CONTACT_ID, rawContactInsertIndex)
                .withValue(Data.MIMETYPE, MatrixContactsProvider.MIME_TYPE_MATRIX_USER)
                .withValue(Data.DATA1, user.userId) // Label 
                .withValue(Data.DATA2, matrixUri) // URI 
                .withValue(Data.DATA3, "Matrix") // Action label
                .build()
        )
                
        // Add avatar if available
        if (syncAvatars && user.avatarUrl != null) {
            val avatarBytes = getAvatarBytes(user.avatarUrl)
            if (avatarBytes != null) {
                operations.add(
                    ContentProviderOperation.newInsert(Data.CONTENT_URI)
                        .withValueBackReference(Data.RAW_CONTACT_ID, rawContactInsertIndex)
                        .withValue(Data.MIMETYPE, CommonDataKinds.Photo.CONTENT_ITEM_TYPE)
                        .withValue(CommonDataKinds.Photo.PHOTO, avatarBytes)
                        .build()
                )
            }
        }
    }
    
    /**
     * Update an existing Matrix contact
     */
    private suspend fun updateContact(
        operations: MutableList<ContentProviderOperation>,
        rawContactId: Long,
        user: MatrixUser,
        syncAvatars: Boolean
    ) {
        val displayName = user.displayName ?: extractUsername(user.userId)
        
        // Update display name
        val nameId = getNameDataId(rawContactId)
        if (nameId != null) {
            operations.add(
                ContentProviderOperation.newUpdate(Data.CONTENT_URI)
                    .withSelection("${Data._ID} = ?", arrayOf(nameId.toString()))
                    .withValue(CommonDataKinds.StructuredName.DISPLAY_NAME, displayName)
                    .build()
            )
        } else {
            // Name doesn't exist, add it
            operations.add(
                ContentProviderOperation.newInsert(Data.CONTENT_URI)
                    .withValue(Data.RAW_CONTACT_ID, rawContactId)
                    .withValue(Data.MIMETYPE, CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(CommonDataKinds.StructuredName.DISPLAY_NAME, displayName)
                    .build()
            )
        }
        
        // Update/add phone number if provided
        if (user.phoneNumber != null && user.phoneNumber.isNotBlank()) {
            val phoneId = getPhoneDataId(rawContactId, user.phoneNumber)
            if (phoneId != null) {
                // Phone exists, update it
                operations.add(
                    ContentProviderOperation.newUpdate(Data.CONTENT_URI)
                        .withSelection("${Data._ID} = ?", arrayOf(phoneId.toString()))
                        .withValue(CommonDataKinds.Phone.NUMBER, user.phoneNumber)
                        .build()
                )
            } else {
                // Phone doesn't exist, add it
                operations.add(
                    ContentProviderOperation.newInsert(Data.CONTENT_URI)
                        .withValue(Data.RAW_CONTACT_ID, rawContactId)
                        .withValue(Data.MIMETYPE, CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                        .withValue(CommonDataKinds.Phone.NUMBER, user.phoneNumber)
                        .withValue(CommonDataKinds.Phone.TYPE, CommonDataKinds.Phone.TYPE_MOBILE)
                        .build()
                )
            }
        }
        
        // Update/add email if provided
        if (user.email != null && user.email.isNotBlank()) {
            val emailId = getEmailDataId(rawContactId, user.email)
            if (emailId != null) {
                // Email exists, update it
                operations.add(
                    ContentProviderOperation.newUpdate(Data.CONTENT_URI)
                        .withSelection("${Data._ID} = ?", arrayOf(emailId.toString()))
                        .withValue(CommonDataKinds.Email.DATA, user.email)
                        .build()
                )
            } else {
                // Email doesn't exist, add it
                operations.add(
                    ContentProviderOperation.newInsert(Data.CONTENT_URI)
                        .withValue(Data.RAW_CONTACT_ID, rawContactId)
                        .withValue(Data.MIMETYPE, CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                        .withValue(CommonDataKinds.Email.DATA, user.email)
                        .withValue(CommonDataKinds.Email.TYPE, CommonDataKinds.Email.TYPE_OTHER)
                        .build()
                )
            }
        }
        
        // Update avatar if available
        if (syncAvatars && user.avatarUrl != null) {
            val avatarBytes = getAvatarBytes(user.avatarUrl)
            val photoId = getPhotoDataId(rawContactId)
            
            if (avatarBytes != null) {
                if (photoId != null) {
                    // Update existing photo
                    operations.add(
                        ContentProviderOperation.newUpdate(Data.CONTENT_URI)
                            .withSelection("${Data._ID} = ?", arrayOf(photoId.toString()))
                            .withValue(CommonDataKinds.Photo.PHOTO, avatarBytes)
                            .build()
                    )
                } else {
                    // Add new photo
                    operations.add(
                        ContentProviderOperation.newInsert(Data.CONTENT_URI)
                            .withValue(Data.RAW_CONTACT_ID, rawContactId)
                            .withValue(Data.MIMETYPE, CommonDataKinds.Photo.CONTENT_ITEM_TYPE)
                            .withValue(CommonDataKinds.Photo.PHOTO, avatarBytes)
                            .build()
                    )
                }
            }
        }
    }
    
    /**
     * Get raw contact ID for a Matrix user ID
     */
    private fun getRawContactId(userId: String): Long? {
        val cursor = context.contentResolver.query(
            RawContacts.CONTENT_URI,
            arrayOf(RawContacts._ID),
            "${RawContacts.SYNC1} = ?",  // removed ACCOUNT_TYPE filter
            arrayOf(userId),
            null
        )
        
        return cursor?.use {
            if (it.moveToFirst()) {
                val id = it.getLong(it.getColumnIndexOrThrow(RawContacts._ID))
                val sync1 = it.getString(it.getColumnIndexOrThrow(RawContacts.SYNC1))
                val type = it.getString(it.getColumnIndexOrThrow(RawContacts.ACCOUNT_TYPE))
                Log.d(TAG, "getRawContactId($userId) → found id=$id sync1=$sync1 accountType=$type")
                id
            } else {
                Log.d(TAG, "getRawContactId($userId) → not found")
                null
            }
        }
    }
    
    /**
     * Get name data ID for a raw contact
     */
    private fun getNameDataId(rawContactId: Long): Long? {
        val cursor = context.contentResolver.query(
            Data.CONTENT_URI,
            arrayOf(Data._ID),
            "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?",
            arrayOf(rawContactId.toString(), CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE),
            null
        )
        
        return cursor?.use {
            if (it.moveToFirst()) {
                it.getLong(it.getColumnIndexOrThrow(Data._ID))
            } else {
                null
            }
        }
    }
    
    /**
     * Get photo data ID for a raw contact
     */
    private fun getPhotoDataId(rawContactId: Long): Long? {
        val cursor = context.contentResolver.query(
            Data.CONTENT_URI,
            arrayOf(Data._ID),
            "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?",
            arrayOf(rawContactId.toString(), CommonDataKinds.Photo.CONTENT_ITEM_TYPE),
            null
        )
        
        return cursor?.use {
            if (it.moveToFirst()) {
                it.getLong(it.getColumnIndexOrThrow(Data._ID))
            } else {
                null
            }
        }
    }
    
    /**
     * Get phone data ID for a raw contact and phone number
     */
    private fun getPhoneDataId(rawContactId: Long, phoneNumber: String): Long? {
        val cursor = context.contentResolver.query(
            Data.CONTENT_URI,
            arrayOf(Data._ID),
            "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ? AND ${CommonDataKinds.Phone.NUMBER} = ?",
            arrayOf(rawContactId.toString(), CommonDataKinds.Phone.CONTENT_ITEM_TYPE, phoneNumber),
            null
        )
        
        return cursor?.use {
            if (it.moveToFirst()) {
                it.getLong(it.getColumnIndexOrThrow(Data._ID))
            } else {
                null
            }
        }
    }
    
    /**
     * Get email data ID for a raw contact and email
     */
    private fun getEmailDataId(rawContactId: Long, email: String): Long? {
        val cursor = context.contentResolver.query(
            Data.CONTENT_URI,
            arrayOf(Data._ID),
            "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ? AND ${CommonDataKinds.Email.DATA} = ?",
            arrayOf(rawContactId.toString(), CommonDataKinds.Email.CONTENT_ITEM_TYPE, email),
            null
        )
        
        return cursor?.use {
            if (it.moveToFirst()) {
                it.getLong(it.getColumnIndexOrThrow(Data._ID))
            } else {
                null
            }
        }
    }
    
    /**
     * Find existing contact by phone number or email
     * Returns the raw contact ID if a match is found
     * This helps identify if a Matrix user should be merged with an existing contact
     */
    fun findExistingContactByPhoneOrEmail(phoneNumber: String?, email: String?): Long? {
        if (phoneNumber == null && email == null) {
            return null
        }
        
        val selection = mutableListOf<String>()
        val selectionArgs = mutableListOf<String>()
        
        if (phoneNumber != null && phoneNumber.isNotBlank()) {
            selection.add("${Data.MIMETYPE} = ? AND ${CommonDataKinds.Phone.NUMBER} = ?")
            selectionArgs.add(CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
            selectionArgs.add(phoneNumber)
        }
        
        if (email != null && email.isNotBlank()) {
            if (selection.isNotEmpty()) {
                selection.add("OR")
            }
            selection.add("${Data.MIMETYPE} = ? AND ${CommonDataKinds.Email.DATA} = ?")
            selectionArgs.add(CommonDataKinds.Email.CONTENT_ITEM_TYPE)
            selectionArgs.add(email)
        }
        
        val cursor = context.contentResolver.query(
            Data.CONTENT_URI,
            arrayOf(Data.RAW_CONTACT_ID),
            selection.joinToString(" "),
            selectionArgs.toTypedArray(),
            null
        )
        
        return cursor?.use {
            if (it.moveToFirst()) {
                val rawContactId = it.getLong(it.getColumnIndexOrThrow(Data.RAW_CONTACT_ID))
                // Check if this contact is NOT already a Matrix contact (to avoid duplicates)
                val isMatrixContact = getRawContactIdByRawContactId(rawContactId) != null
                if (!isMatrixContact) {
                    rawContactId
                } else {
                    null
                }
            } else {
                null
            }
        }
    }
    
    /**
     * Check if a raw contact ID belongs to a Matrix contact
     */
    private fun getRawContactIdByRawContactId(rawContactId: Long): String? {
        val cursor = context.contentResolver.query(
            RawContacts.CONTENT_URI,
            arrayOf(RawContacts.SYNC1),
            "${RawContacts._ID} = ?",        // query by _ID, not SYNC1
            arrayOf(rawContactId.toString()), // single arg
            null
        )
        
        return cursor?.use {
            if (it.moveToFirst()) it.getString(it.getColumnIndexOrThrow(RawContacts.SYNC1)) else null
        }
    }
    
    /**
     * Merge Matrix contact with existing contact
     * This adds Matrix data to an existing contact instead of creating a new one
     */
    suspend fun mergeWithExistingContact(
        existingRawContactId: Long,
        user: MatrixUser,
        syncAvatars: Boolean
    ) = withContext(Dispatchers.IO) {
        val operations = mutableListOf<ContentProviderOperation>()
        
        // Add Matrix user ID as custom MIME type to existing contact
        val matrixUri = "matrix:u/${user.userId.removePrefix("@")}"
        operations.add(
            ContentProviderOperation.newInsert(Data.CONTENT_URI)
                .withValue(Data.RAW_CONTACT_ID, existingRawContactId)
                .withValue(Data.MIMETYPE, MatrixContactsProvider.MIME_TYPE_MATRIX_USER)
                .withValue(Data.DATA1, matrixUri)
                .withValue(Data.DATA2, "Matrix")
                .withValue(Data.DATA3, "Send Matrix message")
                .withValue(Data.DATA4, user.userId)
                .build()
        )   
        
        // Add avatar if available
        if (syncAvatars && user.avatarUrl != null) {
            val avatarBytes = getAvatarBytes(user.avatarUrl)
            if (avatarBytes != null) {
                // Only add if contact doesn't already have a photo
                val photoId = getPhotoDataId(existingRawContactId)
                if (photoId == null) {
                    operations.add(
                        ContentProviderOperation.newInsert(Data.CONTENT_URI)
                            .withValue(Data.RAW_CONTACT_ID, existingRawContactId)
                            .withValue(Data.MIMETYPE, CommonDataKinds.Photo.CONTENT_ITEM_TYPE)
                            .withValue(CommonDataKinds.Photo.PHOTO, avatarBytes)
                            .build()
                    )
                }
            }
        }
        
        try {
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ArrayList(operations))
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Merged Matrix contact with existing contact: ${user.userId}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error merging contact", e)
        }
    }
    
    /**
     * Ensure the sync account exists
     */
    private fun ensureAccountExists(account: Account) {
        val accountManager = android.accounts.AccountManager.get(context)
        val accounts = accountManager.getAccountsByType(accountType)
        
        if (accounts.none { it.name == account.name }) {
            accountManager.addAccountExplicitly(account, null, null)
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Created sync account: ${account.name}")
            }
        }
    }
    
    /**
     * Extract username from Matrix user ID (@username:server.com -> username)
     */
    private fun extractUsername(userId: String): String {
        return userId.removePrefix("@").substringBefore(":")
    }
    
    /**
     * Get avatar as byte array for contact photo
     */
    private suspend fun getAvatarBytes(avatarUrl: String): ByteArray? {
        return try {
            val avatarFile = IntelligentMediaCache.getCachedFile(context, avatarUrl)
            if (avatarFile != null && avatarFile.exists()) {
                val bitmap = BitmapFactory.decodeFile(avatarFile.absolutePath)
                if (bitmap != null) {
                    // Convert bitmap to byte array (PNG format)
                    val outputStream = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                    outputStream.toByteArray()
                } else {
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading avatar bytes for contact", e)
            null
        }
    }
    
    /**
     * Remove a Matrix user from contacts
     */
    suspend fun removeContact(userId: String) = withContext(Dispatchers.IO) {
        val rawContactId = getRawContactId(userId)
        if (rawContactId != null) {
            val operations = mutableListOf<ContentProviderOperation>()
            operations.add(
                ContentProviderOperation.newDelete(RawContacts.CONTENT_URI)
                    .withSelection("${RawContacts._ID} = ?", arrayOf(rawContactId.toString()))
                    .build()
            )
            
            try {
                context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ArrayList(operations))
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Removed contact for Matrix user: $userId")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error removing contact for user: $userId", e)
            }
        }
    }
    
    /**
     * Clear all Matrix contacts
     */
    suspend fun clearAllContacts() = withContext(Dispatchers.IO) {
        val operations = mutableListOf<ContentProviderOperation>()
        val cursor = context.contentResolver.query(
            RawContacts.CONTENT_URI,
            arrayOf(RawContacts._ID),
            "${RawContacts.ACCOUNT_TYPE} = ?",
            arrayOf(accountType),
            null
        )
        
        cursor?.use {
            while (it.moveToNext()) {
                val rawContactId = it.getLong(it.getColumnIndexOrThrow(RawContacts._ID))
                operations.add(
                    ContentProviderOperation.newDelete(RawContacts.CONTENT_URI)
                        .withSelection("${RawContacts._ID} = ?", arrayOf(rawContactId.toString()))
                        .build()
                )
            }
        }
        
        if (operations.isNotEmpty()) {
            try {
                context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ArrayList(operations))
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Cleared ${operations.size} Matrix contacts")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing contacts", e)
            }
        }
    }
}

/**
 * Data class representing a Matrix user for contact syncing
 */
data class MatrixUser(
    val userId: String,
    val displayName: String?,
    val avatarUrl: String?,
    val phoneNumber: String? = null,  // Phone number for contact matching (e.g., "+351919100753")
    val email: String? = null          // Email for contact matching (e.g., "thalitasantos_877@gmail.com")
)



