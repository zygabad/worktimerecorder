package com.zygabad.worktimerecorder.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The phone-side push history ("Historia wysylek" in Ustawienia -> Zegarek): the last 20 push
 * outcomes, each with the time, WHY the phone pushed, what happened and which message id went out.
 *
 * Why it exists: on 2026-11-13/16 the watch showed messages that were a dozen pushes stale, and the
 * one-line "last status" could not say how many pushes had happened, when, or why (the user also
 * asked why anything was sent at night when nothing changed). This history answers that from the
 * phone alone. Everything that decides what the history CONTAINS lives in these pure functions;
 * GarminBridge only calls them, and the Settings composable only prints formatted lines.
 *
 * NOT covered here: that GarminBridge passes the right reason/payload for each SDK exit path (needs
 * the Connect IQ service and a watch) and that SettingsScreen renders the lines (Compose, no
 * Robolectric/instrumentation in this module).
 */
class WatchPushHistoryTest {

    private val zone = ZoneId.of("Europe/Warsaw")

    private fun at(h: Int, m: Int, s: Int): Long =
        LocalDateTime.of(2026, 11, 16, h, m, s).atZone(zone).toInstant().toEpochMilli()

    private fun entry(n: Int) = WatchPushHistoryEntry(
        time = "08:%02d:00".format(n % 60),
        reason = "start",
        status = "status $n",
        messageId = "2611160800%02d".format(n % 100)
    )

    // --- reasons ---

    @Test
    fun `every reason label is plain ASCII, short and unique`() {
        val labels = WatchPushReason.values().map { it.label }
        assertEquals(labels.size, labels.toSet().size)
        labels.forEach { label ->
            assertTrue("'$label' must be ASCII (no Polish diacritics)", label.all { it.code in 0x20..0x7E })
            assertTrue("'$label' must not be blank", label.isNotBlank())
        }
    }

    @Test
    fun `reason labels match the wording agreed for the status line`() {
        assertEquals("start", WatchPushReason.START.label)
        assertEquals("stop", WatchPushReason.STOP.label)
        assertEquals("edycja", WatchPushReason.EDIT.label)
        assertEquals("cel", WatchPushReason.TARGET.label)
        assertEquals("reczne", WatchPushReason.MANUAL.label)
        assertEquals("auto-stop", WatchPushReason.AUTO_STOP.label)
        assertEquals("czyszczenie", WatchPushReason.DRAIN.label)
    }

    // --- appending to the capped list ---

    @Test
    fun `append puts the new entry first`() {
        val history = appendWatchPushHistory(listOf(entry(1)), entry(2))
        assertEquals(listOf(entry(2), entry(1)), history)
    }

    @Test
    fun `append to an empty history gives a one-element list`() {
        assertEquals(listOf(entry(1)), appendWatchPushHistory(emptyList(), entry(1)))
    }

    @Test
    fun `history never grows past 20 and drops the oldest`() {
        var history = emptyList<WatchPushHistoryEntry>()
        for (n in 1..45) history = appendWatchPushHistory(history, entry(n))

        assertEquals(WATCH_PUSH_HISTORY_MAX, history.size)
        assertEquals(20, WATCH_PUSH_HISTORY_MAX)
        assertEquals(entry(45), history.first())
        assertEquals(entry(26), history.last())
    }

    @Test
    fun `identical repeated outcomes are kept as separate events but still capped`() {
        // Two identical lines are two real push attempts (e.g. the same NOT_CONNECTED twice);
        // collapsing them would hide how often the phone pushed - the very question asked.
        var history = emptyList<WatchPushHistoryEntry>()
        repeat(50) { history = appendWatchPushHistory(history, entry(7)) }
        assertEquals(WATCH_PUSH_HISTORY_MAX, history.size)
    }

    @Test
    fun `an oversized history read back from storage is trimmed on the next append`() {
        val oversized = (1..30).map { entry(it) }
        val history = appendWatchPushHistory(oversized, entry(99))
        assertEquals(WATCH_PUSH_HISTORY_MAX, history.size)
        assertEquals(entry(99), history.first())
    }

    // --- formatting one line ---

    @Test
    fun `line shows time, reason, message id and status`() {
        val line = formatWatchPushHistoryLine(
            WatchPushHistoryEntry("08:07:31", "start", "wyslano do fenix 7 (SUCCESS)", "261116080731")
        )
        assertEquals("08:07:31 start #261116080731 — wyslano do fenix 7 (SUCCESS)", line)
    }

    @Test
    fun `line without a message id has no hash placeholder`() {
        val line = formatWatchPushHistoryLine(
            WatchPushHistoryEntry("08:07:31", "reczne", "nie udalo sie zbudowac danych", null)
        )
        assertEquals("08:07:31 reczne — nie udalo sie zbudowac danych", line)
    }

    // --- building an entry from an outcome ---

