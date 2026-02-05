package net.vrkknn.andromuks.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import net.vrkknn.andromuks.AppViewModel
import net.vrkknn.andromuks.RoomListUiState

/**
 * Composable function to remember and derive RoomListUiState from AppViewModel
 * This is separated from AppViewModel to keep the ViewModel file focused on business logic
 */
@Composable
fun AppViewModel.rememberRoomListUiState(): State<RoomListUiState> {
    // derivedStateOf automatically tracks all state reads inside its lambda
    // When currentUserProfile (or any other dependency) changes, derivedStateOf will recompute
    return remember {
        derivedStateOf {
            RoomListUiState(
                currentUserProfile = currentUserProfile,
                currentUserId = currentUserId,
                imageAuthToken = imageAuthToken,
                isProcessingPendingItems = isProcessingPendingItems,
                spacesLoaded = spacesLoaded,
                initialSyncComplete = initialSyncComplete,
                roomListUpdateCounter = roomListUpdateCounter,
                roomSummaryUpdateCounter = roomSummaryUpdateCounter,
                currentSpaceId = currentSpaceId,
                notificationActionInProgress = notificationActionInProgress,
                timestampUpdateCounter = timestampUpdateCounter,
                pendingSyncCompleteCount = pendingSyncCompleteCount,
                processedSyncCompleteCount = processedSyncCompleteCount
            )
        }
    }
}

