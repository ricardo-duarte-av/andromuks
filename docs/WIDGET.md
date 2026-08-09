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
   `cacheDir/room_widget_avatars/`, referenced by **path**.

**Pruning those files is deliberately not done here.** The avatar directory is shared by every
widget, so a keep-set built from the room being refreshed deletes the files other widgets are
displaying — avatars vanish and only return when the widget is re-added. `RoomWidgetRefreshWorker`
prunes instead, *after* storing the snapshot and against
`RoomWidgetStore.allReferencedAvatarPaths` — every live snapshot at once, including the one just
written.

Failures degrade rather than blank: a network error keeps the previous messages and sets
`state = ERROR`.

### Sizing: the widget's size *is* the setting

There is no configured message count. `RoomWidget.fittingMessageCount` derives it from
`LocalSize.current.height`, so a 4x1 widget shows one message (the latest) and a 4x4 shows several,
clamped to `MAX_MESSAGE_LIMIT`.

Two consequences worth keeping:

- **The list is a plain `Column`, never a `LazyColumn`.** A scrollable list inside a widget is the
  wrong interaction — it puts a scrollbar on the home screen and competes with the launcher's
  gestures. The widget shows exactly what fits, so there is nothing to scroll to.
- **Rows are pinned to two fixed heights, and the fit is computed exactly — not by division.**
  A row that draws its sender is 44dp; a continuation row (same sender as the row above, so no
  avatar and no name) is 26dp. Left to wrap, a row ranges from ~26dp to ~63dp, and no single
  average survives that: too low and rows render past the bottom edge, too high and the widget
  wastes a row. So `MessageRow` pins its height with `GlanceModifier.height`, the body is capped at
  one ellipsized line, and `visibleMessages` walks newest-to-oldest spending an exact budget.

  The subtlety that makes this a walk rather than a division: **the same widget holds a different
  number of messages depending on who sent them.** A run from one sender packs into cheap
  continuation rows where alternating senders need expensive sender rows — at 4x2 that is 4 messages
  versus 2. And adding an older message can make its successor *cheaper*, because whichever message
  is drawn first always shows its sender: prepending a same-sender message demotes the previous
  first row and hands budget back. `showsSender` is the render-time rule the budget is computed
  against; the two must stay in step.

The floor is 1, not 5. A floor above what actually fits is precisely what pushes content off the
bottom edge.

`MAX_MESSAGE_LIMIT` is **30**, not a tidy 10: a full-screen widget showing one person talking fits
~28 continuation rows, and the original cap left most of that empty. What bounds it is the
RemoteViews transaction, not the constant — see the bitmap budget below.

Snapshots always store `MAX_MESSAGE_LIMIT` messages regardless of current size, so growing a widget
reveals rows that are already there and needs no refetch. The one exception is a snapshot holding
fewer than the maximum (a room with little history, or a fetch cut short) — the resize handler asks
for a refresh in that case, since the new space might now be fillable.

### The RemoteViews IPC budget

Every bitmap in a widget update rides the `RemoteViews` transaction, which is hard-capped (~1–2 MB)
and **kills the whole update** when exceeded — it does not degrade. Three things keep us clear:

- Avatars are capped at 96 px (~36 KB each).
- Sender identity is drawn only when the sender changes, so a run from one person costs one row's
  worth of bitmap, not one per message.
- **`decodeAvatars` decodes each distinct avatar once and shares the `Bitmap` instance across every
  row that uses it.** `RemoteViews` keeps a `BitmapCache` keyed on the bitmap itself, so a two-person
  conversation costs two bitmaps however many messages are shown. Decoding per row instead scales
  the transaction with the message count — which is what made 30 messages affordable at all.
  `MAX_DISTINCT_AVATARS` (16) is the backstop.

Measured worst case at the 30-message cap: ~432 KB, with 12 distinct senders on a full-screen
widget. Do not raise `AVATAR_PX`, drop the de-duplication, or draw an avatar per row without
recomputing this.

