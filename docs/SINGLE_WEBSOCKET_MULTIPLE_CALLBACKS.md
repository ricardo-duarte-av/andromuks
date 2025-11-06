# Single WebSocket Connection, Multiple Callbacks

## Your Question

> "Both can send commands without overriding each other via a single websocket connection? We cannot open more than one connection to the websocket."

**You're absolutely right!** There's only **ONE** WebSocket connection. Let me clarify how multiple callbacks work with a single connection.

## How It Actually Works

### Current Architecture

```
┌─────────────────────────────────────────┐
│      WebSocketService (Singleton)      │
│                                         │
│  private var webSocket: WebSocket?     │ ← ONE connection stored here
│                                         │
│  fun getWebSocket(): WebSocket? {      │
│      return instance?.webSocket        │
│  }                                      │
└─────────────────────────────────────────┘
                    ↕
        ┌───────────────────┐
        │                   │
        ↓                   ↓
┌──────────────┐    ┌──────────────┐
│ MainActivity │    │ BubbleActivity│
│ AppViewModel │    │ AppViewModel │
│              │    │              │
│ webSocket    │    │ webSocket    │ ← Both reference the SAME object
│ (reference)  │    │ (reference)  │
└──────────────┘    └──────────────┘
```

### Key Point: References, Not Connections

Each AppViewModel stores a **reference** to the same WebSocket object:

```kotlin
// AppViewModel.kt
private var webSocket: WebSocket? = null  // Just a reference!

fun setWebSocket(webSocket: WebSocket) {
    this.webSocket = webSocket  // Store reference to the SAME object
}

private fun sendWebSocketCommand(...): WebSocketResult {
    val ws = this.webSocket  // Get reference
    ws?.send(jsonString)     // Send via the SAME connection
}
```

**There's only ONE actual connection**, but multiple objects can hold references to it!

## The Callback Queue Solution

### Problem with Current Approach

```kotlin
// WebSocketService.kt (CURRENT - PROBLEMATIC)
private var webSocketSendCallback: ((String, Int, Map<String, Any>) -> Boolean)? = null

fun setWebSocketSendCallback(callback: ...) {
    webSocketSendCallback = callback  // ← OVERWRITES previous!
}
```

**Issue**: When BubbleActivity sets its callback, it **overwrites** MainActivity's callback.

### Solution: Callback Queue

```kotlin
// WebSocketService.kt (FIXED)
companion object {
    // Store the ONE WebSocket connection
    private var webSocket: WebSocket? = null
    
    // Store MULTIPLE callbacks (all use the same WebSocket)
    private val webSocketCallbacks = mutableListOf<((String, Int, Map<String, Any>) -> Boolean)>()
    
    /**
     * Set the WebSocket connection (called once when connection is established)
     */
    fun setWebSocket(webSocket: WebSocket) {
        instance?.webSocket = webSocket  // Store the ONE connection
    }
    
    /**
     * Get the WebSocket connection (shared by all)
     */
    fun getWebSocket(): WebSocket? {
        return instance?.webSocket  // Return the SAME connection to everyone
    }
    
    /**
     * Add a callback (multiple Activities can register)
     */
    fun addWebSocketSendCallback(callback: (String, Int, Map<String, Any>) -> Boolean) {
        synchronized(webSocketCallbacks) {
            if (!webSocketCallbacks.contains(callback)) {
                webSocketCallbacks.add(callback)
                android.util.Log.d("WebSocketService", "Added callback (total: ${webSocketCallbacks.size})")
            }
        }
    }
    
    /**
     * Remove a callback (when Activity/ViewModel is destroyed)
     */
    fun removeWebSocketSendCallback(callback: (String, Int, Map<String, Any>) -> Boolean) {
        synchronized(webSocketCallbacks) {
            webSocketCallbacks.remove(callback)
            android.util.Log.d("WebSocketService", "Removed callback (total: ${webSocketCallbacks.size})")
        }
    }
    
    /**
     * Send command via the SINGLE WebSocket connection
     * Tries each callback until one succeeds
     */
    private fun sendWebSocketCommand(command: String, requestId: Int, data: Map<String, Any>): Boolean {
        val ws = getWebSocket()  // Get the ONE connection
        if (ws == null) {
            android.util.Log.w("WebSocketService", "WebSocket not connected")
            return false
        }
        
        synchronized(webSocketCallbacks) {
            // Try each callback (they all use the same WebSocket)
            for (callback in webSocketCallbacks) {
                try {
                    // Callback uses its AppViewModel's sendWebSocketCommand()
                    // Which uses the SAME WebSocket reference
                    if (callback(command, requestId, data)) {
                        return true  // Success!
                    }
                } catch (e: Exception) {
                    android.util.Log.e("WebSocketService", "Error in callback", e)
                    // Continue to next callback
                }
            }
        }
        
        return false  // No callback succeeded
    }
}
```

## How It Works: Step by Step

### Scenario: Bubble Sends a Message

```
1. User types message in Bubble
   ↓
2. BubbleActivity's AppViewModel.sendMessage()
   ↓
3. AppViewModel.sendWebSocketCommand("send_message", ...)
   ↓
4. Gets WebSocket reference: val ws = WebSocketService.getWebSocket()
   ↓
5. ws.send(jsonString)  ← Uses the SAME connection as MainActivity!
   ↓
6. Message sent via the single WebSocket connection ✅
```

### Scenario: MainActivity Sends a Message

```
1. User types message in MainActivity
   ↓
2. MainActivity's AppViewModel.sendMessage()
   ↓
3. AppViewModel.sendWebSocketCommand("send_message", ...)
   ↓
4. Gets WebSocket reference: val ws = WebSocketService.getWebSocket()
   ↓
5. ws.send(jsonString)  ← Uses the SAME connection as Bubble!
   ↓
6. Message sent via the single WebSocket connection ✅
```

