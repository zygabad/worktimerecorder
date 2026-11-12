package com.zygabad.worktimerecorder.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Wire-contract tests for the Garmin watch-face payload. The watch side (ProStat watch face) is
 * built against exactly these keys/types, so these tests are the contract: a rename or a type
 * change here silently breaks the watch face, which has no way to report the failure back.
 *
 * Pure JVM, no Android — buildWatchPayload takes nowMillis + ZoneId as parameters precisely so it
 * can be pinned to a fixed instant in a fixed zone here (same approach as TimeCalculationsTest).
 */
class GarminPayloadTest {

    private val zone = ZoneId.of("Europe/Warsaw")

    /** Readable way to pin "now" — the payload must never depend on the test machine's clock. */
    private fun at(hour: Int, minute: Int, day: Int = 9): Long =
        LocalDateTime.of(2026, 11, day, hour, minute)
            .atZone(zone).toInstant().toEpochMilli()

    // --- Running, time still left on the target ---

    @Test
    fun `running with time left reports finish clock, remaining seconds and no overtime`() {
        val p = buildWatchPayload(
            isWorking = true,
            workedMinutes = 300,          // 5h done
            targetMinutes = 510,          // 8h30 target -> 3h30 left
            nowMillis = at(13, 0),
            zone = zone
        )
        assertEquals(2, p["v"])
        assertEquals(true, p["run"])
        assertEquals("16:30", p["fin"])   // 13:00 + 3h30
        assertEquals(210 * 60, p["rem"])
        assertEquals(0, p["over"])
        assertEquals(300, p["wrk"])
        assertEquals(510, p["tgt"])
    }

    @Test
    fun `payload carries exactly the eight contract keys and nothing else`() {
        val p = buildWatchPayload(
            isWorking = true, workedMinutes = 120, targetMinutes = 480,
            nowMillis = at(10, 0), zone = zone
        )
        assertEquals(setOf("v", "run", "fin", "rem", "over", "wrk", "tgt", "ts"), p.keys)
    }

    // --- "ts": the build time, added in v2 after a 9.5h queued delivery ---

    /**
     * The field bug "ts" exists for: Garmin Connect queued one message for ~9.5 hours. A snapshot
     * built at 09:44 (worked 50, rem 430) arrived at 19:15 and the watch, which anchors the
     * countdown to its OWN clock on arrival, rendered it as fresh — "7h04m left" at 19:21. The
     * watch cannot detect that without knowing when the phone built the snapshot, so v2 puts the
     * build time on the wire. Seconds, not millis — see the Int-range test below.
     */
    @Test
    fun `ts carries the phone's build time in epoch seconds`() {
        val now = at(13, 0)
        val p = buildWatchPayload(
            isWorking = true, workedMinutes = 300, targetMinutes = 510,
            nowMillis = now, zone = zone
        )
        assertEquals((now / 1000).toInt(), p["ts"])
    }

    @Test
    fun `ts is present and still the build time when the timer is stopped`() {
        val now = at(17, 30)
        val p = buildWatchPayload(
            isWorking = false, workedMinutes = 480, targetMinutes = 480,
            nowMillis = now, zone = zone
        )
        assertEquals((now / 1000).toInt(), p["ts"])
    }

    /**
     * The 32-bit trap, made explicit: the Mobile SDK maps Int -> Monkey C Number, which is 32-bit
     * signed (max 2_147_483_647). Epoch MILLIS for 2026 is ~1.76e12 and cannot be represented;
     * epoch SECONDS is ~1.76e9 and fits until 2038. This is the same class of mistake
     * GarminPayload.kt's header warns about — the watch face would just render nothing.
     */
    @Test
    fun `ts for a realistic 2026 instant stays inside the 32-bit Number range`() {
        val now = at(19, 15)
        val p = buildWatchPayload(
            isWorking = true, workedMinutes = 50, targetMinutes = 480,
            nowMillis = now, zone = zone
        )
        val ts = p["ts"] as Int
        assertTrue("ts must be positive, was $ts", ts > 0)
        assertTrue("ts must fit in a 32-bit signed Number, was $ts", ts <= Int.MAX_VALUE)
        // And prove the millis form would NOT have fit, which is the whole reason for seconds.
        assertTrue("epoch millis was expected to overflow Int", now > Int.MAX_VALUE.toLong())
    }

    /**
     * Types matter as much as values: the Mobile SDK maps Int->Number, Boolean->Boolean,
     * String->String, and the watch face reads them with those types. A Long "rem" would arrive
     * as a Number too, but a Double/Float would not — pin the classes so a future refactor can't
     * quietly change them.
     */
    @Test
    fun `payload value types match the Monkey C dictionary the watch face expects`() {
        val p = buildWatchPayload(
            isWorking = true, workedMinutes = 100, targetMinutes = 480,
            nowMillis = at(10, 0), zone = zone
        )
        assertEquals(Integer::class.java, p["v"]!!.javaClass)
        assertEquals(java.lang.Boolean::class.java, p["run"]!!.javaClass)
        assertEquals(java.lang.String::class.java, p["fin"]!!.javaClass)
        assertEquals(Integer::class.java, p["rem"]!!.javaClass)
        assertEquals(Integer::class.java, p["over"]!!.javaClass)
        assertEquals(Integer::class.java, p["wrk"]!!.javaClass)
        assertEquals(Integer::class.java, p["tgt"]!!.javaClass)
        // Must be Integer, never Long: epoch millis in a Long would overflow Monkey C's 32-bit
        // Number, so the type is as load-bearing here as the unit.
        assertEquals(Integer::class.java, p["ts"]!!.javaClass)
    }

