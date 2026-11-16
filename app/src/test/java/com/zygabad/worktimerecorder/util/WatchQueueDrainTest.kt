package com.zygabad.worktimerecorder.util

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Wyczysc kolejke zegarka": automates what the user did by hand on 2026-11-16 (pressing
 * "Wyslij do zegarka teraz" about a dozen times until the watch showed the current message).
 *
 * The loop itself ([runWatchQueueDrain]) is executed here for real, under kotlinx-coroutines-test
 * virtual time, with a fake send step. NOT covered: GarminBridge wiring the real Connect IQ
 * delivery into that send step (needs the SDK + a watch) and the Settings button (Compose).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WatchQueueDrainTest {

    // --- the plan ---

    @Test
    fun `plan is 30 messages two seconds apart`() {
        assertEquals(30, WATCH_QUEUE_DRAIN_COUNT)
        assertEquals(2_000L, WATCH_QUEUE_DRAIN_SPACING_MS)
    }

    // --- progress text ---

    @Test
    fun `progress text is ASCII Polish with sent over total`() {
        assertEquals("czyszczenie kolejki zegarka: 7/30", watchQueueDrainProgressText(7, 30))
        assertTrue(watchQueueDrainProgressText(0, 30).all { it.code in 0x20..0x7E })
    }

    // --- stop-on-failure rule ---

    @Test
    fun `a send counts as delivered only if some device answered SUCCESS`() {
        assertTrue(isDrainSendDelivered(listOf(WatchPushOutcome.SendResult("fenix", "SUCCESS", false))))
        assertTrue(
            isDrainSendDelivered(
                listOf(
                    WatchPushOutcome.AppNotInstalled("fenix", null),
                    WatchPushOutcome.SendResult("fenix", "SUCCESS", true)
                )
            )
        )
        assertTrue(
            "one connected watch is enough when an old one is also paired",
            isDrainSendDelivered(
                listOf(
                    WatchPushOutcome.DeviceNotConnected("stary", "NOT_PAIRED"),
                    WatchPushOutcome.SendResult("fenix", "SUCCESS", false)
                )
            )
        )
    }

    @Test
    fun `every failure shape stops the drain`() {
        val failures = listOf(
            emptyList(),
            listOf(WatchPushOutcome.PayloadBuildFailed),
            listOf(WatchPushOutcome.SdkInitFailed("GCM_NOT_INSTALLED")),
            listOf(WatchPushOutcome.SdkInitTimedOut),
            listOf(WatchPushOutcome.NoKnownDevices),
            listOf(WatchPushOutcome.DeviceNotConnected("fenix", "NOT_CONNECTED")),
            listOf(WatchPushOutcome.SendResult("fenix", "FAILURE_DURING_TRANSFER", false)),
            listOf(WatchPushOutcome.SendResult("fenix", null, false)),
            listOf(WatchPushOutcome.SendTimedOut("fenix", false)),
            listOf(WatchPushOutcome.DevicePushCrashed("fenix", "RuntimeException")),
            listOf(WatchPushOutcome.DeliveryCrashed("RuntimeException"))
        )
        failures.forEach { assertFalse("$it must stop the drain", isDrainSendDelivered(it)) }
    }

    // --- the loop, on virtual time ---

    @Test
    fun `happy path sends all 30, spaced 2s, with no delay before the first or after the last`() = runTest {
        val sendTimes = mutableListOf<Long>()
        val result = runWatchQueueDrain(onProgress = { _, _ -> }) { _ ->
            sendTimes += currentTime
            true
        }

        assertEquals(WatchQueueDrainResult(attempted = 30, total = 30, stoppedEarly = false), result)
        assertEquals(30, sendTimes.size)
        assertEquals(0L, sendTimes.first())
        assertEquals(29 * 2_000L, sendTimes.last())
        sendTimes.zipWithNext().forEach { (a, b) -> assertEquals(2_000L, b - a) }
        assertEquals("no trailing wait after the last send", 29 * 2_000L, currentTime)
    }

    @Test
    fun `send indexes run 1 to count in order`() = runTest {
        val indexes = mutableListOf<Int>()
        runWatchQueueDrain(count = 5, onProgress = { _, _ -> }) { i -> indexes += i; true }
        assertEquals(listOf(1, 2, 3, 4, 5), indexes)
    }

    @Test
    fun `progress is reported from 0 and after every send`() = runTest {
        val progress = mutableListOf<Pair<Int, Int>>()
        runWatchQueueDrain(count = 3, onProgress = { s, t -> progress += s to t }) { true }
        assertEquals(listOf(0 to 3, 1 to 3, 2 to 3, 3 to 3), progress)
    }

    @Test
    fun `stops at the first failed send instead of hammering a dead link`() = runTest {
        var calls = 0
        val result = runWatchQueueDrain(onProgress = { _, _ -> }) { i ->
            calls++
            i < 3 // sends 1 and 2 ok, send 3 fails
        }
        assertEquals(3, calls)
        assertEquals(WatchQueueDrainResult(attempted = 3, total = 30, stoppedEarly = true), result)
        assertEquals(2 * 2_000L, currentTime)
    }

    @Test
    fun `a first send that fails ends the drain immediately`() = runTest {
        var calls = 0
        val result = runWatchQueueDrain(onProgress = { _, _ -> }) { calls++; false }
        assertEquals(1, calls)
        assertTrue(result.stoppedEarly)
        assertEquals(0L, currentTime)
    }

    @Test
    fun `a send step that throws is treated as a failed send`() = runTest {
        var calls = 0
        val result = runWatchQueueDrain(onProgress = { _, _ -> }) { i ->
            calls++
            if (i == 2) throw IllegalStateException("boom")
            true
        }
        assertEquals(2, calls)
        assertEquals(WatchQueueDrainResult(attempted = 2, total = 30, stoppedEarly = true), result)
    }

    // --- reason label recorded at the end ---

    @Test
    fun `final reason label says how far the drain got`() {
        assertEquals("czyszczenie 30/30", watchQueueDrainReasonLabel(WatchQueueDrainResult(30, 30, false)))
        assertEquals("czyszczenie przerwane 3/30", watchQueueDrainReasonLabel(WatchQueueDrainResult(3, 30, true)))
    }

    // --- second press while running ---

    @Test
    fun `gate admits one drain at a time and reopens when it finishes`() {
        val gate = WatchQueueDrainGate()
        assertFalse(gate.isRunning)
        assertTrue(gate.tryStart())
        assertTrue(gate.isRunning)
        assertFalse("second press while running is ignored", gate.tryStart())
        gate.finish()
        assertFalse(gate.isRunning)
        assertTrue(gate.tryStart())
    }
}
