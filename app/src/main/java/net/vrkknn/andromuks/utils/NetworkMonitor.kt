package net.vrkknn.andromuks.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log

/**
 * Monitors network connectivity changes and notifies when network becomes available/unavailable
 * 
 * This allows immediate WebSocket reconnection on network changes instead of waiting
 * for ping timeout (which could take 20-65 seconds).
 */
class NetworkMonitor(
    private val context: Context,
    private val onNetworkAvailable: () -> Unit,
    private val onNetworkLost: () -> Unit
) {
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var isCurrentlyConnected = false
    
    companion object {
        private const val TAG = "NetworkMonitor"
    }
    
    /**
     * Start monitoring network changes
     */
    fun startMonitoring() {
        if (networkCallback != null) {
            Log.d(TAG, "Already monitoring network changes")
            return
        }
        
        // Check initial connectivity state
        isCurrentlyConnected = isNetworkAvailable()
        Log.d(TAG, "Initial network state: connected=$isCurrentlyConnected")
        
        // Register network callback for connectivity changes
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Network available: $network")
                if (!isCurrentlyConnected) {
                    isCurrentlyConnected = true
                    Log.i(TAG, "Network connection restored - triggering reconnect")
                    onNetworkAvailable()
                }
            }
            
            override fun onLost(network: Network) {
                Log.d(TAG, "Network lost: $network")
                // Check if we still have other networks available
                if (!isNetworkAvailable()) {
                    isCurrentlyConnected = false
                    Log.w(TAG, "All networks lost - connection unavailable")
                    onNetworkLost()
                } else {
                    Log.d(TAG, "Network lost but other networks still available")
                }
            }
            
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val isValidated = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                
                Log.d(TAG, "Network capabilities changed - Internet: $hasInternet, Validated: $isValidated")
                
                // If network now has validated internet and we weren't connected before, reconnect
                if (hasInternet && isValidated && !isCurrentlyConnected) {
                    isCurrentlyConnected = true
                    Log.i(TAG, "Network validated - triggering reconnect")
                    onNetworkAvailable()
                }
            }
        }
        
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()
        
        try {
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
            Log.d(TAG, "Network monitoring started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
            networkCallback = null
        }
    }
    
    /**
     * Stop monitoring network changes
     */
    fun stopMonitoring() {
        networkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
                Log.d(TAG, "Network monitoring stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister network callback", e)
            }
            networkCallback = null
        }
    }
    
    /**
     * Check if network is currently available
     */
    fun isNetworkAvailable(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
    
    /**
     * Get current network type (WiFi, Cellular, etc.)
     */
    fun getNetworkType(): NetworkType {
        val network = connectivityManager.activeNetwork ?: return NetworkType.NONE
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return NetworkType.NONE
        
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkType.VPN
            else -> NetworkType.OTHER
        }
    }
    
    enum class NetworkType {
        NONE,
        WIFI,
        CELLULAR,
        ETHERNET,
        VPN,
        OTHER
    }
}

