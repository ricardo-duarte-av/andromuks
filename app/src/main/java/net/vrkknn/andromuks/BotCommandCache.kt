package net.vrkknn.andromuks

import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import net.vrkknn.andromuks.utils.BotCommand

/**
 * BotCommandCache — the per-room index of MSC4391 command descriptions.
 *
 * A singleton, like the other caches, so a bubble or shortcut Activity's own AppViewModel sees the
 * same commands as the main one.
 *
 * **Why this is not part of [net.vrkknn.andromuks.utils.RoomStateStore]**, which already retains
 * arbitrary state event types: that store keeps only `content` in RAM, dropping `sender`; it has no
 * way to enumerate every state key of a type; and its raw tier is a 24-room LRU. All three are
 * fatal here. The `state_key` of a command description is `sha256(command + sender)`, so the sender
 * cannot be recovered from the key, and the sender is exactly what an invocation has to mention.
 *
 * Snapshot-backed (`mutableStateMapOf`) so a composer reading one room recomposes when that room's
 * commands change, mirroring `RoomStateStore.parsedStates`. Writes all come from AppViewModel's
 * response and sync handlers on the main thread; this is not a thread-safe store.
 *
 * **Deliberately not persisted.** `loadAllRoomStatesAfterInitComplete` sweeps `get_room_state` for
 * every room at startup, so the index refills itself within a second of connecting, and a new
 * SQLite table would mean a schema version bump for data with a one-round-trip lifetime. The one
 * gap — a cold-started secondary Activity whose room has not been swept yet — is covered by the
 * composer requesting room state on mount when [isIndexed] is false.
 */
object BotCommandCache {
    private const val TAG = "BotCommandCache"

    /** roomId -> (stateKey -> command). */
    private val commands = mutableStateMapOf<String, Map<String, BotCommand>>()

    /**
     * Replaces everything known about a room, from a full `get_room_state` response.
     *
     * Replace, never merge: a bot that removed a command (or left and had its descriptions cleared)
     * must lose it here too, and a full state response is authoritative about what exists. Callers
     * must therefore invoke this even when [parsed] is empty.
     */
    fun setRoomCommands(roomId: String, parsed: List<BotCommand>) {
        // An empty result still records the room as indexed, so the composer stops asking.
        commands[roomId] = parsed.associateBy { it.stateKey }
        if (BuildConfig.DEBUG && parsed.isNotEmpty()) {
            Log.d(TAG, "setRoomCommands: $roomId now has ${parsed.size} bot command(s)")
        }
    }

    /** Adds or replaces one command, from a live state event. */
    fun upsert(command: BotCommand) {
        val existing = commands[command.roomId] ?: emptyMap()
        commands[command.roomId] = existing + (command.stateKey to command)
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "upsert: '${command.command}' from ${command.sender} in ${command.roomId}")
        }
    }

    /**
     * Drops one command, for a redacted or emptied description.
     *
     * No-op for a room we have never indexed: creating an entry here would falsely mark the room as
     * indexed and suppress the composer's state request.
     */
    fun remove(roomId: String, stateKey: String) {
        val existing = commands[roomId] ?: return
        if (stateKey !in existing) return
        commands[roomId] = existing - stateKey
    }

    /** Every command advertised in a room, unfiltered — see `resolveBotCommands` for the filters. */
    fun rawCommandsFor(roomId: String): List<BotCommand> = commands[roomId]?.values?.toList() ?: emptyList()

    /**
     * Whether a full state response has been seen for this room.
     *
     * Distinguishes "this room has no bot commands" from "we have not looked yet", which is the
     * difference between rendering nothing and requesting room state.
     */
    fun isIndexed(roomId: String): Boolean = commands.containsKey(roomId)

    /** Drops a room, for the room-list prune path. */
    fun clearRoom(roomId: String) {
        commands.remove(roomId)
    }

    /** Drops everything, for logout. */
    fun clear() {
        commands.clear()
        if (BuildConfig.DEBUG) Log.d(TAG, "clear: dropped all bot commands")
    }
}
