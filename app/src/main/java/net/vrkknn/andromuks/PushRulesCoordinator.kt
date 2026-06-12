package net.vrkknn.andromuks

import net.vrkknn.andromuks.utils.NotificationPreset
import net.vrkknn.andromuks.utils.PushAction
import net.vrkknn.andromuks.utils.PushCondition
import net.vrkknn.andromuks.utils.PushRule
import net.vrkknn.andromuks.utils.PushRuleKind
import net.vrkknn.andromuks.utils.PushRuleset
import net.vrkknn.andromuks.utils.RoomNotificationLevel
import net.vrkknn.andromuks.utils.actionsForPreset
import org.json.JSONObject

/**
 * Sends `update_push_rule` commands and applies optimistic updates to [AppViewModel.pushRuleset].
 *
 * Each edit mutates the in-memory ruleset immediately (snappy UI) and fires the matching
 * `update_push_rule` command; the next `m.push_rules` account-data sync re-parses and reconciles
 * the ruleset (see [SyncRoomsCoordinator.processAccountData]).
 *
 * Protocol: `update_push_rule { kind, rule_id, action, new_content?, actions? }` where action is one
 * of enable/disable/delete/put/put_actions (gomuks json-commands.go `UpdatePushRule`).
 */
internal class PushRulesCoordinator(private val vm: AppViewModel) {

    /** Enable or disable a rule (works for default and custom rules). */
    fun setRuleEnabled(kind: PushRuleKind, ruleId: String, enabled: Boolean) = with(vm) {
        send(kind, ruleId, if (enabled) "enable" else "disable", extra = emptyMap())
        mutate(kind, ruleId) { it.copy(enabled = enabled) }
    }

    /**
     * Replace a rule's actions via a known preset. Uses `put_actions` so it also works for default
     * rules (which can't be `put`).
     */
    fun setRulePreset(kind: PushRuleKind, ruleId: String, preset: NotificationPreset) = with(vm) {
        val wire = actionsForPreset(preset)
        send(kind, ruleId, "put_actions", extra = mapOf("actions" to wire))
        mutate(kind, ruleId) { it.copy(actions = wireActionsToModel(wire)) }
    }

    /** Replace a rule's actions with an explicit wire-shape list (used by the raw editor). */
    fun setRuleActions(kind: PushRuleKind, ruleId: String, actions: List<Any>) = with(vm) {
        send(kind, ruleId, "put_actions", extra = mapOf("actions" to actions))
        mutate(kind, ruleId) { it.copy(actions = wireActionsToModel(actions)) }
    }

    /** Create or replace a custom rule (`put`). Not valid for default rules. */
    fun putRule(
        kind: PushRuleKind,
        ruleId: String,
        actions: List<Any>,
        conditions: List<Map<String, Any>> = emptyList(),
        pattern: String = ""
    ) = with(vm) {
        val newContent = mapOf(
            "actions" to actions,
            "conditions" to conditions,
            "pattern" to pattern
        )
        send(kind, ruleId, "put", extra = mapOf("new_content" to newContent))
        val newRule = PushRule(
            kind = kind,
            ruleId = ruleId,
            default = false,
            enabled = true,
            conditions = conditions.map { conditionFromMap(it) },
            actions = wireActionsToModel(actions),
            pattern = pattern.takeIf { it.isNotEmpty() }
        )
        upsert(kind, newRule)
    }

    /** Delete a custom rule (`delete`). Not valid for default rules. */
    fun deleteRule(kind: PushRuleKind, ruleId: String) = with(vm) {
        send(kind, ruleId, "delete", extra = emptyMap())
        pushRuleset = pushRuleset.copyRemoving(kind, ruleId)
    }

    /**
     * Set a room's notification level via its room-scoped rule (rule_id = roomId):
     *  - ALL: put room rule with ["notify"]
     *  - MUTE: put room rule with [] (matches gomuks MuteRoom)
     *  - DEFAULT: delete the room rule (fall back to defaults)
     */
    fun setRoomNotificationLevel(roomId: String, level: RoomNotificationLevel) {
        when (level) {
            RoomNotificationLevel.ALL -> putRule(PushRuleKind.ROOM, roomId, actions = listOf("notify"))
            RoomNotificationLevel.MUTE -> putRule(PushRuleKind.ROOM, roomId, actions = emptyList())
            RoomNotificationLevel.DEFAULT -> deleteRule(PushRuleKind.ROOM, roomId)
        }
    }

    // ── internals ──────────────────────────────────────────────────────────

    private fun AppViewModel.send(
        kind: PushRuleKind,
        ruleId: String,
        action: String,
        extra: Map<String, Any>
    ) {
        val requestId = WebSocketService.allocateRequestId()
        val data = buildMap<String, Any> {
            put("kind", kind.apiName)
            put("rule_id", ruleId)
            put("action", action)
            putAll(extra)
        }
        if (BuildConfig.DEBUG) android.util.Log.d(
            "Andromuks",
            "PushRulesCoordinator: update_push_rule kind=${kind.apiName} rule_id=$ruleId action=$action"
        )
        sendWebSocketCommand("update_push_rule", requestId, data)
    }

    /** Apply [transform] to the matching rule (if present) and publish the new ruleset. */
    private fun AppViewModel.mutate(kind: PushRuleKind, ruleId: String, transform: (PushRule) -> PushRule) {
        val current = pushRuleset.rules(kind)
        if (current.none { it.ruleId == ruleId }) return
        val updated = current.map { if (it.ruleId == ruleId) transform(it) else it }
        pushRuleset = pushRuleset.copyReplacing(kind, updated)
    }

    /** Insert a new rule at the front of its kind, or replace an existing rule with the same id. */
    private fun AppViewModel.upsert(kind: PushRuleKind, rule: PushRule) {
        val current = pushRuleset.rules(kind)
        val updated = if (current.any { it.ruleId == rule.ruleId }) {
            current.map { if (it.ruleId == rule.ruleId) rule else it }
        } else {
            listOf(rule) + current
        }
        pushRuleset = pushRuleset.copyReplacing(kind, updated)
    }

    private fun wireActionsToModel(wire: List<Any>): List<PushAction> = wire.map { element ->
        when (element) {
            is Map<*, *> -> PushAction.fromJson(JSONObject(element.entries.associate { it.key.toString() to it.value }))
            else -> PushAction.fromJson(element)
        }
    }

    private fun conditionFromMap(map: Map<String, Any>): PushCondition = PushCondition(
        kind = map["kind"]?.toString() ?: "",
        key = map["key"]?.toString(),
        pattern = map["pattern"]?.toString(),
        value = map["value"],
        memberCountCondition = map["is"]?.toString(),
        relType = map["rel_type"]?.toString()
    )
}

private fun PushRuleset.copyReplacing(kind: PushRuleKind, rules: List<PushRule>): PushRuleset =
    PushRuleset(byKind.toMutableMap().apply { put(kind, rules) })

private fun PushRuleset.copyRemoving(kind: PushRuleKind, ruleId: String): PushRuleset =
    PushRuleset(byKind.toMutableMap().apply { put(kind, rules(kind).filterNot { it.ruleId == ruleId }) })
