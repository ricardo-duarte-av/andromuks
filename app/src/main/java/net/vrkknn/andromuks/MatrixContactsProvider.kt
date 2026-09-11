package net.vrkknn.andromuks

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.util.Log
import net.vrkknn.andromuks.BuildConfig

/**
 * ContentProvider for Matrix contacts custom MIME type
 * 
 * This allows Android to recognize Matrix contacts and show "Send Matrix message" actions.
 * The custom MIME type is: vnd.android.cursor.item/vnd.net.vrkknn.andromuks.matrix.user
 */
class MatrixContactsProvider : ContentProvider() {

    companion object {
        private const val TAG = "MatrixContactsProvider"

        // Per-flavor authority (see productFlavors in app/build.gradle.kts). Not `const` because
        // BuildConfig fields are Java static finals, not Kotlin compile-time constants.
        private val AUTHORITY = BuildConfig.CONTACTS_AUTHORITY
        private const val MATRIX_USERS = 1
        private const val MATRIX_CALLS = 2
        private const val MATRIX_VIDEO_CALLS = 3

        // Custom MIME type for Matrix user contacts
        const val MIME_TYPE_MATRIX_USER = "vnd.android.cursor.item/vnd.net.vrkknn.andromuks.matrix.user"

        // Call actions on the contact card. Each MIME type is a separate row the Contacts app
        // renders from res/xml/contacts.xml, and a separate intent MainActivity routes to a call
        // rather than to the profile sheet.
        const val MIME_TYPE_MATRIX_CALL = "vnd.android.cursor.item/vnd.net.vrkknn.andromuks.matrix.call"
        const val MIME_TYPE_MATRIX_VIDEO_CALL = "vnd.android.cursor.item/vnd.net.vrkknn.andromuks.matrix.videocall"

        /** True for any MIME type this app puts on a contact — all of them carry `matrix:u/…` in DATA1. */
        fun isMatrixContactMimeType(mimeType: String?): Boolean = mimeType == MIME_TYPE_MATRIX_USER ||
            mimeType == MIME_TYPE_MATRIX_CALL ||
            mimeType == MIME_TYPE_MATRIX_VIDEO_CALL

        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "users", MATRIX_USERS)
            addURI(AUTHORITY, "users/#", MATRIX_USERS)
            addURI(AUTHORITY, "calls", MATRIX_CALLS)
            addURI(AUTHORITY, "calls/#", MATRIX_CALLS)
            addURI(AUTHORITY, "videocalls", MATRIX_VIDEO_CALLS)
            addURI(AUTHORITY, "videocalls/#", MATRIX_VIDEO_CALLS)
        }
    }

    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "query called with URI: $uri")
        }

        return when (uriMatcher.match(uri)) {
            MATRIX_USERS, MATRIX_CALLS, MATRIX_VIDEO_CALLS -> {
                // Return empty cursor - actual data is in ContactsContract
                // This provider exists to register the custom MIME type
                MatrixCursor(arrayOf("_id", "data1", "data2", "data3")).apply {
                    addRow(arrayOf<Any?>(0, "", "", ""))
                }
            }

            else -> null
        }
    }

    override fun getType(uri: Uri): String? = when (uriMatcher.match(uri)) {
        MATRIX_USERS -> MIME_TYPE_MATRIX_USER
        MATRIX_CALLS -> MIME_TYPE_MATRIX_CALL
        MATRIX_VIDEO_CALLS -> MIME_TYPE_MATRIX_VIDEO_CALL
        else -> null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        // Not supported - contacts are created via ContactsContract
        return null
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        // Not supported - contacts are deleted via ContactsContract
        return 0
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        // Not supported - contacts are updated via ContactsContract
        return 0
    }
}
