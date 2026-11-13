package com.zygabad.worktimerecorder.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
     *
     * Scope, narrowed deliberately (v3): this 90-character claim is only true of a line WITHOUT a
     * payload summary, and it always was — the list below has never contained a SendResult that
     * carried one, so the bound was quietly being asserted about the short half of the outcomes
     * only. Since v2 a SendResult with a summary is well past 90, and v3's message id makes it
     * longer again. Rather than delete the budget or inflate it until it means nothing, the claim
     * now says what it can actually honour, and the payload-carrying worst case is pinned
     * separately in the next test.
     */
    @Test
    fun `every outcome without a payload summary fits in a short one or two line message`() {
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
            assertNull("this test is only about payload-less lines", (outcome as? WatchPushOutcome.SendResult)?.payload)
            val text = line(outcome)
            assertTrue("too long to read on a phone: $text", text.length <= 90)
            assertTrue("missing the HH:mm prefix: $text", text.startsWith("14:32 — "))
        }
    }

    /**
     * The other half: a send line that DOES carry a payload summary. This is the realistic worst
     * case, built from the longest form of every part rather than guessed — longest head
     * ("blad wysylki" with the longest IQMessageStatus name we have seen plus the mimo-NOT_INSTALLED
     * marker), longest summary head ("praca: nie - kolko bedzie ukryte"), four-digit worked and
     * target minutes, and the 12-digit message id.
     *
     * It does NOT fit the two-line phone budget and is not pretended to: the number below is a
     * measurement, so that a future addition to the line is a deliberate decision with a visible
     * cost rather than a surprise. Whether the UI should wrap or split this row is a product call,
     * not this test's to make.
     */
    @Test
    fun `the payload-carrying worst case is measured, not assumed to fit`() {
        val worst = formatWatchPushStatus(
            WatchPushOutcome.SendResult(
                "Forerunner 955",
                "FAILURE_DEVICE_NOT_CONNECTED",
                attemptedAnyway = true,
                payload = summarizeWatchPayload(
                    mapOf(
                        "run" to false, "fin" to "", "wrk" to 1439, "tgt" to 1440,
                        "mid" to "261113075612"
                    )
                )
            ),
            at(14, 32)
        )
        assertEquals(
            "14:32 — Forerunner 955: blad wysylki (FAILURE_DEVICE_NOT_CONNECTED, mimo NOT_INSTALLED) | " +
                "praca: nie - kolko bedzie ukryte | 1439/1440 min | #261113075612",
            worst
        )
        // Measured, not assumed: 154 characters at v3. The same line without the id - i.e. what
        // v2 already produced - is measured alongside it so the cost of the id is a number on the
        // record (16 characters) rather than a recollection. Neither fits the two-line phone
        // budget; this assertion is a tripwire against the line growing further unnoticed.
        val sameLineWithoutTheId = formatWatchPushStatus(
            WatchPushOutcome.SendResult(
                "Forerunner 955",
                "FAILURE_DEVICE_NOT_CONNECTED",
                attemptedAnyway = true,
                payload = summarizeWatchPayload(
                    mapOf("run" to false, "fin" to "", "wrk" to 1439, "tgt" to 1440)
                )
            ),
            at(14, 32)
        )
        assertEquals(138, sameLineWithoutTheId.length)
        assertEquals(154, worst.length)
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

    /**
     * "wyslano (SUCCESS)" was not enough. The watch hides the work circle whenever run=false -
     * that is the agreed design, not a bug - so a perfectly healthy push of a STOPPED timer looks
     * on the phone exactly like a push that achieved nothing, and on the watch exactly like a
     * broken bridge. The line has to say what was sent, not only that something was.
     */
    @Test
    fun `a running timer is summarized with its finish clock`() {
        assertEquals(
            "praca: tak, koniec 16:42 | 50/480 min",
            summarizeWatchPayload(mapOf("run" to true, "fin" to "16:42", "wrk" to 50, "tgt" to 480))
        )
    }

    @Test
    fun `a stopped timer says so and says the circle will be hidden`() {
        assertEquals(
            "praca: nie - kolko bedzie ukryte | 50/480 min",
            summarizeWatchPayload(mapOf("run" to false, "fin" to "", "wrk" to 50, "tgt" to 480))
        )
    }

    @Test
    fun `a running timer with no finish clock is still distinguishable`() {
        assertEquals(
            "praca: tak, brak godziny konca | 50/480 min",
            summarizeWatchPayload(mapOf("run" to true, "fin" to "", "wrk" to 50, "tgt" to 480))
        )
    }

    /**
     * The numbers are the reason this tail exists. On the 9.5h-queued-delivery day the status line
     * read only "praca: tak, koniec 16:54" — true, and still not enough to tell which snapshot the
     * watch was showing. "50/480" would have named the 09:44 build on sight.
     */
    @Test
    fun `the worked and target minutes are on the line, not just the finish clock`() {
        assertEquals(
            "praca: tak, koniec 16:54 | 50/480 min",
            summarizeWatchPayload(mapOf("run" to true, "fin" to "16:54", "wrk" to 50, "tgt" to 480))
        )
    }

    // --- "mid": the id the user compares against the watch, digit by digit (v3) ---

    /**
     * On 2026-11-13 08:11 this line said "praca: tak, koniec 15:57 | 14/480 min" while the watch,
     * holding a record received seven seconds earlier, showed 16:54. Both screens were internally
     * plausible and neither carried anything that could prove they were - or were not - the same
     * message. The id ends the guessing: read it off the phone, read it off the watch face, compare
     * the digits. It is printed with a leading "#" so it reads as a label and never as a time.
     */
    @Test
    fun `a running summary carries the message id`() {
        assertEquals(
            "praca: tak, koniec 15:57 | 14/480 min | #261113075612",
            summarizeWatchPayload(
                mapOf(
                    "run" to true, "fin" to "15:57", "wrk" to 14, "tgt" to 480,
                    "mid" to "261113075612"
                )
            )
        )
    }

    @Test
    fun `a stopped summary carries the message id too`() {
        assertEquals(
            "praca: nie - kolko bedzie ukryte | 50/480 min | #261113075612",
            summarizeWatchPayload(
                mapOf(
                    "run" to false, "fin" to "", "wrk" to 50, "tgt" to 480,
                    "mid" to "261113075612"
                )
            )
        )
    }

    /**
     * Same rule as the worked/target tail: omit it rather than print a placeholder. "#?" or "#-"
     * would be read as "the phone sent a broken id", a different bug from "this summarizer could
     * not read the id". Short beats wrong. A v2 payload (no "mid" at all) must still summarize.
     */
    @Test
    fun `a missing blank or wrongly typed message id drops the tail and never throws`() {
        val base = mapOf<String, Any?>("run" to true, "fin" to "15:57", "wrk" to 14, "tgt" to 480)
        val expected = "praca: tak, koniec 15:57 | 14/480 min"
        assertEquals(expected, summarizeWatchPayload(base))
        assertEquals(expected, summarizeWatchPayload(base + ("mid" to null)))
        assertEquals(expected, summarizeWatchPayload(base + ("mid" to "")))
        assertEquals(expected, summarizeWatchPayload(base + ("mid" to "   ")))
        assertEquals(expected, summarizeWatchPayload(base + ("mid" to 261113075612L)))
    }

    /** The id survives even when the numbers could not be read - it is the part that identifies. */
    @Test
    fun `the message id is still shown when the worked and target tail is dropped`() {
        assertEquals(
            "praca: tak, koniec 15:57 | #261113075612",
            summarizeWatchPayload(
                mapOf("run" to true, "fin" to "15:57", "mid" to "261113075612")
            )
        )
    }

    /** The whole status row as Zygmunt reads it on the phone, id included. */
    @Test
    fun `the full send line renders with the message id on the end`() {
        assertEquals(
            "08:11 — wyslano do Forerunner 955 (SUCCESS) | " +
                "praca: tak, koniec 15:57 | 14/480 min | #261113075612",
            formatWatchPushStatus(
                WatchPushOutcome.SendResult(
                    "Forerunner 955",
                    "SUCCESS",
                    attemptedAnyway = false,
                    payload = summarizeWatchPayload(
                        mapOf(
                            "run" to true, "fin" to "15:57", "wrk" to 14, "tgt" to 480,
                            "mid" to "261113075612"
                        )
                    )
                ),
                at(8, 11)
            )
        )
    }

    @Test
    fun `a payload with missing or wrongly typed keys never throws`() {
        assertEquals("praca: nie - kolko bedzie ukryte", summarizeWatchPayload(emptyMap()))
        assertEquals(
            "praca: nie - kolko bedzie ukryte",
            summarizeWatchPayload(mapOf("run" to "yes", "fin" to 1642))
        )
    }

    /**
     * Omit the tail rather than print a placeholder: a diagnostic that says "?/480" or "0/0" would
     * be read as "the phone sent zeroes", which is a different bug from "the phone sent a payload
     * this summarizer could not read". Short beats wrong.
     */
    @Test
    fun `missing worked or target minutes drop the tail instead of inventing numbers`() {
        assertEquals(
            "praca: tak, koniec 16:42",
            summarizeWatchPayload(mapOf("run" to true, "fin" to "16:42"))
        )
        assertEquals(
            "praca: tak, koniec 16:42",
            summarizeWatchPayload(mapOf("run" to true, "fin" to "16:42", "wrk" to 50))
        )
        assertEquals(
            "praca: tak, koniec 16:42",
            summarizeWatchPayload(mapOf("run" to true, "fin" to "16:42", "tgt" to 480))
        )
        assertEquals(
            "praca: nie - kolko bedzie ukryte",
            summarizeWatchPayload(mapOf("run" to false, "fin" to ""))
        )
    }

    @Test
    fun `wrongly typed worked or target minutes drop the tail and never throw`() {
        assertEquals(
            "praca: tak, koniec 16:42",
            summarizeWatchPayload(mapOf("run" to true, "fin" to "16:42", "wrk" to "50", "tgt" to 480))
        )
        assertEquals(
            "praca: tak, koniec 16:42",
            summarizeWatchPayload(mapOf("run" to true, "fin" to "16:42", "wrk" to 50, "tgt" to 480L))
        )
        assertEquals(
            "praca: tak, koniec 16:42",
            summarizeWatchPayload(mapOf("run" to true, "fin" to "16:42", "wrk" to null, "tgt" to null))
        )
    }

    /**
     * The real payload shape, straight through buildWatchPayload — no hand-built map. Updated for
     * v3: a real payload now always carries "mid", so the end-to-end summary carries the id too.
     * The id is the build time of this very snapshot (2026-11-10 09:44:00 local), which is what
     * makes it comparable with the watch.
     */
    @Test
    fun `a real v3 payload summarizes with its worked target minutes and message id`() {
        val payload = buildWatchPayload(
            isWorking = true,
            workedMinutes = 50,
            targetMinutes = 480,
            nowMillis = at(9, 44),
            zone = zone
        )
        assertEquals(
            "praca: tak, koniec 16:54 | 50/480 min | #261110094400",
            summarizeWatchPayload(payload)
        )
    }

    @Test
    fun `the summary is appended to a successful send, so the line says what was sent`() {
        assertEquals(
            "10:14 — wyslano do Forerunner 955 (SUCCESS) | praca: tak, koniec 16:42",
            formatWatchPushStatus(
                WatchPushOutcome.SendResult(
                    "Forerunner 955",
                    "SUCCESS",
                    attemptedAnyway = false,
                    payload = "praca: tak, koniec 16:42"
                ),
                at(10, 14)
            )
        )
    }

    /**
     * The whole line, with the longer v2 summary on it: the status row is what Zygmunt actually
     * reads on the phone, so the composition of prefix + send result + summary is pinned, not just
     * the summary in isolation.
     */
    @Test
    fun `the full send line still renders correctly with the longer payload summary`() {
        assertEquals(
            "10:14 — wyslano do Forerunner 955 (SUCCESS) | praca: tak, koniec 16:54 | 50/480 min",
            formatWatchPushStatus(
                WatchPushOutcome.SendResult(
                    "Forerunner 955",
                    "SUCCESS",
                    attemptedAnyway = false,
                    payload = summarizeWatchPayload(
                        mapOf("run" to true, "fin" to "16:54", "wrk" to 50, "tgt" to 480)
                    )
                ),
                at(10, 14)
            )
        )
    }

    @Test
    fun `a failed send also carries the longer payload summary`() {
        assertEquals(
            "10:14 — Forerunner 955: blad wysylki (FAILURE_UNKNOWN) | " +
                "praca: nie - kolko bedzie ukryte | 50/480 min",
            formatWatchPushStatus(
                WatchPushOutcome.SendResult(
                    "Forerunner 955",
                    "FAILURE_UNKNOWN",
                    attemptedAnyway = false,
                    payload = summarizeWatchPayload(
                        mapOf("run" to false, "fin" to "", "wrk" to 50, "tgt" to 480)
                    )
                ),
                at(10, 14)
            )
        )
    }

    @Test
    fun `a send without a summary keeps the old shorter line`() {
        assertEquals(
            "10:14 — wyslano do Forerunner 955 (SUCCESS)",
            formatWatchPushStatus(
                WatchPushOutcome.SendResult("Forerunner 955", "SUCCESS", attemptedAnyway = false),
                at(10, 14)
            )
        )
    }
}
