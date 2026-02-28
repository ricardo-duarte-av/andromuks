# Network Change Implementation

## Answer to: "Does Android communicate these kind of network changes to the app?"

**Yes, but with limitations:**

1. **Network Type Changes**: Android's `ConnectivityManager.NetworkCallback` provides:
   - `onAvailable(network)` - When a network becomes available
   - `onLost(network)` - When a network is lost
   - `onCapabilitiesChanged(network, capabilities)` - When network capabilities change
   - These callbacks tell you the **network type** (WiFi, Cellular, etc.) but **NOT the SSID/AP name**

2. **WiFi SSID/AP Name**: Android does **NOT** directly provide SSID changes through NetworkCallback. To detect WiFi AP name changes:
   - Use `WifiManager.getConnectionInfo().ssid` to get the current SSID
   - Compare it with the previously stored SSID
   - This requires `ACCESS_WIFI_STATE` permission (which we already have)

## Implementation Summary

### What Was Implemented

1. **SSID Tracking in NetworkMonitor**:
   - Added `getWifiSSID()` function to retrieve WiFi SSID using `WifiManager`
   - Added `lastNetworkIdentity` to track the current SSID
   - Added `onNetworkIdentityChanged` callback to detect WiFi AP changes

2. **Network Change Logic** (as per your requirements):

   | Scenario | Action |
   |----------|--------|
   | WiFi AP Alpha → WiFi AP Alpha | Do nothing (let ping/pong handle failures) |
   | WiFi AP Alpha → WiFi AP Beta | Force reconnect (via `onNetworkIdentityChanged`) |
   | WiFi → 5G | Force reconnect (via `onNetworkTypeChanged`) |
   | 5G → WiFi | Force reconnect (via `onNetworkTypeChanged`) |
   | Any network type → Another network type | Force reconnect (via `onNetworkTypeChanged`) |

3. **Fixed CONNECTING State Bug**:
   - Added stuck state detection (if CONNECTING for >20 seconds, force recovery)
   - Prevents the websocket from getting stuck in "Connecting..." state after rapid network changes

### Code Changes

#### NetworkMonitor.kt
- Added `WifiManager` to get SSID
- Added `lastNetworkIdentity` to track current SSID
- Added `onNetworkIdentityChanged` callback parameter
- Added `getWifiSSID()` and `getNetworkIdentity()` functions
- Updated all network callbacks to track and report identity changes

#### WebSocketService.kt
- Added `lastNetworkIdentity` variable to track SSID
- Updated `onNetworkAvailable` handler:
  - Same network (same type + identity): Do nothing
  - Different network type: Force reconnect
  - Stuck CONNECTING state: Force recovery
- Updated `onNetworkTypeChanged` handler:
  - Always force reconnect on network type change (per your requirements)
- Added `onNetworkIdentityChanged` handler:
  - Always force reconnect when WiFi AP changes (per your requirements)

### How It Works

1. **Network Type Change Detection**:
   - `NetworkCallback.onAvailable()` detects when a new network becomes available
   - `NetworkCallback.onLost()` detects when a network is lost
   - These callbacks provide network type (WiFi, Cellular, etc.)

2. **WiFi AP Name Change Detection**:
   - When WiFi network becomes available, `getWifiSSID()` retrieves the SSID
   - SSID is compared with `lastNetworkIdentity`
   - If different, `onNetworkIdentityChanged` callback is triggered

3. **Reconnection Logic**:
   - Network type change → Always force reconnect
   - WiFi AP change → Always force reconnect
   - Same network → Do nothing (let ping/pong handle failures)

### Testing

To test the implementation:

1. **WiFi AP Alpha → WiFi AP Alpha**:
   - Connect to WiFi AP "Alpha"
   - Disconnect and reconnect to same AP "Alpha"
   - Expected: No reconnection (ping/pong handles failures)

2. **WiFi AP Alpha → WiFi AP Beta**:
   - Connect to WiFi AP "Alpha"
   - Switch to WiFi AP "Beta"
   - Expected: Force reconnect

3. **WiFi → 5G → WiFi AP Beta**:
   - Connect to WiFi AP "Alpha"
   - Move out of range (WiFi disconnects)
   - Phone switches to 5G (briefly)
   - Phone connects to WiFi AP "Beta"
   - Expected: Force reconnect on each change, no stuck state

### Permissions Required

- `ACCESS_NETWORK_STATE` - Already have (for ConnectivityManager)
- `ACCESS_WIFI_STATE` - Already have (for WifiManager to get SSID)

### Limitations

1. **SSID on Android 10+**: On Android 10 (API 29) and above, `WifiManager.getConnectionInfo().ssid` may return `<unknown ssid>` if:
   - Location services are disabled
   - App doesn't have location permission
   - However, this is usually fine for detecting AP changes (if SSID changes from "Alpha" to "Beta", we'll still detect it)

2. **Rapid Network Changes**: The debounce mechanism (500ms) prevents excessive reconnections, but may delay detection of very rapid changes.

3. **Network Validation**: Network validation timeout (2 seconds) may delay reconnection on slow networks.

