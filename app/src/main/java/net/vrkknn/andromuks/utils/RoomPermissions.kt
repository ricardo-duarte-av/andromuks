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
     * First room version where m.room.create's sender and `additional_creators` hold power that
     * m.room.power_levels never mentions (MSC4289). Below it, creating a room buys no standing
     * beyond whatever `users` grants, so the create event tells us nothing about power.
     */
    private const val FIRST_CREATOR_POWER_ROOM_VERSION = 12

    /**
     * The room's creators, who outrank every explicit power level.
     *
     * From room version 12 the creator (the sender of m.room.create) and everyone in that event's
     * `additional_creators` are privileged *implicitly*: they are deliberately absent from
     * m.room.power_levels `users`, so reading power levels alone reports them at `users_default` —
     * which is how they ended up sorted in among ordinary members.
     *
     * Empty for older rooms. The room version is the gate rather than the mere presence of
     * `additional_creators`, because that key carries no meaning in a room whose version does not
     * define it and must not be allowed to confer standing there.
     */
    fun creatorsOf(state: net.vrkknn.andromuks.RoomState?): Set<String> {
        if (state == null) return emptySet()
        val version = state.roomVersion?.toIntOrNull() ?: return emptySet()
        if (version < FIRST_CREATOR_POWER_ROOM_VERSION) return emptySet()
        return buildSet {
            state.creator?.takeIf { it.isNotBlank() }?.let { add(it) }
            state.additionalCreators.filter { it.isNotBlank() }.forEach { add(it) }
        }
    }

    /**
     * The power level of a creator. Creators outrank every level a room can express, so this is
     * deliberately unreachable by m.room.power_levels, which cannot hold a value above this.
     *
     * Only ever compared, never used in arithmetic — adding to it would overflow.
     */
    const val CREATOR_POWER_LEVEL = Int.MAX_VALUE

    /**
     * [userId]'s power level: [CREATOR_POWER_LEVEL] for a creator, else their explicit entry, else
     * the room's `users_default`, else 0 when power levels are unknown.
     *
     * [creators] comes from [creatorsOf] and is empty below room version 12, which is what keeps
     * this identical to the old behaviour for every existing room.
     */
    fun powerLevelOf(powerLevels: PowerLevelsInfo?, creators: Set<String>, userId: String?): Int {
        if (userId.isNullOrBlank()) return 0
        if (userId in creators) return CREATOR_POWER_LEVEL
        if (powerLevels == null) return 0
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
    fun canSendMessage(powerLevels: PowerLevelsInfo?, creators: Set<String>, userId: String?, isEncrypted: Boolean?): Boolean {
        if (powerLevels == null || userId.isNullOrBlank()) return true
        val eventType = if (isEncrypted == true) "m.room.encrypted" else "m.room.message"
        val required = powerLevels.events[eventType] ?: powerLevels.eventsDefault
        return powerLevelOf(powerLevels, creators, userId) >= required
    }

    /**
     * Whether [userId] may redact *other people's* messages.
     *
     * Matrix lets anyone redact their own regardless, so callers gate only others' events on this.
     * It is a flat comparison against the room's `redact` level — deliberately not a comparison
     * with the target's power level, which is a kick/ban rule, not a redaction one.
     */
    fun canRedactOthers(powerLevels: PowerLevelsInfo?, creators: Set<String>, userId: String?): Boolean {
        val required = powerLevels?.redact ?: DEFAULT_POWER_LEVEL
        return powerLevelOf(powerLevels, creators, userId) >= required
    }

    /**
     * Whether [userId] may pin or unpin events.
     *
     * `m.room.pinned_events` is a **state** event, so an unlisted level falls back to
     * `state_default`, not `events_default`. Getting that wrong silently compares against 0 in most
     * rooms and offers pinning to everyone.
     */
    fun canPin(powerLevels: PowerLevelsInfo?, creators: Set<String>, userId: String?): Boolean {
        val required = powerLevels?.events?.get("m.room.pinned_events")
            ?: powerLevels?.stateDefault
            ?: DEFAULT_POWER_LEVEL
        return powerLevelOf(powerLevels, creators, userId) >= required
    }

    /**
     * Whether [actorUserId] may kick [targetUserId].
     *
     * Requires clearing the room's `kick` level *and* being strictly above the target: Matrix does
     * not let you kick a peer at your own level.
     *
     * Creators sit outside that arithmetic in both directions. A creator outranks every level, so
     * they clear any threshold — including when power levels are unknown entirely, since their
     * standing does not come from m.room.power_levels in the first place. And no one outranks a
     * creator, so a creator can never be kicked, not even by another creator: they are equals at
     * [CREATOR_POWER_LEVEL] and the rule is strictly-above.
     */
    fun canKick(powerLevels: PowerLevelsInfo?, creators: Set<String>, actorUserId: String?, targetUserId: String?): Boolean =
        canOutrank(powerLevels, creators, actorUserId, targetUserId, powerLevels?.kick)

    /** Whether [actorUserId] may ban [targetUserId]. Same shape as [canKick], against `ban`. */
    fun canBan(powerLevels: PowerLevelsInfo?, creators: Set<String>, actorUserId: String?, targetUserId: String?): Boolean =
        canOutrank(powerLevels, creators, actorUserId, targetUserId, powerLevels?.ban)

    /** Shared body of [canKick] and [canBan] — they differ only in which threshold they clear. */
    private fun canOutrank(powerLevels: PowerLevelsInfo?, creators: Set<String>, actorUserId: String?, targetUserId: String?, required: Int?): Boolean {
        val theirs = powerLevelOf(powerLevels, creators, targetUserId)
        if (theirs == CREATOR_POWER_LEVEL) return false
        val mine = powerLevelOf(powerLevels, creators, actorUserId)
        if (mine == CREATOR_POWER_LEVEL) return true
        if (required == null) return false
        return mine >= required && mine > theirs
    }

    /**
     * Whether [userId] may redact, for the moderation UI on a user's profile.
     *
     * Differs from [canRedactOthers] only in failing closed on unknown power levels: that screen
     * shows moderation buttons, where offering an action the server will refuse is the worse error.
     * A creator is the exception — their power is not expressed by power levels, so not having
     * fetched them says nothing about a creator.
     */
    fun canRedactAsModerator(powerLevels: PowerLevelsInfo?, creators: Set<String>, userId: String?): Boolean {
        if (userId != null && userId in creators) return true
        if (powerLevels == null) return false
        return powerLevelOf(powerLevels, creators, userId) >= powerLevels.redact
    }
}
