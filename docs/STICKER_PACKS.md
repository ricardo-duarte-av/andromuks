# Sticker & Emoji Packs — Subscriptions and In-Room Discovery

A "pack" is an **`im.ponies.room_emotes`** (or MSC2545 **`m.image_pack`**) *state event* in some
room, named by its state key. A room can host any number of them — the state key is the pack id, so
one sticker room commonly carries a dozen.

Which packs *this account* loads is decided entirely by one account data key. Nothing else makes a
pack appear; a pack sitting in a room you are in is invisible until it is listed there.

```
account data (im.ponies.emote_rooms | m.image_pack.rooms)
  content.rooms = { "!room": { "packName": {} } }
        │
        ▼  SyncRoomsCoordinator.processAccountData
  requestEmojiPackData(roomId, packName, stateEventType)     ──▶ get_specific_room_state
        │
        ▼  handleEmojiPackResponse
  StickerPackParsing.parsePackContent
        │
        ├──▶ EmojiPacksCache    ──▶ emoji picker tabs
        └──▶ StickerPacksCache  ──▶ sticker picker tabs
```

## The two keys

| Account data key | Room state event type | When |
|---|---|---|
| `m.image_pack.rooms` | `m.image_pack` | The official MSC2545 key. Wins whenever present. |
| `im.ponies.emote_rooms` | `im.ponies.room_emotes` | The legacy key. Used otherwise, and written for a first subscription — it is what gomuks and the maunium sticker picker produce. |

The pairing is resolved in exactly one place, `StickerPackCoordinator.resolveKeys`, which both the
sync reader and the subscription writer call. **Do not re-inline this decision.** Writing a
subscription into a key the loader does not read is silent: the account data is correct, the pack
never loads, and nothing logs.

## Parsing — `utils/StickerPackParsing.kt`

`parsePackContent(roomId, packName, content)` turns one pack's `content` into an `EmojiPack` and/or
a `StickerPack`. Each image carries a `usage` array:

| `usage` | Emoji picker | Sticker picker |
|---|---|---|
| `["emoticon"]` | ✅ | ❌ |
| `["sticker"]` | ❌ | ✅ |
| `["sticker", "emoticon"]` | ✅ | ✅ |
| absent or `[]` | ✅ | ✅ |

The last row is the one that surprises people: **an absent `usage` means both**, per the MSC. An
emoticon-only pack legitimately produces *no* sticker pack — it is not a bug, and it is the first
thing to check when a subscribed pack "is missing" from the sticker picker.

The function is pure and shared by both callers — the response handler for a subscribed pack, and
the in-room scan for unsubscribed ones — so the same pack cannot look different depending on how it
was reached. Rules are pinned in `StickerPackParsingTest`.

## Subscribing and unsubscribing — `StickerPackCoordinator`

| Call | Effect |
|---|---|
| `subscribedPacks()` | Flattens `content.rooms` of the active key into `(roomId, packName)` refs. |
| `subscribe(roomId, packName)` | Rewrites the key via `AccountDataCoordinator.setAccountDataRaw`, then fires `requestEmojiPackData` so the pack is usable without waiting for the server to echo the account data back. |
| `unsubscribe(roomId, packName)` | Rewrites the key, then evicts `StickerPacksCache` + `EmojiPacksCache` so the pickers stop offering it immediately. |
| `roomPacks(roomId)` / `roomEmojiPacks(roomId)` | The packs `roomId` hosts that are **not** subscribed. |

`withSubscription` (pure, tested in `StickerPackSubscriptionTest`) rebuilds the `rooms` object rather
than mutating it, so the optimistic cache update cannot alias the object just sent. A room key whose
last pack is removed is **dropped entirely** — an empty room object would leave the loader with a
pack source that yields nothing.

Per-pack values (the user's overrides) are carried across an unrelated edit rather than reset to
`{}`.

## In-room discovery

`roomPacks` reads `RoomStateStore.getRawByType(roomId, stateEventType)` — the raw tier keyed
`"type|stateKey"`, which is why enumerating by type needed a new accessor: the single-key
`getRawContent` cannot answer "which packs does this room host?" when the state keys are unknown up
front.

This costs **no round trip**: the room's state is already resident from the `get_room_state` issued
on room open. A room outside the raw tier's 24-room LRU contributes nothing — the honest answer,
not a claim that it has no packs.

Both pickers (`utils/StickerSelection.kt`, `utils/EmojiSelection.kt`) append these after the
subscribed packs and render them through the shared `PackTab`: outlined instead of filled, with a
`+`. Selecting such a tab is free and non-committal — its images send like any other — and only the
`+` writes account data. Because subscribing fires a network fetch, the pack cannot move into the
subscribed list straight away, so each dialog keeps a local `addedHere` set: the tab stops offering
to add, and does not jump position mid-interaction.

The pickers read `StickerPacksCache.version` / `EmojiPacksCache.version` — snapshot-state counters
bumped on every cache mutation. The pack lists themselves are plain locked collections read from
non-Compose contexts too, so those counters are what makes a picker repaint when a pack arrives.

## Why a subscribed pack can go missing

Two silent-drop paths existed until v1.1.46 and are worth remembering, because the symptom —
"I have two packs in account data and only one shows up" — points nowhere near the cause.

`requestEmojiPackData` registers the pending request in **two** maps, `emojiPackRequests` and
`roomSpecificStateRequests`. Both abandon paths used to clean up only the second:

- the main-thread request purge dropped the `roomSpecificStateRequests` half, so a response arriving
  afterwards matched no dispatch branch and was discarded — and nothing re-requested the pack until
  the next account-data sync. A purge landing between two in-flight requests loses exactly one pack.
  Purged requests are now re-queued onto `deferredEmojiPackRequests`, which the reconnect drain
  re-issues; the map carries the state event type so the legacy/official pairing survives.
- the error branch leaked its `emojiPackRequests` entry forever.

`handleEmojiPackResponse` now warns at `Log.w` — which survives R8, see
[the logging note](../CLAUDE.md#logging-in-release-builds) — when a subscribed pack resolves to no
state event, no content, no images, or no usable images. A pack missing from a picker must be
diagnosable from a user's logcat rather than merely absent.

## UI surfaces

| Surface | Does |
|---|---|
| Settings → **Sticker & emoji packs** (`StickerPackManagerScreen`) | Lists subscribed packs with host room and counts; removes them. Adding is deliberately *not* here — discovery belongs in the room that hosts the pack, where the images can be seen first. |
| Sticker picker (`StickerSelectionDialog`) | Subscribed packs, then this room's unsubscribed ones. |
| Emoji picker (`EmojiSelectionDialog`) | Same, appended after the Unicode category tabs. |

The manager screen renders a subscribed pack whose data never arrived as **"not loaded"** rather
than omitting it. The subscription is real even when the fetch behind it failed, and hiding it would
make that failure invisible — which is exactly how the bug above stayed hidden.