    @Test
    fun `entry carries seconds-precision time, reason label and the payload's mid`() {
        val payload = buildWatchPayload(true, 14, 480, at(8, 7, 31), zone)
        val e = buildWatchPushHistoryEntry(
            WatchPushOutcome.SendResult("fenix 7", "SUCCESS", false, summarizeWatchPayload(payload)),
            reason = WatchPushReason.START.label,
            payload = payload,
            nowMillis = at(8, 7, 33),
            zone = zone
        )
        assertEquals("08:07:33", e.time)
        assertEquals("start", e.reason)
        assertEquals("261116080731", e.messageId)
    }

    @Test
    fun `entry status does not repeat the message id the line already shows`() {
        val payload = buildWatchPayload(true, 14, 480, at(8, 7, 31), zone)
        val e = buildWatchPushHistoryEntry(
            WatchPushOutcome.SendResult("fenix 7", "SUCCESS", false, summarizeWatchPayload(payload)),
            reason = "start",
            payload = payload,
            nowMillis = at(8, 7, 33),
            zone = zone
        )
        assertEquals("wyslano do fenix 7 (SUCCESS) | praca: tak, koniec 15:53 | 14/480 min", e.status)
    }

    @Test
    fun `entry status for a failure is the same text as the status line minus its clock`() {
        val outcome = WatchPushOutcome.DeviceNotConnected("fenix 7", "NOT_CONNECTED")
        val e = buildWatchPushHistoryEntry(outcome, "stop", null, at(22, 1, 0), zone)
        assertEquals("fenix 7: NOT_CONNECTED", e.status)
        assertNull(e.messageId)
        assertEquals("22:01 — " + e.status, formatWatchPushStatus(outcome, at(22, 1, 0), zone))
    }

    @Test
    fun `entry mid is taken from the payload even when the send failed`() {
        val payload = buildWatchPayload(false, 0, 480, at(22, 1, 0), zone)
        val e = buildWatchPushHistoryEntry(
            WatchPushOutcome.SendTimedOut("fenix 7", attemptedAnyway = false), "stop", payload, at(22, 1, 10), zone
        )
        assertEquals("261116220100", e.messageId)
    }

    @Test
    fun `describeWatchPushOutcome is exactly the status line without its time prefix`() {
        val outcomes = listOf(
            WatchPushOutcome.PayloadBuildFailed,
            WatchPushOutcome.SdkInitFailed("GCM_NOT_INSTALLED", remembered = true),
            WatchPushOutcome.SdkInitTimedOut,
            WatchPushOutcome.NoKnownDevices,
            WatchPushOutcome.AppNotInstalled("fenix 7", null),
            WatchPushOutcome.SendResult("fenix 7", "FAILURE_UNKNOWN", true, "praca: nie - kolko bedzie ukryte"),
            WatchPushOutcome.DevicePushCrashed("fenix 7", "IllegalStateException"),
            WatchPushOutcome.DeliveryCrashed(null)
        )
        outcomes.forEach { o ->
            assertEquals("14:32 — " + describeWatchPushOutcome(o), formatWatchPushStatus(o, at(14, 32, 0), zone))
        }
    }

    // --- persistence format ---

    @Test
    fun `serialize then parse round-trips a full history`() {
        val history = (1..20).map { entry(it) }
        assertEquals(history, parseWatchPushHistory(serializeWatchPushHistory(history)))
    }

    @Test
    fun `round-trip survives values containing the separators and the escape character`() {
        val nasty = WatchPushHistoryEntry(
            time = "08:07:31",
            reason = "start|stop\\x",
            status = "wyslano | praca: tak\nnowa linia \\n nie-escape \\| tez | koniec",
            messageId = "26|11\\16\n"
        )
        val plain = entry(3)
        val parsed = parseWatchPushHistory(serializeWatchPushHistory(listOf(nasty, plain)))
        assertEquals(listOf(nasty, plain), parsed)
    }

    @Test
    fun `null message id round-trips as null, not as an empty string`() {
        val e = WatchPushHistoryEntry("08:07:31", "reczne", "x", null)
        assertNull(parseWatchPushHistory(serializeWatchPushHistory(listOf(e))).single().messageId)
    }

    @Test
    fun `empty history serializes to empty and parses back to empty`() {
        assertEquals("", serializeWatchPushHistory(emptyList()))
        assertEquals(emptyList<WatchPushHistoryEntry>(), parseWatchPushHistory(""))
        assertEquals(emptyList<WatchPushHistoryEntry>(), parseWatchPushHistory(null))
    }

    @Test
    fun `garbage records are skipped instead of crashing or poisoning the rest`() {
        val good = serializeWatchPushHistory(listOf(entry(1)))
        val parsed = parseWatchPushHistory("zupelnie nie to\n$good\nfoo|bar")
        assertEquals(listOf(entry(1)), parsed)
    }
}
