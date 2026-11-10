package com.zygabad.worktimerecorder.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the phone is allowed to ask Garmin Connect Mobile again after it once answered
 * "I am not available".
 *
 * Why this is its own pure function: GarminBridge used to latch that verdict for the whole life of
 * the process. The first push of the process runs from WorkTimerApplication.onCreate — phone cold
 * start — which is precisely the moment Garmin Connect Mobile is most likely to be mid-update,
 * force-stopped or battery-restricted, and every one of those surfaces as GCM_NOT_INSTALLED. The
 * foreground service then keeps the process alive for the rest of the workday, so ONE bad answer
 * at 06:50 made every later push a silent no-op until the phone was rebooted. That is an exact
 * match for the reported symptom: timer started at work, watch face blank all day.
 *
 * The decision itself is trivial arithmetic, but it is the difference between a feature that
 * recovers by itself and one that needs a reboot, so it gets pinned here rather than living as an
 * untested inline comparison inside the Android-only bridge.
 */
class WatchPushRetryTest {

    private val t0 = 1_700_000_000_000L

    @Test
    fun `nothing latched means the SDK is always asked`() {
        assertTrue(shouldRetrySdkInit(latchedAtMillis = null, nowMillis = t0, manual = false))
    }

    @Test
    fun `a fresh latch is respected so a cold start does not hammer the SDK`() {
        assertFalse(shouldRetrySdkInit(latchedAtMillis = t0, nowMillis = t0, manual = false))
        assertFalse(
            shouldRetrySdkInit(
                latchedAtMillis = t0,
                nowMillis = t0 + SDK_UNAVAILABLE_RETRY_MS - 1,
                manual = false
            )
        )
    }

    @Test
    fun `the latch expires, so the bridge recovers without a reboot`() {
        assertTrue(
            shouldRetrySdkInit(
                latchedAtMillis = t0,
                nowMillis = t0 + SDK_UNAVAILABLE_RETRY_MS,
                manual = false
            )
        )
        assertTrue(
            shouldRetrySdkInit(
                latchedAtMillis = t0,
                nowMillis = t0 + (6 * SDK_UNAVAILABLE_RETRY_MS),
                manual = false
            )
        )
    }

    @Test
    fun `an explicit request from the user always retries`() {
        assertTrue(shouldRetrySdkInit(latchedAtMillis = t0, nowMillis = t0, manual = true))
    }

    /**
     * Clock-change safety. SystemClock is not used here (the latch has to survive across suspend),
     * and System.currentTimeMillis() can jump backwards on an NTP correction or a manual change.
     * A negative age must not read as "not old enough yet" forever — it means the stamp is
     * nonsense, so the only safe answer is to ask the SDK again.
     */
    @Test
    fun `a latch timestamp in the future is treated as unusable, not as fresh`() {
        assertTrue(
            shouldRetrySdkInit(
                latchedAtMillis = t0 + (60 * 60 * 1000L),
                nowMillis = t0,
                manual = false
            )
        )
    }

    /**
     * The window is a product decision, not an implementation detail: long enough that a phone
     * without any Garmin account is not paying an init timeout on every toggle, short enough that
     * "I started work, nothing showed up" fixes itself before the morning is over.
     */
    @Test
    fun `the retry window is 30 minutes`() {
        assertEquals(30 * 60 * 1000L, SDK_UNAVAILABLE_RETRY_MS)
    }
}