    // --- The exact boundary and past it ---

    @Test
    fun `running exactly at target reports zero remaining, zero overtime and finish clock equal to now`() {
        val p = buildWatchPayload(
            isWorking = true, workedMinutes = 510, targetMinutes = 510,
            nowMillis = at(16, 30), zone = zone
        )
        assertEquals(true, p["run"])
        assertEquals("16:30", p["fin"])
        assertEquals(0, p["rem"])
        assertEquals(0, p["over"])
    }

    @Test
    fun `running past target clamps remaining at zero and reports overtime minutes`() {
        val p = buildWatchPayload(
            isWorking = true, workedMinutes = 555, targetMinutes = 510,
            nowMillis = at(17, 15), zone = zone
        )
        assertEquals(0, p["rem"])         // never negative
        assertEquals(45, p["over"])       // minutes, not seconds
        assertEquals("17:15", p["fin"])   // target already met -> finish clock is "now"
        assertEquals(555, p["wrk"])
    }

    // --- Not running ---

    @Test
    fun `not running reports run false and an empty finish clock`() {
        val p = buildWatchPayload(
            isWorking = false, workedMinutes = 300, targetMinutes = 510,
            nowMillis = at(13, 0), zone = zone
        )
        assertEquals(false, p["run"])
        assertEquals("", p["fin"])        // a stale clock time would be worse than none
        assertEquals(210 * 60, p["rem"])  // still informative: how much of the day is left to do
        assertEquals(0, p["over"])
        assertEquals(300, p["wrk"])
        assertEquals(510, p["tgt"])
    }

    @Test
    fun `not running past target still reports overtime and no finish clock`() {
        val p = buildWatchPayload(
            isWorking = false, workedMinutes = 540, targetMinutes = 510,
            nowMillis = at(17, 0), zone = zone
        )
        assertEquals(false, p["run"])
        assertEquals("", p["fin"])
        assertEquals(0, p["rem"])
        assertEquals(30, p["over"])
    }

    // --- Breaks: the whole reason the phone sends worked-minutes and not a start timestamp ---

    /**
     * The bug this guards: a watch face told only "started at 08:00, target 8h" would show 16:00
     * forever. After an hour of break, the real finish time is 17:00. buildWatchPayload must
     * anchor on now + what is LEFT of the target given minutes actually worked, which is what the
     * main screen's finishClock() does (MainScreen.kt).
     */
    @Test
    fun `a day with a break pushes the finish clock later than start plus target`() {
        val p = buildWatchPayload(
            isWorking = true,
            workedMinutes = 240,          // 4h worked although 5h of wall time passed since 08:00
            targetMinutes = 480,
            nowMillis = at(13, 0),
            zone = zone
        )
        assertEquals("17:00", p["fin"])   // NOT 16:00 (= start 08:00 + 8h)
        assertEquals(240 * 60, p["rem"])
        assertEquals(240, p["wrk"])
    }

    // --- Formatting edge cases of the "HH:MM" string ---

    @Test
    fun `finish clock zero-pads both hour and minute`() {
        val p = buildWatchPayload(
            isWorking = true, workedMinutes = 0, targetMinutes = 65,
            nowMillis = at(8, 0), zone = zone
        )
        assertEquals("09:05", p["fin"])   // not "9:5"
    }

    @Test
    fun `finish clock crossing midnight wraps to the next day's local time`() {
        val p = buildWatchPayload(
            isWorking = true,
            workedMinutes = 420,
            targetMinutes = 510,          // 1h30 left
            nowMillis = at(23, 30),
            zone = zone
        )
        assertEquals("01:00", p["fin"])   // 24h clock, no date component on the wire
        assertEquals(90 * 60, p["rem"])
    }

    @Test
    fun `finish clock is rendered in the supplied zone, not UTC`() {
        // 12:00 Warsaw in November is 11:00 UTC — a UTC-rendered payload would say "13:00".
        val p = buildWatchPayload(
            isWorking = true, workedMinutes = 0, targetMinutes = 120,
            nowMillis = at(12, 0), zone = zone
        )
        assertEquals("14:00", p["fin"])
    }

    @Test
    fun `finish clock at exactly midnight renders as 00 00`() {
        val p = buildWatchPayload(
            isWorking = true, workedMinutes = 0, targetMinutes = 60,
            nowMillis = at(23, 0), zone = zone
        )
        assertEquals("00:00", p["fin"])
    }

    // --- Defensive: nonsense input must not produce nonsense on the wire ---

    @Test
    fun `negative worked or target minutes cannot produce negative wire values`() {
        val p = buildWatchPayload(
            isWorking = true, workedMinutes = -5, targetMinutes = -10,
            nowMillis = at(9, 0), zone = zone
        )
        assertEquals(0, p["rem"])
        assertEquals(0, p["over"])
        assertEquals(0, p["wrk"])
        assertEquals(0, p["tgt"])
        assertEquals("09:00", p["fin"])
    }
}
