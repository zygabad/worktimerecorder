package com.zygabad.worktimerecorder.garmin

import android.content.Context
import android.util.Log
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice
import com.zygabad.worktimerecorder.data.PrefsManager
import com.zygabad.worktimerecorder.data.WorkDatabase
import com.zygabad.worktimerecorder.repository.WorkRepository
import com.zygabad.worktimerecorder.util.WatchPushOutcome
import com.zygabad.worktimerecorder.util.buildWatchPayload
import com.zygabad.worktimerecorder.util.formatWatchPushStatus
import com.zygabad.worktimerecorder.util.shouldRetrySdkInit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Pushes the work-timer state to the "Pro Stat" Garmin watch face so the watch can show the
 * finish clock / remaining countdown without the phone in hand.
 *
 * Design constraints that shaped this file:
 *
 * 1. EVERYTHING here is best-effort and must stay invisible when it fails. No Garmin Connect
 *    Mobile installed, no watch paired, watch out of range, watch face not installed, SDK in a
 *    bad state — all of those are normal, expected outcomes and result in a log line and nothing
 *    else. The work timer is the product; the watch mirror is a bonus, and it may never crash,
 *    block or change the timer's behaviour. Hence the broad `catch (t: Throwable)` wrappers: they
 *    also cover a NoClassDefFoundError if the AAR were ever missing from a build, which a normal
 *    `catch (e: Exception)` would let through.
 *
 * 2. autoUI is false on initialize(). Garmin's `true` option pops a dialog telling the user to
 *    install/upgrade Garmin Connect Mobile — but we send from a foreground Service, from the
 *    Glance widget's ToggleTimerAction and from Application.onCreate, where there is no Activity
 *    in front to host a dialog, so a prompt would be either impossible or a surprise overlay on
 *    whatever the user is actually doing. Failing silently is the right behaviour here.
 *
 * 3. Sends are coalesced on the trailing edge of a 1s window. Starting work fires several state
 *    changes almost at once (widget toggle -> DB write -> foreground service start -> widget
 *    refresh); only the LAST state is worth sending, and the SDK round-trip is slow enough that
 *    firing all of them would queue redundant BLE traffic.
 *
 * 4. The payload goes to EVERY known device that is CONNECTED, instead of guessing "the first
 *    device". Guessing silently loses the feature for anyone with more than one Garmin paired (an
 *    old watch still in Garmin Connect, say).
 *
 * 5. Best-effort does NOT mean undiagnosable. Constraint 1 used to be implemented as "log it and
 *    return", which made the bridge completely invisible when it failed: a blank watch face at
 *    work, with no PC and no adb, looked exactly the same for all eight ways a push can die. So
 *    every exit path now also records a one-line Polish outcome (util/WatchPushStatus.kt) into
 *    PrefsManager, where Ustawienia → Zegarek shows it and the manual "Wyslij do zegarka teraz"
 *    button can refresh it. Recording is itself best-effort and never throws into the caller.
 */
object GarminBridge {

    private const val TAG = "GarminBridge"

    /** Application id (UUID) of the Pro Stat watch face, from C:\projects\garmin\ProStat\manifest.xml. */
    private const val PRO_STAT_APP_ID = "d9d40fc9-1773-4084-8155-86217e4442af"

    private const val COALESCE_WINDOW_MS = 1_000L

    /**
     * The SDK's callbacks are the only signal that a step finished, and nothing guarantees they
     * ever arrive (Garmin Connect Mobile can be killed mid-call). Every await is therefore
     * bounded, so a stuck call can't pin the pump coroutine forever.
     */
    private const val SDK_TIMEOUT_MS = 10_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * CONFLATED: a newer state simply overwrites an older unsent one — exactly the semantics we
     * want, since the payload is a full snapshot and an outdated snapshot has no value.
     */
    private val requests = Channel<Map<String, Any>>(Channel.CONFLATED)
    private val pumpLock = Any()
    private var pump: Job? = null

    // Single ConnectIQ instance, initialized lazily on the first actual send: initializing binds
    // to a Garmin Connect Mobile service, which is not something to do at app start for a feature
    // the user may not even have a watch for.
    private var connectIQ: ConnectIQ? = null
    private var sdkReady = false

    /**
     * When initialization last failed for a reason that cannot fix itself on the spot (Garmin
     * Connect Mobile missing or too old), or null if no such verdict stands. Without it, every
     * toggle would pay the full init timeout again for a user who simply has no Garmin account.
     *
     * This used to be a plain Boolean that was never cleared, and that was a day-long kill switch:
     * the FIRST push of the process runs from WorkTimerApplication.onCreate, i.e. at phone cold
     * start, which is exactly when Garmin Connect Mobile is most likely to be mid-update,
     * force-stopped or battery-restricted — all of which surface as GCM_NOT_INSTALLED. The
     * foreground service then keeps this process alive for the whole workday, so one bad answer
     * at 06:50 silently muted every later push until a reboot. Now the verdict expires; see
     * util/WatchPushRetry.kt for the window and the reasoning.
     */
    @Volatile
    private var sdkUnavailableSince: Long? = null

