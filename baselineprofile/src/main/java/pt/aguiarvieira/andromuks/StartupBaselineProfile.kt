package pt.aguiarvieira.andromuks

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.UiDevice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records the baseline profile shipped with release builds.
 *
 * ## Why this journey and not just startup
 *
 * The stock template this replaced did `pressHome(); startActivityAndWait()` and nothing else,
 * with the navigation lines left commented out — so even once generated it would only have covered
 * `AuthCheck` → `RoomListScreen`. The app's heaviest code is the timeline path
 * (`RoomTimelineScreen`, `TimelineEventItem`, `HtmlParser` and the AnnotatedString builders, plus
 * Coil's decode path), and none of it would have been AOT-compiled. First-scroll JIT warmup is
 * precisely the jank a profile exists to remove, so the journey below opens a room and scrolls it.
 *
 * ## Why coordinates instead of selectors
 *
 * The app sets no `testTag`s and does not enable `testTagsAsResourceId`, so UiAutomator cannot
 * address Compose nodes by resource id; text selectors would depend on whichever account is logged
 * in. Profile collection asserts nothing — it only needs the code paths to *execute* — so
 * fraction-of-screen gestures are both sufficient and far more portable across devices and
 * accounts than any selector.
 *
 * Every interaction is best-effort: a gesture landing on empty space costs coverage, not a run.
 *
 * ## Requirements on the device
 *
 * - Logged in, with at least one room carrying a decent amount of history. An empty account
 *   produces a profile that misses the entire point of the journey.
 * - **Biometric / app lock disabled** — `BiometricLockGate` will block the automation indefinitely.
 * - Screen on and unlocked.
 */
@RunWith(AndroidJUnit4::class)
class StartupBaselineProfile {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generate() = baselineProfileRule.collect(
        packageName = PACKAGE_NAME,
    ) {
        pressHome()
        startActivityAndWait()

        // Cold start does more than compose: AuthCheck dials the WebSocket, the cache-first paint
        // runs, then the initial sync populates the room list. Give that time to finish, or the
        // gestures below fire against an empty list and cover nothing.
        device.waitForIdle()
        Thread.sleep(ROOM_LIST_SETTLE_MS)

        scrollRoomList()
        openFirstRoomAndScroll()
    }

    /** Exercise `RoomListScreen`'s Lazy layout, item composition and the avatar load path. */
    private fun MacrobenchmarkScope.scrollRoomList() {
        repeat(ROOM_LIST_SWIPES) {
            device.swipeVertical(from = 0.75f, to = 0.25f)
            device.waitForIdle()
        }
        // Return to the top so the tap below lands on a room rather than wherever we stopped.
        repeat(ROOM_LIST_SWIPES) {
            device.swipeVertical(from = 0.25f, to = 0.75f)
            device.waitForIdle()
        }
        Thread.sleep(SETTLE_MS)
    }

    /**
     * Open a room and scroll its timeline both ways.
     *
     * Scrolling toward older messages matters most: it renders content that was not already
     * composed, forcing HTML parsing and AnnotatedString building, and triggers pagination.
     * Coming back down exercises the reverse path and Coil's memory-cache hits.
     */
    private fun MacrobenchmarkScope.openFirstRoomAndScroll() {
        // Tap below the header, where the first room rows sit on any screen size.
        device.click(
            device.displayWidth / 2,
            (device.displayHeight * FIRST_ROOM_ROW_FRACTION).toInt(),
        )
        device.waitForIdle()
        Thread.sleep(ROOM_OPEN_SETTLE_MS)

        repeat(TIMELINE_SWIPES) {
            device.swipeVertical(from = 0.3f, to = 0.8f) // toward older messages
            device.waitForIdle()
        }
        Thread.sleep(SETTLE_MS)
        repeat(TIMELINE_SWIPES) {
            device.swipeVertical(from = 0.8f, to = 0.3f) // back toward newer
            device.waitForIdle()
        }

        device.pressBack()
        device.waitForIdle()
    }

    private companion object {
        const val PACKAGE_NAME = "pt.aguiarvieira.andromuks"

        // Generous: covers WebSocket dial + initial sync on a large account. Too short and the
        // gestures fire against an empty list, silently yielding a startup-only profile.
        const val ROOM_LIST_SETTLE_MS = 8_000L
        const val ROOM_OPEN_SETTLE_MS = 4_000L
        const val SETTLE_MS = 1_000L

        const val ROOM_LIST_SWIPES = 3
        const val TIMELINE_SWIPES = 4

        // Far enough down to clear the top app bar on any screen size.
        const val FIRST_ROOM_ROW_FRACTION = 0.3f
    }
}

/**
 * Swipe along the vertical centre between two fractions of screen height.
 *
 * Fractions rather than pixels so the same gesture works on a phone, a tablet or an emulator.
 * [steps] is deliberately low: a fast fling covers more items per gesture, which is both what we
 * want compiled and how the list is actually used.
 */
private fun UiDevice.swipeVertical(from: Float, to: Float, steps: Int = 10) {
    val x = displayWidth / 2
    swipe(x, (displayHeight * from).toInt(), x, (displayHeight * to).toInt(), steps)
}
