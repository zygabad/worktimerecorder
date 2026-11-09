package com.zygabad.worktimerecorder.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Builds the single Map that gets pushed to the "Pro Stat" Garmin watch face (→ a Monkey C
 * Dictionary; see the Mobile SDK's supported-data-types table: Int→Number, Boolean→Boolean,
 * String→String). Kept pure and Android-free on purpose, same reasoning as TimeCalculations.kt:
 * the wire contract is the one part of the Garmin feature that CAN be tested on the plain JVM,
 * and it is also the part the watch face cannot survive a mistake in — a renamed key or a
 * Float-where-Number-was-expected just makes the watch face show nothing, with no error path
 * back to the phone. GarminPayloadTest is therefore the contract document.
 *
 * Deliberately NO absolute timestamps on the wire: the watch anchors its countdown to its own
 * clock at the moment the message arrives, so a phone/watch clock skew (or a different epoch
 * interpretation) cannot corrupt the displayed finish time. Everything is either a duration or a
 * pre-rendered local clock string.
 */
private val wireClockFmt = DateTimeFormatter.ofPattern("HH:mm")

/** Protocol version on the wire — bump only together with the watch face's own parser. */
const val WATCH_PAYLOAD_VERSION = 1

/**
 * "When do I get to go home" as a 24h HH:mm local clock string: now + whatever is LEFT of the
 * target, not start + target. That difference is the whole point — breaks (and any second session
 * later in the day) push the real finish time later, and workedMinutes already excludes them.
 * Shared with MainScreen's finish-time pills so the phone screen and the watch face can never
 * disagree about the same number.
 */
fun formatFinishClock(
    workedMinutes: Int,
    targetMinutes: Int,
    nowMillis: Long,
    zone: ZoneId = ZoneId.systemDefault()
): String {
    val remainingMinutes = (targetMinutes.coerceAtLeast(0) - workedMinutes.coerceAtLeast(0)).coerceAtLeast(0)
    return Instant.ofEpochMilli(nowMillis + remainingMinutes * 60_000L).atZone(zone).format(wireClockFmt)
}

/**
 * The frozen wire contract — exactly these seven keys, no more:
 *  "v"    Int     protocol version, always WATCH_PAYLOAD_VERSION
 *  "run"  Boolean is the work timer running right now
 *  "fin"  String  "HH:MM" 24h local finish clock, "" when not running (a stale clock time on a
 *                 watch face is worse than no clock time at all)
 *  "rem"  Int     SECONDS left until the target, clamped at >= 0
 *  "over" Int     MINUTES of overtime already worked, 0 when not past target
 *  "wrk"  Int     minutes worked today — the same number the main screen shows
 *  "tgt"  Int     target minutes for today
 *
 * rem/over/wrk/tgt stay populated even when run == false: the watch face still wants to show how
 * much of the day is outstanding after a stop, and it has the "run" flag to decide whether to
 * tick the countdown down or freeze it.
 */
fun buildWatchPayload(
    isWorking: Boolean,
    workedMinutes: Int,
    targetMinutes: Int,
    nowMillis: Long,
    zone: ZoneId = ZoneId.systemDefault()
): Map<String, Any> {
    // Clamp the inputs once, up front: bad data (a corrupted session, an empty target field) must
    // never reach the watch as a negative Number, because the watch face would render it verbatim.
    val worked = workedMinutes.coerceAtLeast(0)
    val target = targetMinutes.coerceAtLeast(0)
    val remainingMinutes = (target - worked).coerceAtLeast(0)

    return mapOf(
        "v" to WATCH_PAYLOAD_VERSION,
        "run" to isWorking,
        "fin" to if (isWorking) formatFinishClock(worked, target, nowMillis, zone) else "",
        "rem" to remainingMinutes * 60,
        "over" to (worked - target).coerceAtLeast(0),
        "wrk" to worked,
        "tgt" to target
    )
}