    /** Reason behind [sdkUnavailableSince], kept so a latched verdict can still name itself. */
    private var lastInitError: String? = null

    /**
     * Set by a manual push from Ustawienia. The [sdkUnavailable] latch above is right for the
     * automatic pushes it was written for, but it would turn the diagnostic button into a replay
     * of a verdict reached hours ago — so an explicit human request is allowed to clear it and
     * make the SDK answer for itself again. Volatile because it is written from the UI thread and
     * read by the pump coroutine.
     */
    @Volatile
    private var forceInitRetry = false

    /**
     * In-process mirror of PrefsManager.lastWatchPushStatus so Ustawienia can recompose the moment
     * an outcome is recorded — SharedPreferences has no Flow of its own, and every push in this
     * app happens in this same process (the foreground service declares no android:process), so
     * one StateFlow is enough. Prefs remains the durable copy for when the process WAS killed:
     * [observeLastPushStatus] seeds the flow from it on first use.
     */
    private val _lastPushStatus = MutableStateFlow<String?>(null)

    /**
     * Live view of the last push outcome, already formatted for display; null until something has
     * been recorded (the UI renders "jeszcze nie probowano" for that).
     */
    fun observeLastPushStatus(context: Context): StateFlow<String?> {
        if (_lastPushStatus.value == null) {
            _lastPushStatus.value = try {
                PrefsManager(context.applicationContext).lastWatchPushStatus.takeIf { it.isNotBlank() }
            } catch (t: Throwable) {
                null
            }
        }
        return _lastPushStatus.asStateFlow()
    }

    /**
     * Persists + publishes one push outcome. Wrapped in its own catch: a diagnostic is worth
     * strictly less than the timer, so a failing SharedPreferences write must not take down the
     * push (or the pump) with it.
     */
    private fun record(appContext: Context, outcome: WatchPushOutcome) {
        try {
            val line = formatWatchPushStatus(outcome, System.currentTimeMillis())
            PrefsManager(appContext).lastWatchPushStatus = line
            _lastPushStatus.value = line
            Log.i(TAG, line)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not record the watch diagnostic", t)
        }
    }

    /**
     * The one entry point the rest of the app uses: reads the current state and pushes it.
     * Fire-and-forget by design — callers (ViewModel, Service, widget action) must not have to
     * care how long the watch takes or whether it answered at all.
     *
     * [manual] marks a push the user explicitly asked for (the Ustawienia button): it additionally
     * clears the "Garmin Connect is unavailable" latch so the attempt really is a fresh one.
     */
    fun pushCurrentState(context: Context, manual: Boolean = false) {
        val appContext = context.applicationContext
        if (manual) forceInitRetry = true
        scope.launch {
            try {
                val prefs = PrefsManager(appContext)
                val repo = WorkRepository(WorkDatabase.getDatabase(appContext).workDao())
                // getTodayMinutes() is the same cumulative total the main screen and the widget
                // show (sessions already closed today + the live one), so the watch's countdown
                // agrees with the phone even after a break — see WorkRepository's own comment.
                val payload = buildWatchPayload(
                    isWorking = repo.isWorkInProgress(),
                    workedMinutes = repo.getTodayMinutes(),
                    targetMinutes = prefs.targetWorkMinutes,
                    nowMillis = System.currentTimeMillis()
                )
                requests.trySend(payload)
                ensurePump(appContext)
            } catch (t: Throwable) {
                Log.w(TAG, "Could not build the watch payload — skipping this push", t)
                record(appContext, WatchPushOutcome.PayloadBuildFailed)
            }
        }
    }

    /**
     * One pump coroutine drains the channel for the whole process. Serializing sends also means
     * the lazy SDK initialization below needs no locking of its own: only this coroutine touches
     * connectIQ/sdkReady.
     */
    private fun ensurePump(appContext: Context) {
        synchronized(pumpLock) {
            if (pump?.isActive == true) return
            pump = scope.launch {
                for (firstPayload in requests) {
                    // Trailing edge: let the burst settle, then take whatever is newest. A lone
                    // push therefore lands ~1s late, which is invisible on a watch face and is
                    // the price of never sending a state that was already superseded.
                    delay(COALESCE_WINDOW_MS)
                    val payload = requests.tryReceive().getOrNull() ?: firstPayload
                    try {
                        deliver(appContext, payload)
                    } catch (t: Throwable) {
                        // Must never let the pump die — the next toggle would then be mute.
                        Log.w(TAG, "Watch delivery failed", t)
                        // deliver() records its own outcomes; reaching HERE means something no
                        // inner handler expected, which used to be the one remaining silent path.
                        record(appContext, WatchPushOutcome.DeliveryCrashed(t.javaClass.simpleName))
                    }
                }
            }
        }
    }