## Reactivity: why `redraw` bumps a revision

`provideGlance` runs **once per Glance session**, and `provideContent` never returns. Anything read
above `provideContent` is captured exactly once, at session start — and a widget's session starts
the moment the host binds it, which is *before* the configuration activity has picked a room.

Reading `RoomWidgetStore` up there therefore captured "unconfigured" permanently: refreshes ran,
snapshots were written, `updateAll()` recomposed, and the widget sat on "Tap to configure" forever
because the composition never re-read anything. The refresh button was dead for the same reason —
it was passing an empty room id from the stale capture.

Only `currentState` reads are reactive: `updateAll()` reloads the Glance state DataStore and
recomposes. So the store is read **inside** `provideContent`, inside a `remember` keyed on
`RoomWidget.REVISION_KEY`, and `RoomWidgetUpdater.redraw` bumps that key before calling `updateAll`.
The revision carries no meaning beyond "something changed" — the data still lives in
`RoomWidgetStore`, which workers and `SyncIngestor` can write synchronously from any thread, unlike
Glance state.

**If you add a new write path, route its repaint through `redraw`.** A bare `updateAll()` recomposes
with the previous data and looks like nothing happened.

## Trigger matrix

| Trigger | Path | Network? |
|---|---|---|
| Refresh button | `RefreshWidgetAction` → `requestManualRefresh` → expedited worker | yes |
| Notification posted | `EnhancedNotificationDisplay` → `onRoomNotification` | **no** (optimistic), then a reconciling refresh |
| Notification phase 2 | `NotificationImageWorker` → `requestRefresh` | yes |
| Live sync | `SyncIngestor.processRoom` → `onSyncEvents` | usually no |
| Sync reset / edit / redaction | `SyncIngestor` → `invalidate` → refresh | yes |
| Widget added / host restart | `RoomWidgetReceiver.onUpdate` → `requestRefresh` | yes |
| Resize | `onAppWidgetOptionsChanged` → `redraw` (+ refresh only if the snapshot is short) | usually no |
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

## The picker preview

`android:previewLayout` must point at a **static** RemoteViews layout
(`res/layout/room_widget_preview.xml`) — a hand-built mock of the widget with sample content. The
launcher renders it without ever starting a Glance session, which is why pointing it at Glance's
`glance_default_loading_layout` (as this originally did) showed a spinner that never resolved.

**The launcher inflates it as `RemoteViews`**, which is a much narrower world than a normal layout:
only the `@RemoteView`-annotated classes may appear (`LinearLayout`, `FrameLayout`, `RelativeLayout`,
`GridLayout`, `TextView`, `ImageView`, `ImageButton`, `Button`, `ProgressBar`, `Chronometer`,
`AnalogClock`, the adapter views). A plain `<View>` is **not** among them — using one as an avatar
placeholder made inflation throw, which the launcher renders as a black preview plus "couldn't add
widget". (`View` genuinely lacks the annotation; you can confirm any class with
`unzip -p $ANDROID_HOME/platforms/android-37.0/android.jar android/view/View.class | strings | grep RemoteView`.)

Two consequences follow, and both are easy to undo by accident:

- **Colours are explicit day/night resources, not `?android:attr/...`.** The theme resolved during
  inflation is the launcher's, not ours.
- **No `android:tint`.** Lint rejects it in favour of AppCompat's `app:tint`, which cannot work in a
  RemoteViews layout — so the refresh glyph is a separate drawable with its colour baked in
  (`room_widget_preview_refresh.xml`) rather than a tint of the widget's own icon.

`android:previewImage` is the API < 31 fallback, currently a hand-drawn vector stand-in. To use a
real screenshot instead, drop a PNG at `res/drawable-nodpi/room_widget_preview_image.png` and delete
`res/drawable/room_widget_preview_image.xml`; only the resource name is referenced.

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
