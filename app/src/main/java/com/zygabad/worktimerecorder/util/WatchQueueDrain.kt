package com.zygabad.worktimerecorder.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

/**
 * "Wyczysc kolejke zegarka" (v1.30): send a burst of ordinary, freshly built snapshots so the watch
 * advances through whatever backlog it holds, and the LAST message is the true current state.
 *
 * Field observation behind it (NOT documented Garmin behaviour — Garmin's docs say nothing about a
 * watch-side queue): on 2026-11-13 every press of "Wyslij do zegarka teraz" made the watch show an
 * OLDER message than the one just sent, ~a dozen behind; on 2026-11-16 the watch kept Sunday's
 * stopped-state message for ~20 minutes after work started, until the user had pressed the button
 * about a dozen times; and the watch's mailbox file grew from 5560 to 6712 bytes over a day.
 * ⚠️ Working hypothesis, not confirmed: messages pile up in a watch-side queue and the watch hands
 * ONE queued message to the face per NEW arrival, so a backlog of N leaves the face N pushes stale
 * until N more messages arrive. This file automates exactly what the user did by hand.
 *
 * Pure and Android-free: GarminBridge supplies the actual delivery as [runWatchQueueDrain]'s
 * sendOne, and everything about the loop (count, spacing, progress, stop-on-failure) is pinned by
 * WatchQueueDrainTest on virtual time.
 */

/** How many messages one drain sends. ~2.5x the backlog observed in the field (about a dozen). */
const val WATCH_QUEUE_DRAIN_COUNT = 30

/**
 * Gap between the END of one send and the start of the next. Each message is built fresh, so this
 * also guarantees every one gets its own "mid" (second resolution) and "ts".
 */
const val WATCH_QUEUE_DRAIN_SPACING_MS = 2_000L

/** Shown in the status line while a drain runs. ASCII Polish, like the rest of that line. */
fun watchQueueDrainProgressText(sent: Int, total: Int): String =
    "czyszczenie kolejki zegarka: $sent/$total"

/**
 * Whether one drain send got through: at least one device answered SUCCESS. Anything else —
 * not connected, timeout, SDK unavailable, a crash, no devices at all — stops the drain, so a dead
 * link is not hit [WATCH_QUEUE_DRAIN_COUNT] times in a row.
 */
fun isDrainSendDelivered(outcomes: List<WatchPushOutcome>): Boolean =
    outcomes.any { it is WatchPushOutcome.SendResult && it.status == WATCH_SEND_STATUS_SUCCESS }

/** [attempted] sends were made out of [total]; [stoppedEarly] when the last attempted one failed. */
data class WatchQueueDrainResult(val attempted: Int, val total: Int, val stoppedEarly: Boolean)

/** Reason label recorded in the push history when the drain ends. */
fun watchQueueDrainReasonLabel(result: WatchQueueDrainResult): String =
    if (result.stoppedEarly) {
        "${WatchPushReason.DRAIN.label} przerwane ${result.attempted}/${result.total}"
    } else {
        "${WatchPushReason.DRAIN.label} ${result.attempted}/${result.total}"
    }

/**
 * The drain loop: [sendOne] is called with 1..[count], with [spacingMs] between consecutive sends
 * (none before the first, none after the last). [onProgress] gets (0, count) up front and
 * (i, count) after each send. Stops at the first send that returns false or throws (cancellation
 * still propagates).
 */
suspend fun runWatchQueueDrain(
    count: Int = WATCH_QUEUE_DRAIN_COUNT,
    spacingMs: Long = WATCH_QUEUE_DRAIN_SPACING_MS,
    onProgress: (sent: Int, total: Int) -> Unit,
    sendOne: suspend (index: Int) -> Boolean
): WatchQueueDrainResult {
    onProgress(0, count)
    for (i in 1..count) {
        if (i > 1) delay(spacingMs)
        val delivered = try {
            sendOne(i)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            false
        }
        onProgress(i, count)
        if (!delivered) return WatchQueueDrainResult(attempted = i, total = count, stoppedEarly = true)
    }
    return WatchQueueDrainResult(attempted = count, total = count, stoppedEarly = false)
}

/** Lets only one drain run at a time; a second press while one runs is ignored. */
class WatchQueueDrainGate {
    private val running = AtomicBoolean(false)

    val isRunning: Boolean get() = running.get()

    /** True if this caller may start a drain; false if one is already running. */
    fun tryStart(): Boolean = running.compareAndSet(false, true)

    fun finish() = running.set(false)
}
