package net.vrkknn.andromuks.utils

import org.json.JSONArray
import org.json.JSONObject

/**
 * Push rules domain model + parser.
 *
 * Matrix push rules live in the `m.push_rules` account-data event. Andromuks ingests that key via
 * the allowlist in [net.vrkknn.andromuks.SyncRoomsCoordinator] and stores it (wrapped as
 * `{ "content": { "global": { ... } } }`) in [net.vrkknn.andromuks.AccountDataCache]. This file
 * turns that raw JSON into a typed model the editor screens render, and back into the
 * Kotlin Map/List structures [net.vrkknn.andromuks.PushRulesCoordinator] sends as `update_push_rule`.
 *
 * Protocol notes (verified against gomuks v0.27 / mautrix pushrules):
 *  - Rule kinds (in evaluation order): override, content, room, sender, underride.
 *  - A rule: { rule_id, default, enabled, conditions[], actions[], pattern? }.
 *  - An action element is either a string ("notify"/"dont_notify"/"coalesce") or an object
 *    ({ "set_tweak": "sound", "value": "default" } / { "set_tweak": "highlight" }).
 *  - Default rules (rule_id starts with ".") cannot be `put`/`delete`d — only enabled/disabled or
 *    have their actions replaced (`put_actions`).
 */

enum class PushRuleKind(val apiName: String, val displayName: String) {
    OVERRIDE("override", "Override"),
    CONTENT("content", "Content"),
    ROOM("room", "Room"),
    SENDER("sender", "Sender"),
    UNDERRIDE("underride", "Underride");

    companion object {
        val ordered = listOf(OVERRIDE, CONTENT, ROOM, SENDER, UNDERRIDE)
        fun fromApiName(name: String): PushRuleKind? = entries.firstOrNull { it.apiName == name }
    }
}

/**
 * A simplified, lossless-enough representation of the notification behaviour a rule's `actions`
 * array encodes. [CUSTOM] is used for display only when the actions don't map to a known preset
 * (the user can still edit such rules via the raw-JSON fallback without losing data).
 */
enum class NotificationPreset(val label: String) {
    OFF("Off"),
    NOTIFY("Notify"),
    NOTIFY_SOUND("Notify + Sound"),
    HIGHLIGHT("Highlight"),
    CUSTOM("Custom");

    companion object {
        /** Presets offered as selectable chips (CUSTOM is display-only). */
        val selectable = listOf(OFF, NOTIFY, NOTIFY_SOUND, HIGHLIGHT)
    }
}

/** A single push action. Unknown shapes are preserved verbatim so editing never drops data. */
sealed class PushAction {
    object Notify : PushAction()
    object DontNotify : PushAction()
    object Coalesce : PushAction()
    data class SetSound(val value: String) : PushAction()
    data class SetHighlight(val value: Boolean) : PushAction()
    /** A tweak/action shape we don't model; the original JSON element is kept for round-tripping. */
    data class Unknown(val raw: Any) : PushAction()

    /** Serialize back to the wire shape (String or Map) consumed by `update_push_rule`. */
    fun toWire(): Any = when (this) {
        is Notify -> "notify"
        is DontNotify -> "dont_notify"
        is Coalesce -> "coalesce"
        is SetSound -> mapOf("set_tweak" to "sound", "value" to value)
        is SetHighlight -> mapOf("set_tweak" to "highlight", "value" to value)
        is Unknown -> raw
    }

    companion object {
        fun fromJson(element: Any?): PushAction = when (element) {
            is String -> when (element) {
                "notify" -> Notify
                "dont_notify" -> DontNotify
                "coalesce" -> Coalesce
                else -> Unknown(element)
            }
            is JSONObject -> {
                when (element.optString("set_tweak")) {
                    "sound" -> SetSound(element.opt("value")?.toString() ?: "default")
                    "highlight" -> SetHighlight(
                        // Per spec, a highlight tweak with no value defaults to true.
                        if (element.has("value")) element.optBoolean("value", true) else true
                    )
                    else -> Unknown(element)
                }
            }
            else -> Unknown(element ?: JSONObject())
        }
    }
}

