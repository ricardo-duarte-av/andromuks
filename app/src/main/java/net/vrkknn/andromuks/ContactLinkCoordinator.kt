package net.vrkknn.andromuks

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Links a Matrix contact to an existing phone contact, so *Alice +351912345678* and
 * *Alice @alice:matrix.org* are one person rather than two entries in the address book.
 *
 * **Why aggregation exceptions rather than writing our rows onto their contact.**
 * [ContactsSyncService.mergeWithExistingContact] inserts our custom-MIME rows directly onto a
 * RawContact owned by another account (a Google one, typically). Three things go wrong with that:
 *
 * 1. **The rows would not render.** The Contacts app resolves a custom MIME type through the
 *    `ContactsAccountType` XML of the row's *owning* account. Our `res/xml/contacts.xml` is
 *    registered for `net.vrkknn.andromuks.matrix`, so a Matrix row sitting on a Google RawContact
 *    has no declared icon or labels — "Matrix video call" has nothing to draw it.
 * 2. **Their sync adapter owns those rows** and reconciles them against its own server; we cannot
 *    clean up reliably, because `removeContact` only deletes RawContacts under our account.
 * 3. **It cannot be undone** without hunting rows on a contact we do not own.
 *
 * Keeping our own RawContact and telling the provider the two describe the same person avoids all
 * three: our rows stay where our account type applies, the Contacts app aggregates the pair into one
 * card showing both the phone number and the Matrix actions, and unlinking is one more write.
 *
 * Automatic matching is deliberately absent. [ContactsSyncService.findExistingContactByPhoneOrEmail]
 * needs a phone number or email, and Matrix cannot tell us another user's — so linking is always the
 * user's explicit choice, never a guess on a display name.
 */
internal class ContactLinkCoordinator(private val context: Context) {

    private val syncService by lazy {
        ContactsSyncService(context, accountName = ACCOUNT_NAME, accountType = ACCOUNT_TYPE)
    }

    /**
     * Aggregate our contact for [userId] with the RawContact behind [pickedContactUri] (what
     * `ActivityResultContracts.PickContact` hands back).
     *
     * Returns false when we have no contact for that user yet, the pick resolved to nothing, or the
     * provider refused the write — the caller shows the failure; nothing is left half-linked.
     */
    suspend fun linkToPickedContact(userId: String, pickedContactUri: Uri): Boolean = withContext(Dispatchers.IO) {
        val ourRawContactId = syncService.getRawContactId(userId)
        if (ourRawContactId == null) {
            Androlog("Contacts", "Link aborted: no Matrix contact exists for $userId")
            return@withContext false
        }
        val targetRawContactId = resolveForeignRawContactId(pickedContactUri)
        if (targetRawContactId == null) {
            Androlog("Contacts", "Link aborted: no foreign raw contact behind the picked contact")
            return@withContext false
        }
        if (!writeAggregationException(ourRawContactId, targetRawContactId, ContactsContract.AggregationExceptions.TYPE_KEEP_TOGETHER)) {
            return@withContext false
        }
        // A display cache, not the source of truth: the exception is what aggregates the contacts.
        // Stored as a lookup key because raw-contact ids do not survive a Contacts restore, and on
        // our own RawContact so it is deleted with the contact and can never outlive the link.
        storeLinkedLookupKey(ourRawContactId, lookupKeyOf(pickedContactUri))
        true
    }

    /** Undo a link. Uses KEEP_SEPARATE, not AUTOMATIC, or the provider may simply re-aggregate them. */
    suspend fun unlink(userId: String): Boolean = withContext(Dispatchers.IO) {
        val ourRawContactId = syncService.getRawContactId(userId) ?: return@withContext false
        val linkedKey = readLinkedLookupKey(ourRawContactId) ?: return@withContext false
        val targetRawContactId = resolveRawContactIdForLookupKey(linkedKey)
        if (targetRawContactId != null) {
            writeAggregationException(ourRawContactId, targetRawContactId, ContactsContract.AggregationExceptions.TYPE_KEEP_SEPARATE)
        }
        storeLinkedLookupKey(ourRawContactId, null)
        true
    }

    /** The lookup key of the phone contact [userId] is linked to, or null. */
    suspend fun getLinkedLookupKey(userId: String): String? = withContext(Dispatchers.IO) {
        val ourRawContactId = syncService.getRawContactId(userId) ?: return@withContext null
        readLinkedLookupKey(ourRawContactId)
    }

