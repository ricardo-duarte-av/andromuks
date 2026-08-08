package net.vrkknn.andromuks.utils

import net.vrkknn.andromuks.PowerLevelsInfo

/**
 * Power-level predicates for the actions the UI gates on.
 *
 * These were previously inlined at every call site — `canSendMessage` in three screens,
 * `canPin`/`canUnpin` as near-identical blocks in two files, `canRedactOthersMessages` in three.
 * Being buried inside composables also made them untestable, which is how one of the copies came to
 * compute `canPin` against the wrong `state_default`.
 *
 * ## Unknown power levels are NOT uniform
 *
 * Each predicate keeps the fallback its call sites already had, because they are deliberately
 * different and flipping any of them would be a behaviour change:
 *
 * - [canSendMessage] **fails open**. The composer is enabled when we don't know, and the server
 *   rejects the send if we were wrong. Failing closed would lock people out of rooms they can post
 *   in, on nothing more than a slow state fetch.
 * - Everything else **fails closed**. Offering a moderation action that the server will refuse is
 *   worse than hiding one, and these are all recoverable by reopening the screen once state lands.
 *
 * With room state now persisted and hydrated on cold start, "unknown" is far rarer than it was —
 * but it is still reachable for a room whose state has never been fetched.
 */
object RoomPermissions {

    /** Matrix's default for anything not otherwise specified. */
    private const val DEFAULT_POWER_LEVEL = 50

    /**
     * [userId]'s power level: their explicit entry, else the room's `users_default`, else 0 when
     * power levels are unknown.
     */
    fun powerLevelOf(powerLevels: PowerLevelsInfo?, userId: String?): Int {
        if (powerLevels == null || userId.isNullOrBlank()) return 0
        return powerLevels.users[userId] ?: powerLevels.usersDefault
    }

    /**
     * Whether [userId] may send messages.
     *
     * [isEncrypted] decides which event type the check is against: in an E2EE room the client sends
     * `m.room.encrypted`, so that is what the homeserver enforces power levels on —
     * `m.room.message` never reaches the server at all. A null (unknown) encryption state is
     * treated as unencrypted, matching what the send path itself does.
     *
     * Fails open — see the class doc.
     */
    fun canSendMessage(powerLevels: PowerLevelsInfo?, userId: String?, isEncrypted: Boolean?): Boolean {
        if (powerLevels == null || userId.isNullOrBlank()) return true
        val eventType = if (isEncrypted == true) "m.room.encrypted" else "m.room.message"
        val required = powerLevels.events[eventType] ?: powerLevels.eventsDefault
        return powerLevelOf(powerLevels, userId) >= required
    }

    /**
     * Whether [userId] may redact *other people's* messages.
     *
     * Matrix lets anyone redact their own regardless, so callers gate only others' events on this.
     * It is a flat comparison against the room's `redact` level — deliberately not a comparison
     * with the target's power level, which is a kick/ban rule, not a redaction one.
     */
    fun canRedactOthers(powerLevels: PowerLevelsInfo?, userId: String?): Boolean {
        val required = powerLevels?.redact ?: DEFAULT_POWER_LEVEL
        return powerLevelOf(powerLevels, userId) >= required
    }

    /**
     * Whether [userId] may pin or unpin events.
     *
     * `m.room.pinned_events` is a **state** event, so an unlisted level falls back to
     * `state_default`, not `events_default`. Getting that wrong silently compares against 0 in most
     * rooms and offers pinning to everyone.
     */
    fun canPin(powerLevels: PowerLevelsInfo?, userId: String?): Boolean {
        val required = powerLevels?.events?.get("m.room.pinned_events")
            ?: powerLevels?.stateDefault
            ?: DEFAULT_POWER_LEVEL
        return powerLevelOf(powerLevels, userId) >= required
    }

    /**
     * Whether [actorUserId] may kick [targetUserId].
     *
     * Requires clearing the room's `kick` level *and* being strictly above the target: Matrix does
     * not let you kick a peer at your own level.
     */
    fun canKick(powerLevels: PowerLevelsInfo?, actorUserId: String?, targetUserId: String?): Boolean {
        if (powerLevels == null) return false
        val mine = powerLevelOf(powerLevels, actorUserId)
        return mine >= powerLevels.kick && mine > powerLevelOf(powerLevels, targetUserId)
    }

    /** Whether [actorUserId] may ban [targetUserId]. Same shape as [canKick], against `ban`. */
    fun canBan(powerLevels: PowerLevelsInfo?, actorUserId: String?, targetUserId: String?): Boolean {
        if (powerLevels == null) return false
        val mine = powerLevelOf(powerLevels, actorUserId)
        return mine >= powerLevels.ban && mine > powerLevelOf(powerLevels, targetUserId)
    }

    /**
     * Whether [userId] may redact, for the moderation UI on a user's profile.
     *
     * Differs from [canRedactOthers] only in failing closed on unknown power levels: that screen
     * shows moderation buttons, where offering an action the server will refuse is the worse error.
     */
    fun canRedactAsModerator(powerLevels: PowerLevelsInfo?, userId: String?): Boolean {
        if (powerLevels == null) return false
        return powerLevelOf(powerLevels, userId) >= powerLevels.redact
    }
}
