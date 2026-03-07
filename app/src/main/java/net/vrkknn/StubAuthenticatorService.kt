package net.vrkknn.andromuks

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.NetworkErrorException
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder

class StubAuthenticatorService : Service() {

    private lateinit var authenticator: StubAuthenticator

    override fun onCreate() {
        authenticator = StubAuthenticator(this)
    }

    override fun onBind(intent: Intent?): IBinder {
        return authenticator.iBinder
    }

    class StubAuthenticator(context: Context) : AbstractAccountAuthenticator(context) {
        override fun addAccount(r: AccountAuthenticatorResponse, accountType: String, authTokenType: String?,
            requiredFeatures: Array<String>?, options: Bundle?) = null
        override fun confirmCredentials(r: AccountAuthenticatorResponse, account: Account, options: Bundle?) = null
        override fun editProperties(r: AccountAuthenticatorResponse, accountType: String) = null
        override fun getAuthToken(r: AccountAuthenticatorResponse, account: Account, authTokenType: String, options: Bundle?) = null
        override fun getAuthTokenLabel(authTokenType: String) = null
        override fun hasFeatures(r: AccountAuthenticatorResponse, account: Account, features: Array<String>) = null
        override fun updateCredentials(r: AccountAuthenticatorResponse, account: Account, authTokenType: String?, options: Bundle?) = null
    }
}