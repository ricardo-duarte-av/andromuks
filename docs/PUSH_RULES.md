# Push Rules Editor

A mobile-friendly editor for Matrix push rules (the `m.push_rules` account-data event), plus a
per-room notification-level control. Inspired by webmuks' compartmentalized JSON editor, but built
around **smart per-rule cards** with a raw-JSON escape hatch instead of raw JSON everywhere.

## Data flow

```
sync_complete account_data → SyncRoomsCoordinator.processAccountData
   → AccountDataCache.setAllAccountData (raw storage)
   → parsePushRules(...) → AppViewModel.pushRuleset   (typed, Compose state)
        ▲                                   │
        │ reconcile on next sync            ▼ read
   gomuks server  ◄── update_push_rule ── PushRulesCoordinator ◄── editor UI (optimistic write)
```

- **Ingest**: `m.push_rules` is in the strict `ACCOUNT_DATA_ALLOWLIST`
  (`SyncRoomsCoordinator.kt`). Without it, the key is dropped at the chokepoint. On every sync that
  carries the key, `processAccountData` re-parses it into `AppViewModel.pushRuleset`.
- **Model + parser**: `utils/PushRules.kt`. `PushRuleset` keyed by `PushRuleKind`
  (override/content/room/sender/underride). `parsePushRules` unwraps the stored
  `{ "content": { "global": { ... } } }` shape (also tolerates already-unwrapped input).
  Actions/conditions are typed but preserve unknown shapes (`PushAction.Unknown`) so editing never
  drops data.
- **Write layer**: `PushRulesCoordinator.kt`, exposed via thin `AppViewModel` forwarders
  (`setPushRuleEnabled`, `setPushRulePreset`, `setPushRuleActions`, `putPushRule`, `deletePushRule`,
  `setRoomNotificationLevel`). Each call mutates `pushRuleset` optimistically and fires
  `update_push_rule`; the next sync reconciles.

## The `update_push_rule` command

```
update_push_rule { kind, rule_id, action, new_content?, actions? }
```

| action       | sends                          | used for                                  |
|--------------|--------------------------------|-------------------------------------------|
| `enable`/`disable` | —                        | toggling any rule (incl. default rules)   |
| `put_actions`| `actions: [...]`               | changing a rule's actions (preset chips, default rules) |
| `put`        | `new_content: {actions, conditions, pattern}` | create/replace a **custom** rule |
| `delete`     | —                              | removing a **custom** rule                |

Action elements are strings (`"notify"`, `"dont_notify"`) or objects
(`{"set_tweak":"sound","value":"default"}`, `{"set_tweak":"highlight","value":true}`).

### Editability

Default rules (`rule_id` starts with `.`, or `default: true`) **cannot** be `put`/`delete`d — the UI
hides Delete and routes raw edits through `put_actions` (actions only). Custom rules support the full
set.

## UI

### Global editor — `PushRulesScreen.kt` (route `push_rules`, entry in `SettingsScreen`)

A landing page shows one card/button per kind (with a rule count). Selecting a kind opens a
**lazily-rendered** `LazyColumn` of just that kind's rules — important because the `room` kind alone
can hold 150+ rules, and rendering every kind at once was far too heavy. System-back returns to the
kind picker.

Each kind list has a **search field** (filters by rule ID, pattern, summary, and resolved
room/user display name) and, when both groups are present, splits rules under two headers —
**"Specific rooms & users"** then **"General rules"** (`PushRule.isTargeted`).

The leading identity of each card is rendered by the shared `RuleIdentity` composable
(`PushRule.targetRoomId()` / `targetUserId()`):
- `room` rules and room-targeted override/underride rules → **room avatar + display name + room ID**
  (`AppViewModel.getRoomById`).
- `sender` rules → **user avatar + display name + user ID** (`AppViewModel.getUserProfile`, with an
  on-demand fetch for uncached users).
- everything else (Server ACL, Suppress edits, content matches…) → title + human summary.

Each `PushRuleCard` also shows:
- the identity block above, an enable `Switch`,
- preset `FilterChip`s — **Off / Notify / Notify + Sound / Highlight** (`NotificationPreset`); shows
  "Custom" when the actions don't map to a preset (edit via raw JSON to avoid data loss),
- overflow menu → **Edit raw JSON** (validated before send) / **Delete** (custom only).

Add-rule dialog adapts fields per kind (content→pattern, sender→user id, room→room id,
override/underride→conditions JSON) + an action preset.

### Per-room editor — `RoomPushRulesDialog` in `utils/RoomInfo.kt`

Reached from the **Push Rules** button in the Members / Media Gallery row. Shows:
- a notification-level radio group (**All messages / Default / Mute**) backed by the room-scoped
  `room` rule keyed by the room ID. *Mute* = `room` rule with empty actions (matches gomuks
  `MuteRoom`); *All* = `["notify"]`; *Default* = delete the room rule.
- a list of other rules that specifically target the room (`PushRuleset.rulesAffectingRoom`), each
  with a quick enable/disable switch, and a search field to filter that list.

## Verification

1. Settings → Push Rules lists the 5 sections; cross-check against the Account Data Visualizer's
   `m.push_rules` entry.
2. Toggle a default rule → confirm it round-trips after the next sync.
3. RoomInfo → Push Rules → Mute → confirm notifications stop and a `room` rule with empty actions
   appears in the visualizer.
4. Add then delete a custom content (keyword) rule.
5. Raw-JSON edit with invalid JSON → rejected before send.
