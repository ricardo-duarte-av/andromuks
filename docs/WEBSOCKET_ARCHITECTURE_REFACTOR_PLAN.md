# WebSocket Architecture Refactor - Long-term Solution

## Overview

This document outlines the step-by-step plan to refactor the WebSocket architecture for better resilience when the primary AppViewModel is destroyed.

## Goals

1. **Resilience**: WebSocket callbacks survive primary AppViewModel destruction
2. **Automatic Recovery**: Secondary ViewModels can automatically become primary
3. **Health Monitoring**: Service monitors primary health and promotes when needed

## Implementation Steps

### Step 1: Move Callback Management to WebSocketService

**Goal**: Make service maintain primary callbacks instead of AppViewModel, so callbacks survive AppViewModel destruction.

#### Step 1.1: Add Callback Storage in WebSocketService
- Add `primaryReconnectionCallback: (String) -> Unit?` stored in service
- Add `primaryOfflineModeCallback: (Boolean) -> Unit?` stored in service
- Add `primaryActivityLogCallback: (String, String?) -> Unit?` stored in service
- Store callbacks as lambda functions (not AppViewModel references)

#### Step 1.2: Update AppViewModel to Register Callbacks with Service
- Modify `markAsPrimaryInstance()` to pass callbacks to service (not store in AppViewModel)
- Service stores callbacks directly
- AppViewModel no longer holds callback references

#### Step 1.3: Update Service to Use Stored Callbacks
- Service invokes stored callbacks when needed (reconnection, offline mode, activity log)
- Remove dependency on AppViewModel for callbacks
- Callbacks can work even if AppViewModel is destroyed

#### Step 1.4: Test Step 1
**Test Scenario**:
1. Start app normally (primary AppViewModel created)
2. Force stop MainActivity (primary AppViewModel destroyed)
3. Verify WebSocket reconnection still works (callbacks in service)
4. Verify offline mode detection still works
5. Verify activity logging still works

**Expected Result**: Callbacks continue working even after primary AppViewModel is destroyed.

---

### Step 2: Add ViewModel Lifecycle Tracking and Automatic Promotion

**Goal**: Service tracks which ViewModels are alive and automatically promotes secondary to primary when primary is destroyed.

#### Step 2.1: Add ViewModel Registration/Unregistration
- Add `registerViewModel(viewModelId: String, isPrimary: Boolean)` in service
- Add `unregisterViewModel(viewModelId: String)` in service
- Service maintains list of active ViewModels
- Track which ViewModel is primary

#### Step 2.2: Add Primary Promotion Logic
- When primary ViewModel unregisters, service checks for other ViewModels
- If secondary ViewModel exists, automatically promote it to primary
- Transfer primary callbacks to new primary (if callbacks are stored in service)
- Notify new primary ViewModel of promotion

#### Step 2.3: Update AppViewModel to Handle Promotion
- Add `onPromotedToPrimary()` method in AppViewModel
- When promoted, ViewModel registers as primary
- ViewModel registers primary callbacks with service
- ViewModel takes over WebSocket management

#### Step 2.4: Test Step 2
**Test Scenario**:
1. Start app normally (primary AppViewModel in MainActivity)
2. Open shortcut (secondary AppViewModel in ShortcutActivity)
3. Force stop MainActivity (primary destroyed)
4. Verify ShortcutActivity ViewModel is automatically promoted to primary
5. Verify WebSocket reconnection works with new primary
6. Verify messages are received in ShortcutActivity

**Expected Result**: Secondary ViewModel automatically becomes primary when original primary is destroyed.

---

### Step 3: Add Health Monitoring and Automatic Recovery

**Goal**: Service periodically checks if primary is alive and automatically promotes if needed (handles cases where primary is destroyed while app is backgrounded).

#### Step 3.1: Add Primary Health Check Method
- Add `isPrimaryAlive(): Boolean` in service
- Check if primary ViewModel is still registered
- Check if primary callbacks are still valid
- Return false if primary is missing or callbacks are invalid

#### Step 3.2: Add Periodic Health Monitoring
- Add health check timer (every 30 seconds)
- Service periodically checks primary health
- Log health status for debugging

#### Step 3.3: Add Automatic Promotion on Health Check Failure
- If primary health check fails, automatically promote next available ViewModel
- If no ViewModels available, log warning
- If WebSocket exists but no primary, create new primary from next MainActivity launch

#### Step 3.4: Test Step 3
**Test Scenario**:
1. Start app normally (primary AppViewModel in MainActivity)
2. Background app (MainActivity destroyed, but service running)
3. Wait 1 minute (health check should detect primary is missing)
4. Open shortcut (secondary ViewModel should be promoted to primary)
5. Verify WebSocket reconnection works
6. Verify messages are received

**Expected Result**: Service automatically detects missing primary and promotes secondary when available.

---

## Testing Strategy

### After Each Step

1. **Compile and Run**: Ensure no compilation errors
2. **Basic Functionality**: Verify app still works normally
3. **Step-Specific Test**: Run the test scenario for that step
4. **Regression Test**: Verify existing functionality still works

### Production Testing

- Test with real account on real device
- Monitor logs for errors
- Test edge cases (app backgrounded, force stop, etc.)
- Verify WebSocket reconnection works in all scenarios

## Rollback Plan

If issues are found:
1. Revert the step that caused problems
2. Test previous working state
3. Fix issues before proceeding to next step

## Success Criteria

- ✅ Callbacks work even when primary AppViewModel is destroyed
- ✅ Secondary ViewModels automatically become primary when needed
- ✅ Service automatically recovers from primary destruction
- ✅ No regressions in existing functionality
- ✅ WebSocket reconnection works in all scenarios

