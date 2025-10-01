# Database Integration Guide

## Overview
This guide provides step-by-step instructions for integrating the new database layer into your existing Andromuks Matrix client.

## Files Created

### Database Layer
- `database/entities/` - Room database entities
- `database/dao/` - Data Access Objects
- `database/repository/MatrixRepository.kt` - Repository layer
- `database/websocket/` - WebSocket management with database
- `database/migration/MigrationHelpers.kt` - Migration utilities

### Integration Files
- `AppViewModelWithDatabase.kt` - Enhanced AppViewModel
- `MainActivityWithDatabase.kt` - Enhanced MainActivity
- `NetworkUtilsWithDatabase.kt` - Enhanced NetworkUtils

## Integration Steps

### Step 1: Update Dependencies ✅
The build files have been updated with Room dependencies:
- `gradle/libs.versions.toml` - Added Room version and libraries
- `app/build.gradle.kts` - Added Room dependencies and kapt plugin

### Step 2: Database Initialization
Add database initialization to your MainActivity:

```kotlin
// In MainActivity.onCreate()
private lateinit var databaseInitializer: DatabaseInitializer

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    
    // Initialize database
    databaseInitializer = DatabaseInitializer(this, CoroutineScope(Dispatchers.Main))
    
    // ... rest of your code
}
```

### Step 3: Gradual Migration
Implement dual-write pattern in your existing AppViewModel:

```kotlin
// Add to your existing AppViewModel
private lateinit var databaseInitializer: DatabaseInitializer
private lateinit var repository: MatrixRepository

// In initialization
fun initializeDatabase() {
    databaseInitializer = DatabaseInitializer(context, viewModelScope)
    repository = databaseInitializer.getRepository()
}

// Dual-write in existing methods
suspend fun handleTimelineResponse(requestId: Int, data: Any) {
    // Existing code...
    
    // NEW: Also write to database
    if (::repository.isInitialized) {
        repository.insertEvents(events)
    }
}
```

### Step 4: WebSocket Integration
Update your WebSocket handling to use the new database integration:

```kotlin
// In NetworkUtils.kt - onMessage handler
when (command) {
    "sync_complete" -> {
        // Existing code
        appViewModel.updateRoomsFromSyncJson(jsonObject)
        
        // NEW: Also handle with database
        if (::appViewModel.databaseInitializer.isInitialized) {
            appViewModel.databaseInitializer.getRepository().processSyncComplete(roomId, events)
        }
    }
}
```

### Step 5: UI Migration
Gradually update your UI to use database Flows:

```kotlin
// In RoomListScreen.kt
@Composable
fun RoomListScreen(appViewModel: AppViewModel) {
    // OLD: val rooms by appViewModel.allRooms
    // NEW: Use database Flow
    val rooms by appViewModel.allRooms.collectAsState(initial = emptyList())
    
    // ... rest of your UI code
}
```

## Migration Strategy

### Phase 1: Dual-Write (Week 1)
- Keep existing in-memory structures
- Add database writes alongside existing code
- Test that both systems work

### Phase 2: UI Migration (Week 2)
- Update UI to use database Flows
- Keep in-memory as fallback
- Test UI responsiveness

### Phase 3: Reconnection (Week 3)
- Implement database-backed reconnection
- Use `run_id` and `last_received_id`
- Test reconnection scenarios

### Phase 4: Cleanup (Week 4)
- Remove in-memory structures
- Clean up old SharedPreferences
- Optimize database queries

## Key Benefits

### Performance
- **Instant room loading** from cache
- **Efficient reconnection** with delta sync
- **Reduced server load** with local storage

### Reliability
- **No data loss** during app restarts
- **Offline capability** with cached data
- **Proper redaction handling**

### User Experience
- **Faster navigation** between rooms
- **Search functionality** across history
- **Better typing indicators**

## Testing Checklist

### Database Operations
- [ ] Events are stored correctly
- [ ] Rooms are updated properly
- [ ] User profiles are cached
- [ ] Reactions are aggregated

### WebSocket Integration
- [ ] Messages are processed correctly
- [ ] Redactions are handled
- [ ] Sync state is maintained
- [ ] Reconnection works

### UI Integration
- [ ] Room list updates in real-time
- [ ] Timeline loads from database
- [ ] Search works across history
- [ ] Performance is acceptable

## Troubleshooting

### Common Issues
1. **Database not initialized**: Check MainActivity initialization
2. **Migration fails**: Verify data format compatibility
3. **UI not updating**: Check Flow collection in Compose
4. **WebSocket reconnection**: Verify sync state management

### Debug Logging
Enable debug logging to track database operations:
```kotlin
// Add to your AppViewModel
private fun logDatabaseOperation(operation: String, data: Any) {
    android.util.Log.d("DatabaseDebug", "$operation: $data")
}
```

## Rollback Plan

If issues arise during migration:
1. **Phase 1-2**: Database is additive, no breaking changes
2. **Phase 3**: Can revert UI changes to use in-memory
3. **Phase 4**: Can disable reconnection logic
4. **Phase 5**: Can keep both systems running

## Success Metrics

- **Performance**: Room loading < 500ms
- **Reliability**: 99% successful reconnections
- **Storage**: Database size < 100MB
- **User Experience**: No data loss during restarts

## Next Steps

1. **Test the integration** with a small subset of data
2. **Monitor performance** during migration
3. **Backup existing data** before migration
4. **Plan rollback** if issues arise

This integration provides a robust, scalable foundation for your Matrix client with proper data persistence, efficient reconnection, and excellent user experience.
