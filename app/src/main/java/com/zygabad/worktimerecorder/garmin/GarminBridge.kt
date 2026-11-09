package com.zygabad.worktimerecorder.garmin

import android.content.Context
import android.util.Log
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice
import com.zygabad.worktimerecorder.data.PrefsManager
import com.zygabad.worktimerecorder.data.WorkDatabase
import com.zygabad.worktimerecorder.repository.WorkRepository
import com.zygabad.worktimerecorder.util.buildWatchPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
 * 4. The payload goes to EVERY known device that is CONNECTED and reports the watch face as
 *    INSTALLED, instead of guessing "the first device". Guessing silently loses the feature for
 *    anyone with more than one Garmin paired (an old watch still in Garmin Connect, say).
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
     * Set when initialization fails for a reason that cannot fix itself while the process lives
     * (Garmin Connect Mobile missing or too old). Without this flag every single toggle would pay
     * the full init timeout again for a user who simply has no Garmin account.
     */
    private var sdkUnavailable = false

    /**
     * The one entry point the rest of the app uses: reads the current state and pushes it.
     * Fire-and-forget by design — callers (ViewModel, Service, widget action) must not have to
     * care how long the watch takes or whether it answered at all.
     */
    fun pushCurrentState(context: Context) {
        val appContext = context.applicationContext
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
            return
        }

        if (devices.isEmpty()) {
            Log.i(TAG, "No Garmin device paired in Garmin Connect — nothing to push to")
            return
        }

        for (device in devices) {
            try {
                val status = ciq.getDeviceStatus(device)
                if (status != IQDevice.IQDeviceStatus.CONNECTED) {
                    Log.i(TAG, "Skipping ${device.friendlyName}: $status")
                    continue
                }
                val app = awaitAppInfo(ciq, device)
                if (app == null || app.status != IQApp.IQAppStatus.INSTALLED) {
                    Log.i(TAG, "Pro Stat not installed on ${device.friendlyName} (${app?.status})")
                    continue
                }
                sendPayload(ciq, device, app, payload)
            } catch (t: Throwable) {
                // One unhappy device must not stop the others from getting the update.
                Log.w(TAG, "Push to ${device.friendlyName} failed", t)
            }
        }
    }

    private suspend fun ensureSdkReady(appContext: Context): ConnectIQ? {
        if (sdkUnavailable) return null
        connectIQ?.takeIf { sdkReady }?.let { return it }

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
                                sdkUnavailable = true
                            }
                            Log.i(TAG, "Connect IQ unavailable: $status")
                            if (cont.isActive) cont.resume(false)
                        }

                        override fun onSdkShutDown() {
                            // Garmin Connect Mobile went away; the next push re-initializes.
                            sdkReady = false
                        }
                    })
                }
            } == true

            sdkReady = ready
            if (ready) connectIQ else null
        } catch (t: Throwable) {
            Log.w(TAG, "Connect IQ initialization threw", t)
            null
        }
    }

    /**
     * Confirms the watch face is actually installed before sending. Skipping this check would make
     * a missing watch face indistinguishable from a delivered-but-ignored message in the logs.
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
     */
    private suspend fun sendPayload(ciq: ConnectIQ, device: IQDevice, app: IQApp, payload: Map<String, Any>) {
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
                        if (cont.isActive) cont.resume(Unit)
                    }
                })
            }
        }
    }
}
