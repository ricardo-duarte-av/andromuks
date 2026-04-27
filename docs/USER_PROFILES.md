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

## "Do We Have the Profile?" — The Authoritative Check

**`ProfileCache.hasFlattenedProfile(roomId, userId)`** is the canonical "we already have this user's room profile" signal used by `requestUserProfileOnDemand`. If it returns `true`, no `get_specific_room_state` request is issued, regardless of whether `displayName` or `avatarUrl` are blank.

| `hasFlattenedProfile` | Meaning | Action in `requestUserProfileOnDemand` |
|---|---|---|
| `false` | No room-specific profile stored yet | Send `get_specific_room_state` |
| `true` | Profile stored (may be `MemberProfile("","")`) | Skip — profile already fetched |

A stored `MemberProfile("", "")` is intentional: the `""` sentinel means "profile was fetched and the field is genuinely blank (user has no display name / no avatar configured)." The rendering layer applies a fallback name from the Matrix ID rather than re-requesting (see **Rendering Fallback** below).

This check replaced the previous `getUserProfile() != null && displayName != null && avatarUrl != null` guard, which incorrectly consulted the global profile cache and treated `""` (non-null) as "fully resolved" while callers used `isNullOrBlank()` semantics — causing a permanent deadlock where `TimelineEventItem` wanted to fetch but `requestUserProfileOnDemand` said "already done."

## When `get_specific_room_state` Is Requested

`requestUserProfileOnDemand` fires when a timeline event arrives from a sender not yet in `flattenedMemberCache` for that room. The two triggers:

1. **`LaunchedEffect(timelineEvents)`** in `RoomTimelineScreen` — runs after `timelineEvents` changes; calls `requestUserProfileOnDemand` for every sender whose profile is `null` or has a blank `displayName` *and* is not yet in `flattenedMemberCache`.
2. **`LaunchedEffect(event.sender, event.roomId)`** in `TimelineEventItem` — runs once per new event; calls `requestUserProfileOnDemand` if `userProfileCache[sender]` is missing or both fields are blank.

Requests are batched with an 80 ms flush delay (`PROFILE_BATCH_DELAY_MS`) and throttled per `roomId:userId` to once per 5 seconds (`PROFILE_REQUEST_THROTTLE_MS`) to avoid redundant requests during scroll recycling.

When the profile hint (`m.room.member` with `timeline_rowid: 0`) **is** present in the `sync_complete` payload, `populateMemberCacheFromSync` stores the profile and increments `memberUpdateCounter` before `processSyncEventsArray` runs — so `hasFlattenedProfile` returns `true` by the time any `LaunchedEffect` fires and no network request is needed.

## Rendering Fallback (`memberMapWithFallback`)

`RoomTimelineScreen` builds `memberMapWithFallback` via a `remember(memberMap, ...)` block that:
1. Starts from `memberMap` (the reactive `remember(roomId, memberUpdateCounter)` result).
2. Injects `currentUserProfile` if the local user is missing from the map.
3. Applies a display-name fallback transform over every entry:
   ```kotlin
   displayName = profile.displayName?.takeIf { it.isNotBlank() } ?: userId.removePrefix("@").substringBefore(":")
   ```
   Users with `displayName = ""` (no Matrix display name configured) are shown as the local part of their Matrix ID (e.g., `@alice:server` → `alice`). `avatarUrl` is passed through unchanged — a blank avatar simply shows the generated initials placeholder.

This transform is purely presentational; the stored `MemberProfile("", "")` in the cache is unaffected.

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

Every call to `storeMemberProfile` unconditionally writes to both `flattenedMemberCache` and `roomMemberIndex` for the `(roomId, userId)` pair. `m.room.member` events are authoritative — the room-specific entry must always exist so `getMemberMap` reliably finds the user via `ProfileCache.getRoomUserIds`.

Do **not** skip the write even when the room profile matches the global profile: doing so silently removes users from `getMemberMap`'s ProfileCache path, causing avatar/name to fall back to text placeholders until the next `memberUpdateCounter` cycle.

`updateGlobalProfile` (called only from `handleProfileResponse`) writes to `globalProfileCache` and calls `cleanupMatchingRoomProfiles` to remove room entries that now match the new global.

## `UserInfoScreen` — Always Fresh, Bypasses Guard

`UserInfoScreen` (`utils/UserInfo.kt`) uses `requestPerRoomMemberState` (not `requestUserProfileOnDemand`) to fetch the per-room profile. This function:
- Has **no guard, no throttle, no pending-request check** — it always sends `get_specific_room_state` immediately.
- Stores `roomSpecificProfileCallbacks[requestId] = callback` so the response is delivered directly to the screen via callback.
- `handleRoomSpecificStateResponse` detects the callback and **returns early** before `parseMemberEventsForProfileUpdate` — the response bypasses the cache update path entirely.

This means `UserInfoScreen` always shows the freshest backend data regardless of what is cached, and it does not pollute the timeline's cache with its fetches.

## `getMemberMap` and the Timeline

`getMemberMap(roomId)` iterates `ProfileCache.roomMemberIndex[roomId]` (users with room-specific entries), falls back to their global profile if the flattened slot is missing, then appends any timeline event senders not yet in the map. Receipt-only users (no messages sent in the room) only appear in `getMemberMap` after their `m.room.member` state has been fetched and stored in `flattenedMemberCache`.

`RoomTimelineScreen` and `RoomInfo.kt` both drive avatar/name rendering via `remember(roomId, appViewModel.memberUpdateCounter) { getMemberMap(roomId) }`. `memberUpdateCounter` increments for **every** `m.room.member` join event received while the app is visible and past initial sync — not only for actual joins or profile changes. This guarantees `memberMap` recomputes in the same pass as the timeline update regardless of whether the profile data changed, so avatars and display names are never stale on first render.

## Pre-Render Profile Fetch (Initial Paginate Only)

When an initial room-open paginate response arrives, the app issues **one** `get_specific_room_state` for all user IDs that have no cached profile — collecting senders, mention targets, reply targets, and read receipt holders from the response in a single pass. `handleInitialTimelineBuild` (which sets `isTimelineLoading = false` and makes the timeline visible) is called only inside the `onComplete` callback of this request, so the timeline never renders with missing profiles. A 5-second timeout fires `onComplete` as a fallback if the backend does not respond.

Implemented in `requestRoomProfilesForRender` (`AppViewModel`), called from `processEventsArray` inside `handleTimelineResponse` (`TimelineCacheCoordinator`). Background prefetch and user pull-to-paginate are unaffected.
