# Andromuks Database Migration Plan

## Overview
This document outlines the step-by-step migration from in-memory data structures to a persistent Room database for the Andromuks Matrix client.

## Benefits of Migration
1. **Persistent Storage**: Messages survive app restarts
2. **Efficient Reconnection**: Use `run_id` and `last_received_id` for delta sync
3. **Redaction Support**: Properly handle message redactions
4. **Offline Capability**: App works without network
5. **Performance**: Faster room loading with cached data
6. **Search**: Full-text search across message history

## Database Schema

### Core Entities
- **EventEntity**: Timeline events with redaction support
- **RoomEntity**: Room metadata and state
- **SyncStateEntity**: Sync state for reconnection
- **ReactionEntity**: Message reactions
- **UserProfileEntity**: User profile caching

### Key Features
- Room-based partitioning for efficient queries
- Redaction handling with `isRedacted`, `redactedBy`, `redactedAt`
- Sync state tracking with `runId` and `lastReceivedId`
- User profile caching with `isFromCache` flag

## Migration Steps

### Phase 1: Database Setup (Week 1)
1. **Add Room Dependencies**
   ```kotlin
   // app/build.gradle.kts
   implementation "androidx.room:room-runtime:$room_version"
   implementation "androidx.room:room-ktx:$room_version"
   kapt "androidx.room:room-compiler:$room_version"
   ```

2. **Create Database Classes**
   - ✅ `AndromuksDatabase.kt` - Main database class
   - ✅ `Converters.kt` - Type converters
   - ✅ Entity classes (EventEntity, RoomEntity, etc.)
   - ✅ DAO interfaces (EventDao, RoomDao, etc.)
   - ✅ `MatrixRepository.kt` - Repository layer

3. **Initialize Database in AppViewModel**
   ```kotlin
   class AppViewModel : ViewModel() {
       private val database = AndromuksDatabase.getDatabase(context)
       private val repository = MatrixRepository(
           database.eventDao(),
           database.roomDao(),
           database.syncStateDao(),
           database.reactionDao(),
           database.userProfileDao()
       )
   }
   ```

### Phase 2: Dual-Write Implementation (Week 2)
1. **Modify WebSocket Message Handling**
   - Update `handleTimelineResponse()` to write to database
   - Keep existing in-memory structures for backward compatibility
   - Add database operations alongside existing code

2. **Update Event Processing**
   ```kotlin
   // In NetworkUtils.kt - onMessage handler
   when (command) {
       "sync_complete" -> {
           // Existing code
           appViewModel.updateRoomsFromSyncJson(jsonObject)
           
           // NEW: Also write to database
           appViewModel.repository.processSyncComplete(roomId, events)
       }
   }
   ```

3. **Add Redaction Handling**
   ```kotlin
   // New method in AppViewModel
   suspend fun handleRedactionEvent(redactionEvent: TimelineEvent) {
       repository.handleRedactionEvent(redactionEvent)
   }
   ```

### Phase 3: UI Migration (Week 3)
1. **Update Room List Screen**
   - Change from `allRooms` state to database Flow
   - Implement real-time updates with Room's Flow
   - Add loading states for database operations

2. **Update Timeline Screen**
   - Change from `timelineEvents` state to database Flow
   - Implement pagination for large message histories
   - Add search functionality

3. **Update Profile Management**
   - Migrate from SharedPreferences to database
   - Implement profile caching strategy
   - Add profile search functionality

### Phase 4: Reconnection Logic (Week 4)
1. **Implement Sync State Management**
   ```kotlin
   // In NetworkUtils.kt - connectToWebsocket()
   fun connectToWebsocket(url: String, client: OkHttpClient, token: String, appViewModel: AppViewModel) {
       val syncParams = appViewModel.repository.getSyncParameters()
       val wsUrl = if (syncParams != null) {
           "$url?run_id=${syncParams.first}&last_received_id=${syncParams.second}"
       } else {
           url
       }
       // ... rest of connection logic
   }
   ```

2. **Update WebSocket URL Construction**
   - Store `runId` from initial connection
   - Use `lastReceivedId` for reconnection
   - Handle delta sync properly

3. **Add Connection State Tracking**
   - Track connection state in database
   - Implement automatic reconnection
   - Handle network changes gracefully

### Phase 5: Cleanup and Optimization (Week 5)
1. **Remove In-Memory Structures**
   - Remove `timelineEvents` state
   - Remove `allRooms` state
   - Remove `roomMemberCache` state
   - Update all references to use database

2. **Add Database Maintenance**
   - Implement cleanup for old events
   - Add database size monitoring
   - Optimize queries with proper indexing

3. **Add Error Handling**
   - Handle database errors gracefully
   - Implement fallback mechanisms
   - Add logging for debugging

## Implementation Details

### WebSocket Reconnection
```kotlin
// New method in AppViewModel
suspend fun reconnectWebSocket() {
    val syncParams = repository.getSyncParameters()
    if (syncParams != null) {
        val (runId, lastReceivedId) = syncParams
        val wsUrl = "wss://server/_gomuks/websocket?run_id=$runId&last_received_id=$lastReceivedId"
        connectToWebsocket(wsUrl, client, token, this)
    } else {
        // Initial connection
        connectToWebsocket(baseUrl, client, token, this)
    }
}
```

### Redaction Handling
```kotlin
// In NetworkUtils.kt - message handler
"m.room.redaction" -> {
    val redactionEvent = TimelineEvent.fromJson(eventJson)
    appViewModel.repository.handleRedactionEvent(redactionEvent)
}
```

### Room State Updates
```kotlin
// Update room metadata when events arrive
suspend fun updateRoomFromEvent(event: TimelineEvent) {
    val room = roomDao.getRoomById(event.roomId)
    if (room != null) {
        roomDao.updateMessagePreview(
            roomId = event.roomId,
            messagePreview = extractMessagePreview(event),
            messageSender = event.sender,
            timestamp = event.timestamp
        )
    }
}
```

## Testing Strategy

### Unit Tests
- Test DAO operations
- Test repository methods
- Test data conversion between entities and models

### Integration Tests
- Test WebSocket reconnection
- Test sync state management
- Test redaction handling

### Performance Tests
- Test database query performance
- Test memory usage with large datasets
- Test app startup time with database

## Rollback Plan

If issues arise during migration:
1. **Phase 1-2**: Database is additive, no breaking changes
2. **Phase 3**: Can revert UI changes to use in-memory structures
3. **Phase 4**: Can disable reconnection logic
4. **Phase 5**: Can keep both systems running

## Success Metrics

- **Performance**: Room loading time < 500ms
- **Reliability**: 99% successful reconnections
- **Storage**: Database size < 100MB for typical usage
- **User Experience**: No data loss during app restarts

## Timeline

- **Week 1**: Database setup and entity creation
- **Week 2**: Dual-write implementation
- **Week 3**: UI migration to database
- **Week 4**: Reconnection logic implementation
- **Week 5**: Cleanup and optimization

## Risk Mitigation

1. **Data Loss**: Implement comprehensive backup/restore
2. **Performance**: Monitor database size and query performance
3. **Compatibility**: Maintain backward compatibility during migration
4. **Testing**: Extensive testing at each phase

This migration will significantly improve the app's reliability, performance, and user experience while providing a solid foundation for future features.
