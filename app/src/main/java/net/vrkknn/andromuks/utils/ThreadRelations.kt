package net.vrkknn.andromuks.utils

/**
 * Builds the `m.relates_to` object for a message posted into a thread.
 *
 * Shared by every thread send path — text ([net.vrkknn.andromuks.MessageSendCoordinator.sendThreadReply]),
 * media/stickers/locations (`buildMediaRelatesTo`) and polls
 * ([net.vrkknn.andromuks.PollCoordinator]) — because they previously each rebuilt this map and
 * disagreed about it (see GH #28).
 *
 * Two rules, both easy to get wrong:
 *
 * 1. **`m.in_reply_to` is always present.** It is what non-threaded clients anchor the message to, so
 *    omitting it strands the message for them. When the thread holds nothing but its root, the root
 *    itself is the anchor — hence [threadRootEventId] as the final fallback.
 *
 * 2. **[isFallback] is about user intent, not about whether a target resolved.** It says whether the
 *    `m.in_reply_to` above is a genuine reply the user made (`false`) or merely the thread anchor
 *    (`true`). Deriving it from "did we find an event to point at?" always yields `false`, because an
 *    anchor can essentially always be found — which is exactly the bug in #28: every attachment sent
 *    into a thread claimed to be a deliberate reply to whichever message happened to be last, and
 *    clients that render replies drew a spurious quote above it.
 *
 * @param threadRootEventId the thread's root event
 * @param replyToEventId the anchor to point at; falls back to [threadRootEventId] when null or blank
 * @param isFallback true when [replyToEventId] is only the thread anchor, false for a real reply
 */
internal fun threadRelatesTo(threadRootEventId: String, replyToEventId: String?, isFallback: Boolean): Map<String, Any> = mapOf(
    "rel_type" to "m.thread",
    "event_id" to threadRootEventId,
    "is_falling_back" to isFallback,
    "m.in_reply_to" to mapOf("event_id" to (replyToEventId?.takeIf { it.isNotBlank() } ?: threadRootEventId)),
)