    private suspend fun deliver(appContext: Context, payload: Map<String, Any>) {
        val ciq = ensureSdkReady(appContext) ?: return

        // getKnownDevices() lists everything paired in Garmin Connect Mobile, connected or not;
        // getConnectedDevices() would be shorter but has been less reliable in practice about
        // devices that connected a moment ago, so the status is checked explicitly instead.
        val devices: List<IQDevice> = try {
            ciq.knownDevices ?: emptyList()
        } catch (t: Throwable) {
            Log.i(TAG, "No known Garmin devices available: ${t.javaClass.simpleName}")
            record(appContext, WatchPushOutcome.NoKnownDevices)
            return
        }

        if (devices.isEmpty()) {
            Log.i(TAG, "No Garmin device paired in Garmin Connect — nothing to push to")
            record(appContext, WatchPushOutcome.NoKnownDevices)
            return
        }

        // With several devices the LAST recorded outcome wins, which is the useful one in
        // practice: only one of Zygmunt's paired devices is ever actually connected.
        for (device in devices) {
            try {
                val status = ciq.getDeviceStatus(device)
                if (status != IQDevice.IQDeviceStatus.CONNECTED) {
                    Log.i(TAG, "Skipping ${device.friendlyName}: $status")
                    record(appContext, WatchPushOutcome.DeviceNotConnected(device.friendlyName, status?.name))
                    continue
                }

                // The "is the watch face installed?" check is NOT a gate any more. It used to
                // `continue` here, which means a wrong answer silently killed the entire feature
                // with nothing to show for it. And a wrong answer is likely: Pro Stat is
                // SIDELOADED (built locally, copied to the watch over USB — it was never in the
                // Connect IQ store), and getApplicationInfo is answered by Garmin Connect Mobile
                // from what it knows about store-installed apps, so NOT_INSTALLED here says more
                // about Garmin's bookkeeping than about the watch. A getApplicationInfo that
                // times out (null) is just as uninformative.
                //
                // Sending to an app that genuinely is not on the watch is harmless — it comes
                // back as a failure IQMessageStatus, which is now recorded and readable on the
                // phone. So: note what the lookup said, then send regardless, constructing the
                // IQApp from the known app id when the lookup gave us no object to reuse. The
                // recorded outcome keeps the two cases apart via attemptedAnyway.
                val app = awaitAppInfo(ciq, device)
                val installed = app != null && app.status == IQApp.IQAppStatus.INSTALLED
                if (!installed) {
                    Log.i(TAG, "Pro Stat reported as not installed on ${device.friendlyName} " +
                        "(${app?.status}) — sending anyway")
                    record(appContext, WatchPushOutcome.AppNotInstalled(device.friendlyName, app?.status?.name))
                }

                val target = app ?: IQApp(PRO_STAT_APP_ID)
                val answer = sendPayload(ciq, device, target, payload)
                record(
                    appContext,
                    if (answer == null) {
                        WatchPushOutcome.SendTimedOut(device.friendlyName, attemptedAnyway = !installed)
                    } else {
                        WatchPushOutcome.SendResult(
                            device.friendlyName,
                            answer.status?.name,
                            attemptedAnyway = !installed
                        )
                    }
                )
            } catch (t: Throwable) {
                // One unhappy device must not stop the others from getting the update.
                Log.w(TAG, "Push to ${device.friendlyName} failed", t)
                // Deliberately NOT DeviceNotConnected: rendering an exception class name where a
                // IQDeviceStatus belongs would read as "the watch reported this state", and the
                // whole value of this line is that it says what actually happened.
                record(
                    appContext,
                    WatchPushOutcome.DevicePushCrashed(device.friendlyName, t.javaClass.simpleName)
                )
            }
        }
    }