**Both use the SAME WebSocket connection!** The callbacks are just different ways to access it.

## Why Callbacks Are Needed

The callbacks aren't for creating multiple connections - they're for **routing commands**:

```kotlin
// When WebSocketService needs to send a command (e.g., from ping/pong)
// It calls the callback, which routes to the AppViewModel that has the WebSocket reference

WebSocketService.sendPing() {
    // Needs to send ping command
    // Calls callback to route to AppViewModel
    webSocketSendCallback?.invoke("ping", requestId, data)
        ↓
    AppViewModel.sendWebSocketCommand("ping", requestId, data)
        ↓
    webSocket.send(jsonString)  // Uses the SAME connection
}
```

## Complete Flow Diagram

```
┌─────────────────────────────────────────────────────────┐
│              ONE WebSocket Connection                     │
│  (Stored in WebSocketService.instance.webSocket)        │
└─────────────────────────────────────────────────────────┘
                        ↕
        ┌───────────────────────────────────┐
        │   WebSocketService.getWebSocket() │
        │   Returns the SAME connection     │
        └───────────────────────────────────┘
                        ↕
        ┌───────────────────────────────────┐
        │   Multiple AppViewModel Instances  │
        │   Each holds a REFERENCE to the   │
        │   SAME WebSocket object           │
        └───────────────────────────────────┘
                        ↕
    ┌───────────────────┴───────────────────┐
    ↓                                       ↓
┌──────────────┐                    ┌──────────────┐
│ MainActivity │                    │ BubbleActivity│
│ AppViewModel │                    │ AppViewModel │
│              │                    │              │
│ webSocket =  │                    │ webSocket =  │
│   (ref to    │                    │   (ref to    │
│    same obj) │                    │    same obj) │
│              │                    │              │
│ sendMessage()│                    │ sendMessage()│
│   ↓          │                    │   ↓          │
│ webSocket.   │                    │ webSocket.   │
│   send()     │                    │   send()     │
└──────────────┘                    └──────────────┘
    │                                       │
    └───────────────┬───────────────────────┘
                    ↓
        ┌───────────────────────┐
        │  ONE WebSocket.send() │
        │  (Same connection!)   │
        └───────────────────────┘
```

## Modified AppViewModel

```kotlin
// AppViewModel.kt
class AppViewModel : ViewModel() {
    // Store reference to the shared WebSocket
    private var webSocket: WebSocket? = null
    
    // Callback that uses THIS AppViewModel's sendWebSocketCommand
    private val webSocketCallback = { command: String, requestId: Int, data: Map<String, Any> ->
        sendWebSocketCommand(command, requestId, data) == WebSocketResult.SUCCESS
    }
    
    fun setWebSocket(webSocket: WebSocket) {
        this.webSocket = webSocket  // Store reference to shared connection
        
        // Register THIS AppViewModel's callback
        WebSocketService.addWebSocketSendCallback(webSocketCallback)
    }
    
    override fun onCleared() {
        super.onCleared()
        // Unregister when ViewModel is destroyed
        WebSocketService.removeWebSocketSendCallback(webSocketCallback)
        this.webSocket = null
    }
    
    private fun sendWebSocketCommand(command: String, requestId: Int, data: Map<String, Any>): WebSocketResult {
        // Get the shared WebSocket connection
        val ws = this.webSocket ?: WebSocketService.getWebSocket()
        
        if (ws == null) {
            return WebSocketResult.NOT_CONNECTED
        }
        
        // Send via the SINGLE connection (shared by all AppViewModels)
        return try {
            val json = org.json.JSONObject()
            json.put("command", command)
            json.put("request_id", requestId)
            json.put("data", org.json.JSONObject(data))
            val jsonString = json.toString()
            
            ws.send(jsonString)  // ← Uses the SAME connection!
            WebSocketResult.SUCCESS
        } catch (e: Exception) {
            WebSocketResult.CONNECTION_ERROR
        }
    }
}
```

## Key Points

### 1. One Connection, Multiple References ✅

- **ONE** actual WebSocket connection (stored in WebSocketService)
- **MULTIPLE** AppViewModels can hold references to it
- All references point to the **SAME** connection object

### 2. Callbacks Route Commands ✅

- Callbacks don't create connections
- They route commands to the AppViewModel that has the WebSocket reference
- Multiple callbacks = multiple ways to access the same connection

### 3. Thread Safety ✅

- WebSocket.send() is thread-safe (OkHttp handles this)
- Multiple AppViewModels can call send() simultaneously
- Messages are queued and sent in order

### 4. Why This Works ✅

```kotlin
// MainActivity's AppViewModel
webSocket.send("message1")  // Uses connection A

// BubbleActivity's AppViewModel  
webSocket.send("message2")  // Uses connection A (SAME!)

// Both messages sent via the SAME connection!
```

## Summary

**Your concern is valid**, but the solution works because:

1. ✅ **ONE** WebSocket connection (stored in WebSocketService)
2. ✅ **MULTIPLE** references to that connection (one per AppViewModel)
3. ✅ **MULTIPLE** callbacks (each routes to its AppViewModel)
4. ✅ All callbacks use the **SAME** connection via their AppViewModel's reference

**The callbacks don't create multiple connections** - they're just different ways to access the single shared connection!

Think of it like:
- **One mailbox** (WebSocket connection)
- **Multiple people** (AppViewModels) 
- **Each person has a key** (reference to the mailbox)
- **All keys open the same mailbox** (same connection)

