package net.vrkknn.andromuks

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [ProfileCache], the singleton behind the two-tier profile lookup described in
 * docs/USER_PROFILES.md.
 *
 * The invariant worth guarding here is that [ProfileCache.cleanupFlattenedProfiles] collects
 * *orphans only* and never evicts a live entry: [ProfileCache.hasFlattenedProfile] is the
 * authoritative "do we already have this user's room profile?" sentinel, so dropping a live entry
 * does not merely cost a cache miss — it makes the app re-issue a `get_specific_room_state` request
 * for that user. Bounding this cache by evicting live entries would trade a little RAM for profile
 * request storms.
 *
 * Real Matrix IDs are used throughout because both the flattened key (`"$roomId:$userId"`) and the
 * room/user split have to survive identifiers that themselves contain colons.
 */
class ProfileCacheTest {

    private val room = "!abc:example.org"
    private val otherRoom = "!xyz:example.org"
    private val alice = "@alice:example.org"
    private val bob = "@bob:example.org"

    private fun profile(name: String, avatar: String? = null) = MemberProfile(name, avatar)

    /** Registers a room profile the way the app does: flattened entry plus its room-index entry. */
    private fun putLive(roomId: String, userId: String, profile: MemberProfile) {
        ProfileCache.setFlattenedProfile(roomId, userId, profile)
        ProfileCache.addToRoomIndex(roomId, userId)
    }

    /** A flattened entry with no room-index entry — exactly what orphan collection should reap. */
    private fun putOrphan(roomId: String, userId: String, profile: MemberProfile) {
        ProfileCache.setFlattenedProfile(roomId, userId, profile)
    }

    @Before
    fun setUp() = ProfileCache.clear()

    @After
    fun tearDown() = ProfileCache.clear()

    // ---------------------------------------------------------------- global profiles

    @Test
    fun `global profiles round-trip`() {
        ProfileCache.setGlobalProfile(alice, ProfileCache.CachedProfileEntry(profile("Alice"), 1_000L))

        assertEquals("Alice", ProfileCache.getGlobalProfile(alice)?.profile?.displayName)
        assertEquals("Alice", ProfileCache.getGlobalProfileProfile(alice)?.displayName)
        assertNull(ProfileCache.getGlobalProfileProfile(bob))
        assertEquals(1, ProfileCache.getGlobalCacheSize())
    }

    @Test
    fun `updateGlobalProfileAccess refreshes lastAccess`() {
        ProfileCache.setGlobalProfile(alice, ProfileCache.CachedProfileEntry(profile("Alice"), 1L))

        ProfileCache.updateGlobalProfileAccess(alice)

        assertTrue((ProfileCache.getGlobalProfile(alice)?.lastAccess ?: 0L) > 1L)
    }

    @Test
    fun `updateGlobalProfileAccess on an unknown user is a no-op`() {
        ProfileCache.updateGlobalProfileAccess(bob)

        assertNull(ProfileCache.getGlobalProfile(bob))
    }

    @Test
    fun `cleanupGlobalProfiles evicts the least recently accessed first`() {
        // Unlike the flattened cache, the global cache is a plain LRU — it is only a lookup
        // accelerator, so evicting a live entry costs a re-fetch and nothing more.
        ProfileCache.setGlobalProfile("@old:example.org", ProfileCache.CachedProfileEntry(profile("Old"), 1L))
        ProfileCache.setGlobalProfile("@mid:example.org", ProfileCache.CachedProfileEntry(profile("Mid"), 5L))
        ProfileCache.setGlobalProfile("@new:example.org", ProfileCache.CachedProfileEntry(profile("New"), 9L))

        ProfileCache.cleanupGlobalProfiles(maxSize = 1)

        assertEquals(1, ProfileCache.getGlobalCacheSize())
        assertNotNull(ProfileCache.getGlobalProfile("@new:example.org"))
        assertNull(ProfileCache.getGlobalProfile("@old:example.org"))
    }

    @Test
    fun `cleanupGlobalProfiles under the cap changes nothing`() {
        ProfileCache.setGlobalProfile(alice, ProfileCache.CachedProfileEntry(profile("Alice"), 1L))

        ProfileCache.cleanupGlobalProfiles(maxSize = 10)

        assertEquals(1, ProfileCache.getGlobalCacheSize())
    }

    // ---------------------------------------------------------------- flattened profiles

