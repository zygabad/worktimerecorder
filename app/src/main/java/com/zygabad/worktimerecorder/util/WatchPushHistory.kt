package com.zygabad.worktimerecorder.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The phone-side history of pushes to the "Pro Stat" watch face: the last [WATCH_PUSH_HISTORY_MAX]
 * outcomes, newest first, each with the time, WHY the phone pushed, what happened and which message
 * id ("mid", see GarminPayload.kt) went out. Shown in Ustawienia -> Zegarek as "Historia wysylek".
 *
 * Why (v1.30): the single "last status" line could say what happened to the LAST push, but not how
 * many pushes there had been, when, or what triggered them. That is exactly what was needed when
 * the watch turned out to be a dozen messages behind (see GarminBridge's KDoc) and when the user
 * asked why the phone was sending anything at night with no change in the app. With a reason and a
 * mid per line, the phone screen can be compared digit by digit with the id the watch face prints.
 *
 * Kept pure and Android-free for the same reason as WatchPushStatus.kt: this is the part that can
 * be pinned on a plain JVM (WatchPushHistoryTest). Wording is Polish without diacritics, same as
 * the status line.
 */

/** Why the phone pushed. [label] is what the history line shows. */
enum class WatchPushReason(val label: String) {
    /** Timer started (in-app button or widget double tap). */
    START("start"),

    /** Timer stopped (in-app button or widget double tap). */
    STOP("stop"),

    /** A session's start or end time was edited. */
    EDIT("edycja"),

    /** A session was deleted. */
    DELETE("usuniecie"),

    /** A session was back-filled for a day/time that was never toggled live. */
    BACKFILL("dopisanie"),

    /** The day's target changed (Ustawienia or the quick-preset pills). */
    TARGET("cel"),

    /** The "Wyslij do zegarka teraz" button. */
    MANUAL("reczne"),

    /** The foreground service's 20h safety net closed a forgotten session. */
    AUTO_STOP("auto-stop"),

    /**
     * The cold-start self-heal (WorkRepository.cleanupCorruptedSessions) actually rewrote
     * something — including an auto-stop it performed itself while the service was not running.
     */
    SELF_HEAL("naprawa"),

    /** The "Wyczysc kolejke zegarka" burst (util/WatchQueueDrain.kt). */
    DRAIN("czyszczenie")
}

/** How many entries the history keeps. Older ones fall off the end. */
const val WATCH_PUSH_HISTORY_MAX = 20

/**
 * One recorded push outcome. All fields are already display-ready strings: the history is a log for
 * a human, not data to compute with, and storing strings keeps the persisted format trivial.
 *
 * [time] "HH:mm:ss" local — seconds, unlike the status line, because a drain or a start/stop burst
 * produces several entries inside one minute. [reason] is a [WatchPushReason.label] (or a richer
 * label such as "czyszczenie 30/30"). [status] is [describeWatchPushOutcome]'s text. [messageId] is
 * the payload's "mid", or null when no payload was built.
 */
data class WatchPushHistoryEntry(
    val time: String,
    val reason: String,
    val status: String,
    val messageId: String?
)

private val historyClockFmt = DateTimeFormatter.ofPattern("HH:mm:ss")

/**
 * New entry goes first; the list is cut to [max]. Identical entries are NOT collapsed: two equal
 * lines are two real push attempts, and how often the phone pushed is one of the questions this
 * history exists to answer. The cap alone keeps the stored value bounded — including when a longer
 * list was read back from storage.
 */
fun appendWatchPushHistory(
    history: List<WatchPushHistoryEntry>,
    entry: WatchPushHistoryEntry,
    max: Int = WATCH_PUSH_HISTORY_MAX
): List<WatchPushHistoryEntry> = (listOf(entry) + history).take(max)

/** "08:07:31 start #261116080731 — wyslano do fenix 7 (SUCCESS) | ...". No "#" without an id. */
fun formatWatchPushHistoryLine(entry: WatchPushHistoryEntry): String {
    val id = entry.messageId?.takeIf { it.isNotBlank() }?.let { " #$it" }.orEmpty()
    return "${entry.time} ${entry.reason}$id — ${entry.status}"
}

/**
 * Builds the entry for one recorded outcome. [payload] is the wire map that was being sent (null
 * when none was built); its "mid" becomes [WatchPushHistoryEntry.messageId].
 *
 * The SendResult summary normally ends in "| #mid" (summarizeWatchPayload); here the line already
 * shows the id up front, so the summary is rebuilt from the payload WITHOUT "mid" to avoid printing
 * the same twelve digits twice on one short line. The wire map itself is untouched.
 */
fun buildWatchPushHistoryEntry(
    outcome: WatchPushOutcome,
    reason: String,
    payload: Map<String, Any?>?,
    nowMillis: Long,
    zone: ZoneId = ZoneId.systemDefault()
): WatchPushHistoryEntry {
    val messageId = (payload?.get("mid") as? String)?.takeIf { it.isNotBlank() }
    val forText = if (outcome is WatchPushOutcome.SendResult && payload != null) {
        outcome.copy(payload = summarizeWatchPayload(payload - "mid"))
    } else {
        outcome
    }
    return WatchPushHistoryEntry(
        time = Instant.ofEpochMilli(nowMillis).atZone(zone).format(historyClockFmt),
        reason = reason,
        status = describeWatchPushOutcome(forText),
        messageId = messageId
    )
}

// --- persistence -------------------------------------------------------------------------------
//
// One SharedPreferences String (no new dependency: org.json is an Android stub on the plain-JVM
// test classpath, and nothing else here needs JSON). Records are separated by '\n', fields by '|',
// always exactly four fields: time|reason|status|mid, with an empty mid meaning null. Inside a
// field the escape character is '\': "\\" = '\', "\p" = '|', "\n" = newline, "\r" = carriage
// return — so status text containing " | " (it always does for a SendResult) round-trips intact.
// A record that does not parse (wrong field count, unknown escape) is dropped on its own instead of
// failing the whole history: a diagnostic must never crash or blank the screen it lives on.

private const val RECORD_SEP = '\n'
private const val FIELD_SEP = '|'
private const val ESC = '\\'

private fun escapeField(value: String): String = buildString {
    for (c in value) {
        when (c) {
            ESC -> append("$ESC$ESC")
            FIELD_SEP -> append("${ESC}p")
            '\n' -> append("${ESC}n")
            '\r' -> append("${ESC}r")
            else -> append(c)
        }
    }
}

fun serializeWatchPushHistory(history: List<WatchPushHistoryEntry>): String =
    history.joinToString(RECORD_SEP.toString()) { e ->
        listOf(e.time, e.reason, e.status, e.messageId.orEmpty())
            .joinToString(FIELD_SEP.toString()) { escapeField(it) }
    }

/** Splits one record into unescaped fields, or null if it contains an invalid escape. */
private fun splitRecord(record: String): List<String>? {
    val fields = mutableListOf<String>()
    val current = StringBuilder()
    var i = 0
    while (i < record.length) {
        val c = record[i]
        when (c) {
            ESC -> {
                if (i + 1 >= record.length) return null
                when (record[i + 1]) {
                    ESC -> current.append(ESC)
                    'p' -> current.append(FIELD_SEP)
                    'n' -> current.append('\n')
                    'r' -> current.append('\r')
                    else -> return null
                }
                i += 2
                continue
            }
            FIELD_SEP -> {
                fields += current.toString()
                current.clear()
            }
            else -> current.append(c)
        }
        i++
    }
    fields += current.toString()
    return fields
}

fun parseWatchPushHistory(raw: String?): List<WatchPushHistoryEntry> {
    if (raw.isNullOrEmpty()) return emptyList()
    return raw.split(RECORD_SEP).mapNotNull { record ->
        val f = splitRecord(record) ?: return@mapNotNull null
        if (f.size != 4) return@mapNotNull null
        WatchPushHistoryEntry(
            time = f[0],
            reason = f[1],
            status = f[2],
            messageId = f[3].takeIf { it.isNotEmpty() }
        )
    }
}
