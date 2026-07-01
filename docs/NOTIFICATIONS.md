# Push Notifications Architecture

## Overview

Push notifications flow through three layers:

```
Gomuks backend
    ↓  HTTP POST /_gomuks/push/fcm  (JSON: token, owner, payload, high_priority)
gomuks/push gateway  (push.go)
    ↓  Firebase Admin SDK
FCM
    ↓  onMessageReceived()
FCMService.kt
    ↓
EnhancedNotificationDisplay.kt  →  NotificationManagerCompat
```

## FCM Priority

The push gateway (`gomuks/push`) passes `high_priority` through verbatim from the gomuks backend request. The backend sets it based on `notif.HasImportant`, which is `true` when at least one message in the batch has `Sound: true` (determined by the user's Matrix push rules).

- **High priority** — FCM wakes the device immediately, bypassing Doze. Used for mentions, DMs, and any room whose push rule includes sound.
- **Normal priority** — deferred by Android Doze; delivered in batch on next maintenance window. Used for silent notifications (e.g. dismiss/read-receipt payloads).

If notifications for a room are only arriving when the screen is turned on, check that the room's Matrix push rule includes `"sound": "default"`.

## FCMService

`FCMService` extends `FirebaseMessagingService`. Key responsibilities:

- Decrypts the encrypted `payload` field using the stored push encryption key (`web_client_prefs / push_encryption_key`, base64-encoded AES key).
- Routes decrypted JSON to `handleMessageNotification` or `handleDismissNotification` based on whether the payload has a `messages` or `dismiss` key.
- Suppresses notifications for the currently open room when the app is in the foreground (`shouldSuppressNotification`).
- Skips notifications for rooms in `low_priority_rooms` (SharedPreferences set).
- Verifies `self.id` against `current_user_id` to discard notifications for other accounts.
- Uses `pendingNotifications` (synchronized set) to handle the race condition where a dismiss arrives before the notification is posted.

### Image auth token

Media endpoints (`/_gomuks/media/...`) require a gomuks HMAC token for authentication. The session cookie (`gomuks_auth_token`) is only valid when an active OkHttp session exists — it is rejected in the `NotificationImageWorker` context where no such session is maintained. The server accepts the token as either a `?image_auth=<token>` query parameter or an `Authorization: Image <token>` header.

**Push payload token (used for notifications):** The gomuks backend embeds an `image_auth` field at the top level of every push payload batch that contains messages. This is a 24-hour HMAC token generated once per batch (`generateImageToken(24*time.Hour)` in `push.go`). `FCMService.handleMessageNotification` reads this token (`batchImageAuth`) and uses it for all media downloads in that notification:

- **Message image URL** — appended as `?image_auth=<token>` before storing in `NotificationData.image`. By the time `NotificationImageWorker` downloads the file, the credential is already in the URL.
- **Avatar URLs** — the token is stored in `NotificationData.imageAuthToken`. On the notification path the avatars are loaded **cache-only** in `showEnhancedNotification` (no token needed for a cache read); a miss defers the download to `NotificationImageWorker`, which rebuilds the HTTP URL from the `mxc://` and appends `?image_auth=<token>` for `/_gomuks/media/` endpoints. The persistent session token (`image_auth_token` from SharedPrefs) is the fallback when no batch token is available (e.g. for the current user's avatar). See the [Avatar loading](#avatar-loading--cache-only-in-phase-1-deferred-to-a-worker-on-a-miss) section.

**Avatar URL normalisation:** Push payloads can send avatar URLs in three forms (`mxc://server/mediaId`, `_gomuks/media/server/mediaId[?params]`, or a full `https://host/_gomuks/media/server/mediaId[?params]` URL). `FCMService.normalizeToMxcUrl()` strips query params and converts any form to the canonical `mxc://server/mediaId` before it is stored in `NotificationData`. This is the same key used by `IntelligentMediaCache`, so any avatar previously downloaded by `ConversationsApi` (shortcuts) or `PersonsApi` (contacts sync) will be a cache hit.

**WebSocket token (used by the main app):** The backend also issues an `image_auth_token` via a WebSocket command after connecting. `AppViewModel.updateImageAuthToken` persists it to `AndromuksAppPrefs / image_auth_token`. This is a separate mechanism used by the running app for in-session media requests; it is **not** involved in notification image downloads.

## EnhancedNotificationDisplay

Responsible for building and posting the actual `Notification` via `NotificationManagerCompat`. Uses `MessagingStyle` for conversation-style notifications with per-sender avatars.

### Notification channels

| Channel ID | Name | Used for |
|---|---|---|
| `matrix_direct_messages` | Direct Messages | DM rooms |
| `matrix_group_messages` | Group Messages | Group rooms |

### Avatar loading — cache-only in Phase 1, deferred to a worker on a miss

Avatars (room, sender, current user) are loaded **cache-only** in the FCM callback. `showEnhancedNotification` calls `loadAvatarBitmap`/`loadAvatarAsIcon`/`loadAvatarAsUriIcon` with `allowNetwork = false`:

1. Check `IntelligentMediaCache` (disk cache, keyed by `mxc://server/mediaId`) — no network. On a hit, the real avatar is used synchronously (instant).
2. On a **miss**, the loader returns `null`; the call site renders a generated letter-mark immediately and records the missed MXC URL (`deferRoomAvatar` / `deferSenderAvatar` / `deferMeAvatar`).
3. The notification is posted **right away** with whatever mix of real/lettermark avatars it has — the FCM callback never blocks on the network.

If any avatar was a miss (and/or the message has an image), after the post `showEnhancedNotification` enqueues a single `NotificationImageWorker` (Phase 2) carrying every deferred item.

**Why this changed:** avatars used to be downloaded *synchronously in the FCM callback before posting*. The callback only has a ~20 s `PARTIAL_WAKE_LOCK` budget, but `IntelligentMediaCache.downloadClient` has a 60 s `callTimeout`. In Doze — and especially in **battery-saver mode**, where the WebSocket and foreground service are torn down so FCM is the *only* delivery path — the radio is parked and a cold-cache avatar fetch could outlast the wake budget. The coroutine froze mid-download and the notification did not post until the device was next woken (the "notifications only arrive when I turn the screen on, even though FCM was delivered high-priority" symptom). The fix moves the network off the FCM thread entirely.

Cache hits come from `ConversationsApi` (shortcut icon downloads) and `PersonsApi` (contacts sync). `AvatarImage.kt` does **not** back-fill `IntelligentMediaCache` — UI avatar loads go through Coil only.

Fallback avatars (generated lettermarks from room/sender name) are pure-CPU (`Canvas` draw, no I/O) and are used whenever the real avatar is not yet cached.

The inline-reply update path (`updateNotificationWithReply`) is also cache-only by design and never downloads.

### NotificationImageWorker — the single Phase-2 media worker (image + avatars)

**One** worker finishes the notification, handling the message image *and* the missed avatars in a single pass. This is deliberate: avatars and media are both `mxc://`-keyed (they share one download primitive, `IntelligentMediaCache`), and — critically — one worker doing **one read of the active notification, one rebuild, one `notify()`** makes a clobbering re-post race structurally impossible. Two independent workers (an earlier image/avatar split) could read the same pre-update notification concurrently, and the second `notify()` would wipe the first's contribution (image *or* avatars lost). One writer, no race.

Expedited, `NetworkType.CONNECTED`, `ExistingWorkPolicy.KEEP` keyed `notif_media_<roomId>_<eventId>`. When it runs:

1. Bails if the notification is no longer active (dismissed / marked read).
2. **Fetches the event first** (when an `event_id` is present — see the `get_event` bullet below), because the result decides the notification kind and, for image messages, the preferred image source. Then downloads everything deferred — the image and the room / sender / me avatars — **in parallel**, cache-first (re-reads `homeserver_url` and a fresh `image_auth_token` from SharedPreferences at run time). The image is downloaded **thumbnail-first**: `resolveImageThumbnail` reads `content.info.thumbnail_url` / `thumbnail_file` from the event for **m.image *and* m.video** messages (for an image, a smaller copy — better for the wake/metered path and the downscaled notification view; for a video, the poster frame, which is what the notification should show) and falls back to the full-size payload image if there's no thumbnail or the thumbnail download fails (this also recovers image notifications whose payload URL was unresolvable). The chosen image's MIME is tracked alongside the file. Avatars rebuild their HTTP URL from the `mxc://` + `image_auth`. If nothing downloads it retries up to 3× with backoff.
3. Extracts the live `MessagingStyle` and rebuilds it in one pass: patches the **"me" Person** icon (root), every message `Person` from this sender, and `setData(image)` on the timestamp-matched **target message** (a message can be both — its `Person` is patched *and* its image set). Re-sets the **large icon** (room avatar for groups, sender for DMs; restored from the existing notification if not freshly downloaded).
4. Preserves channel, `contentIntent`, `event_id` extra, `when`, group/shortcut, `LocusId`, and bubble metadata. Reply / mark-read actions are rebuilt with `NotificationCompat.getAction` (not raw `Notification.Action` copies) so each keeps its icon **and** its `RemoteInput` — the inline-reply text field would otherwise be dropped on the silent re-post. The reply action (the one carrying a `RemoteInput`) is also re-attached to a `WearableExtender`, matching Phase 1, so the watch keeps its reply affordance after the update.
   - **Conversation-shortcut icon refresh (the avatar Android actually shows).** A conversation notification (`MessagingStyle` + shortcut + `LocusId`) renders the **linked shortcut's icon** as the conversation avatar in the shade — *not* the notification's large icon, which is why patching the large icon alone left the room avatar as the Phase-1 lettermark. The Phase-1 shortcut was built on the FCM thread (`ConversationsApi.updateShortcutForNotificationSync` → `createShortcutInfoCompat`, which still does a *blocking* `downloadAndCache`); in Doze / battery-saver that download outlives the FCM wake budget and the shortcut is published with the `createFallbackShortcutIconCompat` lettermark. So, **before** re-posting, if the room avatar was freshly downloaded (`dl.room != null`), the worker re-publishes the shortcut via a fresh `ConversationsApi.updateShortcutForNotificationSync`. The download above warmed `IntelligentMediaCache` for the same `mxc://` key, so `createShortcutInfoCompat` now builds the real icon (`updateSingleShortcut` force-refreshes it via remove + `pushDynamicShortcut`). It runs before `notify()` because the notification resolves its shortcut icon at post time. The worker carries `room_name` for the shortcut label.
   - **Event enrichment via `get_event`.** The FCM payload describes neither the message type nor a media `mxc://` for audio, and its `body` ("Sent an audio file") is a localized, unreliable hint. So for **every** notification carrying an `event_id`, Phase 1 sets `fetchEvent = true` and the worker calls `get_event` over the HTTP `/_gomuks/exec/` endpoint (`ExecApi.execRaw` — no WebSocket needed, works in battery-saver). gomuks returns the raw `database.Event`: an **encrypted** event keeps `type: "m.room.encrypted"` with the ciphertext in `content` and the decrypted body in a separate **`decrypted`** field (`decrypted_type` holds its real type); a plaintext event has its content directly in `content`. So the worker reads via `decryptedContent()` (= `decrypted` ?? `content`) everywhere — `classifyKind`, `captionForMedia`, `audioOutcomeFrom`, `resolveImageThumbnail`. (This raw shape differs from the sync/frontend view, which pre-merges the two.) `classifyKind` derives a log label (`text` / `voice` / `audio` / `image` / `file` / `video` / `location` / `notice` / `emote` / `other:<msgtype>`) — logged as `Handling room … notification: type=…`. The event also drives **image thumbnail** selection (step 2) and the audio handling below. For `m.audio` it splits on the voice marker — **presence** (not value) of `org.matrix.msc3245.voice` / `.v2` / `m.voice`:
     - **Voice message** (tiny, ~KBs): resolves the `mxc://` (`content.file.url` when encrypted → media URL with `?encrypted=true`; else `content.url` → `?encrypted=false`), downloads it via a *dedicated* audio client (`IntelligentMediaCache.downloadAndCache` would reject a non-image body), FileProvider-wraps it with the same `com.google.android.projection.gearhead` read-grant as images, and `setData("audio/…", uri)` on the target message + a "🎤 Voice message (m:ss)" caption. Android Auto / Wear then render an inline **Play**; the phone shade shows the caption.
     - **Generic audio** (music/podcast, can be many MB): **no background download** — just a "🎵 filename (m:ss)" caption from `content.info` + `filename`/`body`. Avoids burning the FCM/metered budget on a large file that's rarely wanted inline.
   - **Media captions & labels.** Image/video/audio/file messages can carry a caption. Per the Matrix spec (MSC2530) `extractCaption` treats `body` as the caption **only when `filename` is present and `body` differs from it** (when `filename` is absent, `body` *is* the filename — no caption); it prefers `formatted_body` (HTML → plain). `captionForMedia` glyph-prefixes by type — 📷 image, 🎬 video, 🎤 voice, 🎵 audio (audio also appends `(m:ss)`). **image/video** override the Phase-1 text **only when there's a caption** (the thumbnail conveys the rest); **m.file** *always* gets a label — `📄 <caption or filename> (<human-readable size>)` — since there's no visual and "Sent a file" is uninformative. A file can also carry an optional thumbnail (e.g. a PDF preview), handled by `resolveImageThumbnail` like image/video; most files have none.
     - Non-audio events (plain text, etc.) are a legitimate no-op — the worker does **not** retry "produced nothing" unless an image/avatar was actually *requested*, so text notifications don't trigger a retry storm. (The waveform under `org.matrix.msc1767.audio` is currently ignored; it's the source for a future in-app voice-note bar.)
   - **Formatted text from `sanitized_html` (`richTextForTextMessage`).** For a plain **m.text / m.notice** message, the worker renders the event's `local_content.sanitized_html` into a span-formatted `CharSequence` via `htmlToNotificationText` (bold/italic/underline/strike/`code`/link/quote/list spans — strictly richer than the `**`/`_`/`` ` ``/`[](…)` markdown-guessing `formatNotificationBody` applies to the plain `body` in Phase 1, and it can't *mis*-format literal `*`/`_`, e.g. inside maths like `$a*b*c$`). This **piggybacks on the `get_event` fetch the worker already does** — no extra network. It only overrides when the rendered text actually **carries spans** (an unstyled render equals the Phase-1 body, so overriding would force a pointless silent re-post); plain prose is left untouched. Maths render as a monospace `<code>` span (notifications can't draw the LaTeX graphically — that's a `Spannable`, not a Compose surface — so the source shows, but at least it isn't mangled). Media/audio captions take precedence (`audioCaption ?: mediaCaption ?: richText`) since those msgtypes never produce `richText`.
   - **Per-message profile (`extractPerMessageProfile`).** Bridge relay bots send every message AS one account (e.g. `@github:…`) but stamp each event with the real actor's identity in `content.com.beeper.per_message_profile` (`displayname` + `avatar_url`). This is **not** in the FCM payload, so the worker reads it from the fetched event (same `get_event`, no extra network — mirrors the timeline's per-message-profile handling in [docs/bridges.md](bridges.md#per-message-bridge-profiles)). When present, the worker relabels **only the target message's** sender `Person` (matched by timestamp, since the profile is per-message not per-sender) to **`"<profile displayname> via <bot name>"`** and swaps in the profile avatar (downloaded in the same parallel block as the other avatars; for a DM it also becomes the large icon). The base name is taken from the message's current sender name with any existing `" via …"` stripped first, so a worker **retry** never double-wraps ("ricardo via GitHub bot", not "ricardo via ricardo via GitHub bot"). The generic sender avatar still applies to every *other* message from that account. Persisted via `EnhancedNotificationDisplay.upgradePerMessageProfile` so a later Phase-1 rebuild (new message in the room) keeps the relabelled sender.
5. Re-posts with the same notification ID and `setOnlyAlertOnce(true)` (visual-only, no re-alert, but keeps the alerting rank so the status-bar icon and widget entry survive — `setSilent(true)` would drop them), bumping `when` to now so the People widget re-renders onto the enriched content, then refreshes the group summary. Only reached when Phase 2 actually changed something; a no-op enrichment returns earlier without re-posting.
6. Updates the in-memory cache so a later `showEnhancedNotification` rebuild keeps the result: `upgradeMessageToImage` for the image **or downloaded voice clip** (same data slot), `upgradeAvatarsInCache(roomId, senderId, senderIcon)` for the sender avatar, and `upgradePerMessageProfile(roomId, timestamp, senderId, name, icon)` for a relay bot's per-message identity (one message only). The "me" Person and large icon are not cached per-message — they self-correct on the next rebuild via the now-warm disk cache.
7. Returns `retry` if a requested image **or a wanted voice clip** couldn't be fetched (so it lands on a later attempt), even when other parts already posted; avatar-only and caption-only outcomes are not retried.

### MessagingStyle message history cache

`EnhancedNotificationDisplay.roomMessageCache` (companion object) is a per-room
`ConcurrentHashMap<String, ArrayDeque<MessagingStyle.Message>>` capped at
`MAX_CACHED_MESSAGES_PER_ROOM = 5`. Every incoming message is appended with
`addLast` then trimmed FIFO. On every `notify()` call the cached messages are
re-added to a freshly-built `MessagingStyle`, so the shade renders up to the
last 5 messages for the conversation.

**Why this is needed:** Android's `MessagingStyle` does not preserve history
across `notify()` calls. If we built a `MessagingStyle` containing only the
latest message, the shade would show only that one. The cache is the application
side of the contract.

**Lifetime:** in-memory, tied to process lifetime. No SharedPreferences backing.
A true cold start posts a notification with only the new message until 5 more
arrive.

**Clear sites — the cache must be cleared whenever the user (or the backend
on behalf of the user) acknowledges the conversation, so the next notification
does not replay messages the user has already seen:**

| Where | Why |
|---|---|
| `EnhancedNotificationDisplay.updateNotificationAsRead` / `clearNotificationForRoom` | Inline reply / mark-read action button — user explicitly engaged with the notification from the shade. |
| `FCMService.handleDismissNotification` (all three cancel branches) | Backend pushed a dismiss because the conversation was marked read server-side (e.g. the user read it in another client, or the read-receipt for an in-app read round-tripped). |
| `MainActivity.onCreate` / `onNewIntent` on the `fromNotification == true && roomId != null` branch | User tapped the notification body. Closes the window between tap and the eventual backend-dismiss FCM, and covers "tap but immediately switch away" where the read-receipt never gets sent. |

**Do NOT clear when:**
- A bubble is open for the room — the bubble's UI continuity depends on the
  cached MessagingStyle, and the dismiss FCM's bubble-open branch already
  skips the system cancel for the same reason.
- The dismiss arrives within the 5-second reply-protection window — that
  dismiss is the backend's echo of our own reply's mark-read; the cache was
  already cleared by the reply path.

The single public entry point is `EnhancedNotificationDisplay.clearRoomMessageCache(roomId)`.

### Notification body formatting

FCM push payloads carry only the plain-text `body` field — the `sanitized_html` / `htmlBody` field is not present. `showEnhancedNotification` therefore cannot call `htmlToNotificationText`. Instead it calls `formatNotificationBody(text)`, a private single-pass parser defined in `EnhancedNotificationDisplay`, which returns a `SpannableStringBuilder` with Android text spans applied:

| Markdown syntax | Span applied |
|---|---|
| `` `code` `` | `TypefaceSpan("monospace")` |
| `**bold**` | `StyleSpan(BOLD)` |
| `*italic*` | `StyleSpan(ITALIC)` |

The parser walks the string left-to-right, checking in the order above (code → bold → italic) so that `**` is consumed before a single `*` is considered. Unmatched delimiters (no closing counterpart found) are emitted verbatim. Underscore-italic (`_..._`) is intentionally omitted to avoid false positives inside Matrix user IDs and URLs.

The resulting `CharSequence` is passed directly as the `MessagingStyle.Message` text, which Android renders with the spans intact in the notification shade on API 24+.

### Image notifications

Image messages (FCM payload contains an `image` field) are handled by the **same single Phase-2 worker** as deferred avatars — see [NotificationImageWorker — the single Phase-2 media worker](#notificationimageworker--the-single-phase-2-media-worker-image--avatars) above. In Phase 1 the notification posts immediately with the caption / body text and **no image download is attempted**; the image URL and MXC cache key are parsed (`deferredHttpUrl` / `deferredMxcUrl`) and handed to `NotificationImageWorker.enqueue()` alongside any missed avatars.

Two details specific to the worker's media handling:

**Expedited scheduling:** the worker is marked **expedited** (`setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)`), so `JobScheduler` runs it at the next available slot rather than waiting for a Doze maintenance window — the critical difference from a plain constrained job, which aggressive OEMs (Samsung One UI, MIUI) can defer for minutes or hours. If expedited quota is exhausted it falls back to a normal-priority job.

**Auth token:** `doWork()` re-reads the token from SharedPreferences (`AndromuksAppPrefs / image_auth_token`, falling back to `gomuks_auth_token`) at run time, so a delayed job uses a current credential. It's used as a `Cookie: gomuks_auth=<token>` header by `IntelligentMediaCache.downloadAndCache`; however the image URL already contains `?image_auth=<batchToken>` (appended in `FCMService.handleMessageNotification`) which is the primary credential, and the avatar URLs get `?image_auth=` appended from the same batch token. The cookie is a fallback.

### Dismiss handling

`handleDismissNotification` cancels active notifications for a room when the backend signals the conversation was read. It will **not** cancel if:
- The room is within a 5-second **reply protection window** — set by `FCMService.markRoomReplied(roomId)` when the user replies via the inline notification action (`NotificationReplyReceiver`). Replying marks the room read, which causes the backend to immediately push a dismiss FCM; this window prevents that auto-dismiss from collapsing the notification. Dismisses arriving after the window expire are treated as true remote dismissals and proceed normally.
- A chat bubble for that room is actively open (`BubbleTracker.isBubbleOpen`).

The protection window is **not** set for replies sent from other clients (Webmuks, another Andromuks instance, etc.). Those replies arrive via WebSocket sync and do not go through `NotificationReplyReceiver`, so the dismiss FCM they trigger is allowed through and correctly clears the Android notification.

> ⚠️ **Group-summary invariant: never `cancel()` the group summary while any child is still posted.** Android cancels a group's *children* when its summary is cancelled, so `refreshGroupSummary` only cancels `SUMMARY_NOTIF_ID` when `childCount == 0`. With one child left it leaves the summary in place (self-clears when the last child goes). Cancelling the summary at `childCount == 1` was the "mark one room read → every notification vanishes" bug: the per-room `cancel` dropped its own room, then the summary-cancel cascade took the surviving sibling down with it.

### Dismiss/post race — the per-room dismiss tombstone

A dismiss FCM (`{ "dismiss": [{ "room_id": … }] }`) carries **only `room_id`** — no event id, no timestamp (confirmed against gomuks' `PushDismiss` struct). It is a fire-and-forget `cancel()` with no durable memory. That left two races whenever a dismiss arrived near-simultaneously with the message it was meant to clear — reproducible when another client (e.g. Webmuks) marks a DM read *faster than the FCM round-trip*, so the message FCM and the dismiss FCM land within milliseconds:

- **Race 1 (in-flight post).** The dismiss is processed while `handleMessageNotification` is mid-post: the dismiss sees no active notification (the `notify()` hasn't run yet), so its `cancel()` is a no-op, and then the post lands and is never cancelled again. The old `pendingNotifications` set could not stop this — removing the pending marker doesn't halt a post already past its gate and running under `NonCancellable`.
- **Race 2 (worker resurrection).** The dismiss cancels a posted notification, then `NotificationImageWorker` re-posts after its multi-second download window and **resurrects** it. The worker's start-of-run "still active?" check (`doWork` step 1) is not atomic with the re-post seconds later.

**Fix — `NotificationDismissTracker`** (process-wide singleton, in-memory; `FCMService` and the worker share the process). It holds a per-room **dismiss timestamp** (`recordDismiss` / `isDismissedAfter`) and a per-room **monitor** (`lockFor`):

- Every post site checks `isDismissedAfter(roomId, messageReceivedAt)` immediately before `notify()`; the dismiss path calls `recordDismiss(roomId)` immediately before `cancel()`.
- **Both sides take the *same* monitor.** `EnhancedNotificationDisplay.getRoomLock` now delegates to `NotificationDismissTracker.lockFor`, and the dismiss path (`FCMService.handleDismissNotification`, all three cancel branches) and the worker re-post wrap their `record`/`cancel`/`notify` in `synchronized(lockFor(roomId))`. Two distinct locks would reopen the race, so this delegation is load-bearing.

**Why a timestamp, not a flag.** The comparison is **directional**: a post is suppressed only when the dismiss was processed *after* the message that triggered it was received. `messageReceivedAt` is captured at the top of `handleMessageNotification` (before any async work, so it reflects FCM arrival order) and threaded through `showEnhancedNotification` → `NotificationImageWorker.enqueue`. Both it and the dismiss time are on-device wall-clock, so they compare directly. The tombstone is a **high-water mark** — a stale dismiss can never block a *newer* message, because the newer message's receipt time is greater. This makes quick message bursts in the same room safe: only the messages that were actually read get suppressed (TTL on the map is hygiene only, not correctness).

Additional effects:
- **Suppressed posts clear the room MessagingStyle cache.** The build appends the incoming message to `roomMessageCache` *before* the guard, so a suppressed (already-read) message would otherwise replay in a future notification's history. The suppression branch calls `clearRoomMessageCache`.
- **The worker re-post re-checks active state *and* the tombstone under the lock** (not only at start-of-run). Besides killing resurrection, the tombstone arm stops a stale worker from clobbering a *newer* notification that re-posted during its download window.
- **Androlog.** The dismiss subsystem previously had no `Androlog` (invisible in release). It now logs each outcome under the `Notifications` category: `dismissed (active…)`, `dismiss recorded (… in-flight guard)`, `post suppressed…`, `worker re-post skipped…`.

**The one unsolved edge — dismiss-before-message.** If a dismiss FCM is *delivered before* the message it follows, the directional compare (`dismissTime > messageReceivedAt`) declines to suppress and the notification lingers. This is unsolvable locally because the dismiss payload carries nothing to order it against the message. It is also rare: the message is **high-priority** FCM (delivered immediately, bypasses Doze) while the dismiss is **normal-priority** (deferred) — so ordering normally favours the message. It only inverts if FCM downgrades the message to normal under high-priority quota pressure (e.g. a very bursty DM) and then reorders it past the dismiss. The failure mode is a lingering notification — strictly less-bad than a lost one — so it is accepted rather than fixed. The order-independent fix would be reconciliation against gomuks read state via `/exec` (the message carries `event_rowid`, and the worker already round-trips per notification), or a backend change adding a read-up-to reference to `PushDismiss`.

## Bubble integration

Chat bubbles (Android 11+) are tracked via the `BubbleTracker` singleton. `FCMService` consults it before dismissing notifications to avoid collapsing an open bubble.

Auto-expanded bubbles are tracked in `EnhancedNotificationDisplay.autoExpandedBubbleRooms` to avoid re-expanding on every notification update.

## Notification Tap Navigation

Tapping a notification PendingIntent delivers the intent to `MainActivity` via `onCreate` (cold start) or `onNewIntent` (warm start). Both paths call:

```
appViewModel.setDirectRoomNavigation(roomId, notificationTimestamp, targetEventId)
```

This sets `directRoomNavigation`, increments `directRoomNavigationTrigger`, and clears `pendingRoomToRestore`.

### Critical invariant: `onNewIntent` must NOT call `navigateToRoomWithCache`

`onNewIntent` (warm start) must **only** call `setDirectRoomNavigation`. It must never call `navigateToRoomWithCache` directly.

**Why:** `navigateToRoomWithCache` synchronously calls `updateCurrentRoomIdInPrefs()` on the calling thread, which sets `currentRoomId = newRoom` and (when `previousRoomId != newRoom`) clears `timelineEvents = emptyList()` immediately — before any UI handler has reacted. The currently-visible `RoomTimelineScreen` observes `currentRoomId` changing away from its own `roomId` and `timelineEvents` suddenly empty; its `LaunchedEffect(roomId)` guard for `isAlreadyLoaded` fires `navigateToRoomWithCache(oldRoom)`, racing with the notification navigation. This produces either a navigation that stops mid-way or a room that opens with an empty, non-loading timeline despite a cache being available.

`setDirectRoomNavigation` only increments `directRoomNavigationTrigger` and records the target room — it does not touch `currentRoomId` or `timelineEvents`. The authorised callers of `navigateToRoomWithCache` for notification-triggered flows are:
- `RoomListScreen.LaunchedEffect(navigationTrigger)` — warm-start, different room
- `RoomTimelineScreen.LaunchedEffect(navTrigger)` — different room handler
- `AuthCheck.kt` navigation callback — cold-start path (called from a coroutine, not synchronously on the main thread at risk of racing UI)

### Same-room notification guard in `RoomListScreen`

`RoomListScreen.LaunchedEffect(navigationTrigger)` fires whenever `directRoomNavigationTrigger` increments — including when the user is already on `RoomTimelineScreen` for the target room (user had the room open, backgrounded, tapped its notification). When that happens, both `RoomListScreen.LaunchedEffect(navigationTrigger)` and `RoomTimelineScreen.LaunchedEffect(navTrigger)` fire simultaneously. `RoomTimelineScreen`'s same-room handler correctly clears `directRoomNavigation` and sets the scroll-to-event highlight without reloading the timeline.

To prevent a race where `RoomListScreen` wins and incorrectly calls `navigateToRoomWithCache` (causing an unnecessary reload) and then `navigateToRoomTimelineForExternalEntry` (which would push a duplicate back-stack entry because `auth_check` is already gone from the stack and `popUpTo` is a no-op), `RoomListScreen.LaunchedEffect(navigationTrigger)` contains a same-room guard:

```kotlin
if (directRoomId == appViewModel.currentRoomId) {
    // same room already open — RoomTimelineScreen's same-room handler will consume this
    return@LaunchedEffect
}
```

This guard is placed **before** `clearDirectRoomNavigation()` so the token is still available for `RoomTimelineScreen`.

### `navigateToRoomTimelineForExternalEntry` uses `launchSingleTop`

`navigateToRoomTimelineForExternalEntry` (defined in `RoomListScreen.kt`) uses `launchSingleTop = true` as defence-in-depth:

```kotlin
private fun NavController.navigateToRoomTimelineForExternalEntry(roomId: String) {
    navigate("room_timeline/$roomId") {
        popUpTo("auth_check") { inclusive = true }
        launchSingleTop = true
    }
}
```

`popUpTo("auth_check")` clears the back-stack up to `auth_check` (the root). On warm start, `auth_check` is no longer in the back-stack so `popUpTo` is a no-op. `launchSingleTop = true` prevents a duplicate `room_timeline/<id>` entry from being pushed if the destination is already at the top of the back-stack (e.g. if the same-room guard above incorrectly fell through or another handler raced).

### `openedViaDirectNotification` invariant

Every code path that navigates to `room_timeline/<id>` as a result of a notification or shortcut tap **must** set `appViewModel.openedViaDirectNotification = true` immediately before calling `navController.navigateToRoomTimelineForExternalEntry(roomId)`.

This flag is checked inside `navigateToRoomListIfNeeded`. When `true`, the force-navigation back to `room_list` (which fires when the startup navigation callback runs after `directRoomNavigation` has already been consumed) is suppressed.

Affected call sites (all must set the flag):
- `RoomListScreen.kt` — every `navigateToRoomTimelineForExternalEntry` call inside LaunchedEffects for `directRoomId`/`pendingRoomId` paths (cached and non-cached), the spacesLoaded observer, the timeout fallback, and the `navigationTrigger` reactive handler.
- `RoomTimelineScreen.kt` — the `LaunchedEffect(navTrigger)` "different room" handler.

Missing the flag at any site re-introduces the redirect-back-to-room_list race (Bug 7 in AUTHCHECK.md).

### `isTimelineLoading` reset in abort paths

`navigateToRoomWithCache` sets `isTimelineLoading = true` synchronously before launching its coroutine. The coroutine contains two early-return abort checks (after the batch flush, and before `requestRoomTimeline`) that trigger when a concurrent navigation supersedes the current one (`currentRoomId != roomId`). Both abort handlers reset `isTimelineLoading = false` when `currentRoomId.isEmpty()` (user navigated back to room list), preventing the loading spinner from sticking permanently.

## Shortcut / conversation API

Each room notification creates or updates a `ShortcutInfoCompat` (via `ConversationsApi`) so Android associates the notification with a conversation shortcut. This is required for `MessagingStyle` and bubble support.

`ConversationsApi.onRoomActivity(roomItem)` is called inside the `synchronized` block in `showEnhancedNotification`, **after** `notificationManager.notify()`. This ensures only notifications that are actually posted push the room to the top of the Direct Share ranking — silent/suppressed notifications (low-priority rooms, same-room-app-visible suppression, bubble-already-visible silent updates) exit via early `return` before reaching the synchronized block and do not affect the ranking.

## People / Conversation widget tile updates

The Android **People / Conversation widget** (a launcher-hosted tile bound to a conversation `shortcutId`) is kept current for our rooms — including when the conversation is **silenced in Android** (the original goal: silencing is a device-side action gomuks doesn't know about, so it keeps pushing FCM, but a silenced notification no longer drives the widget on its own). The mechanism below was reverse-engineered against the AOSP People Space service and is **non-obvious** — several "obvious" approaches were tried and disproven, so read this before changing it.

### What actually drives the tile content: the latest notification post

The tile's **message text + unread count come from the notification posts**, not from the shortcut and not from the `ConversationStatus`. Two things that were tried and **do not** move the tile's content (verified by logcat): pushing/re-pushing the `ConversationStatus`, and re-pushing the conversation **shortcut**. Only a `notify()` advances the tile — and the widget renders the **latest** post, as designed, *once the post carries a correct recency marker and a tight shortcut binding*.

**The "one behind" is a real People Space behaviour — the tile advances exactly one `notify()` behind.** This was chased through several wrong theories (re-pushing the shortcut, re-pushing `ConversationStatus`, anchoring `when`); logcat finally settled it. With the post-notify shortcut push **removed** (`onRoomActivity(room, pushShortcut = false)`) and a clean single post (`msgCount=1`, correct `when`, both shortcut fields bound), the tile **still** rendered the previous message. Two posts land it on the current one; one post never does. So the Pixel People Space service renders its **stored** tile and only then stores the new post — a genuine read-then-store lag we cannot patch (we don't own the widget; `notify()` is the only trigger it reacts to).

The design that follows: **one logical message → two posts of its final content.**
- **Plain text / emoji (Phase 2 enriches nothing).** The worker re-posts the **existing** notification **byte-identical**, only OR-ing in `FLAG_ONLY_ALERT_ONCE` so it doesn't re-buzz. Re-posting the existing `Notification` (NOT rebuilding via `extractMessagingStyleFromNotification`) is deliberate: a rebuild re-wraps a single-emoji body and kills the launcher's **jumbo-emoji** tile, whereas the byte-identical re-post preserves the plain-`String` body. This is the second `notify()` the lagging tile needs.
- **Image / caption / avatar (Phase 2 enriches).** Phase 1 posts text ("Sent an image" — the image isn't downloaded yet), the worker posts the enriched (image) notification, and then posts it **once more** after a ~400 ms settle. Because the tile lags one post, the enriched content must be the last **two** posts to land — so the sequence is `text → image → image` and the tile settles on the image. The second enriched post re-reads the *current* active notification (not a stale capture) so a newer message that superseded ours during the settle isn't reverted.

Both supporting fixes stay (hygiene, and they keep the *notification* correct even though they didn't fix the tile lag): Phase-1 `when` tracks the **newest** cached message (`max` over the cache, monotonic against clock skew) instead of the oldest (`60575076`); and the shortcut is bound by **both** `setShortcutInfo` *and* `setShortcutId` in Phase 1 and the worker.

> ⚠️ **Invariant: never mutate a conversation's shortcut (`pushDynamicShortcut` / `removeDynamicShortcuts`) *after* its `notify()`.** Publish/refresh the shortcut **before** the post — a post-notify shortcut change is one more thing that can disturb the tile. (It was not the cause of the one-behind, but keeping shortcut mutations pre-notify removed a confounder.)

Both worker posts stay silent (`setOnlyAlertOnce`) so they never re-buzz. The enriched build also sets `when = max(existing when, now)` — secondary hygiene to keep the notification's recency sensible; the thing that actually lands the image on the tile is the *second* enriched post (the one-behind count), not the `when` value.

- **DM (1:1) tiles patch in place.**
- **Group tiles rebuild (wipe-then-fill) on a content change** → groups visibly "clear then render" when the enriched re-post lands. This is the launcher's group-tile rendering, not our content. Accepted cost. The only way to make a group tile patch-in-place like a DM is `setGroupConversation(false)`, which strips the group treatment from the *notification* itself — deliberately **not** done.

### ConversationStatus — tried, then removed (do not re-add)

We previously pushed a `PeopleManager.addOrUpdateStatus` ConversationStatus per message (activity-type chip from `get_event` msgtype, DM availability dot) to "wake" silenced-conversation tiles. **It has been removed entirely** — do not bring it back without strong evidence:

- It **never drove tile message/count** (the notification posts do, see above), so it only ever provided cosmetic decoration.
- Worse, it **interfered** with the notification-driven render: every `addOrUpdateStatus`/`clearStatus` routes through `DataManager.updateConversationStoreThenNotifyListeners`, poking the People Space listeners and racing the tile's content update — the tile stopped reacting to message content properly while we were writing statuses.
- It was also a footgun: `addOrUpdateStatus` throws `IllegalArgumentException: Start time must be in the past` if `setStartTimeMillis` is in the future, and the **Matrix event timestamp routinely runs a few hundred ms ahead of the device clock** (server skew), so the raw timestamp made *every* push throw (which silently broke a long line of widget experiments).

Silenced conversations stay current anyway: the People Space listener still receives silenced notification posts, and the corrected `when` + shortcut binding land the tile on the latest post directly — no status poke and no extra re-post needed.

### Known limitations (by design, accepted)

- **Inline images on the widget depend on the launcher.** The stock Pixel People tile *does* draw inline images (the FileProvider URI read grant in `contentUriForFile` is what makes the launcher process able to load it). With the `when` / shortcut-binding fixes above the tile lands on the current notification, so an image message's tile shows the image once the worker's enriched post lands. On launchers whose People tile doesn't draw inline media, the tile falls back to the body text — harmless. The *notification* itself always shows the image fine (systemui is granted).
- `WIDGET_POST` / `WIDGET_REPOST` debug logs (in `showEnhancedNotification` and the worker) remain as `Log.d` markers for re-diagnosing tile behavior: they print room type, `groupConversation`, conversation title, shortcut id, message count, and last message text/ts/sender — compare "what we posted" against what the tile renders to localize a regression to our notification vs the People Space service.
