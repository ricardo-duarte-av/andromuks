# Bridge Support (Mautrix / Beeper)

The app has first-class support for Matrix bridges (e.g. Mautrix bridges for WhatsApp, Telegram, etc.).

## Bridge Detection

Bridge info is parsed from `m.bridge` / `uk.half-shot.bridge` state events inside `AppViewModel.parseBridgeInfoEvent()` and `parseRoomStateFromEvents()`. The result is a `BridgeInfo` object stored on `RoomState.bridgeInfo`. Key fields:

MSC2346 defines **three** nested descriptors, and different bridges populate different subsets — all three are parsed:

- `BridgeInfo.protocol` — the remote network *type* (id, displayName, avatarUrl, externalUrl). `"discord"`, `"whatsapp"`.
- `BridgeInfo.network` — the specific *instance* of that network: a Discord guild, an IRC server (id, displayName, avatarUrl, externalUrl).
- `BridgeInfo.channel` — the individual channel/room on that network (id, displayName, avatarUrl, `fi.mau.receiver`, externalUrl).
- `BridgeInfo.roomType` / `roomTypeV2` — `"dm"` means it's a DM-style bridge room (`com.beeper.room_type` / `.v2` in the event content)
- `BridgeInfo.hasRenderableIcon` — true if the bridge has either an avatarUrl or displayName to render

**Do not read `protocol.avatarUrl` directly.** mautrix-discord puts the Discord logo there; OOYE (`moe.cadence.ooye`) leaves `protocol` icon-less and carries the only avatar in the whole event on `network.avatar_url` (the guild icon). The derived accessors walk the fallback chains for you:

| Accessor | Chain |
|---|---|
| `BridgeInfo.avatarUrl` | `protocol` → `network` → `channel` |
| `BridgeInfo.displayName` | `protocol.displayName` → `protocol.id` → `network` → `channel` |
| `BridgeInfo.externalUrl` | `protocol` → `network` → `channel` |
| `BridgeInfo.protocolId` | `protocol.id` → `protocol.displayName` → `network.id` |

Bridge info is persisted per room via `BridgeInfoCache` → `RoomMetadataStore` (SQLite), so it survives app restarts without re-fetching room state. Schema **v5** added `bridge_protocol_id` and `bridge_avatar_is_protocol`.

Three fields on `RoomItem` carry it into the UI:

- `bridgeProtocolAvatarUrl` — the mxc:// URL to draw, from `BridgeInfo.avatarUrl` (full chain, **not** `protocol` alone).
- `bridgeProtocolId` — `BridgeInfo.protocolId`; what the Bridges tab groups on.
- `bridgeAvatarIsProtocolLevel` — whether that avatar is the protocol's shared logo or a per-instance icon.

A room is treated as bridged if either of the first two is non-null.

### v5 cache invalidation

The v4→v5 migration NULLs `bridge_avatar_mxc` for rows where it is `''` **and** a bridge display name exists. Those are rooms the pre-v5 parser saw a bridge event for but found no `protocol.avatar_url` on — it wrote the "not bridged" sentinel, and `BridgeInfoCache.isCached()` (which reads exactly that column) would have made `loadAllRoomStatesAfterInitComplete` skip them forever, so the new `network`-descriptor parsing would never run. NULL restores "never observed" and re-queues one `get_room_state`. The display-name clause is what keeps this from re-requesting state for every genuinely unbridged room in the account.

## Bridges Tab in RoomListScreen

