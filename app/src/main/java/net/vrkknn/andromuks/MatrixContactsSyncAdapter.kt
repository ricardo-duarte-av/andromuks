package net.vrkknn.andromuks

import android.accounts.Account
import android.app.Service
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.Context
import android.content.Intent
import android.content.SyncResult
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import net.vrkknn.andromuks.BuildConfig

class MatrixContactsSyncAdapter(
    context: Context,
    autoInitialize: Boolean
) : AbstractThreadedSyncAdapter(context, autoInitialize) {

    companion object {
        private const val TAG = "MatrixContactsSync"
    }

    override fun onPerformSync(
        account: Account,
        extras: Bundle,
        authority: String,
        provider: ContentProviderClient,
        syncResult: SyncResult
    ) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "onPerformSync called for account: ${account.name}")
        }
        // No-op — contacts are created on-demand via ContactsSyncService
    }
}

// The Service wrapper Android actually instantiates
class MatrixContactsSyncAdapterService : Service() {

    private lateinit var syncAdapter: MatrixContactsSyncAdapter

    override fun onCreate() {
        syncAdapter = MatrixContactsSyncAdapter(applicationContext, true)
    }

    override fun onBind(intent: Intent?): IBinder {
        return syncAdapter.syncAdapterBinder
    }
}