    @Test
    fun `flattened profiles are keyed per room and per user`() {
        ProfileCache.setFlattenedProfile(room, alice, profile("Alice in abc"))
        ProfileCache.setFlattenedProfile(otherRoom, alice, profile("Alice in xyz"))

        assertEquals("Alice in abc", ProfileCache.getFlattenedProfile(room, alice)?.displayName)
        assertEquals("Alice in xyz", ProfileCache.getFlattenedProfile(otherRoom, alice)?.displayName)
        assertNull(ProfileCache.getFlattenedProfile(room, bob))
    }

    @Test
    fun `hasFlattenedProfile is the do-we-have-it sentinel`() {
        assertFalse(ProfileCache.hasFlattenedProfile(room, alice))

        ProfileCache.setFlattenedProfile(room, alice, profile("Alice"))
        assertTrue(ProfileCache.hasFlattenedProfile(room, alice))

        ProfileCache.removeFlattenedProfile(room, alice)
        assertFalse(ProfileCache.hasFlattenedProfile(room, alice))
    }

    @Test
    fun `room index tracks membership and clearRoom drops only that room`() {
        putLive(room, alice, profile("Alice"))
        putLive(room, bob, profile("Bob"))
        putLive(otherRoom, alice, profile("Alice elsewhere"))

        assertEquals(setOf(alice, bob), ProfileCache.getRoomUserIds(room))

        ProfileCache.clearRoom(room)

        assertNull(ProfileCache.getRoomUserIds(room))
        assertFalse(ProfileCache.hasFlattenedProfile(room, alice))
        assertFalse(ProfileCache.hasFlattenedProfile(room, bob))
        // The other room is untouched.
        assertTrue(ProfileCache.hasFlattenedProfile(otherRoom, alice))
    }

    @Test
    fun `removeFromRoomIndex leaves the flattened entry alone`() {
        putLive(room, alice, profile("Alice"))

        ProfileCache.removeFromRoomIndex(room, alice)

        // Index and cache are separate stores; this is what turns an entry into an orphan.
        assertFalse(ProfileCache.getRoomUserIds(room)?.contains(alice) == true)
        assertTrue(ProfileCache.hasFlattenedProfile(room, alice))
    }

    // ---------------------------------------------------------------- orphan collection

    @Test
    fun `cleanupFlattenedProfiles collects orphans and keeps every live entry`() {
        putLive(room, alice, profile("Alice"))
        putLive(room, bob, profile("Bob"))
        putOrphan(otherRoom, alice, profile("Stale"))
        putOrphan(otherRoom, bob, profile("Stale"))

        ProfileCache.cleanupFlattenedProfiles(maxSize = 2)

        assertTrue("live entry was evicted", ProfileCache.hasFlattenedProfile(room, alice))
        assertTrue("live entry was evicted", ProfileCache.hasFlattenedProfile(room, bob))
        assertFalse(ProfileCache.hasFlattenedProfile(otherRoom, alice))
        assertFalse(ProfileCache.hasFlattenedProfile(otherRoom, bob))
    }

    @Test
    fun `orphan collection is not a hard size cap`() {
        // The size check is a *trigger* for orphan collection, not a ceiling. With nothing orphaned
        // the cache legitimately stays over maxSize — its natural size is (rooms × members).
        putLive(room, alice, profile("Alice"))
        putLive(room, bob, profile("Bob"))
        putLive(otherRoom, alice, profile("Alice"))
        putLive(otherRoom, bob, profile("Bob"))

        ProfileCache.cleanupFlattenedProfiles(maxSize = 1)

        assertEquals(4, ProfileCache.getFlattenedCacheSize())
    }

    @Test
    fun `cleanupFlattenedProfiles under the cap does not scan`() {
        putOrphan(room, alice, profile("Stale"))

        ProfileCache.cleanupFlattenedProfiles(maxSize = 10)

        // Orphans below the trigger size are left alone — collecting them is not urgent.
        assertTrue(ProfileCache.hasFlattenedProfile(room, alice))
    }

    @Test
    fun `repeat scans are rate-limited until the cache grows again`() {
        // storeMemberProfile calls this on every m.room.member event. Rescanning each time rebuilt
        // the whole validKeys set under the lock, which was quadratic work on cold-start sync.
        // After a scan, the next one waits for meaningful growth (+500 entries).
        putLive(room, alice, profile("Alice"))
        putLive(room, bob, profile("Bob"))
        putLive(otherRoom, alice, profile("Alice"))

        // First scan: nothing to collect, so the cache stays over the cap and the rate limiter arms.
        ProfileCache.cleanupFlattenedProfiles(maxSize = 2)
        assertEquals(3, ProfileCache.getFlattenedCacheSize())

        putOrphan(otherRoom, bob, profile("Stale"))
        ProfileCache.cleanupFlattenedProfiles(maxSize = 2)

        assertTrue(
            "the rate limiter should have suppressed this rescan",
            ProfileCache.hasFlattenedProfile(otherRoom, bob),
        )
    }