/** A push condition (override/underride rules). Unknown keys are preserved in [extras]. */
data class PushCondition(
    val kind: String,
    val key: String? = null,
    val pattern: String? = null,
    val value: Any? = null,
    val memberCountCondition: String? = null,
    val relType: String? = null
) {
    fun toWire(): Map<String, Any> = buildMap {
        put("kind", kind)
        key?.let { put("key", it) }
        pattern?.let { put("pattern", it) }
        value?.let { put("value", it) }
        memberCountCondition?.let { put("is", it) }
        relType?.let { put("rel_type", it) }
    }

    /** Human-readable one-liner, e.g. `content.msgtype == "m.notice"`. */
    fun describe(): String = when (kind) {
        "event_match" -> "${key ?: "?"} matches \"${pattern ?: ""}\""
        "event_property_is" -> "${key ?: "?"} == ${value ?: ""}"
        "event_property_contains" -> "${key ?: "?"} contains ${value ?: ""}"
        "contains_display_name" -> "contains your display name"
        "room_member_count" -> "room member count ${memberCountCondition ?: ""}"
        "sender_notification_permission" -> "sender can @room notify"
        "related_event_match", "im.nheko.msc3664.related_event_match" ->
            "related event ${key ?: ""} matches \"${pattern ?: ""}\""
        else -> kind
    }

    companion object {
        fun fromJson(obj: JSONObject): PushCondition = PushCondition(
            kind = obj.optString("kind"),
            key = obj.optString("key").takeIf { it.isNotEmpty() },
            pattern = obj.optString("pattern").takeIf { it.isNotEmpty() },
            value = if (obj.has("value")) obj.opt("value") else null,
            memberCountCondition = obj.optString("is").takeIf { it.isNotEmpty() },
            relType = obj.optString("rel_type").takeIf { it.isNotEmpty() }
        )
    }
}

data class PushRule(
    val kind: PushRuleKind,
    val ruleId: String,
    val default: Boolean,
    val enabled: Boolean,
    val conditions: List<PushCondition>,
    val actions: List<PushAction>,
    val pattern: String?
) {
    /** A default (server-provided) rule cannot be created/replaced/deleted, only toggled or re-actioned. */
    val isDefault: Boolean get() = default || ruleId.startsWith(".")

    /** The preset the current [actions] most closely match, or [NotificationPreset.CUSTOM]. */
    fun notificationPreset(): NotificationPreset {
        val notify = actions.any { it is PushAction.Notify || it is PushAction.Coalesce }
        val dontNotify = actions.any { it is PushAction.DontNotify }
        val sound = actions.any { it is PushAction.SetSound }
        val highlight = actions.any { it is PushAction.SetHighlight && it.value }
        val hasUnknown = actions.any { it is PushAction.Unknown }
        return when {
            hasUnknown -> NotificationPreset.CUSTOM
            // Off: empty actions or an explicit dont_notify with no positive tweaks.
            actions.isEmpty() || (dontNotify && !notify) -> if (sound || highlight) NotificationPreset.CUSTOM else NotificationPreset.OFF
            notify && highlight && !sound -> NotificationPreset.HIGHLIGHT
            notify && sound && !highlight -> NotificationPreset.NOTIFY_SOUND
            notify && !sound && !highlight -> NotificationPreset.NOTIFY
            else -> NotificationPreset.CUSTOM
        }
    }

    /** Short subtitle shown under the rule title in the editor cards. */
    fun humanSummary(): String = when (kind) {
        PushRuleKind.CONTENT -> "matches \"${pattern ?: ""}\""
        PushRuleKind.SENDER -> "from $ruleId"
        PushRuleKind.ROOM -> "in $ruleId"
        PushRuleKind.OVERRIDE, PushRuleKind.UNDERRIDE ->
            if (conditions.isEmpty()) "always" else conditions.joinToString("; ") { it.describe() }
    }

    /** A display title; default rules get a tidied-up name derived from their dotted id. */
    fun displayTitle(): String = when {
        kind == PushRuleKind.CONTENT && !pattern.isNullOrBlank() -> pattern
        ruleId.startsWith(".") -> ruleId.substringAfterLast('.')
            .replace('_', ' ')
            .replaceFirstChar { it.uppercase() }
        else -> ruleId
    }

    /** True if this rule specifically targets [roomId] (room-kind by id, or a room_id condition). */
    fun affectsRoom(roomId: String): Boolean = when (kind) {
        PushRuleKind.ROOM -> ruleId == roomId
        PushRuleKind.OVERRIDE, PushRuleKind.UNDERRIDE ->
            conditions.any { it.kind == "event_match" && it.key == "room_id" && it.pattern == roomId }
        else -> false
    }

    /**
     * The room this rule targets, if any: the room-kind rule_id, or a `room_id` event_match
     * condition on an override/underride rule. Used to render the rule with the room's avatar/name.
     */
    fun targetRoomId(): String? = when (kind) {
        PushRuleKind.ROOM -> ruleId.takeIf { it.startsWith("!") }
        PushRuleKind.OVERRIDE, PushRuleKind.UNDERRIDE ->
            conditions.firstOrNull { it.kind == "event_match" && it.key == "room_id" }?.pattern
        else -> null
    }

    /** The user this rule targets, if any: the sender-kind rule_id (a Matrix user ID). */
    fun targetUserId(): String? = when (kind) {
        PushRuleKind.SENDER -> ruleId.takeIf { it.startsWith("@") }
        else -> null
    }

    /** True when the rule references a specific room or user (vs a general/content match). */
    val isTargeted: Boolean get() = targetRoomId() != null || targetUserId() != null
}

