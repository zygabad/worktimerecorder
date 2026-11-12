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
 * Timestamps on the wire — why this changed in v2. Until v1 there were deliberately NONE: the
 * watch anchors its countdown to its own clock at the moment the message arrives, so a phone/watch
 * clock skew (or a different epoch interpretation) could not corrupt the displayed finish time.
 * That held until Garmin Connect QUEUED a message for ~9.5 hours: a snapshot built at 09:44
 * (worked 50 min, 430 min left) was delivered at 19:15, and anchoring to the arrival moment shifted
 * the entire countdown by the delay — the watch showed "7h04m left" at 19:21. Curing seconds of
 * skew had cost hours of error. So v2 adds "ts", the phone's build time, for ONE purpose:
 * staleness detection. It is NOT the basis of the countdown arithmetic — when delivery was prompt
 * the watch still anchors to its own clock, exactly as before, and only uses ts to notice that a
 * snapshot is too old to render as fresh. Everything else is still a duration or a pre-rendered
 * local clock string.
 */
private val wireClockFmt = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Protocol version on the wire — bump only together with the watch face's own parser.
 * v2 added "ts" (see the file header: the 9.5h queued-delivery bug).
 */
const val WATCH_PAYLOAD_VERSION = 2

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
 * The frozen wire contract — exactly these eight keys, no more:
 *  "v"    Int     protocol version, always WATCH_PAYLOAD_VERSION
 *  "run"  Boolean is the work timer running right now
 *  "fin"  String  "HH:MM" 24h local finish clock, "" when not running (a stale clock time on a
 *                 watch face is worse than no clock time at all)
 *  "rem"  Int     SECONDS left until the target, clamped at >= 0
 *  "over" Int     MINUTES of overtime already worked, 0 when not past target
 *  "wrk"  Int     minutes worked today — the same number the main screen shows
 *  "tgt"  Int     target minutes for today
 *  "ts"   Int     epoch SECONDS when the phone built this snapshot (v2+). For staleness detection
 *                 only — see the file header. Seconds and not millis because of the 32-bit trap
 *                 below.
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

    // Epoch SECONDS as an Int, deliberately, and this is not a style choice: the Mobile SDK maps
    // Int -> Monkey C Number, which is 32-bit SIGNED (max 2_147_483_647). Epoch millis for 2026 is
    // ~1.76e12 — far past that — so putting nowMillis on the wire would overflow and the watch face
    // would render nothing, with no error path back to the phone. That is exactly the failure mode
    // this file's header warns about. Epoch seconds is ~1.76e9 and fits until 2038-01-19.
    val builtAtEpochSeconds = (nowMillis / 1000).toInt()

    return mapOf(
        "v" to WATCH_PAYLOAD_VERSION,
        "run" to isWorking,
        "fin" to if (isWorking) formatFinishClock(worked, target, nowMillis, zone) else "",
        "rem" to remainingMinutes * 60,
        "over" to (worked - target).coerceAtLeast(0),
        "wrk" to worked,
        "tgt" to target,
        "ts" to builtAtEpochSeconds
    )
}