    @Test
    fun `clear resets the rate limiter as well as the caches`() {
        putLive(room, alice, profile("Alice"))
        putLive(room, bob, profile("Bob"))
        putLive(otherRoom, alice, profile("Alice"))
        ProfileCache.cleanupFlattenedProfiles(maxSize = 2) // arms the rate limiter

        ProfileCache.clear()

        assertEquals(0, ProfileCache.getFlattenedCacheSize())
        assertEquals(0, ProfileCache.getGlobalCacheSize())

        // A scan straight after a clear must not be suppressed by the previous run's threshold.
        putLive(room, alice, profile("Alice"))
        putLive(room, bob, profile("Bob"))
        putOrphan(otherRoom, alice, profile("Stale"))
        ProfileCache.cleanupFlattenedProfiles(maxSize = 2)

        assertFalse(ProfileCache.hasFlattenedProfile(otherRoom, alice))
    }

    // ---------------------------------------------------------------- global/room reconciliation

    @Test
    fun `cleanupMatchingRoomProfiles drops room profiles that now equal the global one`() {
        // A room profile only earns its place when it *differs* from the global profile; once the
        // user's global profile catches up, the room override is redundant.
        val global = profile("Alice", "mxc://server/avatar")
        putLive(room, alice, profile("Alice", "mxc://server/avatar"))
        putLive(otherRoom, alice, profile("Alice the Admin", "mxc://server/avatar"))
        putLive(room, bob, profile("Alice", "mxc://server/avatar"))

        ProfileCache.cleanupMatchingRoomProfiles(alice, global)

        assertFalse("matching override should be dropped", ProfileCache.hasFlattenedProfile(room, alice))
        assertTrue("differing override must survive", ProfileCache.hasFlattenedProfile(otherRoom, alice))
        assertTrue("another user's entry is not touched", ProfileCache.hasFlattenedProfile(room, bob))
    }

    @Test
    fun `cleanupMatchingRoomProfiles compares avatar as well as display name`() {
        putLive(room, alice, profile("Alice", "mxc://server/old"))

        ProfileCache.cleanupMatchingRoomProfiles(alice, profile("Alice", "mxc://server/new"))

        assertTrue(ProfileCache.hasFlattenedProfile(room, alice))
    }

    @Test
    fun `cleanupMatchingRoomProfiles leaves a stale room-index entry — known wart`() {
        // It derives the room id with key.substringBefore(":"), but the flattened key is
        // "$roomId:$userId" and a Matrix room id always contains a colon, so for "!abc:example.org"
        // it computes "!abc" and the index removal silently misses.
        //
        // Currently benign: an over-inclusive index only makes orphan collection *less* aggressive,
        // which is the safe direction, and the flattened entry itself is removed correctly. Pinned
        // so the leak is visible rather than folklore. Note cleanupFlattenedProfiles builds its key
        // set by joining roomId and userId directly, precisely to avoid this splitting problem.
        putLive(room, alice, profile("Alice"))

        ProfileCache.cleanupMatchingRoomProfiles(alice, profile("Alice"))

        assertFalse("the flattened entry is removed correctly", ProfileCache.hasFlattenedProfile(room, alice))
        assertTrue(
            "index entry leaks because the room id is mis-parsed",
            ProfileCache.getRoomUserIds(room)?.contains(alice) == true,
        )
    }

    // ---------------------------------------------------------------- snapshots

    @Test
    fun `getAll returns snapshots, not live views`() {
        putLive(room, alice, profile("Alice"))
        ProfileCache.setGlobalProfile(alice, ProfileCache.CachedProfileEntry(profile("Alice"), 1L))

        val flattened = ProfileCache.getAllFlattenedProfiles()
        val globals = ProfileCache.getAllGlobalProfiles()
        putLive(otherRoom, bob, profile("Bob"))
        ProfileCache.setGlobalProfile(bob, ProfileCache.CachedProfileEntry(profile("Bob"), 2L))

        assertEquals(1, flattened.size)
        assertEquals(1, globals.size)
        assertEquals(2, ProfileCache.getFlattenedCacheSize())
        assertEquals(2, ProfileCache.getGlobalCacheSize())
    }
}
