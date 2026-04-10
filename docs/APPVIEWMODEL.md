# AppViewModel Reference

`AppViewModel` is the central state holder for Andromuks. It is a `ViewModel` that holds all runtime state, processes sync events, manages the timeline, and coordinates the 18+ Coordinator classes. It is initialized in `MainActivity` and shared across screens via the Compose `viewModel()` call.

The file is extremely large (~10 500 lines). This document provides a navigable reference for every state field and function.

---

## Table of Contents

1. [State Fields](#state-fields)
2. [Functions by Category](#functions-by-category)

---

## State Fields

### Room & Sync

| Field | Type | Description |
|-------|------|-------------|
| `roomMap` | `ConcurrentHashMap<String, RoomItem>` | Thread-safe map of all joined rooms, keyed by room ID. Single source of truth for room data. |
| `allRooms` | `List<RoomItem>` | Flat list of all rooms for direct iteration. |
| `allSpaces` | `List<SpaceItem>` | All spaces the user is a member of. |
| `spaceList` | `List<SpaceItem>` | Filtered/sorted space list used by the UI. |
| `knownSpaceIds` | `MutableSet<String>` | IDs of spaces registered before they are fully loaded. |
| `storedSpaceEdges` | `JSONObject?` | Cached space hierarchy edges for fast parent/child lookups. |
| `currentSpaceId` | `String?` | Currently browsing space ID, `null` means top-level. |
| `currentBridgeId` | `String?` | Currently selected bridge ID. |
| `selectedSection` | `RoomSectionType` | Active section tab (All / Direct / Favorites / Spaces / Bridges). |
| `newlyJoinedRoomIds` | `MutableSet<String>` | Room IDs that appeared in the latest sync batch; sorted to the top of the list. |
| `syncMessageCount` | `Int` | Running count of sync messages received from backend. |

### UI Counters

| Field | Type | Description |
|-------|------|-------------|
| `updateCounter` | `Int` | General-purpose counter incremented to trigger UI recomposition. |
| `roomListUpdateCounter` | `Int` | Counter dedicated to room list re-renders. Incrementing this is cheaper than diffing the full list. |
| `roomSummaryUpdateCounter` | `Int` | Counter for room summary view refreshes. |
| `timestampUpdateCounter` | `Int` | Counter for relative timestamp formatting updates. |
| `memberUpdateCounter` | `Int` | Counter for member profile changes visible in the timeline. |
| `readReceiptsUpdateCounter` | `Int` | Counter to trigger read receipt animation updates. |
| `isLoading` | `MutableState<Boolean>` | Whether a loading indicator should be shown. |
| `isStartupComplete` | `MutableState<Boolean>` | Whether startup is complete and the room list is ready. |

### Initialization & Readiness

| Field | Type | Description |
|-------|------|-------------|
| `initializationComplete` | `Boolean` | `true` after `init_complete` is received from the backend. |
| `initialSyncComplete` | `Boolean` | `true` after the first `sync_complete` batch is queued. |
| `initialSyncProcessingComplete` | `Boolean` | `true` after all queued `sync_complete` messages are processed. |
| `allRoomStatesLoaded` | `Boolean` | `true` after all room states (for bridge badges) are loaded. |
| `spacesLoaded` | `Boolean` | `true` after all spaces are loaded. |
| `navigationCallbackTriggered` | `Boolean` | `true` after the navigation callback fires. Reset by `setNavigationCallback`. |

### Current User & Identity

| Field | Type | Description |
|-------|------|-------------|
| `currentUserId` | `String` | Matrix ID of the logged-in user. |
| `currentUserProfile` | `UserProfile?` | Cached profile (avatar, display name) for the current user. |
| `deviceId` | `String` | Device ID assigned by the Matrix server. |
| `realMatrixHomeserverUrl` | `String` | Actual Matrix homeserver URL (may differ from backend URL). |
| `homeserverUrl` | `String` | Gomuks/backend server URL. |
| `authToken` | `String` | Auth token for backend API calls. |
| `imageAuthToken` | `String` | Separate auth token for image downloads. |

### WebSocket & Session

| Field | Type | Description |
|-------|------|-------------|
| `currentRunId` | `String` | Session `run_id` from the backend used for session resumption. |
| `vapidKey` | `String` | VAPID key used for push notification registration. |
| `requestIdCounter` | `Int` | Monotonically increasing counter for generating unique request IDs. |
| `lastReceivedRequestId` | `Int` | Last `request_id` received from the backend; used to detect resume vs. fresh connection. |
| `isWebSocketInitialized` | `Boolean` | Whether the WebSocket service has been initialized at least once. |
| `reconnectWithResume` | `Boolean?` | Whether the next reconnect should attempt session resume. `null` means auto-detect. |

### Notification & Activity Tracking

| Field | Type | Description |
|-------|------|-------------|
| `newMessageAnimations` | `MutableMap<String, Long>` | Event IDs mapped to timestamps; drives new-message sound/animation triggers. |
| `roomOpenTimestamps` | `MutableMap<String, Long>` | When each room was opened; used as animation cutoff. |
| `timelineForegroundTimestamps` | `MutableMap<String, Long>` | When each room's timeline was last in the foreground. |
| `pendingNotificationActions` | `List<NotificationAction>` | Queued actions from notification taps, processed at startup or foreground resume. |
| `openedViaDirectNotification` | `Boolean` | Whether the app was launched from a notification tap. |
| `openedFromExternalApp` | `Boolean` | Whether the app was opened via a share intent or deep link. |
| `pendingShareTargetRoomId` | `String?` | Room to share content into (from a share intent). |

### Room Timeline & Messages

| Field | Type | Description |
|-------|------|-------------|
| `currentRoomId` | `String?` | Currently displayed room ID. |
| `messageVersions` | `Map<String, VersionedMessage>` | All edit chains, keyed by original event ID. |
| `editToOriginal` | `Map<String, String>` | Maps an edit event ID back to the original event ID. |
| `roomNavigationState` | `Map<String, RoomNavigationState>` | Per-room timeline navigation state (scroll position, pagination cursor). |
| `messageScrollStates` | `MutableMap<String, MessageScrollState>` | Per-room scroll position for the timeline `LazyColumn`. |
| `cachedEventLocations` | `MutableMap<String, Pair<Int, TimelineEvent>>` | Fast lookup: event ID → (index, event) in the current rendered timeline. |
| `shouldSkipTimelineRebuild` | `StateFlow<Boolean>` | `true` while batch processing to suppress individual timeline rebuilds. |
| `isProcessingSyncBatch` | `StateFlow<Boolean>` | `true` while a sync batch is in flight. |
| `processingBatchSize` | `StateFlow<Int>` | Total number of sync messages in the current batch. |
| `processedInBatch` | `StateFlow<Int>` | Count of sync messages already processed in the current batch. |
| `roomsNeedingRebuildDuringBatch` | `MutableSet<String>` | Room IDs whose timelines are deferred until the batch completes. |

### Read Receipts

| Field | Type | Description |
|-------|------|-------------|
| `readReceipts` | `MutableMap<String, MutableList<ReadReceipt>>` | Global map: event ID → list of users who have read up to that event. |
| `readReceiptsLock` | `Any` | Synchronization object for thread-safe receipt mutations. |
| `receiptMovements` | `MutableMap<String, Triple<String?, String, Long>>` | Per-user: `(previousEventId, currentEventId, timestamp)` for slide animations. |
| `roomsWithLoadedReceipts` | `MutableSet<String>` | Rooms that have had their receipts loaded at least once in this session. |

### Reactions

| Field | Type | Description |
|-------|------|-------------|
| `messageReactions` | `Map<String, List<MessageReaction>>` | Event ID → list of emoji reactions. |
| `processedReactions` | `MutableSet<String>` | Reaction event IDs already processed; prevents duplicates. |
| `roomsWithLoadedReactions` | `MutableSet<String>` | Rooms that have had reactions loaded. |

### Emoji & Stickers

| Field | Type | Description |
|-------|------|-------------|
| `recentEmojis` | `List<String>` | Recently used emojis shown in the quick-access row. |
| `customEmojiPacks` | `List<EmojiPack>` | Custom emoji packs available in joined rooms. |
| `stickerPacks` | `List<StickerPack>` | Sticker packs available to the user. |

### Startup Progress Log

| Field | Type | Description |
|-------|------|-------------|
| `_startupProgressMessages` | `MutableStateList<String>` | Ring-buffer of the last 10 startup log messages (newest first). |
| `startupProgressMessages` | `List<String>` | Read-only accessor exposed to the UI. |

### Bridge & Message Delivery Status

| Field | Type | Description |
|-------|------|-------------|
| `messageBridgeSendStatus` | `Map<String, String>` | Event ID → bridge delivery status (`"pending"`, `"delivered"`, `"error"`). |
| `messageBridgeDeliveryInfo` | `MutableMap<String, BridgeMessageInfo>` | Event ID → detailed per-bridge delivery info. |
| `bridgeStatusEventToMessageId` | `MutableMap<String, String>` | Bridge status event ID → original message event ID. |

### Pending Requests & Callbacks

| Field | Type | Description |
|-------|------|-------------|
| `profileRequests` | `MutableMap<Int, String>` | Request ID → user ID for in-flight profile requests. |
| `mutualRoomsRequests` | `MutableMap<Int, (List<String>?, String?) -> Unit>` | Request ID → callback for mutual-rooms queries. |
| `resolveAliasRequests` | `MutableMap<Int, (Pair<String, List<String>>?) -> Unit>` | Request ID → callback for room alias resolution. |
| `getRoomSummaryRequests` | `MutableMap<Int, (Pair<RoomSummary?, String?>?) -> Unit>` | Request ID → callback for room summary fetches. |
| `joinRoomCallbacks` | `MutableMap<Int, (Pair<String?, String?>?) -> Unit>` | Request ID → callback for join-room results. |
| `fullUserInfoCallbacks` | `MutableMap<Int, (JSONObject?) -> Unit>` | Request ID → callback for full user profile info. |

### Cached Unread Counts

These are pre-computed at batch boundaries to keep O(1) reads from the UI.

| Field | Description |
|-------|-------------|
| `cachedUnreadCount` | Total unread room count. |
| `cachedDirectChatsUnreadCount` | Unread count for direct chats. |
| `cachedDirectChatsHasHighlights` | Whether direct chats have highlight (mention) notifications. |
| `cachedFavouritesUnreadCount` | Unread count for favorite rooms. |
| `cachedFavouritesHasHighlights` | Whether favorites have highlights. |

### Background Jobs

| Field | Description |
|-------|-------------|
| `batchUpdateJob` | Batches UI counter increments to avoid multiple rapid recompositions. |
| `acknowledgmentTimeoutJob` | Periodic check for message acknowledgment timeouts. |
| `acknowledgedMessagesCleanupJob` | Periodic cleanup of old acknowledged-message records. |
| `profileBatchFlushJob` | Debounce job that coalesces profile requests before sending them. |
| `profileSaveJob` | Job for persisting profile data to disk. |

---

## Functions by Category

### Lifecycle & Initialization

| Function | Description |
|----------|-------------|
| `init` | Runs on construction: starts periodic cleanup jobs, populates caches from singletons, observes batch-processing state. |
| `attachToExistingWebSocketIfAvailable()` | Called from `AuthCheckScreen` when the service is already connected. Populates `roomMap` from `RoomListCache`, sets `initialSyncComplete = true`, and fires navigation. See *`attachToExistingWebSocketIfAvailable` invariant* in CLAUDE.md. |
| `checkStartupComplete()` | Evaluates all readiness flags and, when all are satisfied, marks startup complete and triggers navigation. |
| `onInitComplete()` | Called when `init_complete` arrives. Sets `initializationComplete = true`, triggers buffered sync drain. |
| `setNavigationCallback(callback: () -> Unit)` | Registers the navigation callback to invoke after initialization. **Always resets `navigationCallbackTriggered` to `false`** — see critical invariant in CLAUDE.md. |
| `markAsPrimaryInstance()` / `isPrimaryInstance()` | Multi-Activity support: marks/queries whether this VM is the "primary" (main UI) instance. |
| `markAsBubbleInstance()` / `setBubbleVisible(visible)` | Marks this instance as serving a chat bubble window. |
| `awaitRoomDataReadiness(roomId, timeoutMs)` | Suspending. Waits until room data is available up to `timeoutMs` ms, used before navigating to a room. |
| `ensureCurrentUserProfileLoaded()` | Suspending. Ensures the current user's profile is populated before the UI needs it. |
| `addStartupProgressMessage(message)` | Appends to the startup log ring-buffer (last 10 messages). |
| `buildSectionSnapshot()` | Suspending. Builds a full `RoomSectionSnapshot` for navigation/share targets. |

### WebSocket & Connection

| Function | Description |
|----------|-------------|
| `initializeWebSocketConnection(homeserverUrl, token, isReconnection)` | Configures and starts the WebSocket service. |
| `startWebSocketService()` | Launches `WebSocketService` as a foreground service. |
| `restartWebSocketConnection(trigger)` | Restarts the connection (used after settings changes or hard failures). |
| `performQuickRefresh()` | Reconnects with session resume: keeps cached state, fetches only deltas. |
| `performFullRefresh()` | Clears all state and reconnects from scratch. |
| `clearWebSocket(reason, closeCode, closeReason)` | Closes and nulls out the WebSocket reference. |
| `onWebSocketCleared(reason)` | Post-close cleanup hook. |
| `handleConnectionFailure(errorType, error, reason)` | Central handler for WebSocket connection errors. |
| `handleTlsError(errorType, error, reason)` | Handles TLS/certificate errors; increments the TLS failure counter. |
| `isWebSocketConnected()` / `isInitializationComplete()` | Cheap boolean state queries used by UI and coordinators. |
| `flushPendingQueueAfterReconnection()` | Re-sends any commands queued while offline. |
| `queueCommandForOfflineRetry(command, requestId, data)` | Queues a command to retry after reconnection. |
| `flushPendingCommandsQueue()` | Sends all queued offline commands. |
| `getDnsFailureCount()` / `setDnsFailureCount()` / `resetDnsFailureCount()` | Tracks DNS failure count for exponential backoff decisions. |
| `getTlsFailureCount()` / `setTlsFailureCount()` / `resetTlsFailureCount()` | Tracks TLS failure count. |
| `getCertificateErrorState()` / `setCertificateErrorState(hasError, reason)` | Tracks whether a certificate error is blocking connection. |

### Sync & Timeline Building

| Function | Description |
|----------|-------------|
| `updateRoomsFromSyncJsonAsync(syncJson)` | Async update of all room entries from a `sync_complete` payload. |
| `checkAndUpdateCurrentRoomTimeline(syncJson)` | If the current room is in the sync payload, triggers a timeline refresh. |
| `cacheTimelineEventsFromSync(syncJson)` | Extracts and caches timeline events from a sync batch into `RoomTimelineCache`. |
| `updateTimelineFromSync(syncJson, roomId)` | Updates the timeline cache for a specific room from the sync payload. |
| `processSyncEventsArray(eventsArray, roomId)` | Iterates a `JSONArray` of events and routes each to the correct handler. |
| `processInitialSyncComplete(syncJson, onComplete)` | Handles the very first `sync_complete` — sets flags, drains buffer. |
| `flushSyncBatchForRoom(roomId)` | Suspending. Flushes deferred sync events for a specific room to the timeline cache. |
| `performBatchedUIUpdates()` | Coalesces multiple counter increments into a single recomposition tick. |
| `executeTimelineRebuild(rebuildComplete)` | Suspending. Rebuilds the displayed timeline from `eventChainMap`. |
| `triggerDeferredRebuild()` | Schedules a timeline rebuild for rooms whose updates were deferred during batch processing. |
| `resolveTimelineRowidsFromRoomData(roomData)` | Reads the `timeline` mapping from room state and returns an event-ID → rowid map. Only events with `rowid > 0` are rendered — see CLAUDE.md. |
| `buildEditChainsFromEvents(timelineList, clearExisting)` | Traverses events and constructs `m.room.message` edit chains. |
| `processNewEditRelationships(newEditEvents)` | Updates existing chains when new `m.replace` relation events arrive. |

### Message Operations

| Function | Description |
|----------|-------------|
| `sendMessage(roomId, text, mentions)` | Delegates to `MessageSendCoordinator` to send a plain-text message. |
| `sendTyping(roomId)` | Sends a `m.typing` ephemeral event. |
| `sendReaction(roomId, eventId, emoji)` | Sends an `m.reaction` event. |
| `sendReply(roomId, text, originalEvent)` | Sends a reply referencing `originalEvent`. |
| `sendEdit(roomId, text, originalEvent)` | Sends an `m.replace` edit of `originalEvent`. |
| `sendMediaMessage(roomId, uri, mimeType, text)` | Generic media sender; routes to the appropriate typed sender. |
| `sendImageMessage(roomId, uri, caption)` | Uploads and sends an image. |
| `sendVideoMessage(roomId, uri, caption)` | Uploads and sends a video. |
| `sendAudioMessage(roomId, uri, caption)` | Uploads and sends an audio file. |
| `sendFileMessage(roomId, uri, caption)` | Uploads and sends an arbitrary file. |
| `sendStickerMessage(roomId, mxcUrl, body)` | Sends a sticker from a sticker pack. |
| `sendDelete(roomId, originalEvent, reason)` | Redacts (deletes) a message. |
| `sendMessageFromNotification(roomId, text, onComplete)` | Sends a quick reply from a notification action. |
| `markRoomAsRead(roomId, eventId)` | Sends a read receipt up to `eventId`. |
| `markRoomAsReadFromNotification(roomId, eventId, onComplete)` | Marks room read from a notification action. |
| `handleSendComplete(eventData, error)` | Receives the `send_complete` event from the backend and resolves the pending echo. |
| `processSendCompleteEvent(eventData, error)` | Internal: updates message state (pending → confirmed or failed). |
| `dismissPendingEcho(eventId)` | Removes a pending local-echo bubble when a confirmation arrives. |

### Room Timeline & Navigation

| Function | Description |
|----------|-------------|
| `setCurrentRoomIdForTimeline(roomId)` | Sets `currentRoomId`; also calls `updateCurrentRoomIdInPrefs` which clears `currentRoomState`. |
| `clearCurrentRoomId(shouldRestoreOnVisible, saveToCacheForRoomTimeline)` | Clears the current room on app backgrounding. |
| `getRoomNavigationState(roomId)` | Returns per-room scroll and pagination cursor. |
| `ensureTimelineCacheIsFresh(roomId, limit, isBackground)` | Suspending. Checks the cache hash and fetches from server if stale. |
| `requestRoomTimeline(roomId, useLruCache)` | Sends a `get_room_timeline` request to the backend. |
| `fullRefreshRoomTimeline(roomId)` | Forces a complete re-fetch of the timeline (bypasses all caches). |
| `prefetchRoomSnapshot(roomId, limit, timeoutMs)` | Suspending. Fetches a minimal room snapshot before navigating (for fast first paint). |
| `navigateToRoomWithCache(roomId, notificationTimestamp)` | Navigates to a room using `LRU` + snapshot prefetch. |
| `loadOlderMessages(roomId, showToast)` | Requests backward pagination in the timeline. |
| `requestPaginationWithSmallestRowId(roomId, limit)` | Paginates backward using the smallest known `rowid` as the anchor. |
| `triggerPreemptivePagination(roomId)` | Speculatively loads older messages before the user reaches the top. |
| `getEvent(roomId, eventId, callback)` | Fetches a single event by ID, used for jump-to-event and permalink navigation. |
| `getEventContext(roomId, eventId, limit, callback)` | Fetches an event plus surrounding context events. |
| `addTimelineEvent(event)` | Appends a single event to the rendered timeline (used for local echo). |

### Member Profiles

| Function | Description |
|----------|-------------|
| `getMemberProfile(roomId, userId)` | Returns the room-scoped profile if available, otherwise the global profile. |
| `getMemberMap(roomId)` | Returns all member profiles for a room. |
| `getMemberMapWithFallback(roomId, timelineEvents)` | Returns member map, filling gaps from the provided event list. |
| `requestUserProfile(userId, roomId)` | Sends a `get_profile` request; result is stored in the member cache. |
| `requestBasicUserProfile(userId, callback)` | Non-blocking profile request; delivers result via callback. |
| `requestUserProfileOnDemand(userId, roomId)` | Batches demand-driven profile requests (debounced by `scheduleProfileBatchFlush`). |
| `requestRoomSpecificUserProfile(roomId, userId)` | Requests the room-specific avatar/display name. |
| `requestFullMemberList(roomId)` | Requests the complete member list for a room. |
| `requestFullUserInfo(userId, forceRefresh, callback)` | Requests full user info including devices and cross-signing state. |
| `getUserProfile(userId, roomId)` | Reads from the in-memory profile cache (no network). |
| `updateGlobalProfile(userId, profile)` | Writes a profile update to all caches and triggers UI refresh. |
| `validateAndRequestMissingProfiles(roomId, timelineEvents)` | Scans timeline events and fetches profiles for senders not yet in cache. |

### Message Versions & Edits

| Function | Description |
|----------|-------------|
| `getMessageVersions(eventId)` | Returns the full edit history list for `eventId`. |
| `isMessageEdited(eventId)` | Returns `true` if `eventId` has at least one edit. |
| `getBodyTextForEdit(event)` | Returns the current body text pre-filled into the edit composer. |
| `getLatestMessageVersion(eventId)` | Returns the most recent edited content for `eventId`. |
| `clearMessageVersions()` | Clears all edit-chain data (called on full refresh). |
| `clearMessageVersionsForRoom(roomId)` | Clears edits for one room. |

### Read Receipts

| Function | Description |
|----------|-------------|
| `getReadReceiptsMap()` | Returns a read-only copy of the global receipt map. |
| `getReceiptMovements()` | Returns the receipt animation map (`userId → movements`). |
| `markTimelineForeground(roomId)` | Records that the timeline UI is currently displaying `roomId`. |
| `getTimelineForegroundTimestamp(roomId)` | Returns the last foreground timestamp for `roomId`. |

### Reactions

| Function | Description |
|----------|-------------|
| `processReactionEvent(reactionEvent, isHistorical)` | Adds or removes a reaction from `messageReactions`. Historical flag prevents duplicate processing. |
| `updateRecentEmojis(emoji)` | Prepends `emoji` to `recentEmojis` and persists. |

### Room State & Management

| Function | Description |
|----------|-------------|
| `requestRoomState(roomId)` | Sends `get_room_state` to the backend. |
| `requestRoomStateWithMembers(roomId, callback)` | Sends `get_room_state` and also requests the full member list. |
| `setRoomTag(roomId, tagType, enabled)` | Adds or removes a tag (`m.favourite`, `m.lowpriority`) via the backend. |
| `pinUnpinEvent(roomId, eventId, pin)` | Sends a `m.room.pinned_events` state update. |
| `setRoomMemberAvatar(roomId, mxcUrl)` | Updates own `m.room.member` avatar in the room. |
| `setRoomAvatar(roomId, mxcUrl)` | Sets the room's `m.room.avatar`. |
| `setGlobalAvatar(mxcUrl)` | Updates the user's global avatar on the homeserver. |
| `leaveRoom(roomId, reason)` | Sends `leave` request. |
| `banUser(roomId, userId, reason, redactSystemMessages)` | Bans `userId` and optionally redacts their system messages. |
| `redactEvent(roomId, eventId, reason)` | Redacts an event (hard delete on the server). |
| `executeCommand(roomId, text, context, navController)` | Parses and executes a `/slash` command entered in the composer. |
| `getRoomState(roomId)` | Returns the cached room state JSON array for `roomId`. |
| `getRoomDisplayName(roomId)` | Returns the display name string for `roomId`. |

### Room Summary & Joining

| Function | Description |
|----------|-------------|
| `joinRoomWithCallback(roomIdOrAlias, viaServers, callback)` | Joins a room and delivers result via callback. |
| `joinRoomAndNavigate(roomId, navController)` | Joins a room then navigates to its timeline. |
| `resolveRoomAlias(alias, callback)` | Resolves a `#alias:server` to a room ID. |
| `getRoomSummary(roomIdOrAlias, viaServers, callback)` | Fetches a room preview (used for invite previews). |
| `acceptRoomInvite(roomId)` / `refuseRoomInvite(roomId)` | Accepts or declines a pending invitation. |
| `getPendingInvites()` | Returns the list of pending room invitations. |

### Spaces

| Function | Description |
|----------|-------------|
| `enterSpace(spaceId)` / `exitSpace()` | Navigates into or out of a space; updates `currentSpaceId`. |
| `enterBridge(bridgeId)` / `exitBridge()` | Navigates into or out of a bridge view; updates `currentBridgeId`. |
| `changeSelectedSection(section)` | Switches the active tab in `RoomListScreen`. |
| `setSpaces(spaces, skipCounterUpdate)` | Updates `spaceList` and optionally increments the update counter. |
| `updateAllSpaces(spaces)` | Updates both `allSpaces` and `SpaceListCache`. |
| `registerSpaceIds(spaceIds)` | Registers space IDs before their data arrives. |
| `storeSpaceEdges(spaceEdges)` | Persists the space hierarchy graph for quick parent/child queries. |

### Notifications & FCM

| Function | Description |
|----------|-------------|
| `initializeFCM(context, homeserverUrl, authToken, skipCacheClear)` | Initializes FCM and (if needed) clears old push registration state. |
| `registerFCMNotifications()` | Requests a new FCM token from Firebase. |
| `registerFCMWithGomuksBackend(forceRegistrationOnConnect, forceNow)` | Sends the FCM token to the gomuks backend. |
| `shouldRegisterPush()` | Returns `true` if the push token should be re-registered (time-based backoff). |
| `markPushRegistrationCompleted()` | Records that push registration succeeded and resets the backoff. |
| `handleFCMRegistrationResponse(requestId, data)` | Handles the backend's acknowledgment of FCM registration. |
| `requestPinShortcut(room)` | Requests Android to create a home-screen shortcut for a room. |
| `updateLowPriorityRooms(rooms)` | Updates the low-priority room set used for notification filtering. |
| `checkAndProcessPendingItemsOnStartup(context)` | Processes notification actions queued while the app was killed. |
| `executePendingNotificationActions()` | Fires any queued notification actions (e.g., navigate to room). |

### Navigation Targets

| Function | Description |
|----------|-------------|
| `setPendingRoomNavigation(roomId, fromNotification)` | Queues a room to navigate to once the UI is ready. |
| `getPendingRoomNavigation()` / `clearPendingRoomNavigation()` | Gets and clears the pending room target. |
| `setDirectRoomNavigation(roomId, timestamp)` | Overrides pending navigation with a direct (high-priority) target. |
| `getDirectRoomNavigation()` / `clearDirectRoomNavigation()` | Gets and clears the direct navigation target. |
| `setPendingUserInfoNavigation(userId)` | Queues a user-info sheet to open. |
| `setPendingBubbleNavigation(roomId)` | Queues a bubble window to open. |
| `setPendingHighlightEvent(roomId, eventId)` | Marks an event to highlight (scroll-to) when the timeline opens. |
| `consumePendingHighlightEvent(roomId)` | Returns and clears the highlight event for `roomId`. |

### Share Intents

| Function | Description |
|----------|-------------|
| `setPendingShare(roomId, payload)` | Stores a share payload for a room. |
| `clearPendingShare()` | Clears all pending share state. |
| `selectPendingShareRoom(roomId)` | Designates which room the share targets. |
| `consumePendingShareForRoom(roomId)` | Returns and removes the share payload for `roomId`. |
| `markPendingShareNavigationHandled()` | Marks the share navigation as complete. |

### Mentions

| Function | Description |
|----------|-------------|
| `requestMentionsList(maxTimestamp, limit)` | Fetches a paginated list of `@`-mention events. |
| `processMentionEvents(events)` | Stores fetched mention events for display in `MentionsScreen`. |

### Threading

| Function | Description |
|----------|-------------|
| `getThreadMessages(roomId, threadRootEventId)` | Returns all events in a thread rooted at `threadRootEventId`. |
| `sendThreadReply(roomId, text, threadRootEventId, mentions)` | Sends a reply in a thread. |

### Gallery

| Function | Description |
|----------|-------------|
| `requestGalleryPaginate(roomId, limit, fromEventId)` | Paginates backward through media events for the media gallery screen. |

### Encryption

| Function | Description |
|----------|-------------|
| `requestUserEncryptionInfo(userId, callback)` | Fetches a user's cross-signing and device key state. |
| `trackUserDevices(userId, callback)` | Sends a request to start tracking `userId`'s devices. |
| `isUserIgnored(userId)` | Returns `true` if `userId` is on the ignore list. |
| `setIgnoredUser(userId, ignore)` | Adds/removes `userId` from the ignore list. |

### Mutual Rooms

| Function | Description |
|----------|-------------|
| `requestMutualRooms(userId, callback)` | Requests the list of rooms shared with `userId`. |

### Settings & Auth

| Function | Description |
|----------|-------------|
| `loadStateFromStorage(context)` | Restores all persisted fields from `SharedPreferences` at startup. |
| `loadSettings(context)` | Loads user-facing settings (compression, Enter behavior, etc.). |
| `handleUnauthorizedError()` | Handles HTTP 401: clears credentials and redirects to `LoginScreen`. |
| `handleClientState(userId, device, homeserver)` | Updates identity fields from a `client_state` backend message. |
| `updateHomeserverUrl(url)` / `updateAuthToken(token)` / `updateImageAuthToken(token)` | Update individual credential fields and persist them. |
| `toggleCompression()` | Toggles image compression before upload. |
| `toggleEnterKeyBehavior()` | Toggles whether Enter sends the message or inserts a newline. |
| `toggleLoadThumbnailsIfAvailable()` | Toggles downloading server-generated thumbnails. |
| `toggleRenderThumbnailsAlways()` | Toggles always rendering thumbnails regardless of size. |
| `toggleShowAllRoomListTabs()` | Toggles showing all section tabs or only non-empty ones. |
| `toggleMoveReadReceiptsToEdge()` | Toggles whether read receipt avatars anchor to the message edge. |
| `toggleTrimLongDisplayNames()` | Toggles truncating long display names in the timeline. |
| `updateElementCallBaseUrl(url)` | Persists the Element Call widget base URL. |
| `updateBackgroundPurgeInterval(minutes)` / `updateBackgroundPurgeThreshold(count)` | Configures background data purge behaviour. |
| `setCustomProfileField(field, value)` | Sets an arbitrary field on the current user's profile. |

### Upload Management

| Function | Description |
|----------|-------------|
| `beginUpload(roomId, uploadType)` / `endUpload(roomId, uploadType)` | Marks upload start/end for the progress indicator. |
| `setUploadProgress(roomId, key, progress)` | Updates per-file upload progress (0.0–1.0). |
| `getUploadProgress(roomId)` | Returns the progress map for a room's active uploads. |
| `hasUploadInProgress(roomId)` | Returns `true` if any upload is active for `roomId`. |
| `getUploadType(roomId)` | Returns the active upload type string for `roomId`. |

### Cache Population & Refresh

| Function | Description |
|----------|-------------|
| `populateRoomMapFromCache()` | Reloads `roomMap` from `RoomListCache` (called on VM attach). |
| `populateSpacesFromCache()` | Reloads `spaceList` from `SpaceListCache`. |
| `populateReadReceiptsFromCache()` | Merges `ReadReceiptCache` into `readReceipts`, evicting stale per-user placements. |
| `populateMessageReactionsFromCache()` | Reloads reactions from `ReactionsCache`. |
| `populateRecentEmojisFromCache()` | Reloads recent emoji list from `SharedPreferences`. |
| `populatePendingInvitesFromCache()` | Reloads pending invitations from `InviteCache`. |
| `populateRoomMemberCacheFromCache()` | Reloads member profiles from `RoomMemberCache`. |
| `populateEmojiPacksFromCache()` | Reloads custom emoji packs from `EmojiPackCache`. |
| `populateStickerPacksFromCache()` | Reloads sticker packs from `StickerPackCache`. |
| `refreshUIFromCache()` | Calls all `populate*` methods then increments UI counters. |
| `refreshTimelineUI()` | Forces a timeline rebuild from the current cache state. |

### Cache Inspection

| Function | Description |
|----------|-------------|
| `getAllMemoryCachedProfiles()` | Suspending. Returns all in-memory cached `MemberProfile` objects. |
| `getAllMemoryCachedMedia(context)` | Suspending. Returns all in-memory cached media (Coil cache). |
| `getAllDiskCachedMedia(context)` | Suspending. Returns all on-disk cached media. |
| `loadCachedProfiles(context)` | Suspending. Loads persisted profiles from disk. |
| `getCacheStatistics(context)` | Suspending. Returns human-readable cache size statistics. |

### Request Routing

| Function | Description |
|----------|-------------|
| `getAndIncrementRequestId()` / `getNextRequestId()` | Returns and increments the monotonic request ID counter. |
| `trackOutgoingRequest(requestId, roomId)` | Associates a request ID with a room so responses can be routed correctly. |
| `handleResponse(requestId, data)` | Master dispatcher: routes every incoming `response` message to the correct handler. |
| `handleError(requestId, errorMessage)` | Handles error responses from the backend. |
| `handleTimelineResponse(requestId, data)` | Processes a `get_room_timeline` response. |
| `handleRoomStateResponse(requestId, data)` | Processes a `get_room_state` response. |
| `handleMessageResponse(requestId, data)` | Processes a single-message fetch response. |
| `handleRoomStateWithMembersResponse(requestId, data)` | Processes room state + member list response. |
| `handleGalleryPaginateResponse(requestId, data)` | Processes media gallery pagination response. |
| `handleEventResponse(requestId, data)` | Processes a single event fetch response. |
| `handleEventContextResponse(requestId, data)` | Processes event-with-context fetch response. |
| `handleResolveAliasResponse(requestId, data)` | Delivers alias resolution result to waiting callback. |
| `handleGetRoomSummaryResponse(requestId, data)` | Delivers room summary to waiting callback. |
| `handleJoinRoomCallbackResponse(requestId, data)` | Delivers join result to waiting callback. |
| `handleMarkReadResponse(requestId, success)` | Processes mark-as-read confirmation. |
| `handleMentionsListResponse(requestId, data)` | Stores the mentions list for `MentionsScreen`. |
| `handleMutualRoomsResponse(requestId, data)` | Delivers mutual rooms list to waiting callback. |
| `handleEmojiPackResponse(roomId, packName, data)` | Stores a fetched emoji pack. |
| `handleFreshnessCheckResponse(requestId, data)` | Processes a cache-freshness verification response. |
| `handleRunId(runId, vapidKey)` | Stores `run_id` and VAPID key received after connection. |

### Calls & Widgets

| Function | Description |
|----------|-------------|
| `setCallActive(active)` / `isCallActive()` | Marks/queries whether a video call is in progress. |
| `setCallReadyForPip(ready)` / `isCallReadyForPip()` | Marks/queries picture-in-picture readiness. |
| `setWidgetToDeviceHandler(handler)` | Registers a handler for `to_device` messages forwarded from an Element Call widget. |
| `handleToDeviceMessage(data)` | Dispatches a `to_device` message to the registered widget handler. |
| `sendWidgetCommand(command, data, onResult)` | Sends a command to the active widget and delivers the result via callback. |

### App Lifecycle

| Function | Description |
|----------|-------------|
| `onAppBecameVisible()` | Called when the app returns to the foreground; triggers quick refresh if stale. |
| `onAppBecameInvisible()` | Called when the app goes to the background; persists state. |
| `logActivity(event, networkType)` | Appends an entry to the in-memory activity log for diagnostics. |
| `getActivityLog()` | Returns the activity log. |
| `performPeriodicMemoryCleanup()` | Evicts stale entries from in-memory caches on a timer. |

### Internal Helpers

| Function | Description |
|----------|-------------|
| `generateTimelineStateHash(events)` | Produces a hash of the rendered event list for change detection. |
| `normalizeTimestamp(primary, vararg fallbacks)` | Returns the first valid (> 0) timestamp from a set of candidates. |
| `scheduleProfileBatchFlush()` | Debounces pending profile requests; flushes them after a short delay. |
| `flushProfileBatch()` | Sends accumulated profile requests in a single batch. |
| `findChainEnd(startEventId)` | Walks the edit chain to find the terminal event. |
| `findChainEndOptimized(startEventId, cache)` | Memoized version of `findChainEnd`. |
| `getFinalEventForBubble(entry)` | Returns the event that should be displayed in the message bubble (latest edit). |
| `findSupersededEvents(newEvent, existingEvents)` | Finds older versions of `newEvent` already in the timeline. |
| `mergeEditContent(originalEvent, editEvent)` | Copies edited body/content into the original event representation. |
| `invalidateRoomSectionCache()` | Clears the cached `RoomSectionSnapshot`. |
| `parseAndLogRoomStateError(errorStr)` | Parses and logs a room state error string for debugging. |
| `parseBridgeInfoEvent(event)` | Extracts `BridgeInfo` from a `m.bridge` state event JSON object. |
| `formatBytes(bytes)` | Formats a byte count as a human-readable string (KB/MB/GB). |
| `hasTimelineEntrancePlayed(eventId)` / `markTimelineEntrancePlayed(eventId)` | Tracks which event entrance animations have already played. |
| `setAutoPaginationEnabled(enabled, reason)` | Enables/disables the auto-pagination feature flag (used for testing). |
| `hasPendingTimelineRequest(roomId)` | Returns `true` if a timeline fetch is already in flight for `roomId`. |
