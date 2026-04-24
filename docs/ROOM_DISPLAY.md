# Room Display Name & Avatar Resolution (m.heroes)

## Where It's Computed

`displayRoomName` and `displayAvatarUrl` are computed near the top of `RoomTimelineScreen` and `BubbleTimelineScreen` using a unified fallback chain that applies to **all** room types (DM and group).

## Fallback Chain

1. **Room has a name** (`roomName` is non-blank and not equal to the raw `roomId`) **or** a canonical alias → use `roomName` / `roomItem?.avatarUrl` directly.
2. **No name and no canonical alias** (`needsHeroesFallback = true`) → apply m.heroes: pick the first member who is neither the current user nor a service member (per `io.element.functional_members`), use their display name and avatar. Falls back to the member's localpart (`@user:domain` → `user`) if no display name is set, and ultimately to the raw `roomName` string if no eligible member is found.

## Service Members

Service members are read from `AppViewModel.functionalMembersCache[roomId]` (a `Map<String, Set<String>>` populated when `io.element.functional_members` state events are processed). The cache is `internal` and directly accessible from both screen files.

## Why No Special DM Code Path

Mautrix bridges often set a proper room name and avatar via room account data — those should be respected. The heroes path activates only when the room genuinely has no name or alias, which covers nameless DMs just as well as nameless group rooms.
