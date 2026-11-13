package com.zygabad.worktimerecorder.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The human-readable outcome of one push attempt to the "Pro Stat" watch face, and the pure
 * function that renders it into the single line shown in Ustawienia → Zegarek.
 *
 * Why this exists: until now GarminBridge logged every failure to logcat and returned. That makes
 * the whole bridge invisible exactly when it breaks — "timer started at work, watch face blank" is
 * unanswerable with no PC and no adb, because all eight ways a push can die look identical from
 * the phone. Each of those eight exit paths now produces one of the outcomes below, which is
 * formatted here, persisted by PrefsManager (it must survive the process being killed — the push
 * usually runs in the foreground service) and shown on screen.
 *
 * Kept pure and Android-free on purpose, same reasoning as GarminPayload.kt/TimeCalculations.kt:
 * this is the only part of the failure path that can be exercised on a plain JVM, so it carries
 * the whole wording/format contract and WatchPushStatusTest pins all of it. Note the deliberate
 * choice to carry SDK statuses as plain Strings rather than ConnectIQ enums — the enums live in
 * the Garmin AAR, which an android-free unit test cannot load; GarminBridge passes `status?.name`.
 *
 * Wording is Polish without diacritics, matching the watch-side comments: the same text also goes
 * to logcat (and may yet end up on the watch), where the encoding is not guaranteed to be UTF-8.
 */
sealed interface WatchPushOutcome {

    /** The snapshot could not even be built (DB read or prefs read threw). Never reached the SDK. */
    data object PayloadBuildFailed : WatchPushOutcome

    /**
     * ConnectIQ.initialize() reported an error. [status] is IQSdkErrorStatus.name
     * (GCM_NOT_INSTALLED / GCM_UPGRADE_NEEDED / SERVICE_ERROR) or a throwable's class name.
     * [remembered] marks the verdict as the process-wide latched one rather than a fresh answer —
     * see GarminBridge.sdkUnavailable.
     */
    data class SdkInitFailed(val status: String?, val remembered: Boolean = false) : WatchPushOutcome

    /** initialize() never called back at all within the SDK timeout. */
    data object SdkInitTimedOut : WatchPushOutcome

    /** Nothing paired in Garmin Connect Mobile (or getKnownDevices threw). */
    data object NoKnownDevices : WatchPushOutcome

    /** A paired device whose status is not CONNECTED. [status] is IQDeviceStatus.name. */
    data class DeviceNotConnected(val device: String?, val status: String?) : WatchPushOutcome

    /**
     * getApplicationInfo said the face is not there ([status] = IQAppStatus.name) or never
     * answered ([status] = null). The send happens anyway — see GarminBridge.deliver().
     */
    data class AppNotInstalled(val device: String?, val status: String?) : WatchPushOutcome

    /**
     * sendMessage came back. [status] is IQMessageStatus.name, so "SUCCESS" is the good case.
     * [attemptedAnyway] is true when this send followed an AppNotInstalled lookup — that is the
     * one thing we must be able to tell apart afterwards.
     */
    data class SendResult(
        val device: String?,
        val status: String?,
        val attemptedAnyway: Boolean,
        /**
         * What was actually in the parcel, from [summarizeWatchPayload]. Without it the line
         * cannot tell a healthy push of a STOPPED timer (the watch then hides the circle on
         * purpose) from a push that achieved nothing - the two look identical everywhere else.
         */
        val payload: String? = null
    ) : WatchPushOutcome

    /** sendMessage never reported a status within the SDK timeout. */
    data class SendTimedOut(val device: String?, val attemptedAnyway: Boolean) : WatchPushOutcome

    /**
     * Something threw while pushing to ONE device, so the loop moved on to the next. Kept separate
     * from [DeviceNotConnected] on purpose: that one's [DeviceNotConnected.status] is an
     * IQDeviceStatus name, and putting an exception class name there would read as a state the
     * watch reported about itself. [reason] is the throwable's class name only.
     */
    data class DevicePushCrashed(val device: String?, val reason: String?) : WatchPushOutcome

    /**
     * The pump's last-resort catch(Throwable) around deliver() fired — something no inner handler
     * expected (a NoClassDefFoundError from a missing AAR, an SDK InvalidStateException). [reason]
     * is the throwable's class name; no message, it can carry anything.
     */
    data class DeliveryCrashed(val reason: String?) : WatchPushOutcome
}

/** IQMessageStatus.SUCCESS by name — the only non-failure send status. */
/**
 * Longest line we are willing to put in front of the user for one exception.
 */
private const val THROWABLE_DESCRIPTION_MAX = 80

/**
 * "ClassName: message", trimmed to one readable line.
 *
 * Why the message matters: the very first failure this diagnostic caught in the field recorded
 * only "RuntimeException". A bare java.lang.RuntimeException is thrown by half of Android and
 * names nothing; its message - "Can't create handler inside thread ... that has not called
 * Looper.prepare()" - named the bug outright. Recording the class alone cost a round trip to the
 * user and a dive into the Garmin AAR's bytecode to work out what it had to have been.
 *
 * Newlines are flattened because this is rendered as a single line, and the whole thing is cut to
 * [THROWABLE_DESCRIPTION_MAX] so one chatty exception cannot push the timestamp off screen.
 */
fun describeThrowable(className: String, message: String?): String {
    val clean = message.orEmpty()
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace('\t', ' ')
        .trim()
    if (clean.isEmpty()) {
        return className
    }
    val full = "$className: $clean"
    return if (full.length <= THROWABLE_DESCRIPTION_MAX) {
        full
    } else {
        full.take(THROWABLE_DESCRIPTION_MAX - 3) + "..."
    }
}

const val WATCH_SEND_STATUS_SUCCESS = "SUCCESS"

