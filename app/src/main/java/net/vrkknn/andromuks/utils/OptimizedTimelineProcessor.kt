package net.vrkknn.andromuks.utils

import android.util.Log
import net.vrkknn.andromuks.TimelineEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Optimized timeline event processing with single-pass algorithms.
 * 
 * This replaces the previous multi-pass event processing that caused
 * performance issues with large timelines. Uses optimized algorithms
 * and better data structures for improved performance.
 */
object OptimizedTimelineProcessor {
    private const val TAG = "OptimizedTimelineProcessor"
    
    /**
     * Process timeline events with optimized single-pass algorithms.
     * 
     * This replaces the previous multi-pass processing that caused
     * performance issues. Uses sequences for lazy evaluation and
     * optimized filtering algorithms.
     * 
     * @param timelineEvents List of raw timeline events
     * @param showUnprocessedEvents Whether to show unprocessed events
     * @param allowedEventTypes Set of allowed event types
     * @return Processed and filtered timeline events
     */
    suspend fun processTimelineEventsOptimized(
        timelineEvents: List<TimelineEvent>,
        showUnprocessedEvents: Boolean,
        allowedEventTypes: Set<String>
    ): List<TimelineEvent> = withContext(Dispatchers.Default) {
        
        Log.d(TAG, "Processing ${timelineEvents.size} events with optimized algorithms")
        
        // PERFORMANCE: Single pass with optimized filtering using sequences
        val processedEvents = timelineEvents
            .asSequence() // Use sequence for lazy evaluation
            .filter { event -> 
                // PERFORMANCE: Optimized event type filtering
                when {
                    event.type == "m.room.redaction" -> false
                    showUnprocessedEvents -> event.type != "m.room.redaction"
                    else -> allowedEventTypes.contains(event.type)
                }
            }
            .filter { event ->
                // PERFORMANCE: Optimized edit filtering
                if (event.type == "m.room.message" || event.type == "m.room.encrypted") {
                    val relatesTo = event.content?.optJSONObject("m.relates_to") 
                        ?: event.decrypted?.optJSONObject("m.relates_to")
                    relatesTo?.optString("rel_type") != "m.replace"
                } else true
            }
            .sortedBy { it.timestamp }
            .toList()
        
        Log.d(TAG, "Optimized processing complete: ${processedEvents.size} events (${timelineEvents.size - processedEvents.size} filtered)")
        processedEvents
    }
    
    /**
     * Build timeline items with optimized algorithms.
     * 
     * This replaces the previous timeline item building that used
     * multiple iterations. Uses single-pass algorithms and pre-computed
     * date formatting for better performance.
     * 
     * @param events List of processed timeline events
     * @return List of timeline items with pre-computed flags
     */
    fun buildTimelineItemsOptimized(events: List<TimelineEvent>): List<net.vrkknn.andromuks.TimelineItem> {
        if (events.isEmpty()) return emptyList()
        
        Log.d(TAG, "Building timeline items for ${events.size} events")
        
        val items = mutableListOf<net.vrkknn.andromuks.TimelineItem>()
        var lastDate: String? = null
        var previousEvent: TimelineEvent? = null
        
        // PERFORMANCE: Pre-compute date formatter to avoid repeated creation
        val dateFormatter = java.text.SimpleDateFormat("dd / MM / yyyy", java.util.Locale.getDefault())
        
        for (event in events) {
            // PERFORMANCE: Single-pass date formatting
            val eventDate = dateFormatter.format(java.util.Date(event.timestamp))
            
            // Add date divider if needed
            if (lastDate == null || eventDate != lastDate) {
                items.add(net.vrkknn.andromuks.TimelineItem.DateDivider(eventDate))
                lastDate = eventDate
                previousEvent = null // Date divider breaks consecutive grouping
            }
            
            // PERFORMANCE: Pre-compute flags efficiently
            val hasPerMessageProfile = event.content?.has("com.beeper.per_message_profile") == true ||
                                      event.decrypted?.has("com.beeper.per_message_profile") == true
            val isConsecutive = !hasPerMessageProfile && previousEvent?.sender == event.sender
            
            items.add(net.vrkknn.andromuks.TimelineItem.Event(
                event = event,
                isConsecutive = isConsecutive,
                hasPerMessageProfile = hasPerMessageProfile
            ))
            
            previousEvent = event
        }
        
        Log.d(TAG, "Timeline items built: ${items.size} items")
        return items
    }
    
    /**
     * Get visible event IDs from LazyListState for viewport-based loading.
     * 
     * This extracts the event IDs of items currently visible in the viewport
     * for optimized profile loading.
     * 
     * @param listState LazyListState from LazyColumn
     * @param timelineItems List of timeline items
     * @return Set of visible event IDs
     */
    fun getVisibleEventIds(
        listState: androidx.compose.foundation.lazy.LazyListState,
        timelineItems: List<net.vrkknn.andromuks.TimelineItem>
    ): Set<String> {
        return listState.layoutInfo.visibleItemsInfo
            .mapNotNull { visibleItem ->
                timelineItems.getOrNull(visibleItem.index)?.let { item ->
                    if (item is net.vrkknn.andromuks.TimelineItem.Event) {
                        item.event.eventId
                    } else null
                }
            }
            .toSet()
    }
    
    /**
     * Check if user is at the bottom of the timeline.
     * 
     * This replaces the previous complex bottom detection logic
     * with a simpler, more efficient algorithm.
     * 
     * @param listState LazyListState from LazyColumn
     * @param timelineItems List of timeline items
     * @return True if user is at the bottom
     */
    fun isAtBottom(
        listState: androidx.compose.foundation.lazy.LazyListState,
        timelineItems: List<net.vrkknn.andromuks.TimelineItem>
    ): Boolean {
        if (timelineItems.isEmpty()) return true
        
        val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        val lastTimelineItemIndex = timelineItems.lastIndex
        
        return lastVisibleIndex >= lastTimelineItemIndex - 1
    }
    
    /**
     * Check if auto-pagination should be triggered.
     * 
     * This replaces the previous complex pagination logic with
     * a simpler, more efficient algorithm.
     * 
     * @param listState LazyListState from LazyColumn
     * @param appViewModel AppViewModel instance
     * @return True if pagination should be triggered
     */
    fun shouldTriggerAutoPagination(
        listState: androidx.compose.foundation.lazy.LazyListState,
        appViewModel: net.vrkknn.andromuks.AppViewModel
    ): Boolean {
        val firstVisibleIndex = listState.firstVisibleItemIndex
        return firstVisibleIndex <= 5 && 
               appViewModel.hasMoreMessages && 
               !appViewModel.isPaginating
    }
}
