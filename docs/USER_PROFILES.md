# User Profile Architecture

## Two Sources, Two Caches

| Source | Cache | Key |
|---|---|---|
| `m.room.member` event content (sync_complete, paginate, get_specific_room_state) | `ProfileCache.flattenedMemberCache` | `"roomId:userId"` |
| `get_profile` response (Matrix global profile endpoint) | `ProfileCache.globalProfileCache` | `userId` |

**Critical invariant:** `m.room.member` data is **always room-scoped**. A user can have a global profile "Alice", a per-room name "Alice (WA)" in a WhatsApp bridge room, and "Alice (TG)" in a Telegram bridge room — all different. `storeMemberProfile` (`MemberProfilesCoordinator`) must **never** promote a room profile to `globalProfileCache`. Only `handleProfileResponse` (the `get_profile` response handler) may write to `globalProfileCache`.

`RoomMemberCache` (`roomId → userId → MemberProfile`) is a parallel legacy store updated alongside `flattenedMemberCache` for backward compatibility. It is the source of truth for the mention list.

## Resolution Order (`getUserProfile`)

1. `ProfileCache.flattenedMemberCache["roomId:userId"]` — room-specific override (highest priority)
2. `RoomMemberCache.getMember(roomId, userId)` — legacy room store
3. `currentUserProfile` — fast path for the local user
4. `ProfileCache.globalProfileCache[userId]` — global fallback

## `currentUserProfile` SharedPreferences Cache

`currentUserProfile` (the logged-in user's own global profile) is cached in `AndromuksAppPrefs` SharedPreferences so that `checkStartupComplete()` can be ungated immediately on the next cold start without waiting for a `get_profile` round-trip.

**Keys:** `current_user_display_name`, `current_user_avatar_mxc`

**Sentinel contract:**
- Key **absent** (`getString` returns `null`) — profile has never been fetched; a `get_profile` network request is required.
- Key **present with value `""`** — profile was fetched and the field is genuinely blank (user has no display name / no avatar).
- Key **present with non-blank value** — use directly.

Both `persistCurrentUserAvatarMxcIfChanged` and `persistCurrentUserDisplayNameIfChanged` always call `putString` (never `remove`) so the presence of the key is unambiguously the "already fetched" signal.

**Fast path in `ensureCurrentUserProfileLoaded()`:**
1. Check SharedPreferences — if **both** keys are non-null, populate `currentUserProfile` and call `checkStartupComplete()` immediately. A background `requestUserProfile` still fires to keep the cached value fresh.
2. Check in-process `ProfileCache` singleton — populated when another VM instance in the same process already fetched the profile.
3. Network — `get_profile` request; response writes both SharedPreferences keys via `persistCurrentUserDisplayNameIfChanged` + `persistCurrentUserAvatarMxcIfChanged`.

**Write paths:** all three paths that assign `currentUserProfile` must call both persist functions: `MemberProfilesCoordinator.handleProfileResponse`, the `m.room.member` state-event path in `AppViewModel`, and the `ProfileCache` fast path in `ensureCurrentUserProfileLoaded`.

## Write Path (`storeMemberProfile`)

| Situation | Action |
|---|---|
| Room profile arrives, **no global profile exists yet** | Store in `flattenedMemberCache` + `roomMemberIndex`. Do **not** promote to global — that slot must remain empty until a `get_profile` round-trip fills it. |
| Global profile exists and room profile **differs** | Store in `flattenedMemberCache` (genuine per-room override). |
| Global profile exists and they **match** | Remove any existing room entry — the global is sufficient. |

`updateGlobalProfile` (called only from `handleProfileResponse`) writes to `globalProfileCache` and calls `cleanupMatchingRoomProfiles` to remove room entries that now match the new global.

## `getMemberMap` and the Timeline

`getMemberMap(roomId)` iterates `ProfileCache.roomMemberIndex[roomId]` (users with room-specific entries), falls back to their global profile if the flattened slot is missing, then appends any timeline event senders not yet in the map. Receipt-only users (no messages sent in the room) only appear in `getMemberMap` after their `m.room.member` state has been fetched and stored in `flattenedMemberCache`.

`RoomTimelineScreen` and `RoomInfo.kt` both drive avatar/name rendering via `remember(roomId, appViewModel.memberUpdateCounter) { getMemberMap(roomId) }`. `memberUpdateCounter` increments whenever `storeMemberProfile` or `parseMemberEventsForProfileUpdate` changes any profile.

## Pre-Render Profile Fetch (Initial Paginate Only)

When an initial room-open paginate response arrives, the app issues **one** `get_specific_room_state` for all user IDs that have no cached profile — collecting senders, mention targets, reply targets, and read receipt holders from the response in a single pass. `handleInitialTimelineBuild` (which sets `isTimelineLoading = false` and makes the timeline visible) is called only inside the `onComplete` callback of this request, so the timeline never renders with missing profiles. A 5-second timeout fires `onComplete` as a fallback if the backend does not respond.

Implemented in `requestRoomProfilesForRender` (`AppViewModel`), called from `processEventsArray` inside `handleTimelineResponse` (`TimelineCacheCoordinator`). Background prefetch and user pull-to-paginate are unaffected.
