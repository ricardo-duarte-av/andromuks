# Inline media galleries (MSC4274)

Rendering support for MSC4274 "Inline media galleries via msgtypes" — one event carrying several
media items, shown as a single mosaic in the timeline.

Source of truth for the event shape:
<https://raw.githubusercontent.com/Johennes/matrix-spec-proposals/refs/heads/johannes/msgtype-galleries/proposals/4274-inline-media-galleries.md>

**Receiving only.** Sending galleries is not implemented — see [Not implemented](#not-implemented).

## Event shape

```json5
{
  "type": "m.room.message",
  "content": {
    "msgtype": "m.gallery",              // unstable: "dm.filament.gallery"
    "body": "Legenda",                   // gallery-level caption
    "format": "org.matrix.custom.html",  // optional
    "formatted_body": "…",               // optional, requires `format`
    "itemtypes": [                       // ordered
      {
        "itemtype": "m.image",           // `itemtype`, NOT `msgtype`
        "body": "1000115270.png",        // per-item filename
        "url": "mxc://…",                // or `file` in encrypted rooms
        "info": { "w": 996, "h": 1601, "thumbnail_url": "mxc://…", … }
      }
    ]
  }
}
```

Each object in `itemtypes` has the same schema as the `content` of an `m.image` / `m.video` /
`m.audio` / `m.file` message, except the type key is `itemtype` instead of `msgtype`.

Two consequences that drive the implementation:

- **An item is a `MediaMessage`.** The only difference from a standalone media message is the key
  name, which is why the parser takes a `typeKey` parameter (see [Phase 1](#phase-1--shared-parser)).
- **Items have no captions.** Per-item `body` is the filename; the caption belongs to the gallery
  (`content.body` / `formatted_body`, with `local_content.sanitized_html` as usual) and is rendered
  once beneath the mosaic.

Both `m.gallery` and `dm.filament.gallery` are accepted. The MSC prescribes no fallback for clients
without support, so a gallery from another client carries no plain-text alternative — everything a
user sees comes from the code below.

Galleries are capped by the 64 KiB event limit at roughly 60 items.

## Phase 1 — shared parser

`utils/MediaContentParser.kt` holds `parseMediaMessage(content, body, typeKey, localContent)`,
extracted from the two copies that previously lived inline in `TimelineEventItem.kt` (the plaintext
path in `RoomMediaMessageContent` and the near-identical copy in `EncryptedMessageContent`). All
three media paths — plaintext, encrypted, gallery items — now go through it.

`typeKey` is `"msgtype"` for message content and `"itemtype"` for gallery items. It returns `null`
when the content has no usable `url` (neither a direct `url` nor `file.url`), which is the signal
callers use to fall back to rendering `body` as plain text.

The unified parser takes the superset of the two originals, so the **encrypted** path gained four
behaviours it was missing:

| Behaviour | Was (encrypted path) | Now |
|---|---|---|
| `m.file` filename | `filename` field only — blank when absent | falls back to `body`, per the Matrix spec |
| `thumbnail_url` / `thumbnail_info` | read only for `m.image` / `m.video` | read for all media types |
| BlurHash key | `xyz.amorgan.blurhash` only | also the bare `blurhash` fallback key |
| Caption after an edit | always `event.localContent` | prefers `editedBy.localContent` (avoids stale `orig_local_content`) |

`extractWaveform` (MSC1767 voice-message samples) moved into the same file alongside the parser.

`mediaContentHasEncryptedFile(content)` is exposed next to it: encryption is a per-item property in
a gallery (each item has its own `url` or `file`), so the flag has to travel with the item rather
than being derived once per event.

## Phase 2 — detection

`GALLERY_MSGTYPES = setOf("m.gallery", "dm.filament.gallery")` is checked in `RoomMessageContent`
and `EncryptedMessageContent` *before* the `m.image`/`m.video`/`m.audio`/`m.file` branch. Edits
arrive as `m.new_content` with the same msgtype, which the existing `editMsgType` read already
handles, so a caption edit re-renders the gallery rather than dropping to text.

Redaction, reactions, replies, and thread wrapping all operate at event level and need no changes.

## Phase 3 — timeline mosaic

The mosaic fills the standard `STANDARD_MEDIA_WIDTH` (288 dp) frame, 2 dp gaps, outer corners
rounded to match the bubble. Tile arrangement is chosen by item count:

| Items | Layout |
|---|---|
| 1 | rendered exactly like a plain `m.image` — natural aspect, no crop |
| 2 | two half-width tiles |
| 3 | one large tile left, two stacked right |
| 4 | 2 × 2 |
| 5–8 | 3 × 3, last row partially filled |
| 9+ | 3 × 3 with a `+N` overlay on the ninth tile |

Nested `Row`/`Column`, never `LazyVerticalGrid` — the height is then a pure function of the item
count, which keeps `LazyColumn` scroll anchoring stable and avoids nested scrolling. Only the root
layout receives the incoming `modifier` (detekt's `ModifierReused`).

Per tile:

- **Thumbnail first, original as fallback.** Tiles are 94–144 dp, so the pre-baked thumbnail is
  always the right asset; `shouldUseTimelineThumbnail` takes a `targetWidth` so the 2× stretch rule
  is evaluated against the tile, not the 288 dp frame. If the thumbnail fails to load, the tile
  retries with the item's original URL — the same fallback ladder single media uses.
- **BlurHash placeholder** from that item's own hash.
- **Individually tappable**, reporting its own bounds via `onGloballyPositioned`, so the viewer opens
  at that item's index with the shared-element animation originating from the tapped tile.
- **Video tiles** get the play badge and open `VideoPlayerDialog` directly — video does not enter the
  image pager.

`resolveShowMediaPreviews` gates the whole mosaic at once, not per tile.

The visible-window media prefetchers in `EventContextScreen.kt` and `BubbleTimelineScreen.kt` read
`info.thumbnail_url` / `thumbnail_file.url` straight off the content and know nothing about
`itemtypes`, so gallery tiles are not prefetched — they load on display like any uncached media.

## Phase 4 — pager viewer

`ImageViewerDialog` takes `items: List<MediaMessage>` plus `initialIndex`, with a single-item
overload so the seven existing call sites (stickers, avatars, room info, narrator, inline HTML
images, room media gallery, timeline) are unchanged.

Per-page state — scale, offset, rotation, cached file, cache-bypass retry, error body — lives in a
private `ImageViewerPage`. The toolbar, open animation, and button auto-hide stay at dialog level.

Zoom and gesture model:

- `ContentScale.Fit`: at rest the whole image is visible, no overflow in either axis.
- `scale` clamps to `1f..5f` — **zooming out below fit is not possible** (was `0.5f`); a pinch that
  would undershoot snaps back to `1f`.
- At `scale == 1f` a horizontal drag goes to the pager: **slide** between items.
- At `scale > 1f` the pager's `userScrollEnabled` is false and drags **pan** the zoomed image.
- Changing page resets scale/offset/rotation, so every new page starts in slide mode.

The shared-element open animation applies to `initialIndex` only; other pages fade in. Save acts on
`pagerState.currentPage`. Adjacent pages (±1) are prefetched through Coil. A `3 / 9` indicator sits
in the toolbar row.

## Phase 5 — text fallbacks

A gallery has no plain-text fallback of its own, so every surface that summarises an event by
msgtype needs a case, preferring the caption when non-blank and otherwise showing
`🖼️ Gallery (N)`:

- `utils/SpaceRoomParser.kt` — room list preview
- `NotificationDataParser.kt`, `NotificationImageWorker.kt` — notification title/body
- `utils/ReplyFunctions.kt` — reply preview (three sites)

## Not implemented

- **Sending.** Composing a gallery, and the `file` vs `url` split for encrypted rooms on upload.
- **Mixed types.** v1 renders `m.image` and `m.video` items. `m.audio` / `m.file` items are filtered
  out of the mosaic and summarised as a muted `+N other attachments` line under the caption, so they
  are never silently dropped. Rendering them properly (compact `AudioPlayer` / `FileDownload` rows
  below the mosaic) is future work.
- **Room media browser.** `utils/RoomMediaGallery.kt` scans events for `m.image`/`m.video` msgtypes
  and does not look inside `itemtypes`, so gallery items do not appear in a room's media browser
  yet. Self-contained follow-up: flatten gallery items when building `GalleryMediaItem`s.
