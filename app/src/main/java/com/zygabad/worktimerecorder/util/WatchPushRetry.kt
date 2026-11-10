package com.zygabad.worktimerecorder.util

/**
 * How long a "Garmin Connect Mobile is unavailable" verdict is allowed to stand before the phone
 * asks again: 30 minutes.
 *
 * Long enough that a phone with no Garmin account at all is not paying a 10 s init timeout on
 * every single toggle (which is what the original process-lifetime latch was protecting against),
 * short enough that a bad answer given at cold start has fixed itself long before the workday is.
 */
const val SDK_UNAVAILABLE_RETRY_MS = 30 * 60 * 1000L

/**
 * Whether GarminBridge may try to initialize the Connect IQ SDK again after it previously
 * answered that it was unavailable.
 *
 * [latchedAtMillis] is when that verdict was recorded, or null if there is no standing verdict.
 * [manual] is true for a push the user explicitly asked for — those always retry, because the
 * whole point of the button in Ustawienia is to get a fresh answer rather than a replay.
 *
 * A [latchedAtMillis] in the future (wall-clock jumped backwards via NTP or by hand) is treated as
 * unusable rather than as "very fresh": otherwise a single bad timestamp could suppress every
 * retry indefinitely, which is the exact failure mode this function exists to end.
 */
fun shouldRetrySdkInit(latchedAtMillis: Long?, nowMillis: Long, manual: Boolean): Boolean {
    if (manual) return true
    if (latchedAtMillis == null) return true
    val age = nowMillis - latchedAtMillis
    if (age < 0) return true
    return age >= SDK_UNAVAILABLE_RETRY_MS
}
