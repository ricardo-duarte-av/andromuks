# Home-screen Room Widget

One room per widget instance: room header, then the last 5–10 renderable messages with sender
avatars and display names, a manual refresh button, and automatic updates on notification and live
sync.

Code lives in `app/src/main/java/net/vrkknn/andromuks/widget/`. Built with **Glance**
(`androidx.glance:glance-appwidget`, pinned in `gradle/libs.versions.toml` — it ships its own
release train and is deliberately not under the Compose BOM; see
[DEPENDENCIES.md](DEPENDENCIES.md)).

## Why a snapshot, and not "just read the timeline"

**There is no persisted timeline.** `RoomTimelineCache` is an in-memory `object`, and
`AppViewModel.persistRenderableEvents` is a documented no-op. A home-screen widget outlives the app
process by design — most of the time it is painting while the process is dead — so it cannot read
the timeline, the room list, `ProfileCache` or `RoomMemberCache` and expect anything to be there.

So the widget renders from **one durable per-widget snapshot and nothing else**:

```
trigger ──► RoomWidgetRefresher / optimistic append ──► RoomWidgetStore (SharedPreferences)
                                                              │
                                                              ▼
                                                     RoomWidget.provideGlance  (pure render)
```

Render never touches the network, never interprets an event, and never blocks. Every trigger writes
a snapshot and asks Glance to repaint.

## Files

| File | Role |
|---|---|
| `RoomWidget.kt` | The Glance UI + `RefreshWidgetAction`. Pure render from a snapshot. |
| `RoomWidgetReceiver.kt` | `GlanceAppWidgetReceiver`; AppWidget lifecycle (update / resize / delete). |
| `RoomWidgetSnapshot.kt` | `RoomWidgetSnapshot` + `WidgetMessage` and their JSON codec. Pure, unit-tested. |
| `RoomWidgetStore.kt` | Bindings + snapshots in `AndromuksWidgetPrefs`; owns `boundRoomIds()`. |
| `RoomWidgetRefresher.kt` | The `/exec` → filter → format → avatars pipeline, and the incremental `appendEvents`. |
| `RoomWidgetAvatars.kt` | Circular avatar PNGs on disk, referenced by path. |
| `RoomWidgetUpdater.kt` | The **only** API other code calls. Every method is cheap when no widget exists. |
| `RoomWidgetRefreshWorker.kt` | Runs the refresher via WorkManager; unique-per-room = debounce. |
| `WidgetConfigActivity.kt` | Room picker, fed by `RoomMetadataStore` so it works with the app force-stopped. |
| `WidgetEventFormatter.kt` | Event → one display line. Pure, unit-tested. |

Resources: `res/xml/room_widget_info.xml`, `res/drawable/ic_widget_refresh.xml`, the
`room_widget_*` strings, and the receiver + config activity in `AndroidManifest.xml`.

## Refresh pipeline (`RoomWidgetRefresher.refresh`)

1. `ExecApi.readCredentials(context)` — same prefs/token path notifications use. Invalid → a
   `SIGNED_OUT` snapshot with a tap-to-sign-in target.
2. Events: if `RoomTimelineCache` happens to be warm with ≥ `PAGINATE_LIMIT` events, use it and skip
   the network entirely. Otherwise `/exec paginate` with `max_timeline_id: 0, limit: 40`. The
   response is either `{events: […]}` or a bare array — `handleTimelineResponse` accepts both, so
   this does too. 40 because the render filter discards a lot to yield ~10 visible lines.
3. Parse with `TimelineEvent.fromJson`.
4. Filter with the **real** render filter: `processTimelineEvents(...)` from `RoomTimelineScreen.kt`
   — a top-level `suspend fun`, callable headless. `WIDGET_ALLOWED_TYPES` is the timeline whitelist
   minus `m.reaction`.
5. Resolve edits locally (`WidgetEventFormatter.collectEdits`, newest by `timelineRowid`).
6. Format each line with `WidgetEventFormatter`, a thin wrapper over
   `ReplyFunctions.formatEventForReplyPreview` — so "📷 Sent a photo" means the same thing in a
   widget row as in a reply preview. The wrapper adds redaction handling and reply-quote stripping.
7. Sender identity: `ProfileCache` → `RoomMemberCache` → one batched `/exec
   get_specific_room_state` for whoever is left. **Paginate responses carry no sender profiles**, so
   on a cold start that request is the only way to render a name rather than a localpart.
8. Room header: `RoomMetadataStore` (SQLite, survives process death) → `/exec get_room_summary` →
   the name captured at configuration time → the raw room id.
9. Avatars: `IntelligentMediaCache` for the download, then a 96 px circular PNG in
   `cacheDir/room_widget_avatars/`, referenced by **path**. Unreferenced files are pruned each
   refresh.

Failures degrade rather than blank: a network error keeps the previous messages and sets
`state = ERROR`.

### The RemoteViews IPC budget

Every bitmap in a widget update rides the `RemoteViews` transaction, which is hard-capped (~1–2 MB)
and **kills the whole update** when exceeded. Two things keep us clear of it: avatars are capped at
96 px (~36 KB), and sender identity is drawn only when the sender changes from the previous row, so
a run of messages from one person costs one bitmap. Do not raise `AVATAR_PX` or draw an avatar per
row without recalculating this.

## Trigger matrix

