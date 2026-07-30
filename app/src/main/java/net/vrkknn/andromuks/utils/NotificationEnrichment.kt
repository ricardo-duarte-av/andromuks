package net.vrkknn.andromuks.utils

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Read-only `/exec` lookups that fill in *metadata* an FCM payload left out.
 *
 * Best-effort and budget-gated ([ExecBudget]): a failure yields "no enrichment", never a lost or
 * delayed notification. See docs/NOTIFICATIONS.md.
 *
 * ## What this must never do: render history
 *
 * An earlier version of this file also backfilled recent room messages (`paginate_manual`) and the
 * parent of a reply (`get_event`) into the notification's `MessagingStyle`. It was removed after
 * testing, and the reason is worth keeping so it is not rebuilt:
 *
 * A notification must only ever show messages that arrived **while that notification existed**.
 * `MessagingStyle` renders every line identically, so server-fetched history was indistinguishable
 * from new arrivals — one new message showed up as five, with no way to tell which was which, and
 * messages the user had already read and dismissed came back. Notifications accumulate naturally as
 * pushes arrive, and a dismiss is final; that is the contract, and fetching history breaks it.
 *
 * Metadata enrichment is a different thing and stays: it changes how the *current* message is
 * labelled, and adds no lines to the conversation.
 */
object NotificationEnrichment {
    /**
     * The room's display name via `get_room_summary`, for pushes that arrive without one.
     *
     * Worth a call because the room name is not cosmetic here: it becomes the conversation title,
     * the notification *channel* name and the shortcut label, and the channel name outlives the
     * notification that created it — so a raw `!abc:server.tld` sticks around. Returns null when
     * the budget is out or on any failure.
     */
    suspend fun fetchRoomName(context: Context, roomId: String, budget: ExecBudget): String? {
        if (!budget.tryConsume("room-summary")) return null
        return withContext(Dispatchers.IO) {
            val creds = ExecApi.readCredentials(context)
            if (!creds.isValid()) return@withContext null
            val body = JSONObject().apply { put("room_id", roomId) }
            val summary = ExecApi.callObject(creds, "get_room_summary", body)
            summary?.optString("name")?.takeIf { it.isNotBlank() }
                ?: summary?.optString("canonical_alias")?.takeIf { it.isNotBlank() }
        }
    }
}
