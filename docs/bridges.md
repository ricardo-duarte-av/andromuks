# Bridge Support (Mautrix / Beeper)

The app has first-class support for Matrix bridges (e.g. Mautrix bridges for WhatsApp, Telegram, etc.).

## Bridge Detection

Bridge info is parsed from `m.bridge` / `uk.half-shot.bridge` state events inside `AppViewModel.parseBridgeInfoEvent()` and `parseRoomStateFromEvents()`. The result is a `BridgeInfo` object stored on `RoomState.bridgeInfo`. Key fields:

- `BridgeInfo.protocol` — network protocol info (id, displayName, avatarUrl, externalUrl)
- `BridgeInfo.channel` — individual channel within the protocol
- `BridgeInfo.roomType` / `roomTypeV2` — `"dm"` means it's a DM-style bridge room (`com.beeper.room_type` / `.v2` in the event content)
- `BridgeInfo.hasRenderableIcon` — true if the bridge has either an avatarUrl or displayName to render

Bridge info (avatar URL + display name) is also persisted to `SharedPreferences` via `BridgeInfoCache` (keyed by `roomId`), so it survives app restarts without re-fetching room state.

`RoomItem.bridgeProtocolAvatarUrl` stores the mxc:// URL of the bridge protocol avatar for the room (null for non-bridged rooms). This is the field used to identify bridged rooms throughout the UI.

## Bridges Tab in RoomListScreen

In `RoomListScreen`, the **Bridges** tab (`RoomSectionType.BRIDGES`) groups bridged rooms by their `bridgeProtocolAvatarUrl`. Each unique avatar URL becomes a pseudo-space (a `SpaceItem`) representing one bridge network. Tapping a bridge network (`currentBridgeId`) filters the room list to show only rooms on that network. `AppViewModel.exitBridge()` clears `currentBridgeId`. The tab animates between the bridge network list and the filtered room list using `AnimatedVisibility`. Room items in this tab show a badge with the bridge protocol icon overlaid on the room avatar (rendered in `BridgeDecorations.kt` via `BridgeNetworkBadge`).

## Bridge Icon in Timeline Top Bar

In `RoomTimelineScreen` and `BubbleTimelineScreen`, the top bar normally shows a **Refresh** icon button. When the current room is bridged (`roomState?.bridgeInfo?.hasRenderableIcon == true`), the refresh icon is replaced by a `BridgeNetworkBadge` (from `ui/components/BridgeDecorations.kt`). The badge shows the bridge protocol avatar and still triggers `onRefreshClick` when tapped. `BridgeNetworkBadge` also has an optional non-clickable variant (no `onClick`). Additionally, `BridgeBackgroundLayer` renders a blurred, low-opacity version of the bridge avatar as a subtle background in the timeline.

## Per-Message Bridge Profiles

Mautrix bridges can attach `com.beeper.per_message_profile` to individual message events, overriding the sender's display name and avatar for that specific message (used for ghost users representing external network contacts). Handled in `TimelineEventItem`, `RoomTimelineScreen`, `BubbleTimelineScreen`, `ThreadViewerScreen`, and `ChatBubbleScreen`.

## Bridge Send Status (`com.beeper.message_send_status`)

Some bridges (when configured) send `com.beeper.message_send_status` events to confirm delivery of a Matrix message to the other network. These are **not** displayed in the timeline — they update the sender's message bubble with a small delivery status icon, and expose a "Delivery Info" dialog via the message long-press menu.

### Event structure

```json
{
  "type": "com.beeper.message_send_status",
  "sender": "@bridgebot:homeserver",
  "content": {
    "m.relates_to": { "rel_type": "m.reference", "event_id": "$original-event-id" },
    "status": "SUCCESS",
    "reason": "m.foreign_network_error",       // optional, on failures
    "message": "Human-readable error",          // optional
    "delivered_to_users": ["@user:homeserver"]  // absent = no tracking; [] = tracking not yet delivered; [...] = delivered to these users
  }
}
```

### `status` values

| status | stored as | icon |
|---|---|---|
| `SUCCESS` | `"sent"` or `"delivered"` (see below) | `Check` or `DoneAll` |
| `FAIL_RETRIABLE` | `"error_retriable"` | `Warning` (error tint) |
| `FAIL_PERMANENT` | `"error_permanent"` | `Error` (error tint) |
| `PENDING` | ignored — wait for final status | — |