    /** The display name of the linked contact, for the button label. Null when not linked. */
    suspend fun getLinkedDisplayName(userId: String): String? = withContext(Dispatchers.IO) {
        val key = getLinkedLookupKey(userId) ?: return@withContext null
        val uri = ContactsContract.Contacts.getLookupUri(0L, key) ?: return@withContext null
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.Contacts.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
    }

    /**
     * The RawContact to aggregate against: one belonging to any account but ours, preferring one
     * that carries a phone number, since that is the row the user is really trying to unify with.
     */
    private fun resolveForeignRawContactId(contactUri: Uri): Long? {
        val contactId = runCatching {
            context.contentResolver.query(contactUri, arrayOf(ContactsContract.Contacts._ID), null, null, null)
                ?.use { if (it.moveToFirst()) it.getLong(0) else null }
        }.getOrNull() ?: return null

        val candidates = mutableListOf<Long>()
        runCatching {
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts._ID, ContactsContract.RawContacts.ACCOUNT_TYPE),
                "${ContactsContract.RawContacts.CONTACT_ID} = ?",
                arrayOf(contactId.toString()),
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getString(1) != ACCOUNT_TYPE) candidates.add(cursor.getLong(0))
                }
            }
        }
        if (candidates.isEmpty()) return null
        return candidates.firstOrNull { hasPhoneRow(it) } ?: candidates.first()
    }

    private fun hasPhoneRow(rawContactId: Long): Boolean = runCatching {
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data._ID),
            "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(rawContactId.toString(), ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE),
            null,
        )?.use { it.count > 0 }
    }.getOrNull() ?: false

    private fun writeAggregationException(rawContactId1: Long, rawContactId2: Long, type: Int): Boolean = try {
        // The documented convention is ascending ids; AOSP tolerates either order but don't rely on it.
        val values = ContentValues().apply {
            put(ContactsContract.AggregationExceptions.RAW_CONTACT_ID1, minOf(rawContactId1, rawContactId2))
            put(ContactsContract.AggregationExceptions.RAW_CONTACT_ID2, maxOf(rawContactId1, rawContactId2))
            put(ContactsContract.AggregationExceptions.TYPE, type)
        }
        context.contentResolver.update(ContactsContract.AggregationExceptions.CONTENT_URI, values, null, null)
        Androlog("Contacts", "Aggregation type=$type written for raw contacts $rawContactId1 + $rawContactId2")
        true
    } catch (e: Exception) {
        Log.e(TAG, "Could not write the aggregation exception", e)
        Androlog("Contacts", "Aggregation write failed: ${e.javaClass.simpleName}: ${e.message}")
        false
    }

    private fun lookupKeyOf(contactUri: Uri): String? = runCatching {
        context.contentResolver.query(contactUri, arrayOf(ContactsContract.Contacts.LOOKUP_KEY), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    private fun resolveRawContactIdForLookupKey(lookupKey: String): Long? = runCatching {
        val uri = ContactsContract.Contacts.getLookupUri(0L, lookupKey) ?: return null
        val contactId = context.contentResolver
            .query(uri, arrayOf(ContactsContract.Contacts._ID), null, null, null)
            ?.use { if (it.moveToFirst()) it.getLong(0) else null } ?: return null
        context.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts._ID),
            "${ContactsContract.RawContacts.CONTACT_ID} = ? AND ${ContactsContract.RawContacts.ACCOUNT_TYPE} != ?",
            arrayOf(contactId.toString(), ACCOUNT_TYPE),
            null,
        )?.use { if (it.moveToFirst()) it.getLong(0) else null }
    }.getOrNull()

    private fun storeLinkedLookupKey(ourRawContactId: Long, lookupKey: String?) {
        runCatching {
            val values = ContentValues().apply { put(ContactsContract.RawContacts.SYNC2, lookupKey) }
            context.contentResolver.update(
                ContactsContract.RawContacts.CONTENT_URI,
                values,
                "${ContactsContract.RawContacts._ID} = ?",
                arrayOf(ourRawContactId.toString()),
            )
        }.onFailure { Log.e(TAG, "Could not store the contact link", it) }
    }

    private fun readLinkedLookupKey(ourRawContactId: Long): String? = runCatching {
        context.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts.SYNC2),
            "${ContactsContract.RawContacts._ID} = ?",
            arrayOf(ourRawContactId.toString()),
            null,
        )?.use { if (it.moveToFirst()) it.getString(0)?.takeIf { key -> key.isNotBlank() } else null }
    }.getOrNull()

    private companion object {
        const val TAG = "ContactLink"
        const val ACCOUNT_NAME = "Andromuks"
        const val ACCOUNT_TYPE = "net.vrkknn.andromuks.matrix"
    }
}
