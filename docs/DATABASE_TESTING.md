# Database Testing

## Overview

Comprehensive test suite for the Room database persistence layer, covering event deduplication, ordering, edits/redactions, pagination, and performance benchmarks.

## Test Structure

### Test Files

1. **DatabaseTestUtils.kt** - Test utilities and helpers
   - In-memory database creation
   - Test data factories (events, room states, summaries)
   - Sync JSON builders

2. **EventDaoTest.kt** - DAO-level tests
   - Event insertion and retrieval
   - Event ordering (ASC/DESC)
   - Event deduplication via upsert
   - TTL deletion (`deleteEventsOlderThan`)

3. **SyncIngestorTest.kt** - Sync ingestion tests
   - Event persistence from `sync_complete`
   - Event deduplication across syncs
   - Edit event handling
   - Redaction handling
   - Run ID change detection and data clearing

4. **BootstrapLoaderTest.kt** - Bootstrap loading tests
   - Loading rooms from database
   - Loading events for specific rooms
   - Handling empty database
   - Handling missing run_id

5. **PaginationPersistenceTest.kt** - Pagination tests
   - Persisting paginated events
   - Event retrieval and ordering
   - Deduplication with sync_complete events

6. **DatabasePerformanceTest.kt** - Performance benchmarks
   - Bulk insert performance (1000 events)
   - Query performance with large datasets (5000 events)
   - TTL deletion performance
   - Event ordering with large datasets

## Test Coverage

### ✅ Event Deduplication
- **Test**: `EventDaoTest.testEventDeduplication`
- **Verifies**: Same event ID inserted twice results in single event (upsert behavior)
- **Test**: `SyncIngestorTest.testEventDeduplication`
- **Verifies**: Same event in multiple sync messages only stored once

### ✅ Event Ordering
- **Test**: `EventDaoTest.testEventOrdering`
- **Verifies**: Events retrieved in correct order (by `timelineRowId`)
- **Test**: `EventDaoTest.testGetEventsForRoomAsc`
- **Verifies**: Ascending order retrieval
- **Test**: `DatabasePerformanceTest.testEventOrderingWithLargeDataset`
- **Verifies**: Correct ordering with 1000 events inserted in random order

### ✅ Edit Event Handling
- **Test**: `SyncIngestorTest.testEditEventHandling`
- **Verifies**: Edit events are persisted with correct `relatesToEventId`
- **Verifies**: Both original and edit events are stored

### ✅ Redaction Handling
- **Test**: `SyncIngestorTest.testRedactionHandling`
- **Verifies**: Redaction events marked with `isRedaction=true`
- **Verifies**: Redaction linked to original event via `relatesToEventId`

### ✅ Pagination Persistence
- **Test**: `PaginationPersistenceTest.testPersistPaginatedEvents`
- **Verifies**: Paginated events correctly stored and retrieved
- **Test**: `PaginationPersistenceTest.testPaginatedEventsDeduplicateWithSyncEvents`
- **Verifies**: Paginated events don't create duplicates with sync_complete events

### ✅ Run ID Change Detection
- **Test**: `SyncIngestorTest.testRunIdChangeClearsData`
- **Verifies**: When `run_id` changes, old data is cleared
- **Verifies**: Only new events from new run remain

### ✅ Performance Benchmarks
- **Bulk Insert**: 1000 events in < 5 seconds
- **Query Performance**: 100 events from 5000 total in < 100ms
- **TTL Deletion**: 1000 events deleted in < 2 seconds
- **Ordering**: 1000 events correctly ordered despite random insert

## Running Tests

**Note**: Android instrumented tests require a connected device or emulator.

### Run All Database Tests
```bash
# Windows
.\gradlew.bat connectedDebugAndroidTest

# Linux/Mac
./gradlew connectedDebugAndroidTest
```

### Run Specific Test Class
```bash
# Windows
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=net.vrkknn.andromuks.database.EventDaoTest

# Linux/Mac
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=net.vrkknn.andromuks.database.EventDaoTest
```

### Run Performance Tests
```bash
# Windows
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=net.vrkknn.andromuks.database.DatabasePerformanceTest

# Linux/Mac
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=net.vrkknn.andromuks.database.DatabasePerformanceTest
```

### Run All Tests (All Variants)
```bash
# Windows
.\gradlew.bat connectedAndroidTest

# Linux/Mac
./gradlew connectedAndroidTest
```

## Test Architecture

### In-Memory Database
- Tests use `Room.inMemoryDatabaseBuilder()` for speed
- Database is created fresh for each test class
- No disk I/O, faster test execution

### Test Isolation
- Each test method is independent
- `@Before` sets up test database
- `@After` cleans up (closes database)

### Note on Singleton Pattern
- `SyncIngestor` uses `AndromuksDatabase.getInstance()` (singleton)
- Tests using `SyncIngestor` work with the actual database instance
- For more isolated testing, consider dependency injection in `SyncIngestor`

## Future Test Improvements

1. **Dependency Injection** - Make `SyncIngestor` and `BootstrapLoader` accept database instances for better test isolation

2. **Integration Tests** - Test full flow: sync_complete → persistence → bootstrap load

3. **Concurrency Tests** - Test concurrent writes and reads

4. **Edge Cases** - Test with malformed JSON, missing fields, very large events

5. **Migration Tests** - Test database schema migrations

## Test Results

### Expected Performance
- **Bulk Insert**: ~1-3ms per event (1000 events in 1-3 seconds)
- **Query**: < 50ms for 100 events from large dataset
- **TTL Deletion**: < 1 second for 1000 events
- **Ordering**: Correct ordering maintained regardless of insert order

### Success Criteria
- All tests pass
- No memory leaks (database properly closed)
- Performance within acceptable bounds
- Correct ordering and deduplication in all scenarios