### `"sent"` vs `"delivered"` logic for `SUCCESS`

- If `delivered_to_users` is **absent or empty** → `"sent"` (message reached the bridge, no delivery confirmation yet).
- If `delivered_to_users` is **non-empty**, the **exclusion set** is built first: `currentUserId` + `bridgeBotId` (the status event sender) + all users in `functionalMembersCache[roomId]` (from `io.element.functional_members`, see below). Then:
  - **DM room** (`RoomItem.isDirectMessage == true`): any delivery to a user **not** in the exclusion set → `"delivered"`.
  - **Group room**: `"delivered"` only when every joined member **not** in the exclusion set appears in `delivered_to_users`. Otherwise stays `"sent"`.

### Status transition rules

- `"delivered"` is never downgraded to `"sent"` (a later event with fewer delivered users is ignored for the delivered→sent direction).
- Error states (`error_retriable`, `error_permanent`) always overwrite any previous status.

### Functional members (`io.element.functional_members` / MSC4171)

Bridge rooms contain service accounts (bridge bots, virtual users) that are not real participants. The `io.element.functional_members` state event lists them in `content.service_members`. These users must be excluded from the delivery check alongside `currentUserId` and the bridge bot sender.

- `AppViewModel.functionalMembersCache: MutableMap<String, Set<String>>` — roomId → set of service member user IDs.
- Populated in `parseRoomStateFromEvents()` (initial room state load) and updated live in `processSyncEventsArray()` when a new `io.element.functional_members` event arrives.
- Cleared on full state reset (`functionalMembersCache.clear()`) and per-room on room cache clear (`functionalMembersCache.remove(roomId)`).

### Implementation

- `AppViewModel.messageBridgeSendStatus: Map<String, String>` — eventId → status string, observed by Compose.
- `AppViewModel.bridgeSendStatusCounter` — incremented on every update to trigger recomposition.
- `AppViewModel.messageBridgeDeliveryInfo: MutableMap<String, BridgeDeliveryInfo>` — eventId → `BridgeDeliveryInfo(sentAt, deliveries)`, used by the Delivery Info dialog. **Not** Compose state (read on demand).
- `BridgeDeliveryInfo` (defined in `RoomItem.kt`) — `data class BridgeDeliveryInfo(val sentAt: Long?, val deliveries: Map<String, Long>)` where deliveries maps userId → first-seen delivery timestamp.
- `AppViewModel.processBridgeSendStatus(roomId, relatedEventId, bridgeBotId, status, deliveredToUsers, eventTimestamp)` — single entry point called from both live sync and paginated history. Uses `RoomMemberCache.getRoomMembers(roomId)` for the group check and `functionalMembersCache[roomId]` for the exclusion set.
- **Live sync:** `AppViewModel.processSyncEventsArray()` — the `com.beeper.message_send_status` branch; also the `io.element.functional_members` branch.
- **Paginated history:** `TimelineCacheCoordinator.processEventsArray()` — after the `m.reaction` branch, before `allowedEventTypes` filtering.
- **UI (bubble icon):** `TimelineEventItem` reads `appViewModel.messageBridgeSendStatus[event.eventId]` (keyed on `bridgeSendStatusCounter`) and renders a 10dp icon below the timestamp in the avatar column for own messages. Applies to both non-consecutive (with avatar) and consecutive messages. Icons: `DoneAll` / `Check` in `onSurfaceVariant`; `Warning` / `Error` in `colorScheme.error`.
- **UI (Delivery Info dialog):** `utils/BridgeDeliveryInfoDialog.kt` — floating dialog (animated scale/fade, matching `ReactionDetailsDialog` style). Shows sent-to-network timestamp with status icon, then a "Received by" `LazyColumn` with avatar, display name, and per-user reception timestamp. Opened from the **More** submenu of the message long-press menu (`MessageMenuBar.kt`, `onShowBridgeDeliveryInfo` callback in `MessageMenuConfig`) — only shown when the message has a bridge status. Integrated in `RoomTimelineScreen`, `BubbleTimelineScreen`, and `ThreadViewerScreen`.
- **State cleared** on full reset and on per-room cache clear alongside `messageReactions`.
- These events are **not** added to `allowedEventTypes` and do not appear in the timeline — they are side-effect-only.
