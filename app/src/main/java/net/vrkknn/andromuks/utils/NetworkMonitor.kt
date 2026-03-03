package net.vrkknn.andromuks.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import net.vrkknn.andromuks.BuildConfig

/**
 * NetworkMonitor - Simple network state observer
 * 
 * Reports meaningful network changes only:
 * - Offline → Online (network became available)
 * - Online → Offline (network lost)
 * - Network type changed (WiFi ↔ Mobile, etc.)
 * - Network identity changed (different WiFi AP)
 * 
 * Does NOT report transient blips or validation changes on the same network.
 * WebSocketService decides what to do based on its own connection state.
 */
class NetworkMonitor(
    private val context: Context,
    private val onNetworkAvailable: (NetworkType) -> Unit,  // Network became available (offline → online)
    private val onNetworkLost: () -> Unit,                   // Network lost (online → offline)
    private val onNetworkTypeChanged: (NetworkType, NetworkType) -> Unit,  // Network type changed (e.g., WiFi → Mobile)
    private val onNetworkIdentityChanged: (NetworkType, String?, NetworkType, String?) -> Unit  // Network identity changed (e.g., WiFi AP Alpha → WiFi AP Beta)
) {
    private val connectivityManager: ConnectivityManager = 
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager: WifiManager? = 
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    
    // Track current network state
    private var currentNetworkType: NetworkType = NetworkType.NONE
    private var currentNetworkIdentity: String? = null  // SSID for WiFi
    private var hasValidatedNetwork: Boolean = false
    
    /**
     * Start monitoring network changes
     */
    fun start() {
        if (networkCallback != null) {
            if (BuildConfig.DEBUG) Log.w("NetworkMonitor", "NetworkMonitor already started")
            return
        }
        
        // Initialize current state
        updateCurrentNetworkState()
        
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
                val networkIdentity = getNetworkIdentity(networkType, network)
                
                if (BuildConfig.DEBUG) {
                    Log.d("NetworkMonitor", "Network available: $networkType (identity: $networkIdentity)")
                }
                
                // Check if this is a meaningful change
                val previousType = currentNetworkType
                val previousIdentity = currentNetworkIdentity
                val wasOffline = previousType == NetworkType.NONE
                
                // Update state
                val oldType = currentNetworkType
                val oldIdentity = currentNetworkIdentity
                currentNetworkType = networkType
                currentNetworkIdentity = networkIdentity
                hasValidatedNetwork = true
                
                // Only report if this is a meaningful change
                if (wasOffline) {
                    // Offline → Online: Always report
                    if (BuildConfig.DEBUG) {
                        Log.i("NetworkMonitor", "Network became available: $networkType")
                    }
                    onNetworkAvailable(networkType)
                } else if (oldType != networkType) {
                    // Network type changed: Report as type change
                    if (BuildConfig.DEBUG) {
                        Log.i("NetworkMonitor", "Network type changed: $oldType → $networkType")
                    }
                    onNetworkTypeChanged(oldType, networkType)
                } else if (networkType == NetworkType.WIFI && oldIdentity != networkIdentity) {
                    // Same type (WiFi) but identity changed (different AP)
                    if (BuildConfig.DEBUG) {
                        Log.i("NetworkMonitor", "Network identity changed: $oldType ($oldIdentity) → $networkType ($networkIdentity)")
                    }
                    onNetworkIdentityChanged(oldType, oldIdentity, networkType, networkIdentity)
                } else {
                    // Same network type and identity - don't report (just a validation blip)
                    if (BuildConfig.DEBUG) {
                        Log.d("NetworkMonitor", "Network available but no meaningful change (type: $networkType, identity: $networkIdentity) - not reporting")
                    }
                }
            }
            
            override fun onLost(network: Network) {
                if (BuildConfig.DEBUG) {
                    Log.w("NetworkMonitor", "Network lost: $network")
                }
                
                // Check if we still have other networks
                updateCurrentNetworkState()
                
                if (currentNetworkType == NetworkType.NONE) {
                    // No networks available - we're offline
                    val previousType = currentNetworkType
                    currentNetworkType = NetworkType.NONE
                    currentNetworkIdentity = null
                    hasValidatedNetwork = false
                    
                    if (previousType != NetworkType.NONE) {
                        if (BuildConfig.DEBUG) {
                            Log.i("NetworkMonitor", "Network lost - going offline")
                        }
                        onNetworkLost()
                    }
                } else {
                    // Another network is available - report as type change
                    val newType = currentNetworkType
                    val newIdentity = currentNetworkIdentity
                    
                    // Don't report if it's the same type and identity (just a transient blip)
                    // This handles cases where WiFi temporarily loses validation but comes back
                    if (BuildConfig.DEBUG) {
                        Log.d("NetworkMonitor", "Network lost but other network available: $newType - not reporting")
                    }
                }
            }
            
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                val networkType = getNetworkType(networkCapabilities)
                val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val isValidated = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                
                if (BuildConfig.DEBUG) {
                    Log.d("NetworkMonitor", "Network capabilities changed: $networkType (hasInternet: $hasInternet, isValidated: $isValidated)")
                }
                
                // Only report if validation status changed meaningfully
                val wasValidated = hasValidatedNetwork
                val nowValidated = hasInternet && isValidated
                
                if (!wasValidated && nowValidated) {
                    // Network just became validated - treat as "available"
                    val networkIdentity = getNetworkIdentity(networkType, network)
                    val previousType = currentNetworkType
                    val previousIdentity = currentNetworkIdentity
                    
                    currentNetworkType = networkType
                    currentNetworkIdentity = networkIdentity
                    hasValidatedNetwork = true
                    
                    if (previousType == NetworkType.NONE) {
                        // Offline → Online
                        if (BuildConfig.DEBUG) {
                            Log.i("NetworkMonitor", "Network validated - became available: $networkType")
                        }
                        onNetworkAvailable(networkType)
                    } else if (previousType != networkType) {
                        // Type changed
                        if (BuildConfig.DEBUG) {
                            Log.i("NetworkMonitor", "Network validated - type changed: $previousType → $networkType")
                        }
                        onNetworkTypeChanged(previousType, networkType)
                    } else if (networkType == NetworkType.WIFI && previousIdentity != networkIdentity) {
                        // Identity changed
                        if (BuildConfig.DEBUG) {
                            Log.i("NetworkMonitor", "Network validated - identity changed: $previousType ($previousIdentity) → $networkType ($networkIdentity)")
                        }
                        onNetworkIdentityChanged(previousType, previousIdentity, networkType, networkIdentity)
                    }
                    // Otherwise: same network, just gained validation - don't report
                } else if (wasValidated && !nowValidated) {
                    // Network lost validation - but don't report unless we actually go offline
                    // (onLost will be called if network actually disconnects)
                    hasValidatedNetwork = false
                    if (BuildConfig.DEBUG) {
                        Log.d("NetworkMonitor", "Network lost validation but still connected - not reporting")
                    }
                }
            }
            
            override fun onUnavailable() {
                if (BuildConfig.DEBUG) {
                    Log.w("NetworkMonitor", "Network unavailable")
                }
                val previousType = currentNetworkType
                currentNetworkType = NetworkType.NONE
                currentNetworkIdentity = null
                hasValidatedNetwork = false
                
                if (previousType != NetworkType.NONE) {
                    onNetworkLost()
                }
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
        currentNetworkType = NetworkType.NONE
        currentNetworkIdentity = null
        hasValidatedNetwork = false
    }
    
    /**
     * Get current network type
     */
    fun getCurrentNetworkType(): NetworkType {
        return currentNetworkType
    }
    
    /**
     * Update current network state from ConnectivityManager
     */
    private fun updateCurrentNetworkState() {
        val activeNetwork = connectivityManager.activeNetwork
        if (activeNetwork == null) {
            currentNetworkType = NetworkType.NONE
            currentNetworkIdentity = null
            hasValidatedNetwork = false
            return
        }
        
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        if (capabilities == null) {
            currentNetworkType = NetworkType.NONE
            currentNetworkIdentity = null
            hasValidatedNetwork = false
            return
        }
        
        currentNetworkType = getNetworkType(capabilities)
        currentNetworkIdentity = getNetworkIdentity(currentNetworkType, activeNetwork)
        hasValidatedNetwork = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                              capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
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
     * Get network type from Network object
     */
    private fun getNetworkType(network: Network): NetworkType {
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return NetworkType.NONE
        return getNetworkType(capabilities)
    }
    
    /**
     * Get WiFi SSID from the active network
     */
    private fun getWifiSSID(network: Network? = null): String? {
        if (wifiManager == null) return null
        
        return try {
            val wifiInfo: WifiInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                wifiManager.connectionInfo
            } else {
                wifiManager.connectionInfo
            }
            
            val ssid = wifiInfo?.ssid
            if (ssid != null && ssid != "<unknown ssid>" && ssid.isNotBlank()) {
                ssid.removeSurrounding("\"")
            } else {
                null
            }
        } catch (e: SecurityException) {
            if (BuildConfig.DEBUG) {
                Log.w("NetworkMonitor", "Permission denied getting WiFi SSID: ${e.message}")
            }
            null
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.w("NetworkMonitor", "Failed to get WiFi SSID: ${e.message}")
            }
            null
        }
    }
    
    /**
     * Get network identity string (SSID for WiFi, null for other types)
     */
    private fun getNetworkIdentity(networkType: NetworkType, network: Network? = null): String? {
        return when (networkType) {
            NetworkType.WIFI -> getWifiSSID(network)
            else -> null
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
