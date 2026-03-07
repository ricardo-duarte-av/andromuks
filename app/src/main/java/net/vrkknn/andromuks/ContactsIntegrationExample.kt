package net.vrkknn.andromuks

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.vrkknn.andromuks.BuildConfig

/**
 * Example integration of ContactsSyncService with AppViewModel
 * 
 * This shows how to sync Matrix users to Android contacts so they appear
 * in the system contacts app and enable "Send Matrix message" actions.
 * 
 * USAGE:
 * 1. Call syncDirectMessageContacts() to sync users from DMs
 * 2. Call syncRoomMemberContacts() to sync users from specific rooms
 * 3. Call syncAllKnownUsers() to sync all users the app knows about
 * 
 * The contacts will appear in Android's contacts app and other apps can
 * offer "Send Matrix message" as an option (like WhatsApp does).
 */
class ContactsIntegrationExample(
    private val context: Context,
    private val appViewModel: AppViewModel,
    private val homeserverUrl: String
) {
    companion object {
        private const val TAG = "ContactsIntegration"
    }
    
    private val contactsSyncService = ContactsSyncService(
        context = context,
        accountName = appViewModel.currentUserId ?: "matrix_user",
        accountType = "net.vrkknn.andromuks.matrix"
    )
    
    /**
     * Sync contacts from direct messages only
     * This is the most common use case - sync users you have DMs with
     */
    fun syncDirectMessageContacts() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val directMessageRooms = appViewModel.allRooms.filter { it.isDirectMessage }
                val users = mutableSetOf<MatrixUser>()
                
                for (room in directMessageRooms) {
                    // Get the other user in the DM (not the current user)
                    val members = appViewModel.getMemberMap(room.id)
                    for ((userId, profile) in members) {
                        // Skip current user
                        if (userId == appViewModel.currentUserId) {
                            continue
                        }
                        
                        // Only add users with valid Matrix IDs
                        if (userId.startsWith("@") && userId.contains(":")) {
                            // TODO: If you have access to phone/email from Matrix account data or 3PIDs,
                            // pass them here to enable automatic contact matching:
                            // phoneNumber = getPhoneFromAccountData(userId),
                            // email = getEmailFromAccountData(userId)
                            users.add(
                                MatrixUser(
                                    userId = userId,
                                    displayName = profile.displayName,
                                    avatarUrl = profile.avatarUrl,
                                    phoneNumber = null, // TODO: Extract from Matrix account data if available
                                    email = null        // TODO: Extract from Matrix account data if available
                                )
                            )
                        }
                    }
                }
                
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Syncing ${users.size} contacts from direct messages")
                }
                
                contactsSyncService.syncContacts(users.toList(), syncAvatars = true)
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing direct message contacts", e)
            }
        }
    }
    
    /**
     * Sync contacts from a specific room's members
     * Useful for syncing users from important rooms
     */
    fun syncRoomMemberContacts(roomId: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val members = appViewModel.getMemberMap(roomId)
                val users = mutableListOf<MatrixUser>()
                
                for ((userId, profile) in members) {
                    // Skip current user
                    if (userId == appViewModel.currentUserId) {
                        continue
                    }
                    
                    // Only add users with valid Matrix IDs
                    if (userId.startsWith("@") && userId.contains(":")) {
                        users.add(
                            MatrixUser(
                                userId = userId,
                                displayName = profile.displayName,
                                avatarUrl = profile.avatarUrl
                            )
                        )
                    }
                }
                
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Syncing ${users.size} contacts from room: $roomId")
                }
                
                contactsSyncService.syncContacts(users, syncAvatars = true)
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing room member contacts", e)
            }
        }
    }
    
    /**
     * Sync all known users from profile cache
     * This syncs all users the app has profile data for
     */
    fun syncAllKnownUsers() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val allProfiles = ProfileCache.getAllGlobalProfiles()
                val users = mutableListOf<MatrixUser>()
                
                for ((userId, entry) in allProfiles) {
                    // Skip current user
                    if (userId == appViewModel.currentUserId) {
                        continue
                    }
                    
                    // Only add users with valid Matrix IDs
                    if (userId.startsWith("@") && userId.contains(":")) {
                        users.add(
                            MatrixUser(
                                userId = userId,
                                displayName = entry.profile.displayName,
                                avatarUrl = entry.profile.avatarUrl
                            )
                        )
                    }
                }
                
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Syncing ${users.size} contacts from all known users")
                }
                
                contactsSyncService.syncContacts(users, syncAvatars = true)
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing all known users", e)
            }
        }
    }
    
    /**
     * Remove a contact when user is removed from rooms
     */
    fun removeContact(userId: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                contactsSyncService.removeContact(userId)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing contact", e)
            }
        }
    }
    
    /**
     * Clear all Matrix contacts (useful when logging out)
     */
    fun clearAllContacts() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                contactsSyncService.clearAllContacts()
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing contacts", e)
            }
        }
    }
}

