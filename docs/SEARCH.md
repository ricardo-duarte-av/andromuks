# Message Search

`SearchResultsScreen` lets the user search messages either on the homeserver
(`search_server`) or in the local SQLite database (`search_local`), with a few
toggles. It is reached from the **More (⋮)** overflow menu in the room timeline
header (`RoomTimelineScreen` → `RoomHeader`).

## Entry point & navigation

- **Header menu**: `RoomHeader`'s overflow (`MoreVert`) dropdown contains a
  **Search** item. It navigates to `search?roomId={roomId}` with the current
  room ID URL-encoded.
- **Route**: defined in `MainActivity` as `search?roomId={roomId}` (nullable
  arg, mirrors the `mentions` route) → `SearchResultsScreen(roomId = ...)`.

## UI

A header with a back button + title, a search text box, and four option
switches:

| Option | Default | Notes |
|---|---|---|
| **Current room only** | on (off/disabled if no room context) | Restricts the search to `roomId`. |
| **Sort by time** | off | Order by timestamp instead of relevance. |
| **Search local database** | off (**on** if the room is E2EE) | Picks `search_local` vs `search_server`. The homeserver can't search encrypted content, so E2EE rooms default to local. |
| **Include redacted events** | off | **Hidden unless local search is on** — it's a `search_local`-only parameter. |

E2EE detection prefers `currentRoomState?.isEncrypted` when the search room is
the active room, otherwise falls back to
`isRoomEncryptedFromState(getRoomState(roomId))`.

Results render as cards (modeled on `MentionsScreen`'s `MentionItem`): room
context (avatar + name) + sender + time + message body via
`AdaptiveMessageText`, grouped under date dividers. Tapping a result navigates
to `event_context/{roomId}/{eventId}` (`EventContextScreen`), which loads the
message in context (5 before / 5 after) and scrolls to it.

## WebSocket protocol

Both commands return a `ManualPaginationResponse` — `{ events, next_batch }`
(the same shape `paginate_manual` uses) — so the app handles them through one
path.

`AppViewModel.searchMessages(...)` builds the request and issues either
`search_local` or `search_server` depending on the `searchLocal` flag:

```jsonc
// search_local
{"command":"search_local","request_id":N,"data":{
  "search_term":"…","raw_like":"","limit":50,
  "room_ids":["!room:hs"],"senders":[],
  "include_redacted":false,"sort_by_time":false}}

// search_server (no include_redacted)
{"command":"search_server","request_id":N,"data":{
  "search_term":"…","raw_like":"","limit":50,
  "room_ids":["!room:hs"],"senders":[],"sort_by_time":false}}
```

- `include_redacted` is sent **only** for `search_local` (server search ignores
  it).
- `next_batch` is added to `data` only when continuing a previous search; per
  the API contract, all other params must remain identical between pages.

Wiring in `AppViewModel`:

- `searchRequests: MutableMap<Int, (List<TimelineEvent>, String) -> Unit>` —
  pending requests keyed by `request_id`, parallel to `threadPaginateRequests`.
- `handleSearchResponse(...)` parses `events` into `TimelineEvent`s (via
  `TimelineEvent.fromJson`, which reads `room_id`) and the `next_batch` token.
- Registered in the `isInRequestMap` staleness guard and the response dispatch
  chain.
- A 15 s timeout invokes the callback with empty results so the screen never
  hangs.

## Pagination (`next_batch`)

Both commands support pagination:

- **`search_local`**: offset-based. The server emits
  `next_batch = "local_offset:<N>"` **only when the page comes back full**
  (`len(events) >= limit`). A short page means no more results, so no token.
- **`search_server`**: returns the homeserver's own Matrix `/search`
  `next_batch` cursor, independent of page fullness.

The screen keeps the latest token and **auto-loads** the next page via a
`snapshotFlow` on the last visible index (fetches when within 3 items of the
end and a token exists). A footer also shows a spinner while loading and acts
as a manual "Load more" tap. Overlapping pages are de-duplicated by `eventId`.

## Date-divider keys (crash guard)

Search results — especially relevance-sorted (`sort_by_time = false`) — repeat
the same calendar date in non-contiguous segments. Keying `LazyColumn` date
dividers on the date string alone produced duplicate keys and an
`IllegalArgumentException`. The fix: `SearchResultItem.DateDivider` carries the
**anchor event ID** of the first event under it, making the key
`date_${date}_$anchorEventId` unique per segment. This is the same
non-monotonic-ordering pitfall noted for the main timeline.
