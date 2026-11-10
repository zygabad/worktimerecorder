package com.zygabad.worktimerecorder.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The diagnostic the phone leaves behind after every push attempt to the Pro Stat watch face.
 *
 * Why this is a pure function with its own test file: the watch bridge is invisible when it fails
 * (it only ever logged to logcat), and the one real case — "timer started at work, watch face
 * blank, no PC, no adb" — is exactly the case we could not diagnose. So the status LINE is the
 * product here, and it is the only part of the failure path that can be executed on a plain JVM:
 * WatchPushOutcome carries plain Strings, not Garmin SDK enums, precisely so this file needs no
 * Android and no ConnectIQ AAR. Same reasoning as GarminPayloadTest for the wire contract.
 *
 * NOT covered here, and deliberately so: that GarminBridge.deliver() picks the RIGHT outcome for
 * each SDK exit path, and that SettingsScreen renders it. Both need the Connect IQ service plus a
 * paired watch (deliver) or a Compose runtime (the screen); there is no instrumentation/Robolectric
 * setup in this module, and a source-text assertion would only prove a line still exists, not that
 * the app behaves. Those two remain verified by Zygmunt reading the line on the phone.
 */
class WatchPushStatusTest {

    private val zone = ZoneId.of("Europe/Warsaw")

    /** Pins "now" so the timestamp prefix never depends on the test machine's clock. */
    private fun at(hour: Int, minute: Int): Long =
        LocalDateTime.of(2026, 11, 10, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun line(outcome: WatchPushOutcome, hour: Int = 14, minute: Int = 32): String =
        formatWatchPushStatus(outcome, at(hour, minute), zone)

    // --- 1. Payload could not even be built ---

    @Test
    fun `payload build failure is reported with the time it happened`() {
        assertEquals("14:32 — nie udalo sie zbudowac danych", line(WatchPushOutcome.PayloadBuildFailed))
    }

    // --- 2. Connect IQ initialization refused, with the SDK's own reason ---

    @Test
    fun `init failure names the IQSdkErrorStatus value`() {
        assertEquals(
            "14:32 — Garmin Connect niedostepny: GCM_NOT_INSTALLED",
            line(WatchPushOutcome.SdkInitFailed("GCM_NOT_INSTALLED"))
        )
    }

    @Test
    fun `init failure with no status still produces a readable line`() {
        assertEquals(
            "14:32 — Garmin Connect niedostepny: nieznany blad",
            line(WatchPushOutcome.SdkInitFailed(null))
        )
    }

    /**
     * GarminBridge latches GCM_NOT_INSTALLED / GCM_UPGRADE_NEEDED for the life of the process, so
     * a later push can report a verdict that was reached hours ago. The line has to say so,
     * otherwise the timestamp lies about when the SDK was last actually asked.
     */
    @Test
    fun `a remembered init failure is marked as cached, not freshly measured`() {
        assertEquals(
            "14:32 — Garmin Connect niedostepny: GCM_NOT_INSTALLED (zapamietane)",
            line(WatchPushOutcome.SdkInitFailed("GCM_NOT_INSTALLED", remembered = true))
        )
    }

    // --- 3. Initialization never called back at all ---

    @Test
    fun `init timeout is distinguishable from an init error`() {
        assertEquals(
            "14:32 — Garmin Connect nie odpowiedzial (timeout init)",
            line(WatchPushOutcome.SdkInitTimedOut)
        )
    }

    // --- 4. Nothing paired ---

    @Test
    fun `no known devices says so plainly`() {
        assertEquals(
            "14:32 — brak sparowanego zegarka w Garmin Connect",
            line(WatchPushOutcome.NoKnownDevices)
        )
    }

    // --- 5. Device known but not connected ---

    @Test
    fun `a not-connected device reports its name and the SDK status`() {
        assertEquals(
            "14:32 — Forerunner 955: NOT_CONNECTED",
            line(WatchPushOutcome.DeviceNotConnected("Forerunner 955", "NOT_CONNECTED"))
        )
    }

    @Test
    fun `a device with an unreadable status still reports the device`() {
        assertEquals(
            "14:32 — Forerunner 955: status nieznany",
            line(WatchPushOutcome.DeviceNotConnected("Forerunner 955", null))
        )
    }

    // --- 6. getApplicationInfo claims the face is not there ---

    /**
     * For a SIDELOADED watch face this answer is unreliable, so the bridge now sends anyway — the
     * line must make clear both that Garmin said "not there" AND that we did not give up.
     */
    @Test
    fun `not-installed lookup says the send is attempted anyway`() {
        assertEquals(
            "14:32 — Forerunner 955: Garmin nie widzi tarczy (NOT_INSTALLED) — wysylam mimo to",
            line(WatchPushOutcome.AppNotInstalled("Forerunner 955", "NOT_INSTALLED"))
        )
    }

    @Test
    fun `app-info timeout or null reports no answer instead of a fake status`() {
        assertEquals(
            "14:32 — Forerunner 955: Garmin nie widzi tarczy (brak odpowiedzi) — wysylam mimo to",
            line(WatchPushOutcome.AppNotInstalled("Forerunner 955", null))
        )
    }

    // --- 7. sendMessage came back with a status ---

    @Test
    fun `a successful send names the device and SUCCESS`() {
        assertEquals(
            "14:32 — wyslano do Forerunner 955 (SUCCESS)",
            line(WatchPushOutcome.SendResult("Forerunner 955", "SUCCESS", attemptedAnyway = false))
        )
    }

    @Test
    fun `a failed send names the failure status, not just failure`() {
        assertEquals(
            "14:32 — Forerunner 955: blad wysylki (FAILURE_DEVICE_NOT_CONNECTED)",
            line(WatchPushOutcome.SendResult("Forerunner 955", "FAILURE_DEVICE_NOT_CONNECTED", attemptedAnyway = false))
        )
    }

    @Test
    fun `a send with no status at all is still readable`() {
        assertEquals(
            "14:32 — Forerunner 955: blad wysylki (status nieznany)",
            line(WatchPushOutcome.SendResult("Forerunner 955", null, attemptedAnyway = false))
        )
    }

    /**
     * The whole point of removing the not-installed gate: afterwards it must be obvious from the
     * stored line whether the message went out despite Garmin claiming the face was absent.
     */
    @Test
    fun `a send attempted despite not-installed is marked on both success and failure`() {
        assertEquals(
            "14:32 — wyslano do Forerunner 955 (SUCCESS, mimo NOT_INSTALLED)",
            line(WatchPushOutcome.SendResult("Forerunner 955", "SUCCESS", attemptedAnyway = true))
        )
        assertEquals(
            "14:32 — Forerunner 955: blad wysylki (FAILURE_UNKNOWN, mimo NOT_INSTALLED)",
            line(WatchPushOutcome.SendResult("Forerunner 955", "FAILURE_UNKNOWN", attemptedAnyway = true))
        )
    }

    // --- 8. sendMessage never answered ---

    @Test
    fun `send timeout is distinguishable from a failure status`() {
        assertEquals(
            "14:32 — Forerunner 955: brak odpowiedzi na wysylke (timeout)",
            line(WatchPushOutcome.SendTimedOut("Forerunner 955", attemptedAnyway = false))
        )
    }

    @Test
    fun `send timeout also carries the attempted-anyway marker`() {
        assertEquals(
            "14:32 — Forerunner 955: brak odpowiedzi na wysylke (timeout, mimo NOT_INSTALLED)",
            line(WatchPushOutcome.SendTimedOut("Forerunner 955", attemptedAnyway = true))
        )
    }

    // --- Last-resort net: the pump's own catch(Throwable) around deliver() ---

    /**
     * deliver() handles its own failures per device, but the pump keeps a broad catch so it can
     * never die. That catch used to only log, which is the same blind spot this whole change is
     * about — an unexpected throw (a missing AAR surfacing as NoClassDefFoundError, say) must be
     * readable on the phone too.
     */
    @Test
    fun `an unexpected bridge crash names the throwable`() {
        assertEquals(
            "14:32 — blad mostka: NoClassDefFoundError",
            line(WatchPushOutcome.DeliveryCrashed("NoClassDefFoundError"))
        )
        assertEquals(
            "14:32 — blad mostka: nieznany wyjatek",
            line(WatchPushOutcome.DeliveryCrashed(null))
        )
    }

    // --- Timestamp formatting ---

    @Test
    fun `timestamp is a zero-padded 24h local clock`() {
        assertEquals(
            "08:05 — brak sparowanego zegarka w Garmin Connect",
            line(WatchPushOutcome.NoKnownDevices, hour = 8, minute = 5)
        )
        assertEquals(
            "23:59 — brak sparowanego zegarka w Garmin Connect",
            line(WatchPushOutcome.NoKnownDevices, hour = 23, minute = 59)
        )
        assertEquals(
            "00:00 — brak sparowanego zegarka w Garmin Connect",
            line(WatchPushOutcome.NoKnownDevices, hour = 0, minute = 0)
        )
    }

    @Test
    fun `timestamp is rendered in the supplied zone, not UTC`() {
        // 12:00 Warsaw in November is 11:00 UTC — a UTC-rendered line would read "11:00".
        assertEquals(
            "12:00 — brak sparowanego zegarka w Garmin Connect",
            line(WatchPushOutcome.NoKnownDevices, hour = 12, minute = 0)
        )
    }

    // --- Missing device name: IQDevice.friendlyName can be null or blank ---

    @Test
    fun `a missing or blank device name falls back to a generic word`() {
        assertEquals(
            "14:32 — zegarek: NOT_CONNECTED",
            line(WatchPushOutcome.DeviceNotConnected(null, "NOT_CONNECTED"))
        )
        // "wyslano do ..." needs the genitive, so the nameless fallback is declined there; a real
        // device name (Forerunner 955) is never declined, which is why both forms exist.
        assertEquals(
            "14:32 — wyslano do zegarka (SUCCESS)",
            line(WatchPushOutcome.SendResult("   ", "SUCCESS", attemptedAnyway = false))
        )
    }

    // --- Fits on a phone screen ---

    /**
     * The line is read in a Card in Ustawienia, on a phone, at work. Two lines of body text is the
     * budget; ~80 characters is comfortably inside that at bodyMedium, so this guards against a
     * future outcome being given a paragraph for a message.
     */
    @Test
    fun `every outcome fits in a short one or two line message`() {
        val all = listOf(
            WatchPushOutcome.PayloadBuildFailed,
            WatchPushOutcome.SdkInitFailed("GCM_UPGRADE_NEEDED", remembered = true),
            WatchPushOutcome.SdkInitTimedOut,
            WatchPushOutcome.NoKnownDevices,
            WatchPushOutcome.DeviceNotConnected("Forerunner 955", "NOT_PAIRED"),
            WatchPushOutcome.AppNotInstalled("Forerunner 955", "NOT_INSTALLED"),
            WatchPushOutcome.SendResult("Forerunner 955", "FAILURE_DEVICE_NOT_CONNECTED", attemptedAnyway = true),
            WatchPushOutcome.SendTimedOut("Forerunner 955", attemptedAnyway = true),
            WatchPushOutcome.DeliveryCrashed("ServiceUnavailableException")
        )
        all.forEach { outcome ->
            val text = line(outcome)
            assertTrue("too long to read on a phone: $text", text.length <= 90)
            assertTrue("missing the HH:mm prefix: $text", text.startsWith("14:32 — "))
        }
    }

    /**
     * A throwable thrown while pushing to one device must NOT be rendered through
     * DeviceNotConnected. That outcome's second field is an IQDeviceStatus name, so an exception
     * class name there would read as a state the watch reported about itself - in a line whose
     * only job is to say what really happened.
     */
    @Test
    fun `a crash while pushing to one device is not reported as a device status`() {
        val crash = formatWatchPushStatus(
            WatchPushOutcome.DevicePushCrashed("Forerunner 955", "InvalidStateException"),
            at(14, 32)
        )
        assertEquals("14:32 — Forerunner 955: blad wysylki (InvalidStateException)", crash)

        val notConnected = formatWatchPushStatus(
            WatchPushOutcome.DeviceNotConnected("Forerunner 955", "NOT_CONNECTED"),
            at(14, 32)
        )
        assertTrue(notConnected != crash)
    }

    @Test
    fun `a nameless device crash still reads as a sentence`() {
        assertEquals(
            "08:05 — zegarek: blad wysylki (nieznany wyjatek)",
            formatWatchPushStatus(WatchPushOutcome.DevicePushCrashed(null, null), at(8, 5))
        )
    }

    /**
     * The first real field failure recorded only "RuntimeException" - the class name alone - and
     * that cost a round trip plus a bytecode dive to identify. A bare java.lang.RuntimeException
     * says nothing; its MESSAGE ("Can't create handler inside thread ... Looper.prepare()") says
     * everything. So the message travels with the class name, trimmed to stay one readable line.
     */
    @Test
    fun `a throwable is described by class and message, not class alone`() {
        assertEquals(
            "RuntimeException: Can't create handler inside thread",
            describeThrowable("RuntimeException", "Can't create handler inside thread")
        )
    }

    @Test
    fun `a long message is cut instead of pushing the line off screen`() {
        val described = describeThrowable("RuntimeException", "x".repeat(200))
        assertTrue(described.length <= 80)
        assertTrue(described.startsWith("RuntimeException: "))
        assertTrue(described.endsWith("..."))
    }

    @Test
    fun `a missing or blank message leaves just the class name`() {
        assertEquals("SecurityException", describeThrowable("SecurityException", null))
        assertEquals("SecurityException", describeThrowable("SecurityException", "   "))
    }

    @Test
    fun `newlines in a message never break the single-line contract`() {
        val described = describeThrowable("IllegalStateException", "first line\nsecond line")
        assertFalse(described.contains("\n"))
        assertEquals("IllegalStateException: first line second line", described)
    }
}