    private suspend fun ensureSdkReady(appContext: Context): ConnectIQ? {
        val manual = forceInitRetry
        if (manual) {
            forceInitRetry = false
        }
        if (!shouldRetrySdkInit(sdkUnavailableSince, System.currentTimeMillis(), manual)) {
            // Report the latched reason again (marked as remembered) instead of returning in
            // silence: "nothing happened at all" is the one answer that helps nobody.
            record(appContext, WatchPushOutcome.SdkInitFailed(lastInitError, remembered = true))
            return null
        }
        sdkUnavailableSince = null
        connectIQ?.takeIf { sdkReady }?.let { return it }

        var initError: String? = null
        return try {
            // NOTE: the published doc shows getInstance(IQConnectType) — that overload does not
            // exist in the 2.4.0 AAR; it is getInstance(Context, IQConnectType).
            val ciq = ConnectIQ.getInstance(appContext, ConnectIQ.IQConnectType.WIRELESS)
            connectIQ = ciq

            // Nothing else on the SDK may be called before onSdkReady() — doing so throws
            // InvalidStateException — so initialization is awaited here, once, before any use.
            val ready = withTimeoutOrNull(SDK_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    ciq.initialize(appContext, /* autoUI = */ false, object : ConnectIQ.ConnectIQListener {
                        override fun onSdkReady() {
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onInitializeError(status: ConnectIQ.IQSdkErrorStatus?) {
                            // GCM missing/too old can't resolve itself while we run; a
                            // SERVICE_ERROR can, so that one stays retryable.
                            if (status == ConnectIQ.IQSdkErrorStatus.GCM_NOT_INSTALLED ||
                                status == ConnectIQ.IQSdkErrorStatus.GCM_UPGRADE_NEEDED
                            ) {
                                sdkUnavailableSince = System.currentTimeMillis()
                            }
                            initError = status?.name
                            lastInitError = initError
                            Log.i(TAG, "Connect IQ unavailable: $status")
                            if (cont.isActive) cont.resume(false)
                        }

                        override fun onSdkShutDown() {
                            // Garmin Connect Mobile went away; the next push re-initializes.
                            sdkReady = false
                        }
                    })
                }
            }

            // Three outcomes, not two: true = ready, false = the SDK said why (initError),
            // null = withTimeoutOrNull gave up and no callback ever arrived. The last two look
            // identical in a log line but mean very different things on the phone.
            sdkReady = ready == true
            when (ready) {
                true -> connectIQ
                false -> {
                    record(appContext, WatchPushOutcome.SdkInitFailed(initError))
                    null
                }
                null -> {
                    record(appContext, WatchPushOutcome.SdkInitTimedOut)
                    null
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Connect IQ initialization threw", t)
            record(appContext, WatchPushOutcome.SdkInitFailed(t.javaClass.simpleName))
            null
        }
    }

    /**
     * Asks Garmin Connect Mobile what it thinks of the watch face. Informational only now (see the
     * comment in deliver()): a null result — not installed, or no answer inside the timeout — no
     * longer stops the send, it only ends up in the recorded diagnostic.
     */
    private suspend fun awaitAppInfo(ciq: ConnectIQ, device: IQDevice): IQApp? =
        withTimeoutOrNull(SDK_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                ciq.getApplicationInfo(PRO_STAT_APP_ID, device, object : ConnectIQ.IQApplicationInfoListener {
                    override fun onApplicationInfoReceived(app: IQApp?) {
                        if (cont.isActive) cont.resume(app)
                    }

                    override fun onApplicationNotInstalled(applicationId: String?) {
                        if (cont.isActive) cont.resume(null)
                    }
                })
            }
        }

    /**
     * Awaits the send status rather than firing and forgetting: the status is the only diagnostic
     * available for "the watch face shows nothing" and it keeps the loop from flooding a flaky
     * link with overlapping transfers.
     *
     * Returns a [SendAnswer] when the SDK reported back, or null if it never did inside the
     * timeout — the caller records the difference. The box exists only to keep those two apart: a
     * bare IQMessageStatus? return would collapse "answered with a null status" into "silence".
     */
    private suspend fun sendPayload(
        ciq: ConnectIQ,
        device: IQDevice,
        app: IQApp,
        payload: Map<String, Any>
    ): SendAnswer? =
        withTimeoutOrNull(SDK_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                ciq.sendMessage(device, app, payload, object : ConnectIQ.IQSendMessageListener {
                    override fun onMessageStatus(
                        d: IQDevice?,
                        a: IQApp?,
                        status: ConnectIQ.IQMessageStatus?
                    ) {
                        if (status == ConnectIQ.IQMessageStatus.SUCCESS) {
                            Log.i(TAG, "Pushed $payload to ${device.friendlyName}")
                        } else {
                            Log.w(TAG, "Push to ${device.friendlyName} returned $status")
                        }
                        if (cont.isActive) cont.resume(SendAnswer(status))
                    }
                })
            }
        }

    /** See [sendPayload]: "the SDK answered", with whatever status it chose to answer with. */
    private class SendAnswer(val status: ConnectIQ.IQMessageStatus?)
}
