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
        private const val AUTHORITY = "net.vrkknn.andromuks.matrix.contacts"
        private const val MATRIX_USERS = 1
        
        // Custom MIME type for Matrix user contacts
        const val MIME_TYPE_MATRIX_USER = "vnd.android.cursor.item/vnd.net.vrkknn.andromuks.matrix.user"
        
        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "users", MATRIX_USERS)
            addURI(AUTHORITY, "users/#", MATRIX_USERS)
        }
    }
    
    override fun onCreate(): Boolean {
        return true
    }
    
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "query called with URI: $uri")
        }
        
        return when (uriMatcher.match(uri)) {
            MATRIX_USERS -> {
                // Return empty cursor - actual data is in ContactsContract
                // This provider exists to register the custom MIME type
                MatrixCursor(arrayOf("_id", "data1", "data2", "data3")).apply {
                    addRow(arrayOf(0, "", "", ""))
                }
            }
            else -> null
        }
    }
    
    override fun getType(uri: Uri): String? {
        return when (uriMatcher.match(uri)) {
            MATRIX_USERS -> MIME_TYPE_MATRIX_USER
            else -> null
        }
    }
    
    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        // Not supported - contacts are created via ContactsContract
        return null
    }
    
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        // Not supported - contacts are deleted via ContactsContract
        return 0
    }
    
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int {
        // Not supported - contacts are updated via ContactsContract
        return 0
    }
}

