package net.vrkknn.andromuks.database.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Pre-resolved, render-ready timeline entry.
 *
 * This captures the already-processed state of an event (edits/redactions applied,
 * reactions aggregated, reply/thread references, and a display-ready body).
 * Downstream UI can bind directly without re-walking chains on room open.
 */
@Entity(
    tableName = "renderable_events",
    indices = [
        Index(value = ["roomId", "timelineRowId"]),
        Index(value = ["roomId", "timestamp"]),
        Index(value = ["roomId"])
    ]
)
data class RenderableEventEntity(
    @PrimaryKey val eventId: String,
    val roomId: String,
    val timelineRowId: Long,
    val timestamp: Long,
    val sender: String,
    /** Final event type to render (decrypted type if available, else raw). */
    val eventType: String,
    /** Matrix msgtype for m.room.message (if applicable). */
    val msgType: String?,
    /** Plain-text body after applying edits/redactions. */
    val body: String?,
    /** Formatted body (HTML/Markdown) if available after resolution. */
    val formattedBody: String?,
    /** Whether this event is redacted (resolved). */
    val isRedacted: Boolean,
    /** Whether this event was superseded by an edit (for UI badges). */
    val isEdited: Boolean,
    /** Reply target (if any). */
    val replyToEventId: String?,
    /** Thread root (if any). */
    val threadRootEventId: String?,
    /** Aggregated reactions JSON (emoji -> counts/users). */
    val aggregatedReactionsJson: String?,
    /** Optional lightweight parent preview JSON for replies/threads. */
    val parentPreviewJson: String?,
    /** Resolved content payload (media info, stickers, etc.) as JSON. */
    val contentJson: String?,
    /** Decrypted type if message was encrypted; useful for UI hints. */
    val decryptedType: String?
)