| Trigger | Path | Network? |
|---|---|---|
| Refresh button | `RefreshWidgetAction` → `requestManualRefresh` → expedited worker | yes |
| Notification posted | `EnhancedNotificationDisplay` → `onRoomNotification` | **no** (optimistic), then a reconciling refresh |
| Notification phase 2 | `NotificationImageWorker` → `requestRefresh` | yes |
| Live sync | `SyncIngestor.processRoom` → `onSyncEvents` | usually no |
| Sync reset / edit / redaction | `SyncIngestor` → `invalidate` → refresh | yes |
| Widget added / host restart | `RoomWidgetReceiver.onUpdate` → `requestRefresh` | yes |
| Resize | `onAppWidgetOptionsChanged` → `redraw` | no |
| Boot | `BootStartReceiver` → `refreshAll` | yes |

`updatePeriodMillis` is **0**. The AppWidget polling minimum is 30 minutes — too slow to be useful
and too wasteful to justify next to the push-driven paths.

Sync-driven refreshes are debounced by 2 s (`SYNC_DEBOUNCE_MS`) and the worker is unique per room
with `ExistingWorkPolicy.REPLACE`, so a burst collapses into one fetch. Optimistic in-place updates
are **not** debounced — they cost nothing and should feel immediate.

### The notification path is free

The FCM payload already carries sender, display name, avatar URL, body and timestamp. So
`onRoomNotification` appends a row built straight from `NotificationData` — using
`NotificationDataParser.createNotificationBody`, the same vocabulary the notification itself shows —
and repaints with **zero network**. The refresh queued behind it only reconciles what the payload
cannot express (a server-applied edit, a per-message profile).

## The `SyncIngestor` change

`SyncIngestor.processRoom` used to parse the `timeline` and `events` arrays only when the room was
in the timeline LRU; every other room had its events walked and discarded. A widget is usually on a
room the user has **not** opened this session, so under that rule it would never see a single live
message.

The gate is now `shouldParseEvents = isRoomCached || isWidgetRoom`. Four things make that safe:

1. **`widgetRoomIds` is resolved once per `sync_complete`**, next to `getCachedRoomIds()`, and
   answered from a `@Volatile` in-memory set. `processRoom` runs per room per sync — this must never
   touch disk. With no widget installed the set is empty and the check is a `Set.contains` on an
   empty set.
2. **Widening the parse gate does not widen the persist semantics.** `hasPersistedEvents` stays keyed
   on `isRoomCached` alone, so `roomsWithEvents`, the `IngestResult` and the
   `cacheUpdateListener.onEventsForCachedRoom` contract are byte-for-byte unchanged. A widget-only
   room must never start looking like a cached room to `AppViewModel`.
3. **The widget sink fires independently** of `hasPersistedEvents`, before the cache listener. A room
   that is both cached and widget-bound feeds both; the widget de-duplicates by `eventId`.
4. **`reset` invalidates the snapshot** the same way it clears the timeline cache — handled at the
   reset branch rather than only in the sink, because a reset can arrive with no events at all.

`RoomWidgetStore.pruneOrphans` (called from `onUpdate`) drops bindings whose widget vanished without
an `onDeleted`, which otherwise would keep a room in `boundRoomIds()` — and keep `SyncIngestor`
parsing events — forever.

### What the widget deliberately cannot do

Edits, redactions and reactions arriving over sync mutate messages already on screen, and resolving
them needs `EditVersionCoordinator`'s edit-chain machinery. The widget does not reimplement that.
`SyncIngestor` already computes `hasEditRedactionReaction`; it is passed straight through as
`requiresFullRefresh`, and the widget marks its snapshot stale and refetches over `/exec`. Marking
rather than clearing is deliberate — stale rows keep showing until real ones replace them, which
beats blanking the widget for the couple of seconds a refetch takes.

## Relationship to the People / Conversation tile

The AOSP People Space tile ([NOTIFICATIONS.md](NOTIFICATIONS.md)) is **not** replaced in code and
nothing was deleted. `ConversationsApi` conversation shortcuts must stay — they back
`MessagingStyle`, bubbles (`ChatBubbleActivity`), Direct Share and `setShortcutId`. This widget is
simply the app-owned home-screen surface we recommend instead; the tile keeps working untouched.

## Tapping

Both the widget body and each row reuse the intent contract from
`EnhancedNotificationDisplay.createRoomIntent` — `MainActivity`, `ACTION_VIEW`, data
`matrix:roomid/<id>[/e/<eventId>]`, extras `room_id` / `event_id` / `direct_navigation` — so widget
taps land through the same navigation path as notification taps. `from_notification` is false: that
flag drives notification freshness and dismissal handling which does not apply here.

## Testing

`RoomWidgetSnapshotTest` (codec round-trip, schema-version rejection, malformed-row tolerance) and
`WidgetEventFormatterTest` (every msgtype, edits, redaction precedence, quote stripping, capping) —
both pure functions, the `PollFunctions` pattern. The Glance UI, the store and the refresher have no
unit harness; see GH issue #20.

Manual checks that matter:

- Add the widget with the app force-stopped — the picker must list rooms and the widget must paint
  from `/exec` with the WebSocket down. This is the whole point of the design.
- Send a message from another client while backgrounded — the widget must update on the
  notification, before any refetch.
- **The `SyncIngestor` case**: with the app in the foreground and the widget's room *never opened
  this session* (so not in the LRU), send a message from another client. The widget must update from
  sync alone, and the room list and timeline must behave exactly as before for that room.
