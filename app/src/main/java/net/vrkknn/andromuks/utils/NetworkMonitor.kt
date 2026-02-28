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
    private val onNetworkTypeChanged: (NetworkType, NetworkType) -> Unit,  // Network type changed (e.g., WiFi → Mobile)
    private val onNetworkIdentityChanged: (NetworkType, String?, NetworkType, String?) -> Unit  // Network identity changed (e.g., WiFi AP Alpha → WiFi AP Beta, includes SSID for WiFi)
) {
    private val connectivityManager: ConnectivityManager = 
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager: WifiManager? = 
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastNetworkType: NetworkType? = null
    private var lastNetwork: Network? = null
    private var lastNetworkIdentity: String? = null  // SSID for WiFi, null for other types
    // Track all available networks to detect when WiFi is still connected but lost validation
    private val availableNetworks = mutableMapOf<Network, NetworkType>()
    
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
                val previousIdentity = lastNetworkIdentity
                
                // Get network identity (SSID for WiFi)
                val networkIdentity = getNetworkIdentity(networkType, network)
                
                // Track this network as available
                availableNetworks[network] = networkType
                
                if (BuildConfig.DEBUG) {
                    Log.d("NetworkMonitor", "Network available: $networkType (identity: $networkIdentity, previous: $previousType/$previousIdentity, total available: ${availableNetworks.size})")
                }
                
                // CRITICAL FIX: Prefer Cellular (5G/4G) over WiFi, but only report network type change
                // if the previous network actually disconnected (not just lost validation)
                // This prevents false "network type change" notifications when WiFi is still connected
                val preferredNetworkType = getPreferredNetworkType()
                
                // Check if network identity changed (e.g., WiFi AP Alpha → WiFi AP Beta)
                val identityChanged = if (networkType == NetworkType.WIFI && previousType == NetworkType.WIFI) {
                    // Both are WiFi - check if SSID changed
                    networkIdentity != previousIdentity
                } else {
                    // Network type changed or not WiFi
                    false
                }
                
                // Only report network type change if:
                // 1. Previous network type was different AND
                // 2. Previous network actually disconnected (not just lost validation)
                // OR
                // 3. We're going from offline to online
                if (previousType != null && previousType != NetworkType.NONE) {
                    if (previousType != preferredNetworkType) {
                        // Check if previous network type is still available (e.g., WiFi still connected)
                        val previousNetworkStillAvailable = availableNetworks.values.contains(previousType)
                        
                        if (previousNetworkStillAvailable) {
                            // Previous network is still connected - don't report network type change
                            // Just silently switch to the preferred network (5G over WiFi)
                            if (BuildConfig.DEBUG) {
                                Log.d("NetworkMonitor", "Previous network ($previousType) still connected but switching to preferred network ($preferredNetworkType) - not reporting as network type change")
                            }
                            // Update to preferred network type but don't trigger callbacks
                            lastNetworkType = preferredNetworkType
                            lastNetwork = network
                            lastNetworkIdentity = getNetworkIdentity(preferredNetworkType, network)
                            return // Don't report network type change
                        } else {
                            // Previous network type is not available anymore - this is a real change
                            Log.i("NetworkMonitor", "Network type changed: $previousType → $preferredNetworkType")
                            onNetworkTypeChanged(previousType, preferredNetworkType)
                        }
                    } else if (identityChanged) {
                        // Same network type (WiFi) but identity changed (different AP)
                        Log.i("NetworkMonitor", "Network identity changed: $previousType ($previousIdentity) → $preferredNetworkType ($networkIdentity)")
                        onNetworkIdentityChanged(previousType, previousIdentity, preferredNetworkType, networkIdentity)
                    }
                }
                
                lastNetworkType = preferredNetworkType
                lastNetwork = network
                lastNetworkIdentity = networkIdentity
                
                // Network is available - trigger reconnection if needed
                onNetworkAvailable(preferredNetworkType)
            }
            
            override fun onLost(network: Network) {
                // Get network type before removing (for logging and type change notification)
                val lostNetworkType = availableNetworks[network]
                val lostIdentity = if (network == lastNetwork) lastNetworkIdentity else null
                
                // Remove from available networks
                availableNetworks.remove(network)
                
                if (BuildConfig.DEBUG) {
                    Log.w("NetworkMonitor", "Network lost: $network (type: $lostNetworkType, identity: $lostIdentity, was: $lastNetworkType/$lastNetworkIdentity, remaining: ${availableNetworks.size})")
                }
                
                // Check if this was the active network
                if (network == lastNetwork) {
                    // Check if there are other networks available
                    val preferredNetworkType = getPreferredNetworkType()
                    
                    if (preferredNetworkType != NetworkType.NONE) {
                        // Another network is available - switch to it
                        if (BuildConfig.DEBUG) {
                            Log.d("NetworkMonitor", "Active network lost, switching to: $preferredNetworkType")
                        }
                        val previousType = lostNetworkType ?: lastNetworkType ?: NetworkType.NONE
                        val newNetwork = availableNetworks.entries.firstOrNull { it.value == preferredNetworkType }?.key
                        val newIdentity = getNetworkIdentity(preferredNetworkType, newNetwork)
                        
                        lastNetworkType = preferredNetworkType
                        lastNetwork = newNetwork
                        lastNetworkIdentity = newIdentity
                        onNetworkTypeChanged(previousType, preferredNetworkType)
                        onNetworkAvailable(preferredNetworkType)
                    } else {
                        // No networks available - we're offline
                        lastNetworkType = NetworkType.NONE
                        lastNetwork = null
                        lastNetworkIdentity = null
                        onNetworkLost()
                    }
                }
            }
            
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                val networkType = getNetworkType(networkCapabilities)
                val previousType = lastNetworkType
                
                // Update available networks if this network is validated
                val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val isValidated = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                
                if (BuildConfig.DEBUG) {
                    Log.d("NetworkMonitor", "Network capabilities changed: $networkType (hasInternet: $hasInternet, isValidated: $isValidated)")
                }
                
                // CRITICAL FIX: Prefer Cellular (5G/4G) over WiFi, but only report network type change
                // if the previous network actually disconnected (not just lost validation)
                val preferredNetworkType = getPreferredNetworkType()
                
                // If network gained internet access or validation
                if (hasInternet && isValidated) {
                    // Update available networks
                    if (networkType != NetworkType.NONE) {
                        availableNetworks[network] = networkType
                    }
                    
                    // Only report network type change if:
                    // 1. Previous network type was different AND
                    // 2. Previous network is not still available (actually disconnected)
                    if (previousType != null && previousType != NetworkType.NONE) {
                        if (previousType != preferredNetworkType) {
                            // Check if previous network type is still available
                            val previousNetworkStillAvailable = availableNetworks.values.contains(previousType)
                            
                            if (previousNetworkStillAvailable) {
                                // Previous network is still connected - don't report network type change
                                // Just silently switch to the preferred network (5G over WiFi)
                                if (BuildConfig.DEBUG) {
                                    Log.d("NetworkMonitor", "Previous network ($previousType) still connected but switching to preferred network ($preferredNetworkType) - not reporting as network type change")
                                }
                                // Update to preferred network type but don't trigger callbacks
                                lastNetworkType = preferredNetworkType
                                lastNetwork = network
                                return // Don't report network type change
                            } else {
                                // Previous network type is not available anymore - this is a real change
                                Log.i("NetworkMonitor", "Network type changed via capabilities: $previousType → $preferredNetworkType")
                                onNetworkTypeChanged(previousType, preferredNetworkType)
                            }
                        }
                    } else if (previousType == null || previousType == NetworkType.NONE) {
                        // Network became available (from offline)
                        if (BuildConfig.DEBUG) {
                            Log.d("NetworkMonitor", "Network became available via capabilities: $preferredNetworkType")
                        }
                        onNetworkAvailable(preferredNetworkType)
                    }
                    
                    lastNetworkType = preferredNetworkType
                    lastNetwork = network
                    lastNetworkIdentity = getNetworkIdentity(preferredNetworkType, network)
                } else if (!hasInternet || !isValidated) {
                    // Network lost internet access or validation
                    // Remove from available networks if it lost validation
                    if (!isValidated) {
                        availableNetworks.remove(network)
                    }
                    
                    if (network == lastNetwork) {
                        if (BuildConfig.DEBUG) {
                            Log.w("NetworkMonitor", "Network lost internet access or validation: $networkType")
                        }
                        
                        // CRITICAL FIX: Prefer Cellular (5G/4G) over WiFi when both are available
                        // When WiFi loses validation, switch to Cellular if available
                        // But only report network type change if WiFi actually disconnected
                        val newPreferredType = getPreferredNetworkType()
                        
                        if (newPreferredType != NetworkType.NONE && newPreferredType != networkType) {
                            // Check if the current network (that lost validation) is still connected
                            // by checking ConnectivityManager directly (not just availableNetworks)
                            val allNetworks = connectivityManager.allNetworks
                            val currentNetworkStillConnected = allNetworks.any { n ->
                                val caps = connectivityManager.getNetworkCapabilities(n)
                                caps?.hasTransport(when (networkType) {
                                    NetworkType.WIFI -> NetworkCapabilities.TRANSPORT_WIFI
                                    NetworkType.CELLULAR -> NetworkCapabilities.TRANSPORT_CELLULAR
                                    NetworkType.ETHERNET -> NetworkCapabilities.TRANSPORT_ETHERNET
                                    NetworkType.VPN -> NetworkCapabilities.TRANSPORT_VPN
                                    else -> return@any false
                                }) == true
                            }
                            
                            if (currentNetworkStillConnected) {
                                // Current network is still connected - silently switch to preferred network
                                // Don't report as network type change
                                if (BuildConfig.DEBUG) {
                                    Log.d("NetworkMonitor", "Network ($networkType) lost validation but still connected - switching to preferred network ($newPreferredType) silently")
                                }
                                val newNetwork = availableNetworks.entries.firstOrNull { it.value == newPreferredType }?.key
                                lastNetworkType = newPreferredType
                                lastNetwork = newNetwork
                                lastNetworkIdentity = getNetworkIdentity(newPreferredType, newNetwork)
                                // Don't trigger callbacks - this is a silent switch
                            } else {
                                // Current network actually disconnected - this is a real change
                                if (BuildConfig.DEBUG) {
                                    Log.d("NetworkMonitor", "Network lost validation and disconnected, switching to: $newPreferredType")
                                }
                                val newNetwork = availableNetworks.entries.firstOrNull { it.value == newPreferredType }?.key
                                val newIdentity = getNetworkIdentity(newPreferredType, newNetwork)
                                lastNetworkType = newPreferredType
                                lastNetwork = newNetwork
                                lastNetworkIdentity = newIdentity
                                onNetworkTypeChanged(networkType, newPreferredType)
                                onNetworkAvailable(newPreferredType)
                            }
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
                lastNetworkIdentity = null
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
        lastNetworkIdentity = null
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
     * Get WiFi SSID from the active network
     * Returns null if not on WiFi or SSID cannot be determined
     */
    private fun getWifiSSID(network: Network? = null): String? {
        if (wifiManager == null) return null
        
        return try {
            val wifiInfo: WifiInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ (API 29+): Use WifiManager.getConnectionInfo() which works with Network objects
                // The Network object is used by ConnectivityManager to route traffic, but WifiManager
                // still provides the SSID through connectionInfo
                wifiManager.connectionInfo
            } else {
                // Android 9 and below: Direct access
                wifiManager.connectionInfo
            }
            
            val ssid = wifiInfo?.ssid
            // SSID is returned with quotes on some devices, remove them
            // Also check for "<unknown ssid>" which indicates SSID is not available
            if (ssid != null && ssid != "<unknown ssid>" && ssid.isNotBlank()) {
                ssid.removeSurrounding("\"")
            } else {
                null
            }
        } catch (e: SecurityException) {
            // Permission denied (shouldn't happen if permissions are set correctly)
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
            else -> null  // Other network types don't have identity we track
        }
    }
    
    /**
     * Get preferred network type when multiple networks are available
     * Prefers in this order: ETHERNET > CELLULAR (5G/4G) > VPN > WIFI > OTHER
     * 
     * CRITICAL: Prefers Cellular (5G/4G) over WiFi because Cellular is typically more stable
     * and has better signal quality than weak WiFi connections
     */
    private fun getPreferredNetworkType(): NetworkType {
        // Check all available networks (validated networks)
        val availableTypes = availableNetworks.values.toSet()
        
        // Also check ConnectivityManager for validated networks
        val allNetworks = connectivityManager.allNetworks
        var ethernetAvailable = false
        var cellularAvailable = false
        var vpnAvailable = false
        var wifiAvailable = false
        
        for (network in allNetworks) {
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: continue
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                when {
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> ethernetAvailable = true
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> cellularAvailable = true
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> vpnAvailable = true
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> wifiAvailable = true
                }
            }
        }
        
        // Prefer in this order: ETHERNET > CELLULAR > VPN > WIFI > OTHER
        // Cellular (5G/4G) is preferred over WiFi for better stability
        return when {
            availableTypes.contains(NetworkType.ETHERNET) || ethernetAvailable -> NetworkType.ETHERNET
            availableTypes.contains(NetworkType.WIFI) || wifiAvailable -> NetworkType.WIFI
            availableTypes.contains(NetworkType.CELLULAR) || cellularAvailable -> NetworkType.CELLULAR
            availableTypes.contains(NetworkType.VPN) || vpnAvailable -> NetworkType.VPN
            availableTypes.contains(NetworkType.OTHER) -> NetworkType.OTHER
            else -> NetworkType.NONE
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

