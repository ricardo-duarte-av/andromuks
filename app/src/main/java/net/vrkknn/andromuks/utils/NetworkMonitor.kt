package net.vrkknn.andromuks.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import net.vrkknn.andromuks.BuildConfig

/**
 * PHASE 3.1: NetworkMonitor - Detects network changes immediately
 * 
 * Handles all network transition types:
 * - WiFi ↔ WiFi (different networks)
 * - Mobile ↔ Mobile (different carriers/networks)
 * - Offline ↔ WiFi/Mobile
 * - WiFi ↔ Mobile
 * 
 * Uses ConnectivityManager.NetworkCallback to detect changes immediately
 * instead of waiting for ping timeout (reduces delay from 20-65s to 1-3s)
 */
class NetworkMonitor(
    private val context: Context,
    private val onNetworkAvailable: (NetworkType) -> Unit,  // Network became available (or changed type)
    private val onNetworkLost: () -> Unit,                   // Network lost (offline)
    private val onNetworkTypeChanged: (NetworkType, NetworkType) -> Unit  // Network type changed (e.g., WiFi → Mobile)
) {
    private val connectivityManager: ConnectivityManager = 
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastNetworkType: NetworkType? = null
    private var lastNetwork: Network? = null
    
    /**
     * Start monitoring network changes
     */
    fun start() {
        if (networkCallback != null) {
            if (BuildConfig.DEBUG) Log.w("NetworkMonitor", "NetworkMonitor already started")
            return
        }
        
        // Build network request to monitor all network types
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .build()
        
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val networkType = getNetworkType(network)
                val previousType = lastNetworkType
                
                if (BuildConfig.DEBUG) {
                    Log.d("NetworkMonitor", "Network available: $networkType (previous: $previousType)")
                }
                
                // Check if this is a network type change (e.g., WiFi → Mobile, or different WiFi network)
                if (previousType != null && previousType != NetworkType.NONE && previousType != networkType) {
                    Log.i("NetworkMonitor", "Network type changed: $previousType → $networkType")
                    onNetworkTypeChanged(previousType, networkType)
                }
                
                lastNetworkType = networkType
                lastNetwork = network
                
                // Network is available - trigger reconnection if needed
                onNetworkAvailable(networkType)
            }
            
            override fun onLost(network: Network) {
                if (BuildConfig.DEBUG) {
                    Log.w("NetworkMonitor", "Network lost: $network (was: $lastNetworkType)")
                }
                
                // Check if this was the active network
                if (network == lastNetwork) {
                    lastNetworkType = NetworkType.NONE
                    lastNetwork = null
                    onNetworkLost()
                }
            }
            
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                val networkType = getNetworkType(networkCapabilities)
                val previousType = lastNetworkType
                
                // Check if network gained internet access or validation
                val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val isValidated = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                
                if (BuildConfig.DEBUG) {
                    Log.d("NetworkMonitor", "Network capabilities changed: $networkType (hasInternet: $hasInternet, isValidated: $isValidated)")
                }
                
                // If network gained internet access or validation, and we're not already tracking this network
                if (hasInternet && isValidated) {
                    if (previousType != networkType && previousType != null) {
                        // Network type changed (e.g., WiFi → Mobile, or different WiFi network)
                        Log.i("NetworkMonitor", "Network type changed via capabilities: $previousType → $networkType")
                        onNetworkTypeChanged(previousType, networkType)
                    } else if (previousType == null || previousType == NetworkType.NONE) {
                        // Network became available (from offline)
                        if (BuildConfig.DEBUG) {
                            Log.d("NetworkMonitor", "Network became available via capabilities: $networkType")
                        }
                        onNetworkAvailable(networkType)
                    }
                    
                    lastNetworkType = networkType
                    lastNetwork = network
                } else if (!hasInternet || !isValidated) {
                    // Network lost internet access or validation
                    if (network == lastNetwork) {
                        if (BuildConfig.DEBUG) {
                            Log.w("NetworkMonitor", "Network lost internet access or validation: $networkType")
                        }
                        // Don't call onNetworkLost() here - wait for onLost() to be called
                        // This handles cases where network is still connected but has no internet
                    }
                }
            }
            
            override fun onUnavailable() {
                if (BuildConfig.DEBUG) {
                    Log.w("NetworkMonitor", "Network unavailable")
                }
                lastNetworkType = NetworkType.NONE
                lastNetwork = null
                onNetworkLost()
            }
        }
        
        try {
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
            if (BuildConfig.DEBUG) Log.d("NetworkMonitor", "NetworkMonitor started - monitoring network changes")
        } catch (e: Exception) {
            Log.e("NetworkMonitor", "Failed to register network callback", e)
            networkCallback = null
        }
    }
    
    /**
     * Stop monitoring network changes
     */
    fun stop() {
        networkCallback?.let { callback ->
            try {
                connectivityManager.unregisterNetworkCallback(callback)
                if (BuildConfig.DEBUG) Log.d("NetworkMonitor", "NetworkMonitor stopped")
            } catch (e: Exception) {
                Log.e("NetworkMonitor", "Failed to unregister network callback", e)
            }
            networkCallback = null
        }
        lastNetworkType = null
        lastNetwork = null
    }
    
    /**
     * Get current network type
     */
    fun getCurrentNetworkType(): NetworkType {
        val activeNetwork = connectivityManager.activeNetwork ?: return NetworkType.NONE
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return NetworkType.NONE
        return getNetworkType(capabilities)
    }
    
    /**
     * Get network type from Network object
     */
    private fun getNetworkType(network: Network): NetworkType {
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return NetworkType.NONE
        return getNetworkType(capabilities)
    }
    
    /**
     * Get network type from NetworkCapabilities
     */
    private fun getNetworkType(capabilities: NetworkCapabilities): NetworkType {
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkType.VPN
            else -> NetworkType.OTHER
        }
    }
    
    /**
     * Network type enumeration (matches WebSocketService.NetworkType)
     */
    enum class NetworkType {
        NONE,
        WIFI,
        CELLULAR,
        ETHERNET,
        VPN,
        OTHER
    }
}