/** The full parsed `global` ruleset, keyed by kind. */
data class PushRuleset(val byKind: Map<PushRuleKind, List<PushRule>>) {
    fun rules(kind: PushRuleKind): List<PushRule> = byKind[kind].orEmpty()

    /** The room-scoped rule keyed by [roomId], if any (drives the per-room level selector). */
    fun roomRule(roomId: String): PushRule? = rules(PushRuleKind.ROOM).firstOrNull { it.ruleId == roomId }

    /** All rules (other than the room-scoped rule itself) that specifically target [roomId]. */
    fun rulesAffectingRoom(roomId: String): List<PushRule> =
        PushRuleKind.ordered.flatMap { rules(it) }
            .filter { it.affectsRoom(roomId) && !(it.kind == PushRuleKind.ROOM && it.ruleId == roomId) }

    companion object {
        val EMPTY = PushRuleset(emptyMap())
    }
}

/**
 * The room's effective notification level, derived from its `room` rule.
 *  - ALL: room rule with a `notify` action.
 *  - MUTE: room rule present with empty actions (gomuks' mute representation).
 *  - DEFAULT: no room rule — behaviour falls back to default/content/sender/override rules.
 */
enum class RoomNotificationLevel { ALL, DEFAULT, MUTE }

fun PushRuleset.roomNotificationLevel(roomId: String): RoomNotificationLevel {
    val rule = roomRule(roomId) ?: return RoomNotificationLevel.DEFAULT
    return if (rule.actions.any { it is PushAction.Notify }) RoomNotificationLevel.ALL
    else RoomNotificationLevel.MUTE
}

/** Wire-shape `actions` array for a selectable [NotificationPreset]. */
fun actionsForPreset(preset: NotificationPreset): List<Any> = when (preset) {
    NotificationPreset.OFF -> emptyList()
    NotificationPreset.NOTIFY -> listOf("notify")
    NotificationPreset.NOTIFY_SOUND -> listOf("notify", mapOf("set_tweak" to "sound", "value" to "default"))
    NotificationPreset.HIGHLIGHT -> listOf(
        "notify",
        mapOf("set_tweak" to "highlight", "value" to true),
        mapOf("set_tweak" to "sound", "value" to "default")
    )
    NotificationPreset.CUSTOM -> emptyList()
}

/**
 * Parse the stored `m.push_rules` account-data value into a [PushRuleset].
 *
 * Accepts the value as stored in [net.vrkknn.andromuks.AccountDataCache] (wrapped in `content`),
 * or an already-unwrapped object, or one that already starts at `global`.
 */
fun parsePushRules(stored: JSONObject?): PushRuleset {
    if (stored == null) return PushRuleset.EMPTY
    val content = stored.optJSONObject("content") ?: stored
    val global = content.optJSONObject("global") ?: content
    val byKind = LinkedHashMap<PushRuleKind, List<PushRule>>()
    for (kind in PushRuleKind.ordered) {
        val arr = global.optJSONArray(kind.apiName) ?: continue
        val rules = ArrayList<PushRule>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            rules.add(parseRule(kind, obj))
        }
        byKind[kind] = rules
    }
    return PushRuleset(byKind)
}

private fun parseRule(kind: PushRuleKind, obj: JSONObject): PushRule {
    val conditions = ArrayList<PushCondition>()
    obj.optJSONArray("conditions")?.let { condArr ->
        for (i in 0 until condArr.length()) {
            condArr.optJSONObject(i)?.let { conditions.add(PushCondition.fromJson(it)) }
        }
    }
    val actions = ArrayList<PushAction>()
    obj.optJSONArray("actions")?.let { actArr ->
        for (i in 0 until actArr.length()) {
            actions.add(PushAction.fromJson(actArr.opt(i)))
        }
    }
    return PushRule(
        kind = kind,
        ruleId = obj.optString("rule_id"),
        default = obj.optBoolean("default", false),
        enabled = obj.optBoolean("enabled", true),
        conditions = conditions,
        actions = actions,
        pattern = obj.optString("pattern").takeIf { it.isNotEmpty() }
    )
}