In `RoomListScreen`, the **Bridges** tab (`RoomSectionType.BRIDGES`) groups bridged rooms by their **`bridgeProtocolId`** (falling back to `bridgeProtocolAvatarUrl` for rooms whose protocol id isn't resolved yet — pre-v5 cache rows, or state never fetched). Each unique protocol becomes a pseudo-space (a `SpaceItem`) representing one bridge network.

Because a protocol group's rooms can carry **different** avatars (mautrix-discord's logo next to OOYE's per-guild icons), the space's icon and name are chosen by fixed rules, never by list position: a protocol-level avatar wins over any per-instance one, and ties break on the string value. `rooms` is ordered by recency, so an order-based pick would flip the space's icon every time a message arrived in a differently-iconed room.

**Why not group by avatar URL** (the pre-2026-07 behaviour): bridges that expose a per-instance icon rather than a protocol logo — OOYE gives each Discord *guild* its own icon — produced one pseudo-space per guild, and rooms bridged to the same network by two different bridge implementations landed in separate spaces. Protocol id is stable across both. Tapping a bridge network (`currentBridgeId`) filters the room list to show only rooms on that network. `AppViewModel.exitBridge()` clears `currentBridgeId`. The tab animates between the bridge network list and the filtered room list using `AnimatedVisibility`. Room items in this tab show a badge with the bridge protocol icon overlaid on the room avatar (rendered in `BridgeDecorations.kt` via `BridgeNetworkBadge`).

## Bridge Icon in Timeline Top Bar

In `RoomTimelineScreen` and `BubbleTimelineScreen`, the top bar normally shows a **Refresh** icon button. When the current room is bridged (`roomState?.bridgeInfo?.hasRenderableIcon == true`), the refresh icon is replaced by a `BridgeNetworkBadge` (from `ui/components/BridgeDecorations.kt`). The badge shows the bridge protocol avatar and still triggers `onRefreshClick` when tapped. `BridgeNetworkBadge` also has an optional non-clickable variant (no `onClick`). Additionally, `BridgeBackgroundLayer` renders a blurred, low-opacity version of the bridge avatar as a subtle background in the timeline.

## Bridge Badge Shared-Element Transition

When opening a bridged room from `RoomListScreen`, the bridge protocol badge flies from the room list item to the `BridgeNetworkBadge` in `RoomHeader`, matching the existing room-avatar shared-element flight.

**Implementation:**
- `RoomListItem` (list side): computes a `sharedBoundsModifier` with key `"bridge-badge-${room.id}"` inside `with(sharedTransitionScope)`, then applies it via `.then()` on the badge `Box` between `.align(BottomEnd)` and `.size(16.dp)`. The modifier is computed outside the `with` block so that `BoxScope.align()` and `SharedTransitionScope.sharedBounds()` are called in their respective receiver contexts without conflict.
- `RoomHeader` (destination side): same key `"bridge-badge-${roomId}"`. The modifier is computed and passed into `BridgeNetworkBadge` via its existing `modifier` parameter. No API change to `BridgeNetworkBadge` was needed.
- Uses `sharedBounds` (not `sharedElement`) because the visual representation differs between the two ends — 16 dp circle with border/background in the list vs. 36 dp `IconButton` in the header. `sharedBounds` animates the region and cross-fades the content.

**Key invariant:** The shared key is always `"bridge-badge-${roomId}"` — keyed on the **room ID**, not on the bridge protocol avatar URL. Multiple rooms on the same bridge network (e.g., several WhatsApp conversations) each get a distinct key, so only the badge for the room being opened participates in the transition. Using the protocol URL as the key would cause all rooms sharing that protocol to register a shared element simultaneously, breaking the animation.

## Per-Message Bridge Profiles

Mautrix bridges can attach `com.beeper.per_message_profile` to individual message events, overriding the sender's display name and avatar for that specific message (used for ghost users representing external network contacts). Handled in `TimelineEventItem`, `RoomTimelineScreen`, `BubbleTimelineScreen`, `ThreadViewerScreen`, and `ChatBubbleScreen`.

**Fields are independently optional.** Some bridges supply only `displayname` (no `avatar_url`) — e.g. IRC-side users on the `chaos@...` network where the bridge doesn't have a protocol avatar. In that case the renderer falls back to the **sender's** cached avatar (the underlying Matrix user, often a ghost with a real bridge avatar), not a letter-mark. Logic in `TimelineEventItem`:

- `displayName`: `per_message_profile.displayname` → `userProfileCache[sender].displayName` → username from MXID.
- `avatarUrl`: `per_message_profile.avatar_url` → `userProfileCache[sender].avatarUrl` → letter-mark.

The on-demand sender-profile fetch is gated on "per-message supplies BOTH fields"; otherwise we still need the sender cache populated so the fallback target is available.

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
- **Live sync (incremental append):** `TimelineCacheCoordinator.appendEventsToCachedRoom()` — the `com.beeper.message_send_status` branch mirrors `processCachedEvents`: calls `processBridgeSendStatus` and records `bridgeStatusEventToMessageId`, but **never** inserts the event into `eventChainMap`. This prevents status events from polluting the LRU-cached processedState and corrupting the sort order when the room is reopened.
- **Paginated history:** `TimelineCacheCoordinator.processEventsArray()` — after the `m.reaction` branch, before `allowedEventTypes` filtering.
- **UI (bubble icon):** `TimelineEventItem` reads `appViewModel.messageBridgeSendStatus[event.eventId]` (keyed on `bridgeSendStatusCounter`) and renders a 10dp icon below the timestamp in the avatar column for own messages. Applies to both non-consecutive (with avatar) and consecutive messages. Icons: `DoneAll` / `Check` in `onSurfaceVariant`; `Warning` / `Error` in `colorScheme.error`.
- **UI (Delivery Info dialog):** `utils/BridgeDeliveryInfoDialog.kt` — floating dialog (animated scale/fade, matching `ReactionDetailsDialog` style). Shows sent-to-network timestamp with status icon, then a "Received by" `LazyColumn` with avatar, display name, and per-user reception timestamp. Opened from the **More** submenu of the message long-press menu (`MessageMenuBar.kt`, `onShowBridgeDeliveryInfo` callback in `MessageMenuConfig`) — only shown when the message has a bridge status. Integrated in `RoomTimelineScreen`, `BubbleTimelineScreen`, and `ThreadViewerScreen`.
- **State cleared** on full reset and on per-room cache clear alongside `messageReactions`.
- These events are **not** added to `allowedEventTypes` and do not appear in the timeline — they are side-effect-only.

### Read receipts for status events

A `com.beeper.message_send_status` event is invisible in the timeline but still holds a real timeline rowid, so **other Matrix clients count the room as unread** until a read receipt advances past it. Two paths cover this:

- **Open room:** `processSyncEventsArray()` already marks the room read with the newest event of each sync batch (which includes the status event).
- **Non-open room:** `TimelineCacheCoordinator.appendEventsToCachedRoom()` (which runs for every actively-cached room, not just the open one) detects a newly-arrived status event and calls `markRoomAsRead(roomId, newestEventId)` **only when the latest *real* event in the room was sent by us** (`newestReal.sender == currentUserId`). This guard prevents clobbering a genuinely-unread message from someone else — read receipts are monotonic, so we can't mark the status event read while skipping an earlier unread message. This handles the common case: the user sends a message in a bridged room, then navigates away (or backgrounds the app) before the bridge confirms delivery.

- They are also excluded from the **Notifications screen** (`MentionsScreen`): `processMentionEvents()` skips any event whose `type == "com.beeper.message_send_status"` before building the mention list.