/**
 * One phrase describing the snapshot that went out, for the diagnostic line.
 *
 * Reads the wire payload defensively (it is a Map<String, Any>, and a future protocol version may
 * reshape it) - a diagnostic that throws is worse than no diagnostic.
 *
 * The "| wrk/tgt min" tail was added after the queued-delivery day (see GarminPayload.kt's header):
 * the line on screen said "praca: tak, koniec 16:54", which was true and still did not say WHICH
 * snapshot the watch had - the numbers that went out (50/480, built at 09:44) would have named it
 * on sight. The tail is omitted entirely when either number is missing or not an Int, rather than
 * printing a placeholder: "?/480" or "0/0" would be read as "the phone sent zeroes", a different
 * bug from "this summarizer could not read the payload". A diagnostic that lies is worse than a
 * short one.
 *
 * The "| #id" tail was added in v3, for the same reason one step further on: at 08:11 on
 * 2026-11-13 this line read "praca: tak, koniec 15:57 | 14/480 min" while the watch, holding a
 * record received seven seconds earlier, showed 16:54. The numbers named the snapshot the PHONE
 * built; nothing on either screen could prove the watch was showing that same one. The id (see
 * GarminPayload.kt's "mid") is printed on both screens so the question is settled by comparing
 * digits. The "#" is there so it reads as a label and never as another clock time, and the tail is
 * dropped entirely when the id is missing, blank or not a String - a v2 payload must still
 * summarize, and "#?" would be read as a broken id rather than an unreadable one.
 */
fun summarizeWatchPayload(payload: Map<String, Any?>): String {
    val running = payload["run"] as? Boolean ?: false
    val head = if (!running) {
        "praca: nie - kolko bedzie ukryte"
    } else {
        val finish = (payload["fin"] as? String).orEmpty()
        if (finish.isBlank()) "praca: tak, brak godziny konca" else "praca: tak, koniec $finish"
    }
    val worked = payload["wrk"] as? Int
    val target = payload["tgt"] as? Int
    val withMinutes = if (worked == null || target == null) head else "$head | $worked/$target min"
    val messageId = (payload["mid"] as? String)?.takeIf { it.isNotBlank() }
    return if (messageId == null) withMinutes else "$withMinutes | #$messageId"
}

private val statusClockFmt = DateTimeFormatter.ofPattern("HH:mm")

/** IQDevice.friendlyName is nullable and has been seen blank; never render an empty name. */
private fun deviceLabel(device: String?): String =
    device?.takeIf { it.isNotBlank() } ?: "zegarek"

/**
 * Same, for the one sentence that needs the genitive ("wyslano do ..."). Device names themselves
 * are not declined — only the nameless fallback has to be.
 */
private fun deviceLabelGenitive(device: String?): String =
    device?.takeIf { it.isNotBlank() } ?: "zegarka"

/** Rendered inside the trailing parentheses, so the marker never costs a separate line. */
private fun anywayMark(attemptedAnyway: Boolean): String =
    if (attemptedAnyway) ", mimo NOT_INSTALLED" else ""

/**
 * Renders one outcome as "HH:mm — <co sie stalo>", short enough to read on a phone at a glance.
 * The timestamp is the moment the outcome was recorded, in local time.
 */
fun formatWatchPushStatus(
    outcome: WatchPushOutcome,
    nowMillis: Long,
    zone: ZoneId = ZoneId.systemDefault()
): String {
    val time = Instant.ofEpochMilli(nowMillis).atZone(zone).format(statusClockFmt)
    val text = when (outcome) {
        WatchPushOutcome.PayloadBuildFailed ->
            "nie udalo sie zbudowac danych"

        is WatchPushOutcome.SdkInitFailed ->
            "Garmin Connect niedostepny: ${outcome.status ?: "nieznany blad"}" +
                if (outcome.remembered) " (zapamietane)" else ""

        WatchPushOutcome.SdkInitTimedOut ->
            "Garmin Connect nie odpowiedzial (timeout init)"

        WatchPushOutcome.NoKnownDevices ->
            "brak sparowanego zegarka w Garmin Connect"

        is WatchPushOutcome.DeviceNotConnected ->
            "${deviceLabel(outcome.device)}: ${outcome.status ?: "status nieznany"}"

        is WatchPushOutcome.AppNotInstalled ->
            "${deviceLabel(outcome.device)}: Garmin nie widzi tarczy " +
                "(${outcome.status ?: "brak odpowiedzi"}) — wysylam mimo to"

        is WatchPushOutcome.SendResult -> {
            val head = if (outcome.status == WATCH_SEND_STATUS_SUCCESS) {
                "wyslano do ${deviceLabelGenitive(outcome.device)} " +
                    "($WATCH_SEND_STATUS_SUCCESS${anywayMark(outcome.attemptedAnyway)})"
            } else {
                "${deviceLabel(outcome.device)}: blad wysylki " +
                    "(${outcome.status ?: "status nieznany"}${anywayMark(outcome.attemptedAnyway)})"
            }
            val summary = outcome.payload?.takeIf { it.isNotBlank() }
            if (summary == null) head else "$head | $summary"
        }

        is WatchPushOutcome.SendTimedOut ->
            "${deviceLabel(outcome.device)}: brak odpowiedzi na wysylke " +
                "(timeout${anywayMark(outcome.attemptedAnyway)})"

        is WatchPushOutcome.DevicePushCrashed ->
            "${deviceLabel(outcome.device)}: blad wysylki " +
                "(${outcome.reason ?: "nieznany wyjatek"})"

        is WatchPushOutcome.DeliveryCrashed ->
            "blad mostka: ${outcome.reason ?: "nieznany wyjatek"}"
    }
    return "$time — $text"
}
