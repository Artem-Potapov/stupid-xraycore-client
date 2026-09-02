package com.justme.xtls_core_proxy.vpn

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.justme.xtls_core_proxy.MainActivity
import com.justme.xtls_core_proxy.R
import com.justme.xtls_core_proxy.bridge.XrayBridge
import com.justme.xtls_core_proxy.config.ConfigBuilder
import com.justme.xtls_core_proxy.config.DnsPreferences
import com.justme.xtls_core_proxy.config.FragmentationPreferences
import com.justme.xtls_core_proxy.config.LogSettings
import com.justme.xtls_core_proxy.config.MuxPreferences
import com.justme.xtls_core_proxy.config.RoutingPreferences
import com.justme.xtls_core_proxy.config.TuningSettings
import com.justme.xtls_core_proxy.config.XrayCorePreferences
import com.justme.xtls_core_proxy.config.XrayLogLevel
import com.justme.xtls_core_proxy.db.AppDatabase
import com.justme.xtls_core_proxy.db.Profile
import androidx.annotation.StringRes
import com.justme.xtls_core_proxy.failover.AndroidNetworkAvailability
import com.justme.xtls_core_proxy.failover.FailoverDecision
import com.justme.xtls_core_proxy.failover.FailoverEpisodeDecision
import com.justme.xtls_core_proxy.failover.FailoverPoolResolver
import com.justme.xtls_core_proxy.failover.FailoverPreferences
import com.justme.xtls_core_proxy.failover.FailoverSettings
import com.justme.xtls_core_proxy.failover.Http204HealthProbe
import com.justme.xtls_core_proxy.failover.RotationAdmission
import com.justme.xtls_core_proxy.failover.TunnelHealthMonitor
import com.justme.xtls_core_proxy.geo.GeoAssetPreparer
import com.justme.xtls_core_proxy.i18n.SupportedLanguage
import com.justme.xtls_core_proxy.killswitch.AndroidUsageStatsEventSource
import com.justme.xtls_core_proxy.killswitch.ForegroundAppMonitor
import com.justme.xtls_core_proxy.killswitch.KillSwitchRepository
import com.justme.xtls_core_proxy.killswitch.UsageStatsForegroundAppMonitor
import com.justme.xtls_core_proxy.log.GiveUpOngoingLine
import com.justme.xtls_core_proxy.log.LogPreferences
import com.justme.xtls_core_proxy.log.LogRepository
import com.justme.xtls_core_proxy.log.VpnConnectionState
import com.justme.xtls_core_proxy.log.XrayCoreLogTailer
import com.justme.xtls_core_proxy.state.ActiveProfileRepository
import com.justme.xtls_core_proxy.split.SplitTunnelMode
import com.justme.xtls_core_proxy.split.SplitTunnelPlanner
import com.justme.xtls_core_proxy.split.SplitTunnelRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

@SuppressLint("VpnServicePolicy")
class XrayVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.justme.xtls_core_proxy.action.START"
        const val ACTION_STOP = "com.justme.xtls_core_proxy.action.STOP"
        /**
         * Internal ReconnectFlow teardown: close the tunnel but leave this service alive for the
         * sequenced ACTION_START. User Disconnect / tile / notification Stops never set this.
         */
        const val EXTRA_RECONNECT_STOP = "extra_reconnect_stop"
        // Fired by the ongoing notification's deleteIntent when the user swipes it away.
        // Android 14+ makes ongoing foreground-service notifications user-dismissable with
        // no opt-out flag, so we re-post to keep the status persistent while the VPN runs.
        const val ACTION_NOTIFICATION_DISMISSED = "com.justme.xtls_core_proxy.action.NOTIFICATION_DISMISSED"
        const val EXTRA_PROFILE_ID = "extra_profile_id"
        /**
         * Set by the QS tile and the ongoing notification's Stop action. Absent on the in-app /
         * ReconnectFlow `ACTION_STOP`, so [LogRepository.signalUserStopRequested] can tell a
         * user Off from the flow's own settle teardown without giving `stopVpn` anything that
         * awaits (RISK-1).
         */
        const val EXTRA_USER_INITIATED_STOP = "extra_user_initiated_stop"

        private const val CHANNEL_ID = "xray_vpn_channel"
        private const val ERROR_CHANNEL_ID = "xray_vpn_error_channel"
        private const val ERROR_NOTIFICATION_ID = 1102
    }

    private val lock = Any()
    private var tunInterface: ParcelFileDescriptor? = null
    /**
     * What [tunInterface] currently holds. Must stay in lock-step with every write/clear of that
     * field: a blackhole or adopted bridge is an unread fd, not a live proxy, and
     * [classifyGiveUpOutcome] needs the distinction.
     */
    private var tunInterfaceKind: TunInterfaceKind = TunInterfaceKind.NONE

    /**
     * The rotation BRIDGE fd: an unread TUN held only across the window in which a failover
     * rotation owns no interface. Guarded by `lock`.
     *
     * A SEPARATE FIELD ON PURPOSE — this is the whole reason the bridge is not simply written into
     * [tunInterface]. That field's *presence* is overloaded (live proxy OR unread containment after
     * a give-up); the bridge must not join that overload mid-rotation, or a give-up in the gap
     * would inherit CONTAINED_BY_LIVE_TUNNEL copy before adoption can re-kind it. See
     * [tunInterfaceKind] and the tunnel-role enum refactor recommendation in the Wave A report.
     *
     * Other readers that still treat null specially:
     *  - `clearGiveUpStateOnRecovery` refuses to clear a give-up while it is null, because a probe
     *    with no tunnel travels the clear network and succeeds for the wrong reason;
     *  - `bringUpTunnel` and the blackhole builder both `check` it is null before `establish()`.
     *
     * The bridge is never both: it is opened only while [tunInterface] is null and is released or
     * ADOPTED into it before anything else can be established.
     */
    private var rotationBridgeInterface: ParcelFileDescriptor? = null
    private var running = false
    private var nextSessionEpoch = 0L
    private var activeSessionEpoch: Long? = null
    private var sessionTunnelState = SessionTunnelState.STOPPED

    // Lock-free mirror of activeSessionEpoch (authored ONLY under `lock`, when activeSessionEpoch is
    // set/cleared). The screen-state BroadcastReceiver reads this to answer "is this still the current
    // session?" without taking the full lifecycle lock on the main thread. It is a best-effort hint:
    // the receiver only decides whether to enqueue lightweight polling pause/resume, and the monitor's
    // own state machine is the source of truth. `lock` remains the authority for all session mutation.
    @Volatile private var activeEpochVolatile: Long? = null

    @Volatile private var currentProfileId: Long = -1L

    // Captured ONCE per connection in startVpn(); reused by bringUpTunnel() on the initial
    // connect AND every kill-switch revive, so a mid-session log-level change can't leak in.
    @Volatile private var sessionLog: LogSettings = LogSettings(XrayLogLevel.WARNING, null)
    @Volatile private var sessionLogFile: File? = null

    // Captured ONCE per connection alongside sessionLog; reused on kill-switch revive so a
    // mid-session fragmentation change can't leak in (same discipline as sessionLog).
    @Volatile private var sessionTuning: TuningSettings = TuningSettings.NONE
    private var logTailer: XrayCoreLogTailer? = null

    // Last controlled-app label that triggered the exposed state; used to rebuild the
    // exposed notification if the user swipes it away while paused.
    @Volatile private var lastTriggerLabel: String = ""

    // A kill-switch event that landed while a transition was in flight (REVIVING or ROTATING) is
    // deferred here instead of being dropped, then replayed once that transition commits CONNECTED.
    // Mutated only under `lock`.
    //
    // This marker — NOT the tunnel state — is what "a kill is queued for replay" means, and it is
    // deliberately still armed while the session is CONNECTED and a replay is on its way. The
    // commit that dispatches a replay leaves it set; replayDeferredKill discharges it when it runs
    // (consumeDeferredKillLocked), and a leave-foreground callback can withdraw it up to that point
    // (withdrawDeferredKillLocked). Both run on the one serialized tunnelOpScope, so their FIFO
    // order decides which wins — see replayDeferredKill for why that is the only ordering that
    // actually closes the "paused for an app that already left" hole.
    //
    // Also cleared by the give-up funnel, by the kill-switch disable branch, and on full-stop
    // teardown so it never leaks across sessions.
    private var pendingKillLabel: String? = null

    private var killSwitchMonitor: UsageStatsForegroundAppMonitor? = null
    private var screenReceiver: BroadcastReceiver? = null
    private var settingsObserverJob: Job? = null

    // --- Auto-failover ---
    /** Live health monitor, or null when failover is not armed. Guarded by `lock`. */
    private var failoverMonitor: TunnelHealthMonitor? = null
    /**
     * The settings [failoverMonitor] was CONSTRUCTED from. Interval/timeout/threshold are
     * constructor arguments of TunnelHealthMonitor/Http204HealthProbe and cannot be changed on a
     * running instance, so this is what tells a live timing edit apart from a no-op re-emission.
     * Guarded by `lock`.
     */
    private var failoverMonitorSettings: FailoverSettings? = null
    private var failoverSettingsJob: Job? = null
    /** Pending "try again once the thrash window elapsed" timer from a give-up. Guarded by `lock`. */
    private var failoverRearmJob: Job? = null
    /** Rotation attempt timestamps for the sliding thrash window. Guarded by `lock`. */
    private var rotationAttempts: List<Long> = emptyList()
    /** Servers that failed a health probe or bring-up in the CURRENT rotation episode. Guarded by `lock`. */
    private var episodeFailedIds: Set<Long> = emptySet()
    /**
     * What the last failover give-up left behind, or null when no give-up state is showing.
     *
     * Not a bare "blackholed" boolean: the three outcomes need three different messages, and the
     * uncontained one must never be reported with containment copy. Authored under `lock`; read
     * off-lock by repostOngoingNotification, which uses it to tell a give-up (service still
     * running, restore the ongoing line) from a session that is simply dying (nothing to restore).
     */
    @Volatile private var giveUpOutcome: FailoverGiveUpOutcome? = null

    /**
     * Which ongoing-notification (1101) line is TRUE for the BLACKHOLED state currently on screen,
     * or null when no give-up has published one.
     *
     * NOT a duplicate of [giveUpOutcome], and deliberately not derived from it at read time. That
     * field is the "an automatic recovery is still owed" marker — `shouldRestartForRecovery` keys
     * off it, and `applyFailoverPreferences`' disable branch CLEARS it while deliberately leaving
     * the connection state BLACKHOLED. Re-deriving the line from it therefore relabelled a
     * still-proxying tunnel with the blackhole copy after a release: the two contained outcomes have
     * OPPOSITE packet truths, so that told a user with a working connection that their traffic was
     * being held. Restoring the marker instead is not available — its release is load-bearing.
     *
     * Written under `lock` by the ONE producer of BLACKHOLED ([giveUpRotationLocked]) and cleared on
     * successful recovery or full teardown. It is paired with [LogRepository.giveUpLine] so a
     * successful transition cannot leave home/tile carrying a line from the previous give-up.
     */
    @Volatile private var giveUpLine: GiveUpOngoingLine? = null

    /**
     * Whether the single automatic recovery attempt granted to an UNPROTECTED give-up has been
     * spent. "Disconnect now, stop if the re-arm fails": the first unprotected give-up re-arms, and
     * if the re-armed rotation also fails to bring anything up we stop the service rather than
     * leaving it running-but-unprotected forever. Cleared wherever [giveUpOutcome] is — successful
     * rotation, successful revive, the recovery callback, and full teardown.
     *
     * **Set by an ATTEMPT, never by the decision to schedule one.** It is written inside
     * [rotateTunnel]'s reservation, once `canReserveRotation` has actually admitted the transition
     * — not at schedule time in [giveUpRotationLocked]. Writing it at schedule time meant a
     * kill-switch pause landing before the timer fired spent the retry with nothing attempted:
     * [rotateTunnel] bailed at `canReserveRotation`, no second give-up ever happened, so
     * [shouldStopServiceOnGiveUp] could never fire, and the foreground service went on running with
     * NO TUN until the user intervened. A recovery that cannot be attempted is now re-armed instead
     * (see [unprotectedRetryAction]), and the re-arming is itself bounded so the bound survives.
     *
     * One carry-over case is known and ACCEPTED rather than fixed, because it errs towards stopping
     * a service that cannot protect anything: a retry whose give-up classifies
     * CONTAINED_BY_BLACKHOLE leaves the flag set (traffic is contained, so nothing clears it), so a
     * later UNPROTECTED stops immediately with no retry of its own.
     *
     * Guarded by `lock`.
     */
    private var unprotectedRetryConsumed: Boolean = false

    /**
     * Instant the current UNPROTECTED episode began, for [unprotectedRetryAction]'s deferral
     * deadline. Survives a disable/re-enable so restoring the re-arm does not restart the bound.
     * Cleared wherever [giveUpOutcome] is cleared. Guarded by `lock`.
     */
    private var unprotectedEpisodeSinceMs: Long? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val tunnelOpScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO.limitedParallelism(1)
    )

    private data class SessionContext(
        val epoch: Long,
        val profileId: Long,
        val log: LogSettings,
    )

    private class StaleSessionException : IllegalStateException("VPN session is no longer active")

    private fun isCurrentSessionLocked(sessionEpoch: Long): Boolean =
        acceptsSessionLifecycleCallback(running, activeSessionEpoch, sessionEpoch)

    private fun ownsTunnelTransitionLocked(
        sessionEpoch: Long,
        expectedState: SessionTunnelState,
    ): Boolean = ownsTunnelTransition(
        running = running,
        activeSessionEpoch = activeSessionEpoch,
        callbackSessionEpoch = sessionEpoch,
        tunnelState = sessionTunnelState,
        expectedState = expectedState,
    )

    private fun isCurrentSession(sessionEpoch: Long): Boolean =
        synchronized(lock) { isCurrentSessionLocked(sessionEpoch) }

    override fun onBind(intent: Intent?): IBinder? {
        return super.onBind(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val profileId = intent?.getLongExtra(EXTRA_PROFILE_ID, StartCommandDecision.SENTINEL)
            ?: StartCommandDecision.SENTINEL
        when (val decision = StartCommandDecision.decide(intent?.action, profileId)) {
            is StartCommandDecision.StartProfile -> startVpn(decision.profileId)
            StartCommandDecision.StartActiveProfile -> resolveActiveAndStart()
            StartCommandDecision.Stop -> {
                val stopService = StartCommandDecision.stopServiceForReconnect(
                    isReconnectStop = intent?.getBooleanExtra(EXTRA_RECONNECT_STOP, false) == true,
                )
                // Publish BEFORE launching stopVpn so a second Stop that early-returns still
                // aborts an in-flight ReconnectFlow. No await (RISK-1): tryEmit-style StateFlow bump.
                if (intent?.getBooleanExtra(EXTRA_USER_INITIATED_STOP, false) == true) {
                    LogRepository.signalUserStopRequested()
                }
                // Route through tunnelOpScope instead of running the blocking stopVpn (Xray/TUN
                // teardown, plus contention on the lock a connect holds across the seconds-long
                // XrayBridge.startXray) on the main thread — a Disconnect during a connect would
                // otherwise freeze the UI / risk an ANR. limitedParallelism(1) serializes this
                // behind any in-flight kill/revive; stopVpn (no expected epoch) then tears down
                // whatever session is current, so a stop landing mid-start still reliably stops
                // the tunnel. onDestroy/onRevoke keep the SYNCHRONOUS stopVpn where teardown must
                // complete inline.
                tunnelOpScope.launch { stopVpn(stopService = stopService) }
            }
            StartCommandDecision.RepostNotification -> {
                // User swiped the ongoing notification (allowed on Android 14+). Re-post it
                // so the connected/exposed status stays visible while the VPN runs; if we are
                // no longer running this was a stale delivery, so just clean up.
                // Marshal the re-post onto tunnelOpScope so it serializes behind any in-flight
                // kill/revive (which write the same NOTIFICATION_ID); a swipe mid-transition then
                // reads the settled state instead of racing the authoritative notification writer.
                // If the VPN has stopped by the time it runs, connectionState is DISCONNECTED and
                // repostOngoingNotification() is a no-op.
                if (running) tunnelOpScope.launch { repostOngoingNotification() } else stopSelf()
            }
            StartCommandDecision.RefuseNoProfile -> {
                LogRepository.setConnectionState(VpnConnectionState.ERROR)
                LogRepository.emitError(R.string.vpn_start_failed_error)
                LogRepository.append("Refused to start: no profile ID provided")
                stopSelf()
            }
        }
        // REDELIVER: an OS kill or process crash re-delivers the last ACTION_START
        // (profile id included) so the same profile reconnects. An explicit stop calls
        // no-arg stopSelf(), which clears all pending intents, so it is not redelivered.
        return START_REDELIVER_INTENT
    }

    private fun resolveActiveAndStart() {
        // System-initiated start (always-on / boot) arrives with startForegroundService
        // semantics: we must call startForeground within the OS deadline (~5s) or be killed.
        // pickOrPersistActive is an async Room read that can be slow on a cold boot, so
        // promote to foreground BEFORE resolving, and tear it down if no profile resolves.
        createNotificationChannel()
        startForeground(
            VpnNotifications.NOTIFICATION_ID,
            buildNotification(localizedString(R.string.vpn_status_connecting))
        )
        serviceScope.launch {
            val id = ActiveProfileRepository.pickOrPersistActive(this@XrayVpnService)
            if (id == null) {
                LogRepository.append("Always-on start: no active profile to bring up")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } else {
                startVpn(id)
            }
        }
    }

    override fun onRevoke() {
        LogRepository.append("VPN permission revoked by system")
        LogRepository.emitError(R.string.vpn_permission_revoked_error)
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        tunnelOpScope.cancel()
        stopVpn()
        super.onDestroy()
    }

    private fun startVpn(profileId: Long) {
        val sessionEpoch = synchronized(lock) {
            if (running) {
                if (!shouldRestartForRecovery(running, giveUpOutcome)) {
                    LogRepository.append("VPN already running")
                    // The caller recorded the requested profile as active before dispatching this
                    // start; we are refusing it, so roll that write back to the profile the tunnel
                    // is really on or the UI and the QS tile would label a server we never
                    // connected to as connected. Same rollback duty as rotateTunnel's bring-up
                    // failure arm, from the other side: ActiveProfileRepository must only ever
                    // name a profile some tunnel actually carries. setActiveProfileId writes via
                    // apply(), so this holds no disk I/O under the lock.
                    activeProfileIdToRestoreOnRefusedStart(profileId, currentProfileId)?.let {
                        ActiveProfileRepository.setActiveProfileId(this@XrayVpnService, it)
                    }
                    return
                }
                // The user acted on the "turn the VPN off and on again, or choose another server"
                // copy from an UNPROTECTED give-up: the service is running but owns no tunnel and
                // is protecting nothing, so the early return above would silently swallow their
                // only in-app recovery. Tear the dead session down and fall through to the NORMAL
                // start path, which takes a fresh epoch — no parallel bring-up. stopService = false
                // keeps this service instance alive across the restart: a real stopSelf() here
                // would schedule our own destruction and onDestroy would then tear down the session
                // we are about to start. Reentrant on the same thread, and it flips `running` false
                // so the assignment below stays coherent.
                LogRepository.append(
                    "Restarting the tunnel to recover from an unprotected state (profile id=$profileId)"
                )
                stopVpn(stopService = false)
            }
            running = true
            nextSessionEpoch += 1
            activeSessionEpoch = nextSessionEpoch
            activeEpochVolatile = nextSessionEpoch
            sessionTunnelState = SessionTunnelState.STARTING
            nextSessionEpoch
        }

        val foregrounded = synchronized(lock) {
            if (!isCurrentSessionLocked(sessionEpoch)) {
                false
            } else {
                createNotificationChannel()
                startForeground(
                    VpnNotifications.NOTIFICATION_ID,
                    buildNotification(localizedString(R.string.vpn_status_connecting))
                )
                true
            }
        }
        if (!foregrounded) return

        // Defensive re-check: a caller (e.g. the QS tile) may have pre-flighted
        // VpnService.prepare() before dispatching ACTION_START, and the user
        // could have revoked permission in the gap before we got here. Without
        // this guard, establish() would later fail with a silent
        // SecurityException and the user would only see ERROR state with no
        // explanation. startForeground above satisfies the FGS contract before
        // we stop ourselves.
        if (VpnService.prepare(this) != null) {
            failInitialStart(
                sessionEpoch = sessionEpoch,
                errorRes = R.string.vpn_permission_revoked_error,
                logMessage = "Refused to start: VPN permission not granted",
                postNotification = ::postPermissionRevokedNotification,
            )
            return
        }

        val announced = synchronized(lock) {
            if (!isCurrentSessionLocked(sessionEpoch)) {
                false
            } else {
                LogRepository.setConnectionState(VpnConnectionState.CONNECTING)
                LogRepository.append("Starting VPN service")
                true
            }
        }
        if (!announced) return

        Thread {
            try {
                val profile = runBlocking {
                    AppDatabase.get(this@XrayVpnService).profileDao().getById(profileId)
                }
                if (profile == null) {
                    failInitialStart(
                        sessionEpoch = sessionEpoch,
                        errorRes = R.string.vpn_start_failed_error,
                        logMessage = "Profile not found (id=$profileId)",
                    )
                    return@Thread
                }

                // Capture the log level for the whole session (survives kill-switch revives).
                val logFile = File(filesDir, "logs/xray-core.log")
                val initialLog = LogSettings(
                    LogPreferences.getLogLevel(this@XrayVpnService),
                    logFile.absolutePath,
                )
                val initialized = synchronized(lock) {
                    if (!isCurrentSessionLocked(sessionEpoch)) {
                        false
                    } else {
                        logFile.parentFile?.mkdirs()
                        // Truncation is best-effort: a failure must not abort connect, but leave a
                        // sanitized breadcrumb so operators know core logs may be missing.
                        runCatching { logFile.writeText("") }
                            .onFailure {
                                LogRepository.append(
                                    "Core log file truncate failed; Xray-core logs may be unavailable this session"
                                )
                            }
                        currentProfileId = profileId
                        sessionLogFile = logFile
                        sessionTuning = TuningSettings(
                            fragmentation = FragmentationPreferences.load(this@XrayVpnService),
                            mux = MuxPreferences.load(this@XrayVpnService),
                            dns = DnsPreferences.load(this@XrayVpnService),
                            routing = RoutingPreferences.load(this@XrayVpnService),
                            core = XrayCorePreferences.load(this@XrayVpnService),
                        )
                        sessionLog = initialLog
                        true
                    }
                }
                if (!initialized) return@Thread

                bringUpTunnel(
                    profile = profile,
                    log = initialLog,
                    sessionEpoch = sessionEpoch,
                    expectedState = SessionTunnelState.STARTING,
                )
                    .onSuccess {
                        if (!isCurrentSession(sessionEpoch)) return@onSuccess
                        val prefs = KillSwitchRepository.load(this@XrayVpnService)
                        // Seeded read, mirroring the KillSwitchRepository.load above and for the
                        // same reason: FailoverPreferences.state is a process-global
                        // MutableStateFlow(DEFAULT), so an observer-only wiring would receive
                        // `enabled = false` and never arm failover on any path where the settings
                        // Activity never ran in this process (process death, always-on restart, a
                        // first-launch QS-tile connect). Both loads touch SharedPreferences and so
                        // stay OUTSIDE the lifecycle lock.
                        val failoverPrefs = FailoverPreferences.load(this@XrayVpnService)
                        val committed = synchronized(lock) {
                            if (!ownsTunnelTransitionLocked(sessionEpoch, SessionTunnelState.STARTING)) {
                                false
                            } else {
                                sessionLogFile?.let { f ->
                                    if (logTailer == null) {
                                        logTailer = XrayCoreLogTailer(f).also { it.start() }
                                    }
                                }
                                sessionTunnelState = SessionTunnelState.CONNECTED
                                LogRepository.setConnectionState(VpnConnectionState.CONNECTED)
                                updateNotification(localizedString(R.string.vpn_status_connected))
                                applyKillSwitchPreferences(prefs, sessionEpoch)
                                settingsObserverJob?.cancel()
                                settingsObserverJob = serviceScope.launch {
                                    KillSwitchRepository.state.collect { newPrefs ->
                                        if (isCurrentSession(sessionEpoch)) {
                                            applyKillSwitchPreferences(newPrefs, sessionEpoch)
                                        }
                                    }
                                }
                                applyFailoverPreferences(failoverPrefs, sessionEpoch)
                                failoverSettingsJob?.cancel()
                                failoverSettingsJob = serviceScope.launch {
                                    FailoverPreferences.state.collect { newSettings ->
                                        applyFailoverPreferences(newSettings, sessionEpoch)
                                    }
                                }
                                true
                            }
                        }
                        if (!committed) return@onSuccess
                    }
                    .onFailure { error ->
                        failInitialStart(
                            sessionEpoch = sessionEpoch,
                            errorRes = R.string.vpn_start_failed_error,
                            logMessage = "Xray start failed: ${error.message}",
                        )
                    }
            } catch (error: Throwable) {
                failInitialStart(
                    sessionEpoch = sessionEpoch,
                    errorRes = R.string.vpn_start_failed_error,
                    logMessage = "VPN start failed: ${error.message}",
                )
            }
        }.start()
    }

    private fun failInitialStart(
        sessionEpoch: Long,
        @StringRes errorRes: Int,
        logMessage: String,
        postNotification: (() -> Unit)? = null,
    ) {
        val shouldStop = synchronized(lock) {
            if (!ownsTunnelTransitionLocked(sessionEpoch, SessionTunnelState.STARTING)) {
                false
            } else {
                LogRepository.setConnectionState(VpnConnectionState.ERROR)
                LogRepository.emitError(errorRes)
                LogRepository.append(logMessage)
                postNotification?.invoke()
                true
            }
        }
        if (shouldStop) stopVpn(expectedSessionEpoch = sessionEpoch)
    }

    private fun bringUpTunnel(
        profile: Profile,
        log: LogSettings,
        sessionEpoch: Long,
        expectedState: SessionTunnelState,
    ): Result<Unit> {
        return runCatching {
            val ownsTransition = synchronized(lock) {
                ownsTunnelTransitionLocked(sessionEpoch, expectedState)
            }
            if (!ownsTransition) throw StaleSessionException()
            val configJson = ConfigBuilder.buildRuntimeConfig(profile.config, log, sessionTuning)

            val geoAssetDir = GeoAssetPreparer.prepare(this)
                .getOrElse { error ->
                    throw IllegalStateException("Geofile preparation failed: ${error.message}", error)
                }

            val builder = Builder()
                .setSession(localizedString(R.string.app_name))
                .setMtu(sessionTuning.core.mtu)
                .addAddress("10.7.0.1", 32)
                .addAddress("fd00:1:fd00:1::1", 128)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .addDnsServer("1.1.1.1")
                .also { if (sessionTuning.core.ipv6) it.addDnsServer("2606:4700:4700::1111") }

            val splitPrefs = SplitTunnelRepository.load(this@XrayVpnService)
            if (splitPrefs.mode == SplitTunnelMode.ALLOW_ONLY && splitPrefs.packages.isEmpty()) {
                LogRepository.append("Split tunnel allow-only mode enabled with no selected apps")
            }
            // Whole-app tunneling: self is never excluded. Xray's own sockets bypass the
            // tun via protect() (registered above), not via app exclusion.
            val plan = SplitTunnelPlanner.plan(splitPrefs.mode, splitPrefs.packages, packageName)
            plan.allowedPackages.forEach { pkg ->
                try {
                    builder.addAllowedApplication(pkg)
                } catch (_: PackageManager.NameNotFoundException) {
                    LogRepository.append("Split tunnel skipped missing package: $pkg")
                }
            }
            plan.disallowedPackages.forEach { pkg ->
                try {
                    builder.addDisallowedApplication(pkg)
                } catch (_: PackageManager.NameNotFoundException) {
                    LogRepository.append("Split tunnel skipped missing package: $pkg")
                }
            }

            // Serialise ownership of the global TUN/Xray bridge with full stop and the next
            // session admission. A stale starter is rejected before it can publish resources.
            synchronized(lock) {
                if (!ownsTunnelTransitionLocked(sessionEpoch, expectedState)) {
                    throw StaleSessionException()
                }
                check(tunInterface == null) {
                    "Cannot establish a tunnel while the active transition already owns a TUN interface"
                }

                // ---- HANDOVER ----
                // A rotation has been holding a bridge TUN over this whole bring-up (a no-op for the
                // initial connect and for a kill-switch revive, neither of which opens one). Release
                // it HERE, immediately before establish(), with no I/O of any kind between the two
                // calls: everything expensive — buildRuntimeConfig, geo-asset prep, the split read,
                // the Builder itself — has already run above, so the window in which no interface
                // exists collapses from seconds to one binder round-trip.
                //
                // Release-then-establish, deliberately, and NOT the other way round. `establish()`
                // replaces the process's active VPN interface, so establishing first and closing the
                // bridge after would arguably be seamless — but what happens to the bridge when that
                // second establish FAILS is exactly the part no documentation settles, and being
                // wrong there means a leak instead of a stall. It needs a device; it is recorded as
                // a follow-up in docs/features/auto-failover.md rather than guessed at here.
                //
                // If establish() then fails, the bring-up failure arm re-opens a bridge under this
                // same lock before the next candidate is tried.
                releaseRotationBridgeLocked("handing over to the newly established tunnel")

                val pfd = builder.establish()
                    ?: throw IllegalStateException("VpnService.establish() returned null")

                tunInterface = pfd
                tunInterfaceKind = TunInterfaceKind.LIVE_PROXY
                val fd = pfd.fd
                LogRepository.append("TUN established with fd=$fd")
                LogRepository.append("Using geofiles from ${geoAssetDir.absolutePath}")

                // Loop-avoidance: Xray's own sockets bypass the tun via protect().
                // Must succeed before Xray dials, or (with self-exclusion removed in
                // Task 2) the proxy socket would route into the tun and loop. getOrThrow()
                // also surfaces a controller-install failure from the Go bridge.
                XrayBridge.registerProtector(this@XrayVpnService).getOrThrow()

                XrayBridge.startXray(configJson, fd, geoAssetDir.absolutePath).getOrThrow()
                LogRepository.append("Xray core started")
            }
        }
    }

    private fun tearDownTunnelLocked() {
        XrayBridge.stopXray().onFailure { error ->
            LogRepository.append("Xray stop warning: ${error.message}")
        }
        try {
            tunInterface?.close()
        } catch (error: Throwable) {
            LogRepository.append("TUN close warning: ${error.message}")
        } finally {
            tunInterface = null
            tunInterfaceKind = TunInterfaceKind.NONE
        }
    }

    /**
     * Tears the tunnel down because a kill-listed app came to the foreground.
     *
     * @param triggerPackageLabel the app whose foregrounding triggered this kill, or `null` for the
     *   REPLAY of a previously deferred one. [replayDeferredKill] is the only caller that passes
     *   null, and its KDoc carries the reason a replay resolves its label here instead of carrying
     *   one.
     */
    private fun killTunnel(sessionEpoch: Long, triggerPackageLabel: String?) {
        tunnelOpScope.launch {
            try {
                synchronized(lock) {
                    // A replay carries no label of its own: it reads and discharges the deferred
                    // marker HERE, at execution time. A leave-foreground callback queued ahead of
                    // it has therefore already been able to withdraw the marker, in which case
                    // there is nothing left to replay and the tunnel must stay up.
                    val label = triggerPackageLabel
                        ?: consumeDeferredKillLocked(sessionEpoch)
                        ?: run {
                            LogRepository.append(
                                "Kill-switch: no deferred kill left to replay — it was withdrawn " +
                                    "when the app left the foreground, or the session moved on"
                            )
                            return@launch
                        }
                    if (!ownsTunnelTransitionLocked(sessionEpoch, SessionTunnelState.CONNECTED)) {
                        // A kill can only tear down a CONNECTED tunnel. If the same session is
                        // mid-transition — a kill-switch revive OR a failover rotation, both of
                        // which tear the tunnel down and bring it back up — DEFER the event (record
                        // it, replay once the transition commits) rather than dropping it: the
                        // foreground monitor is edge-triggered and would never re-fire this safety
                        // event, leaving the tunnel CONNECTED with a kill-listed app in the
                        // foreground. Any other state (stale epoch, stopped, already paused) has
                        // nothing to defer to, so drop as before.
                        if (shouldDeferKillDuringTransition(
                                running = running,
                                activeSessionEpoch = activeSessionEpoch,
                                callbackSessionEpoch = sessionEpoch,
                                tunnelState = sessionTunnelState,
                            )
                        ) {
                            pendingKillLabel = label
                            LogRepository.append(
                                "Kill-switch: deferring kill for $label " +
                                    "until the in-flight transition completes"
                            )
                        }
                        return@launch
                    }
                    // The kill-switch subsystem may have been disabled after this event was queued on
                    // tunnelOpScope (applyKillSwitchPreferences stops + nulls the monitor under the lock).
                    // Do not tear down a tunnel for a feature that is no longer active — drop the stale
                    // queued kill.
                    //
                    // A REPLAY can reach here, and an earlier revision of this comment wrongly said
                    // it could not. applyKillSwitchPreferences runs on serviceScope, not on this
                    // scope, so a killTunnel enqueued BEFORE a disable can execute AFTER it — and
                    // its defer branch above runs ahead of this check, so it can arm the marker
                    // post-disable. The commit then dispatches a replay that lands right here.
                    // Dropping it is the correct outcome (the feature is off), so only the
                    // impossibility claim was wrong, never the behaviour.
                    if (killSwitchMonitor == null) {
                        LogRepository.append(
                            "Kill-switch: ignoring queued kill for $label (feature disabled)"
                        )
                        return@launch
                    }
                    LogRepository.append("Kill-switch: tearing down tunnel for $label")
                    tearDownTunnelLocked()
                    // A rotation episode that failed a candidate returns to CONNECTED with the
                    // bridge still open and then dispatches its retry as a SEPARATE coroutine, so a
                    // kill queued on this same serialized scope can land in between and reach here.
                    // PAUSED means "no tunnel must exist"; leaving the bridge up would leave the
                    // kill-listed app captured by an unread fd — no internet at all, and the
                    // kill-switch silently not honoured. It would also be stranded: the retry then
                    // bails at canReserveRotation and nothing else owns it.
                    releaseRotationBridgeLocked("the kill-switch paused the session")
                    sessionTunnelState = SessionTunnelState.PAUSED
                    // No tunnel exists while PAUSED, so every health probe would fail and we would
                    // "rotate" a tunnel the kill-switch deliberately tore down. Stop, don't pause:
                    // pausePolling() preserves the failure count, which would then trip instantly on
                    // revive. reviveTunnel re-applies prefs to bring the monitor back. The screen
                    // receiver is deliberately NOT reconciled here — the kill-switch monitor is
                    // still live, so it must stay registered.
                    stopFailoverMonitorLocked()
                    // State first, then the notification: notify() is a silent no-op when
                    // POST_NOTIFICATIONS is denied, but writing state ahead keeps the machine
                    // correct even if the exposed-notification build ever throws.
                    LogRepository.setConnectionState(VpnConnectionState.PAUSED)
                    lastTriggerLabel = label
                    // Quiet, persistent FGS notification (id 1101, low channel) drops to a
                    // paused status line; the loud heads-up exposed alert is a SEPARATE
                    // notification on the high channel (id 1103) so it can actually alert.
                    updateNotification(localizedString(R.string.vpn_status_paused, label))
                    // 1106 says a kill was DEFERRED and the listed app is still tunnelled. The kill
                    // has now landed and the tunnel is gone, so that notice is false — and it lives
                    // on this same high-importance channel, so leaving it up would pair "VPN is OFF
                    // for every app" with "that app is still going through the VPN". Retract before
                    // posting so the two contradictory heads-ups never coexist.
                    VpnNotifications.cancelKillSwitchNotApplied(this@XrayVpnService)
                    // 1105, the give-up alert, for exactly the same reason — and it IS reachable,
                    // for all three outcomes. giveUpRotationLocked leaves sessionTunnelState
                    // CONNECTED on every path that posts 1105 (mechanically required, so the re-arm
                    // can reserve another rotation), which is precisely the state this kill needs to
                    // proceed from, and nothing in the give-up path touches the kill-switch monitor.
                    // So a blackholed or degraded session can be paused, and its "your connection
                    // was paused to keep you protected" alert would then sit beside 1103's "the VPN
                    // is OFF and you're exposed" on an equally loud channel.
                    //
                    // giveUpOutcome is deliberately NOT cleared here. It has FOUR readers, and one
                    // of them IS reachable while PAUSED:
                    //   * repostOngoingNotification — takes its PAUSED branch, never reads it;
                    //   * clearGiveUpStateOnRecovery — early-returns unless the state is CONNECTED,
                    //     and the failover monitor that drives it was just stopped above;
                    //   * shouldRestartForRecovery — unreachable, because every start surface
                    //     refuses PAUSED (connectAction(PAUSED) == UNAVAILABLE,
                    //     decideTileClick(PAUSED) == Stop);
                    //   * applyFailoverPreferences' disable branch — REACHABLE. The settings
                    //     observer is live throughout the pause and that branch is gated only on
                    //     `enabled`, not on tunnel state. So switching auto-failover off during a
                    //     kill-switch pause, with a CONTAINED give-up still recorded, releases it
                    //     and emits vpn_failover_disabled_while_blackholed / _while_degraded —
                    //     whose "tap Reconnect" names a button PAUSED does not offer.
                    // That last one is cosmetic: it misstates the remedy, never the protection
                    // posture, and the release itself is harmless here (the marker would have been
                    // cleared by the revive anyway). It is recorded under Known limitations in
                    // docs/features/auto-failover.md.
                    //
                    // The marker cannot outlive the pause either — reviveTunnel's success path
                    // clears it, and failRevive stops the session, which clears it too. Against
                    // that, clearing it here would add a SECOND disarm site for the marker
                    // shouldRestartForRecovery keys off, which is exactly the coupling that
                    // produced the running-but-unconnectable bug on the disable path. If a start
                    // affordance is ever added in PAUSED, clear it here first — that is the
                    // trade-off being made, not an oversight.
                    VpnNotifications.cancelFailoverBlackholed(this@XrayVpnService)
                    VpnNotifications.postExposed(
                        this@XrayVpnService,
                        label,
                        notificationDismissIntent()
                    )
                }
            } catch (error: Throwable) {
                failKillSwitch(sessionEpoch, "killTunnel failed: ${error.message}")
            }
        }
    }

    /**
     * Replays the kill [shouldDeferKillDuringTransition] parked, now that the transition it was
     * waiting on has committed CONNECTED.
     *
     * The label is resolved from `pendingKillLabel` INSIDE the replay coroutine rather than captured
     * by the commit that dispatches it, and that is load-bearing rather than stylistic.
     * `tunnelOpScope` is `Dispatchers.IO.limitedParallelism(1)` and `bringUpTunnel` is not a
     * `suspend` function, so it holds that single slot for its whole blocking span. A
     * leave-foreground callback arriving during a bring-up is therefore queued AHEAD of this replay
     * but does not execute until the transition has already committed. If the commit had captured
     * the label, that leave would find the marker empty, withdraw nothing, and this replay would go
     * on to pause the tunnel for an app that has already gone — the exact hole being closed.
     *
     * Reading the marker here lets FIFO order settle every case: a leave queued BEFORE this replay
     * withdraws the marker and the replay finds nothing to do; a leave queued AFTER it runs against
     * a tunnel this replay has already paused, which is [reviveTunnel]'s ordinary PAUSED input.
     */
    private fun replayDeferredKill(sessionEpoch: Long) =
        killTunnel(sessionEpoch, triggerPackageLabel = null)

    /**
     * Reads and DISCHARGES the deferred-kill marker on behalf of a replay — or null when there is
     * nothing left to replay, because a leave-foreground callback withdrew it
     * ([withdrawDeferredKillLocked]) while the replay sat in `tunnelOpScope`'s queue.
     *
     * Session-guarded for the same reason [deferredKillToWithdraw] is: a replay dispatched by a
     * session that has since been superseded must not discharge a marker the live session armed.
     *
     * Caller must hold `lock`.
     */
    private fun consumeDeferredKillLocked(sessionEpoch: Long): String? {
        if (!isCurrentSessionLocked(sessionEpoch)) return null
        return pendingKillLabel.also { pendingKillLabel = null }
    }

    /**
     * Withdraws a kill parked by [shouldDeferKillDuringTransition] because the app it was deferred
     * for has left the foreground while the transition was still in flight.
     *
     * **Deliberately posts nothing, and specifically NOT notification 1106.** That notice
     * ([announceDroppedDeferredKillLocked]) looks like the obvious precedent and is not: it fires
     * when a give-up drops a deferred kill while the listed app is STILL tunneled, so the user asked
     * for the VPN to be off for that app, it is not, and they have to be told. Here the app has
     * already left — nothing the user asked for went unhonoured, there is no app riding the tunnel
     * to warn about, and posting it would alert them to a failure that did not occur. Log, do not
     * alert.
     *
     * Caller must hold `lock`.
     */
    private fun withdrawDeferredKillLocked(sessionEpoch: Long) {
        val label = deferredKillToWithdraw(
            pendingKillLabel = pendingKillLabel,
            running = running,
            activeSessionEpoch = activeSessionEpoch,
            callbackSessionEpoch = sessionEpoch,
        ) ?: return
        pendingKillLabel = null
        LogRepository.append(
            "Kill-switch: $label left the foreground before the in-flight transition committed; " +
                "withdrawing the kill deferred for it"
        )
    }

    private fun failKillSwitch(sessionEpoch: Long, logMessage: String) {
        val shouldStop = synchronized(lock) {
            if (!isCurrentSessionLocked(sessionEpoch)) {
                false
            } else {
                LogRepository.append(logMessage)
                LogRepository.setConnectionState(VpnConnectionState.ERROR)
                LogRepository.emitError(R.string.vpn_revive_error)
                true
            }
        }
        if (shouldStop) stopVpn(expectedSessionEpoch = sessionEpoch)
    }

    private fun reviveTunnel(sessionEpoch: Long) {
        tunnelOpScope.launch {
            // Mirror killTunnel's guard: revive's async getById/append/bringUpTunnel would otherwise
            // let an unexpected Throwable escape into the SupervisorJob scope with no handler, which
            // crashes the process. Route any escape through failRevive (a no-op unless this coroutine
            // still owns the REVIVING transition for sessionEpoch).
            try {
                val session = synchronized(lock) {
                    // The leave-foreground edge that brings us here is ALSO the signal that any kill
                    // deferred for that app is no longer wanted. Withdrawn before the revive
                    // decision below and INDEPENDENTLY of it, because the two questions have
                    // different answers: a revive reserves only from PAUSED, while the deferral that
                    // strands the session is outstanding in every other state. (The other caller,
                    // applyKillSwitchPreferences' disable branch, clears the marker itself before
                    // launching this — but only USUALLY a no-op there: it launches and drops the
                    // lock, so a killTunnel queued ahead of this coroutine can re-arm the marker in
                    // between. Clearing it again is then real work, and harmless.)
                    withdrawDeferredKillLocked(sessionEpoch)
                    if (!canReserveRevive(
                            running = running,
                            activeSessionEpoch = activeSessionEpoch,
                            callbackSessionEpoch = sessionEpoch,
                            tunnelState = sessionTunnelState,
                        )
                    ) {
                        null
                    } else {
                        // Reserve PAUSED → REVIVING before any async DB/config work. A duplicate
                        // same-epoch revive now observes REVIVING and returns without establishing.
                        sessionTunnelState = SessionTunnelState.REVIVING
                        SessionContext(sessionEpoch, currentProfileId, sessionLog)
                    }
                } ?: return@launch
                if (session.profileId == -1L) {
                    failRevive(session.epoch, "reviveTunnel: no current profile, cannot revive")
                    return@launch
                }
                if (!isCurrentSession(session.epoch)) return@launch
                LogRepository.append("Kill-switch: reviving tunnel for profile id=${session.profileId}")
                val profile = AppDatabase.get(this@XrayVpnService).profileDao().getById(session.profileId)
                if (profile == null) {
                    failRevive(session.epoch, "reviveTunnel: profile ${session.profileId} not found")
                    return@launch
                }
                bringUpTunnel(
                    profile = profile,
                    log = session.log,
                    sessionEpoch = session.epoch,
                    expectedState = SessionTunnelState.REVIVING,
                )
                    .onSuccess {
                        val hasDeferredKill = synchronized(lock) {
                            if (!ownsTunnelTransitionLocked(session.epoch, SessionTunnelState.REVIVING)) {
                                return@onSuccess
                            }
                            // ---- COMMIT ----
                            // From here the revive has succeeded, and nothing that follows may
                            // reach the outer `catch (error: Throwable)`. Rotation guards its own
                            // post-commit work because an escape there RECLASSIFIES a healthy
                            // tunnel; this one is guarded because an escape here vanishes into
                            // SILENCE. failRotation's counterpart, failRevive, demands REVIVING —
                            // which the line below has just left — so it logs nothing, reports
                            // nothing and stops nothing. The session would simply be left with a
                            // live tunnel, a dead watchdog and a dropped kill-switch event, and
                            // the log would not say so.
                            sessionTunnelState = SessionTunnelState.CONNECTED
                            // ---- POST-COMMIT, still under `lock` ----
                            // Guarded PER STEP, never as one shared block, for the reason recorded
                            // at rotation's commit: the steps below have INDEPENDENT consequences,
                            // so one guard would let a throw in the first take out the rest. The
                            // bare field writes between them are unguarded because they cannot
                            // throw, and the last expression is the block's return value.
                            //
                            // The three guarded calls here reach a subsystem: notify/cancel are
                            // binder calls and localizedString builds a configuration context and
                            // resolves a resource. setConnectionState is a bare StateFlow write
                            // that realistically cannot throw; it is guarded anyway so no single
                            // post-commit step is left as the one path back to the outer catch.
                            //
                            // Guarded IN PLACE rather than moved off the lock: publishing
                            // CONNECTED under the same lock as the state transition it describes
                            // is what stops a concurrent kill-switch pause or stop — both of which
                            // write LogRepository state while holding `lock` — from being
                            // overwritten by a late CONNECTED from here.
                            clearGiveUpLineLocked()
                            afterReviveCommitted("publishing the connected state") {
                                LogRepository.setConnectionState(VpnConnectionState.CONNECTED)
                            }
                            // Dismiss the separate exposed heads-up; restore the connected status.
                            // Its own guard: dropping it would leave 1103's "the VPN is OFF and
                            // you're exposed" heads-up standing over a tunnel that is back up.
                            afterReviveCommitted("retracting the exposure alert") {
                                VpnNotifications.cancelExposed(this@XrayVpnService)
                            }
                            // A revive that lands on a blackholed session replaces the unread fd
                            // with a real Xray-backed tunnel, so the give-up alert would now be
                            // actively misleading — it claims the internet is off while it works.
                            giveUpOutcome = null
                            unprotectedRetryConsumed = false
                            unprotectedEpisodeSinceMs = null
                            afterReviveCommitted("retracting the give-up alert") {
                                VpnNotifications.cancelFailoverBlackholed(this@XrayVpnService)
                            }
                            afterReviveCommitted("refreshing the ongoing notification") {
                                updateNotification(localizedString(R.string.vpn_status_connected))
                            }
                            // A kill-switch event deferred during this revive must now be replayed
                            // so the tunnel does not stay CONNECTED with the kill-listed app in the
                            // foreground. The marker is deliberately left ARMED and only observed
                            // here: replayDeferredKill discharges it when it actually runs, so a
                            // leave-foreground callback queued ahead of that replay still has
                            // something to withdraw. See replayDeferredKill.
                            pendingKillLabel != null
                        }
                        // ---- POST-COMMIT, off the lock ----
                        // Restart failover for the restored tunnel — nothing else does, so without
                        // this the feature would stay dead for the rest of the session after the
                        // first kill-switch pause. Reads the current settings flow value (the
                        // observer keeps it fresh) and re-checks epoch + CONNECTED internally.
                        // MUST run outside the lock block above: it takes the lock itself, and
                        // running it before CONNECTED is committed would read REVIVING and no-op.
                        // Guarded separately from the replay below because skipping it leaves the
                        // watchdog dead for the rest of the session.
                        afterReviveCommitted("restarting the health monitor") {
                            applyFailoverPreferences(authoritativeFailoverSettings(), session.epoch)
                        }
                        // Ordered before the replay so a replayed kill correctly stops the monitor
                        // again through killTunnel's pause path. Its own guard because skipping it
                        // silently drops a kill-switch event the user asked for.
                        if (hasDeferredKill) {
                            afterReviveCommitted("replaying the deferred kill") {
                                replayDeferredKill(session.epoch)
                            }
                        }
                    }
                    .onFailure { error ->
                        failRevive(session.epoch, "reviveTunnel failed: ${error.message}")
                    }
            } catch (ce: CancellationException) {
                // Structured-concurrency cancellation (e.g. tunnelOpScope.cancel() in onDestroy) must
                // propagate, not be reported as a revive failure. Unlike killTunnel (whose body has no
                // suspension points), reviveTunnel suspends at getById, so its catch CAN observe a CE.
                throw ce
            } catch (error: Throwable) {
                failRevive(sessionEpoch, "reviveTunnel failed: ${error.message}")
            }
        }
    }

    private fun failRevive(sessionEpoch: Long, logMessage: String) {
        val shouldStop = synchronized(lock) {
            if (!ownsTunnelTransitionLocked(sessionEpoch, SessionTunnelState.REVIVING)) {
                false
            } else {
                LogRepository.append(logMessage)
                LogRepository.emitError(R.string.vpn_revive_error)
                postReviveErrorNotification()
                true
            }
        }
        if (shouldStop) stopVpn(expectedSessionEpoch = sessionEpoch)
    }

    /**
     * Failover rotation: tear the dead tunnel down and bring a sibling server up, inside the SAME
     * session epoch. Sibling of [killTunnel]/[reviveTunnel] and keeps their locking discipline —
     * reserve the transition under `lock` before any async work, re-check ownership before every
     * mutation, and route every escape through a single fail path.
     *
     * [unprotectedRecoverySinceMs] marks this as THE single automatic recovery an UNPROTECTED
     * give-up is granted, carrying the instant that give-up happened. Only the re-arm timer passes
     * it; ordinary rotations (the health monitor, and this method's own recursive retry through the
     * candidate list) pass null because they are not spending that budget.
     */
    private fun rotateTunnel(sessionEpoch: Long, unprotectedRecoverySinceMs: Long? = null) {
        tunnelOpScope.launch {
            try {
                val session = synchronized(lock) {
                    if (!canReserveRotationFromAuthoritativeState(
                            running = running,
                            activeSessionEpoch = activeSessionEpoch,
                            callbackSessionEpoch = sessionEpoch,
                            tunnelState = sessionTunnelState,
                        )
                    ) {
                        // A failed candidate returns to CONNECTED with no live TUN and its unread
                        // rotation bridge held across the queued retry. If disable wins this race,
                        // the bridge is the sole containment and must enter the give-up funnel for
                        // adoption; releasing it would expose traffic between sessions.
                        if (shouldFunnelRotationReservationRefusal(
                                running = running,
                                activeSessionEpoch = activeSessionEpoch,
                                callbackSessionEpoch = sessionEpoch,
                                tunnelState = sessionTunnelState,
                                hasTunnel = tunInterface != null,
                                hasRotationBridge = rotationBridgeInterface != null,
                            )
                        ) {
                            giveUpRotationLocked(
                                sessionEpoch,
                                "rotation reservation refused while holding the containment bridge",
                            )
                            return@launch
                        }
                        // Nothing was attempted — the transition could not even be reserved
                        // (kill-switch pause, stale epoch, …). No sole containment bridge is held
                        // in this branch, so release remains the correct cleanup.
                        releaseRotationBridgeLocked("rotation reservation refused")
                        // A recovery that never happened must not spend the single retry, and it
                        // must not silently drop the bound that stops a service protecting nothing
                        // either, so re-arm with the ORIGINAL give-up instant — but only while the
                        // feature is still enabled (same veto shape as shouldFireFailoverRetry).
                        if (unprotectedRecoverySinceMs != null &&
                            isCurrentSessionLocked(sessionEpoch) &&
                            authoritativeFailoverSettings().enabled
                        ) {
                            LogRepository.append(
                                "Failover: recovery rotation could not reserve the transition " +
                                    "(tunnel is $sessionTunnelState); re-arming without spending " +
                                    "the retry"
                            )
                            scheduleFailoverRearmLocked(
                                sessionEpoch,
                                retryByRotation = true,
                                unprotectedSinceMs = unprotectedRecoverySinceMs,
                            )
                        }
                        return@launch
                    }
                    // ---- THE ATTEMPT ----
                    // Written here, and deliberately BEFORE the thrash-cap admission below: the
                    // reservation is what makes this a real attempt, and a denied admission still
                    // funnels into giveUpRotationLocked, which must see the budget as spent or a
                    // second UNPROTECTED outcome would re-arm instead of stopping.
                    if (unprotectedRecoverySinceMs != null) unprotectedRetryConsumed = true
                    // ONE tuple, read once. Both fields describe the same sliding window, so taking
                    // them from two separate reads could mix a new maxRotations with an old
                    // rotationWindowMs if a save landed between them.
                    val admissionSettings = authoritativeFailoverSettings()
                    when (val admission = FailoverDecision.admitRotation(
                        attempts = rotationAttempts,
                        now = System.currentTimeMillis(),
                        maxRotations = admissionSettings.maxRotations,
                        windowMs = admissionSettings.rotationWindowMs,
                    )) {
                        RotationAdmission.Denied -> {
                            giveUpRotationLocked(sessionEpoch, "thrash cap reached")
                            return@launch
                        }
                        is RotationAdmission.Admitted -> rotationAttempts = admission.attempts
                    }
                    sessionTunnelState = SessionTunnelState.ROTATING
                    val session = SessionContext(sessionEpoch, currentProfileId, sessionLog)
                    episodeFailedIds = FailoverEpisodeDecision.recordProbeFailedCurrent(
                        failedIds = episodeFailedIds,
                        currentId = session.profileId,
                    )
                    // The monitor that fired is already TERMINAL (TunnelHealthMonitor clears its own
                    // isStarted/job before invoking the listener) but the FIELD still holds it. Drop
                    // it here so the post-rotation re-apply constructs a FRESH monitor instead of
                    // early-returning on a non-null field — otherwise failover would arm exactly
                    // once per session. The screen receiver is deliberately NOT reconciled yet: the
                    // rotation is transient, and the post-rotation apply reconciles it.
                    stopFailoverMonitorLocked()
                    session
                }

                val dao = AppDatabase.get(this@XrayVpnService).profileDao()
                val current = dao.getById(session.profileId)
                if (current == null) {
                    failRotation(
                        session.epoch,
                        "rotateTunnel: current profile ${session.profileId} not found"
                    )
                    return@launch
                }
                val pool = FailoverPoolResolver.resolve(dao, current)
                val failed = synchronized(lock) { episodeFailedIds }
                val next = FailoverDecision.nextCandidate(pool, current.id, failed)
                if (next == null) {
                    synchronized(lock) {
                        // Same ownership re-check as the bring-up block below, and for the same
                        // reason: getById and FailoverPoolResolver.resolve both ran OFF-LOCK just
                        // above, so a stop+restart in that window would otherwise let this
                        // old-epoch give-up stop the NEW session's monitor, unregister its shared
                        // screen receiver, and write BLACKHOLED over a healthy tunnel — with its
                        // re-arm keyed to the dead epoch, so nothing would ever clear it.
                        if (!ownsTunnelTransitionLocked(session.epoch, SessionTunnelState.ROTATING)) {
                            return@launch
                        }
                        giveUpRotationLocked(session.epoch, "no healthy candidate left in pool")
                    }
                    return@launch
                }

                LogRepository.append("Failover: rotating ${current.name} -> ${next.name}")
                synchronized(lock) {
                    if (!ownsTunnelTransitionLocked(session.epoch, SessionTunnelState.ROTATING)) {
                        return@launch
                    }
                    tearDownTunnelLocked()
                    // ---- BRIDGE THE GAP ----
                    // bringUpTunnel does buildRuntimeConfig, geo-asset prep and the split read
                    // OFF-lock before it reaches establish(), so without this the session would own
                    // no VPN interface for SECONDS — on every routine rotation, and once per dead
                    // candidate while a pool is exhausted — and every tunneled app would emit
                    // cleartext on the underlying network for that whole span. Opened here, under
                    // the same lock as the teardown, so nothing can observe the session between the
                    // two. The trade is deliberate and is the safe direction: during a switch, apps
                    // briefly lose connectivity instead of briefly leaking.
                    //
                    // Not folded into tearDownTunnelLocked: that function is also what the
                    // kill-switch and stopVpn use to reach a genuinely tunnel-less state.
                    val bridgeRequired = shouldEstablishRotationBridge(
                        hasTunnel = tunInterface != null,
                        hasRotationBridge = rotationBridgeInterface != null,
                        tunnelState = sessionTunnelState,
                    )
                    if (bridgeRequired) {
                        establishRotationBridgeLocked()
                    }
                    if (shouldAbortRotationForMissingBridge(
                            bridgeRequired = bridgeRequired,
                            bridgeHeld = rotationBridgeInterface != null,
                        )
                    ) {
                        LogRepository.append(
                            "Failover: rotation bridge could not be established after teardown; " +
                                "aborting the uncovered rebuild into give-up"
                        )
                        giveUpRotationLocked(session.epoch, "rotation bridge could not be established")
                        return@launch
                    }
                    currentProfileId = next.id
                    // ANNOUNCE THE SWITCH. The bridge holds traffic but does not carry it, so
                    // leaving the UI, the ongoing notification and the QS tile saying CONNECTED
                    // would still claim a working connection the user does not have. The
                    // teardown-before-bring-up ordering is forced by bringUpTunnel's
                    // check(tunInterface == null) and is not changed here. Every arm below
                    // re-announces: success -> CONNECTED, retry -> CONNECTING again on the next
                    // attempt, give-up -> BLACKHOLED/ERROR.
                    LogRepository.setConnectionState(VpnConnectionState.CONNECTING)
                    updateNotification(localizedString(R.string.vpn_status_switching))
                }

                bringUpTunnel(
                    profile = next,
                    log = session.log,
                    sessionEpoch = session.epoch,
                    expectedState = SessionTunnelState.ROTATING,
                )
                    .onSuccess {
                        val hasDeferredKill = synchronized(lock) {
                            if (!ownsTunnelTransitionLocked(session.epoch, SessionTunnelState.ROTATING)) {
                                return@onSuccess
                            }
                            // ---- COMMIT ----
                            // This write is the commit: from here the rotation has succeeded, and
                            // NOTHING that follows may reach the outer `catch (error: Throwable)`.
                            // That calls failRotation, which funnels straight into
                            // giveUpRotationLocked with sessionTunnelState == CONNECTED and a real
                            // fd, i.e. classifies CONTAINED_BY_LIVE_TUNNEL and writes BLACKHOLED,
                            // posts the give-up alert and stops the monitor OVER A HEALTHY,
                            // JUST-RESTORED TUNNEL. A committed success must not be reclassifiable.
                            sessionTunnelState = SessionTunnelState.CONNECTED
                            // ---- POST-COMMIT, still under `lock` ----
                            // Two of the three calls below reach a subsystem and can therefore
                            // throw: getSystemService(...).notify/cancel are binder calls, and
                            // localizedString builds a configuration context and resolves a
                            // resource. The third, setConnectionState, is a bare StateFlow
                            // assignment and realistically cannot — it is guarded anyway so all
                            // three post-commit steps read alike and none becomes the one path
                            // back to the outer catch. The bare field writes between them are not
                            // guarded: they cannot throw, and wrapping the last would change the
                            // block's return value.
                            //
                            // They are guarded IN PLACE rather than moved out of the lock. Order
                            // and atomicity are load-bearing for the first one: publishing
                            // CONNECTED under the same lock as the state transition it describes is
                            // what stops a concurrent kill-switch pause or stop — both of which
                            // write LogRepository state while holding `lock` — from being
                            // overwritten by a late CONNECTED from here. Moving the commit itself
                            // after the calls would not help either: a throw would then escape with
                            // the state still ROTATING and a live fd, which failRotation classifies
                            // CONTAINED_BY_LIVE_TUNNEL just the same while additionally stranding
                            // the transition.
                            //
                            // afterRotationCommitted is safe to call while holding `lock`: it is a
                            // plain try/catch whose only side effect is LogRepository.append (taken
                            // under this lock in a dozen places here), it never suspends and never
                            // takes a lock of its own. CancellationException still propagates — out
                            // of the synchronized block, out of .onSuccess, to the CE arm below.
                            //
                            // Swallowing these is the lesser evil, not a free win, and the cost is
                            // NOT self-healing: a dropped setConnectionState leaves the UI reading
                            // CONNECTING over a live tunnel until something else writes the state —
                            // a kill-switch pause, a later rotation, a give-up or a stop.
                            // repostOngoingNotification does not fix it; it re-renders whatever
                            // LogRepository already says. clearGiveUpStateOnRecovery does not
                            // either, since giveUpOutcome is null by then. So the failure is
                            // understating a working connection, indefinitely, which is a lie in
                            // the SAFE direction — against letting the throw escape and tear down a
                            // healthy session's monitor while writing BLACKHOLED over it.
                            clearGiveUpLineLocked()
                            afterRotationCommitted("publishing the connected state") {
                                LogRepository.setConnectionState(VpnConnectionState.CONNECTED)
                            }
                            // Traffic flows again, so a give-up alert left over from an earlier
                            // blackhole would now claim the internet is off while it works. This
                            // also covers a rotation kicked off by the re-arm timer.
                            giveUpOutcome = null
                            unprotectedRetryConsumed = false
                            unprotectedEpisodeSinceMs = null
                            afterRotationCommitted("retracting the give-up alert") {
                                VpnNotifications.cancelFailoverBlackholed(this@XrayVpnService)
                            }
                            afterRotationCommitted("refreshing the ongoing notification") {
                                updateNotification(localizedString(R.string.vpn_status_connected))
                            }
                            // OBSERVED, not consumed. The marker stays armed until the replay
                            // coroutine actually runs, so a leave-foreground callback queued ahead
                            // of it can still withdraw the kill — which is the whole reason a
                            // rotation no longer parks the tunnel in PAUSED for an app that opened
                            // and closed while the switch was in flight. See replayDeferredKill.
                            pendingKillLabel != null
                        }
                        // ---- POST-COMMIT, off the lock ----
                        // Same rule as inside the block: none of this may reach the outer
                        // `catch (error: Throwable)`, for the reason recorded at the commit above.
                        //
                        // Guarded per step rather than as one block, deliberately: the last two are
                        // not cosmetic. Skipping applyFailoverPreferences leaves the watchdog dead
                        // for the rest of the session, and skipping the replay silently drops a
                        // kill-switch event the user asked for. A single shared guard would let a
                        // throw in the first, most trivial step take both of those out.
                        //
                        // The app's notion of "active profile" MUST follow, or the UI, the QS tile,
                        // and the next manual reconnect all still point at the dead server. It is
                        // also what a system-initiated start reads: resolveActiveAndStart (always-on
                        // / boot) brings up whatever ActiveProfileRepository names, so without this
                        // an always-on restart would return to the server failover just rotated off.
                        // NOT START_REDELIVER_INTENT, though — a redelivered intent carries the
                        // original EXTRA_PROFILE_ID and StartCommandDecision.decide routes it by
                        // that, never through the active profile.
                        // The OWNERSHIP RE-CHECK is not redundant with the guard around it:
                        // afterRotationCommitted is a try/catch, which answers "did this throw",
                        // never "does this coroutine still own the session". This step runs OFF the
                        // lock, so a stop — or a stop plus a whole new session — can have landed
                        // since the commit, and this is a write to PROCESS-GLOBAL state that
                        // outlives the process: resolveActiveAndStart reads exactly this value, so
                        // a superseded rotation would point the UI, the QS tile and the next
                        // always-on/boot start at a server the current session is not using. Same
                        // discipline as every other mutation in this method — re-check under
                        // `lock`, and do the write under the SAME acquisition so the check cannot
                        // go stale between the two. setActiveProfileId writes via apply(), so this
                        // holds no disk I/O under the lock.
                        afterRotationCommitted("recording the new active profile") {
                            synchronized(lock) {
                                if (isCurrentSessionLocked(session.epoch)) {
                                    ActiveProfileRepository.setActiveProfileId(
                                        this@XrayVpnService,
                                        next.id,
                                    )
                                } else {
                                    LogRepository.append(
                                        "Failover: superseded rotation is not recording " +
                                            "${next.name} as the active profile"
                                    )
                                }
                            }
                        }
                        afterRotationCommitted("posting the switched-server notice") {
                            VpnNotifications.postFailover(this@XrayVpnService, current.name, next.name)
                        }
                        afterRotationCommitted("restarting the health monitor") {
                            applyFailoverPreferences(authoritativeFailoverSettings(), session.epoch)
                        }
                        if (hasDeferredKill) {
                            afterRotationCommitted("replaying the deferred kill") {
                                replayDeferredKill(session.epoch)
                            }
                        }
                    }
                    .onFailure { error ->
                        synchronized(lock) {
                            if (ownsTunnelTransitionLocked(session.epoch, SessionTunnelState.ROTATING)) {
                                // INSIDE the ownership check, like every other mutation here. This
                                // set is per-session episode state, and getById / resolve /
                                // bringUpTunnel all ran off-lock above — so a stop+restart in that
                                // window would otherwise let this old-epoch failure blacklist a
                                // server in the NEW session's episode, skipping a server that is
                                // perfectly healthy for it. Keeping it inside also keeps the retry
                                // correct in the case that matters: this is the branch that hands
                                // control to the recursive rotateTunnel below, and that attempt
                                // needs next.id excluded or it would pick the same dead server
                                // again. A rotation that no longer owns the transition dispatches
                                // no such retry — canReserveRotation refuses it — so it has
                                // nothing to record for.
                                episodeFailedIds = FailoverEpisodeDecision.recordBringUpFailure(
                                    failedIds = episodeFailedIds,
                                    candidateId = next.id,
                                )
                                // bringUpTunnel can fail AFTER establish() (e.g. startXray threw),
                                // leaving a real fd with an indeterminate Xray behind it. Drop it
                                // and clear tunInterfaceKind, so a give-up cannot mistake that
                                // half-built fd for either a working tunnel or an unread containment.
                                tearDownTunnelLocked()
                                // Re-open the bridge before handing control to the next candidate.
                                // Idempotent by shouldEstablishRotationBridge, and BOTH answers are
                                // load-bearing here: a bring-up that died before establish() still
                                // holds the bridge from the teardown block, and must keep the same
                                // fd rather than churn it; one that died after establish() had
                                // already released it at the handover, so this is what covers the
                                // recursive retry below — which re-reads the DB and re-resolves the
                                // pool OFF-lock before it reaches its own teardown block.
                                //
                                // Ordered BEFORE the CONNECTED write on purpose: the predicate is
                                // ROTATING-only, which is what confines the bridge to a reserved
                                // rotation. The two writes have no reader between them under this
                                // lock, so the swap is behaviour-preserving for everything else.
                                // If the bridge cannot be established, abort into give-up HERE —
                                // still ROTATING — rather than returning to CONNECTED and recursing
                                // through an uncovered rebuild.
                                val bridgeRequired = shouldEstablishRotationBridge(
                                    hasTunnel = tunInterface != null,
                                    hasRotationBridge = rotationBridgeInterface != null,
                                    tunnelState = sessionTunnelState,
                                )
                                if (bridgeRequired) {
                                    establishRotationBridgeLocked()
                                }
                                if (shouldAbortRotationForMissingBridge(
                                        bridgeRequired = bridgeRequired,
                                        bridgeHeld = rotationBridgeInterface != null,
                                    )
                                ) {
                                    LogRepository.append(
                                        "Failover: rotation bridge could not be re-established; " +
                                            "aborting the uncovered rebuild into give-up"
                                    )
                                    giveUpRotationLocked(
                                        session.epoch,
                                        "rotation bridge could not be established",
                                    )
                                    return@launch
                                }
                                // Return to CONNECTED so the next attempt can reserve the transition.
                                sessionTunnelState = SessionTunnelState.CONNECTED
                                // Roll the profile back to the last one that actually connected.
                                // currentProfileId is what reviveTunnel brings up, so leaving it on
                                // a server we just proved dead makes a kill-switch revive fail and
                                // stopVpn take the whole tunnel down. It also keeps this in step
                                // with ActiveProfileRepository, which only advances on success.
                                currentProfileId = session.profileId
                            }
                        }
                        LogRepository.append("Failover: ${next.name} failed to come up: ${error.message}")
                        rotateTunnel(session.epoch)   // try the next candidate, still under the cap
                    }
            } catch (ce: CancellationException) {
                // Structured-concurrency cancellation (tunnelOpScope.cancel() in onDestroy) must
                // propagate rather than be reported as a rotation failure. Like reviveTunnel and
                // unlike killTunnel, this body suspends (getById/resolve), so it CAN observe a CE.
                throw ce
            } catch (error: Throwable) {
                failRotation(sessionEpoch, "rotateTunnel failed: ${error.message}")
            }
        }
    }

    /**
     * Give up on rotation, FAIL-CLOSED.
     *
     * "Just keep the tunnel established" does not hold on the path that matters: the all-servers-
     * dead case tears the TUN down *before* bring-up fails, so a give-up can land with
     * `tunInterface == null` and hand the user's traffic straight back to the clear network —
     * and whether that happens would depend on where bring-up died (after `establish()` = contained,
     * before = exposed), which is worse than either consistent answer. So when this session should
     * own a tunnel and has none, re-establish a BLACKHOLE one: same routes and captured apps, no
     * protector, no Xray — packets enter an fd nobody reads and are dropped.
     *
     * Note the DELIBERATE disagreement between the two "states" this leaves behind:
     * [sessionTunnelState] returns to CONNECTED because that is mechanically required — a rotation
     * reserves from CONNECTED, so the re-arm timer could never try again otherwise — while the
     * user-facing [LogRepository] connection state becomes BLACKHOLED (or ERROR when nothing could
     * be contained), which is what the UI and the tile show.
     *
     * The three outcomes are reported DIFFERENTLY on every user-facing surface. In particular the
     * uncontained one must never inherit the reassuring "your connection is paused on purpose"
     * copy — that would tell a user their traffic is safe at the exact moment it is not.
     *
     * **This does not always leave the service running.** Under "disconnect now, stop if the re-arm
     * fails", an UNPROTECTED outcome gets exactly one automatic recovery attempt (a rotation driven
     * from [scheduleFailoverRearmLocked], not a monitor restart — there is no tunnel for a probe to
     * test). If a second give-up is still uncontained, [shouldStopServiceOnGiveUp] fires and this
     * method calls [stopVpn]: an honest off state beats a service that is running and protecting
     * nothing while its own notification tells the user to reconnect. The two contained outcomes
     * never stop the service.
     *
     * Caller must hold `lock`.
     */
    private fun giveUpRotationLocked(sessionEpoch: Long, reason: String) {
        LogRepository.append("Failover: giving up ($reason)")
        // A rotation has THREE exits, and this is the third. The two that commit CONNECTED replay a
        // kill deferred during the rotation; a give-up must DROP it — replaying it up to
        // rotationWindowMs later would tear down a just-restored tunnel and blame an app the user
        // closed long ago. Cleared HERE, in the one funnel every give-up passes through, so no exit
        // below (including the stand-down return) can leave the marker armed.
        val deferredKillLabel = pendingKillLabel.also { pendingKillLabel = null }
        if (sessionTunnelState == SessionTunnelState.ROTATING) {
            sessionTunnelState = SessionTunnelState.CONNECTED
        }
        episodeFailedIds = emptySet()
        stopFailoverMonitorLocked()
        reconcileScreenReceiverLocked(sessionEpoch)

        if (sessionTunnelState != SessionTunnelState.CONNECTED) {
            // Another owner holds this session's tunnel — most importantly the kill-switch's PAUSED
            // state, whose compliance contract is literally "no tunnel must exist". Establishing a
            // blackhole (or overwriting the PAUSED connection state) here would break that outright.
            LogRepository.append(
                "Failover: tunnel is $sessionTunnelState; leaving it to its owner"
            )
            // A bridge would break that owner's contract exactly as a blackhole would, and this
            // stand-down is the one give-up exit that establishes nothing — so nothing downstream
            // would ever adopt it. Backstop rather than a live path: killTunnel already releases the
            // bridge on its way into PAUSED, and stopVpn on its way out of the session.
            releaseRotationBridgeLocked("standing down to the $sessionTunnelState tunnel's owner")
            scheduleFailoverRearmLocked(sessionEpoch, retryByRotation = false)
            announceDroppedDeferredKillLocked(deferredKillLabel)
            return
        }

        // heldKind is captured BEFORE the containment step. Within one give-up that ordering keeps
        // an adopted bridge classified CONTAINED_BY_BLACKHOLE (adoption writes tunInterface and
        // retags the kind). Across give-ups the kind must stay honest: after a blackhole/adopt,
        // tunInterface holds an unread fd tagged UNREAD_CONTAINMENT — a boolean `!= null` would
        // mislabel the next give-up as CONTAINED_BY_LIVE_TUNNEL.
        val heldKind = tunInterfaceKind
        val blackholeEstablished = when (
            containmentForGiveUp(
                hasTunnel = heldKind != TunInterfaceKind.NONE,
                hasRotationBridge = rotationBridgeInterface != null,
                tunnelState = sessionTunnelState,
            )
        ) {
            // The bridge IS the blackhole this give-up would otherwise build — same builder, same
            // captured apps, no protector, no Xray — so adopting it is both the honest answer and
            // the only one that does not strand an fd.
            GiveUpContainment.ADOPT_ROTATION_BRIDGE -> adoptRotationBridgeLocked()
            GiveUpContainment.ESTABLISH_BLACKHOLE -> establishBlackholeTunnelLocked()
            GiveUpContainment.NONE -> false
        }

        val outcome = classifyGiveUpOutcome(heldKind, blackholeEstablished)

        if (shouldStopServiceOnGiveUp(outcome, unprotectedRetryConsumed)) {
            // The one automatic recovery attempt has been spent and traffic is STILL not contained.
            // Leaving a service running that cannot protect anything — while its own copy tells the
            // user to reconnect — is the dishonest option. Land in the real off state instead.
            LogRepository.append(
                "Failover: recovery attempt also failed to bring up a tunnel; stopping the VPN"
            )
            giveUpOutcome = null
            unprotectedRetryConsumed = false
            unprotectedEpisodeSinceMs = null
            // Both surfaces, because stopVpn clears the foreground notification: the in-app error
            // for a user who is looking, the 1102 error notification (its own id, survives
            // stopForeground) for one who is not.
            LogRepository.emitError(R.string.vpn_failover_stopped_error)
            postErrorNotification(R.string.vpn_failover_stopped_error)
            stopVpn(expectedSessionEpoch = sessionEpoch)
            // After stopVpn there is no tunnel, so this is a no-op by rule rather than by omission:
            // the VPN really is off for the kill-listed app, which is what the deferred kill wanted.
            announceDroppedDeferredKillLocked(deferredKillLabel)
            return
        }

        giveUpOutcome = outcome
        // Recorded HERE, beside the state it describes, because it must outlive giveUpOutcome: the
        // disable branch releases that marker while leaving the state BLACKHOLED, and a repost
        // after that must not fall back to a line describing the other outcome's packet truth.
        giveUpLine = giveUpOngoingLine(outcome)
        // Same recorded answer the 1101 line uses — publish BEFORE the state so a collector that
        // reacts to BLACKHOLED already sees the matching line (disable later clears giveUpOutcome
        // but must not clear this).
        LogRepository.setGiveUpLine(giveUpLine)
        // State first, then the notifications — same ordering discipline as killTunnel.
        LogRepository.setConnectionState(connectionStateForGiveUp(outcome))
        when (outcome) {
            FailoverGiveUpOutcome.CONTAINED_BY_LIVE_TUNNEL -> {
                LogRepository.append(
                    "Failover: no server to switch to; the current tunnel is still up and traffic " +
                        "stays inside it"
                )
                updateNotification(localizedString(R.string.vpn_status_no_response))
                VpnNotifications.postFailoverNoResponse(this)
            }
            FailoverGiveUpOutcome.CONTAINED_BY_BLACKHOLE -> {
                LogRepository.append(
                    "Failover: traffic is held in an unread tunnel; nothing leaks to the open network"
                )
                updateNotification(localizedString(R.string.vpn_status_blackholed))
                VpnNotifications.postFailoverBlackholed(this)
            }
            FailoverGiveUpOutcome.UNPROTECTED -> {
                LogRepository.append(
                    "Failover: WARNING - no tunnel could be established, traffic is NOT contained"
                )
                // AGENTS.md: user-visible errors go through LogRepository, not only the log buffer.
                // The Logs screen is not where a user learns their traffic just went clear.
                LogRepository.emitError(R.string.vpn_failover_unprotected_error)
                updateNotification(localizedString(R.string.vpn_status_unprotected))
                VpnNotifications.postFailoverUnprotected(this)
            }
        }

        // Scheduled LAST, and only on the paths that keep the service alive. An unprotected give-up
        // arms its single recovery attempt here but does NOT spend it: unprotectedRetryConsumed is
        // written by rotateTunnel's reservation, i.e. by an attempt that actually happened. Marking
        // it here spent the retry for a session that was merely paused when the timer fired, which
        // left shouldStopServiceOnGiveUp unable to ever fire. The instant below is the start of the
        // deferral deadline that bounds the re-arming that replaces it.
        val retryByRotation = outcome == FailoverGiveUpOutcome.UNPROTECTED
        val sinceMs = System.currentTimeMillis()
        if (retryByRotation) unprotectedEpisodeSinceMs = sinceMs
        scheduleFailoverRearmLocked(
            sessionEpoch,
            retryByRotation = retryByRotation,
            unprotectedSinceMs = sinceMs,
        )
        announceDroppedDeferredKillLocked(deferredKillLabel)
    }

    /**
     * Tells the user that a kill-switch event deferred during a rotation was DROPPED because no
     * server could be reached — so the listed app is still going through the tunnel they asked to
     * have torn down. A silently non-functioning kill-switch is the failure mode this closes.
     *
     * Posts nothing when [deferredKillNoticeLabel] returns null: no kill was deferred, or the
     * give-up left no tunnel at all (in which case the app is not behind a VPN and the claim would
     * be false — those outcomes report themselves on their own surfaces).
     *
     * Caller must hold `lock`, and must have already settled the session state: same ordering
     * discipline as [killTunnel] — state first, then notifications.
     */
    private fun announceDroppedDeferredKillLocked(deferredKillLabel: String?) {
        val label = deferredKillNoticeLabel(
            pendingKillLabel = deferredKillLabel,
            tunnelStillUp = tunInterface != null,
        ) ?: return
        LogRepository.append(
            "Kill-switch: dropping the kill deferred for $label — no server could be reached, so " +
                "the tunnel was not torn down for it"
        )
        VpnNotifications.postKillSwitchNotApplied(this, label)
    }

    /**
     * Clears a give-up state once the tunnel is demonstrably passing traffic again.
     *
     * Driven by [TunnelHealthMonitor]'s recovery callback, because the give-up state would
     * otherwise be able to outlive the condition it describes: the no-candidate and thrash-cap
     * give-ups can both land on a tunnel that is merely having a bad minute, and the monitor only
     * ever reported failure — so the re-armed monitor would probe successfully, never fire again,
     * and the user would be left staring at an error state over a working connection until they
     * stopped and restarted the VPN by hand.
     */
    private fun clearGiveUpStateOnRecovery(sessionEpoch: Long) {
        synchronized(lock) {
            if (!isCurrentSessionLocked(sessionEpoch)) return
            if (sessionTunnelState != SessionTunnelState.CONNECTED) return
            episodeFailedIds = FailoverEpisodeDecision.clearOnHealthyProbe(episodeFailedIds)
            if (giveUpOutcome == null) return
            // Load-bearing: after an UNPROTECTED give-up there is NO tunnel, so the probe travels
            // the clear network and succeeds for the wrong reason. Clearing on that would announce
            // CONNECTED with no VPN at all — the exact lie this fix round exists to remove. That
            // state has no automatic recovery by design; it needs the user action the notification
            // and the in-app error both ask for.
            if (tunInterface == null) return
            LogRepository.append("Failover: tunnel is passing traffic again; clearing the give-up state")
            giveUpOutcome = null
            unprotectedRetryConsumed = false
            unprotectedEpisodeSinceMs = null
            // The episode is demonstrably over, so the sliding thrash window starts clean too.
            //
            // KNOWN CONSEQUENCE, accepted rather than overlooked: after a THRASH-CAP give-up this
            // hands the session a full rotation budget back INSIDE the window the cap was meant to
            // bound. The cap is a rate limit, and a flapping tunnel produces exactly the healthy
            // probe that resets it — so one success is weaker evidence here than it is for the
            // no-candidate give-up, where the pool really was exhausted and a working tunnel really
            // does end the episode.
            //
            // Why it is left this way rather than given an exception:
            //   * reaching this line early takes a USER ACTION. A give-up stops the monitor, so
            //     onHealthy cannot fire again until something restarts it, and the only restart
            //     that arrives before the re-arm timer (which clears the window itself when it
            //     fires) is a failover-settings save re-emitting on the settings flow. That is the
            //     same "explicit try again" reading applyFailoverPreferences already gives its own
            //     unconditional reset on disable;
            //   * an exception would have to record WHICH give-up reason produced the state — a
            //     second marker paired with giveUpOutcome, i.e. exactly the extra disarm site whose
            //     coupling produced the running-but-unconnectable defect on the disable path;
            //   * the blast radius is maxRotations extra rotations in one window, each bridged by
            //     an unread TUN and each announced. It is a churn cost, never an exposure one.
            rotationAttempts = emptyList()
            clearGiveUpLineLocked()
            LogRepository.setConnectionState(VpnConnectionState.CONNECTED)
            updateNotification(localizedString(R.string.vpn_status_connected))
            VpnNotifications.cancelFailoverBlackholed(this)
        }
    }

    /**
     * Re-arm failover once the thrash window has fully elapsed, so a network that recovers on its
     * own self-heals without a manual reconnect.
     *
     * This lives in the single funnel every give-up passes through: the thrash-cap and no-candidate
     * give-ups call [giveUpRotationLocked] directly and never go through [failRotation], so wiring
     * the timer there would leave the common "all servers dead" case permanently disarmed.
     *
     * [retryByRotation] is set only for an UNPROTECTED give-up, where restarting the health monitor
     * would achieve nothing: there is NO tunnel, so its probe travels the clear network, succeeds
     * for the wrong reason, and can never ask for a rotation. That state's one recovery attempt has
     * to be a rotation driven directly from here. Every other give-up leaves a tunnel in place, so
     * the monitor is the right thing to re-arm.
     *
     * [unprotectedSinceMs] is meaningful only when [retryByRotation] is set: it is the instant the
     * UNPROTECTED episode began, and it is carried unchanged through every re-arm so
     * [unprotectedRetryAction]'s deferral deadline measures the whole episode rather than restarting
     * with each timer.
     *
     * Caller must hold `lock`.
     */
    private fun scheduleFailoverRearmLocked(
        sessionEpoch: Long,
        retryByRotation: Boolean,
        unprotectedSinceMs: Long = System.currentTimeMillis(),
    ) {
        val windowMs = authoritativeFailoverSettings().rotationWindowMs
        failoverRearmJob?.cancel()
        failoverRearmJob = serviceScope.launch {
            delay(windowMs)
            // BACKSTOP for the cancel in applyFailoverPreferences: this timer was scheduled under
            // one set of preferences and fires up to an hour later, possibly concurrently with the
            // settings edit that disables the feature. Re-read the flow rather than the captured
            // settings — the user may have edited them during the wait.
            val proceed = synchronized(lock) {
                val ok = shouldFireFailoverRetry(
                    failoverEnabled = authoritativeFailoverSettings().enabled,
                    isCurrentSession = isCurrentSessionLocked(sessionEpoch),
                )
                if (ok) rotationAttempts = emptyList()
                ok
            }
            if (!proceed) {
                LogRepository.append(
                    "Failover: retry timer stood down (feature disabled or session ended)"
                )
                return@launch
            }
            if (!retryByRotation) {
                // Re-checks epoch, running and CONNECTED internally, so a stale timer is a no-op.
                applyFailoverPreferences(authoritativeFailoverSettings(), sessionEpoch)
                return@launch
            }
            // ---- The UNPROTECTED recovery ----
            // Dispatching rotateTunnel unconditionally is what broke the bound: from any state but
            // CONNECTED it silently bails, and with the retry already marked spent nothing was left
            // to end a service that owns no TUN. Decide instead, under the lock, from the state
            // that actually determines whether an attempt is possible.
            val action = synchronized(lock) {
                if (!isCurrentSessionLocked(sessionEpoch)) return@launch
                val decided = unprotectedRetryAction(
                    tunnelState = sessionTunnelState,
                    unprotectedSinceMs = unprotectedSinceMs,
                    now = System.currentTimeMillis(),
                    rotationWindowMs = authoritativeFailoverSettings().rotationWindowMs,
                )
                if (decided == UnprotectedRetryAction.DEFER) {
                    LogRepository.append(
                        "Failover: recovery rotation cannot be attempted yet (tunnel is " +
                            "$sessionTunnelState); re-arming without spending the retry"
                    )
                    // Re-armed from inside the job this call cancels. Safe: nothing suspends
                    // between here and the end of this coroutine, and the replacement job is a
                    // fresh child of serviceScope rather than of this one.
                    scheduleFailoverRearmLocked(
                        sessionEpoch,
                        retryByRotation = true,
                        unprotectedSinceMs = unprotectedSinceMs,
                    )
                }
                decided
            }
            when (action) {
                // rotateTunnel re-checks epoch + CONNECTED under the lock itself and spends the
                // retry only once it has reserved the transition, so a state change in this gap
                // costs another re-arm rather than the recovery.
                UnprotectedRetryAction.ATTEMPT ->
                    rotateTunnel(sessionEpoch, unprotectedRecoverySinceMs = unprotectedSinceMs)
                UnprotectedRetryAction.DEFER -> Unit
                UnprotectedRetryAction.STOP_SERVICE -> {
                    // The episode has outlasted its deferral deadline without ever becoming
                    // rotatable. A foreground service that owns no TUN, protects nothing and
                    // cannot even try must land in an honest OFF state rather than persist. Both
                    // surfaces, as in giveUpRotationLocked's stop branch: stopVpn clears the
                    // foreground notification, so the 1102 error notification is what reaches a
                    // user who is not looking at the app.
                    LogRepository.append(
                        "Failover: no tunnel, and no chance to retry for " +
                            "$UNPROTECTED_UNATTEMPTED_RETRY_WINDOWS rotation windows; " +
                            "stopping the VPN"
                    )
                    LogRepository.emitError(R.string.vpn_failover_stopped_error)
                    postErrorNotification(R.string.vpn_failover_stopped_error)
                    stopVpn(expectedSessionEpoch = sessionEpoch)
                }
            }
        }
    }

    /**
     * Runs one settle-up [step] that follows a COMMITTED tunnel transition ([transition] names it
     * in the log), converting a throw into a log line instead of letting it escape.
     *
     * ONE body, TWO transitions — see [afterRotationCommitted] and [afterReviveCommitted] for the
     * distinct consequence each of them is protecting against. What both share is the shape: the
     * transition's own `catch (Throwable)` funnel is still armed while its follow-up work runs, and
     * that funnel was written for a transition that has NOT committed. Both callers must therefore
     * wrap every post-commit step, and both must do it PER STEP: the steps have independent
     * consequences, so a single shared guard would let a throw in the first, most trivial one take
     * out the rest.
     *
     * `CancellationException` still propagates — structured-concurrency cancellation
     * (`tunnelOpScope.cancel()` in `onDestroy`) is not a step failure and must not be swallowed,
     * the same rule both surrounding handlers follow.
     *
     * Safe to call while holding `lock`: a plain try/catch whose only side effect is
     * `LogRepository.append`, taken under this lock in a dozen places here. It never suspends and
     * never takes a lock of its own.
     */
    private fun afterTransitionCommitted(transition: String, step: String, block: () -> Unit) {
        try {
            block()
        } catch (ce: CancellationException) {
            throw ce
        } catch (error: Throwable) {
            LogRepository.append(
                "$transition: $step failed after the transition committed: ${error.message}"
            )
        }
    }

    /**
     * Post-commit guard for [rotateTunnel]. An escape here would RECLASSIFY a success:
     * `rotateTunnel`'s body is wrapped in `catch (Throwable) -> failRotation`, which funnels into
     * `giveUpRotationLocked`; after the commit that runs with `sessionTunnelState == CONNECTED` and
     * a live fd, so it classifies `CONTAINED_BY_LIVE_TUNNEL` and writes `BLACKHOLED` over the
     * healthy tunnel the rotation just restored.
     */
    private fun afterRotationCommitted(step: String, block: () -> Unit) =
        afterTransitionCommitted("Failover rotation", step, block)

    /**
     * Post-commit guard for [reviveTunnel]. An escape here would SILENCE a failure rather than
     * reclassify a success: the counterpart funnel, `failRevive`, demands `REVIVING`, and the
     * commit has already left it — so it logs nothing, reports nothing and stops nothing, and the
     * session is left with a live tunnel, a dead watchdog and a dropped kill-switch event with no
     * trace of why.
     */
    private fun afterReviveCommitted(step: String, block: () -> Unit) =
        afterTransitionCommitted("Kill-switch revive", step, block)

    private fun failRotation(sessionEpoch: Long, logMessage: String) {
        synchronized(lock) {
            if (!isCurrentSessionLocked(sessionEpoch)) return
            LogRepository.append(logMessage)
            giveUpRotationLocked(sessionEpoch, "rotation error")
        }
    }

    /**
     * Establishes a TUN with no reader attached: same session name, MTU, addresses, default routes,
     * DNS servers and split-tunnel plan as a real bring-up, but NO protector registration and NO
     * Xray. Packets enter the fd and are dropped — which is what makes the give-up genuinely
     * fail-closed rather than fail-closed-if-you-are-lucky, and what lets a rotation switch servers
     * without ever handing traffic back to the clear network.
     *
     * ONE body, TWO users — the give-up blackhole and the rotation bridge. Deliberately not copied:
     * a drifted second copy would capture a different app set than the tunnel it replaced, which is
     * a silent leak of exactly the apps the user tunneled. [label] only names the caller in the log.
     *
     * Returns the fd, or null when nothing could be established (the caller then degrades to "no
     * containment"). Caller must hold `lock`.
     */
    private fun establishUnreadTunnelLocked(label: String): ParcelFileDescriptor? {
        return try {
            // INSIDE the try on purpose. Both callers are reachable from rotateTunnel's
            // `catch (Throwable)` — the give-up directly, the bridge via a rotation that re-enters
            // teardown — and a throw raised inside a catch block escapes that try/catch entirely,
            // landing uncaught on a SupervisorJob with no handler, i.e. process death with the VPN
            // up. Both call sites are guarded by a pure predicate under the same held lock, but a
            // contract violation must degrade to "uncontained", never to a crash.
            check(tunInterface == null && rotationBridgeInterface == null) {
                "Cannot establish an unread TUN while this session already owns one"
            }
            val builder = Builder()
                .setSession(localizedString(R.string.app_name))
                .setMtu(sessionTuning.core.mtu)
                .addAddress("10.7.0.1", 32)
                .addAddress("fd00:1:fd00:1::1", 128)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                // Mirrors bringUpTunnel: DNS is aimed INTO the tun, so resolver traffic is dropped
                // here too instead of falling back to the underlying network's resolvers.
                .addDnsServer("1.1.1.1")
                .also { if (sessionTuning.core.ipv6) it.addDnsServer("2606:4700:4700::1111") }

            // Same plan as a real bring-up, so exactly the same apps stay captured: anything the
            // user split OUT keeps the direct route it already had while connected, and everything
            // else keeps riding the tun — now into the blackhole.
            val splitPrefs = SplitTunnelRepository.load(this)
            val plan = SplitTunnelPlanner.plan(splitPrefs.mode, splitPrefs.packages, packageName)
            plan.allowedPackages.forEach { pkg ->
                try {
                    builder.addAllowedApplication(pkg)
                } catch (_: PackageManager.NameNotFoundException) {
                    LogRepository.append("Failover: $label TUN skipped missing package: $pkg")
                }
            }
            plan.disallowedPackages.forEach { pkg ->
                try {
                    builder.addDisallowedApplication(pkg)
                } catch (_: PackageManager.NameNotFoundException) {
                    LogRepository.append("Failover: $label TUN skipped missing package: $pkg")
                }
            }

            val pfd = builder.establish()
            if (pfd == null) {
                LogRepository.append("Failover: $label establish() returned null")
            } else {
                LogRepository.append("Failover: $label TUN established with fd=${pfd.fd}")
            }
            pfd
        } catch (error: Throwable) {
            LogRepository.append("Failover: $label TUN could not be established: ${error.message}")
            null
        }
    }

    /**
     * Give-up containment: takes ownership of the unread TUN as [tunInterface], because from here on
     * it IS this session's tunnel. Returns whether traffic is now actually contained.
     *
     * Caller must hold `lock`, and must only call this while [tunInterface] is null (see
     * [containmentForGiveUp], which is the only thing that authorises it).
     */
    private fun establishBlackholeTunnelLocked(): Boolean {
        val pfd = establishUnreadTunnelLocked("blackhole") ?: return false
        tunInterface = pfd
        tunInterfaceKind = TunInterfaceKind.UNREAD_CONTAINMENT
        return true
    }

    /**
     * Opens the rotation bridge so no packet sees the clear network while a rotation rebuilds the
     * tunnel. Returns whether a bridge fd is now held.
     *
     * On failure the caller must NOT continue the uncovered rebuild — see
     * [shouldAbortRotationForMissingBridge]. That aborts into give-up (blackhole / UNPROTECTED)
     * rather than reopening the clear-network window for seconds. Never strands an fd: a null
     * return leaves [rotationBridgeInterface] untouched.
     *
     * Caller must hold `lock`, and must have checked [shouldEstablishRotationBridge].
     */
    private fun establishRotationBridgeLocked(): Boolean {
        val pfd = establishUnreadTunnelLocked("rotation bridge") ?: return false
        rotationBridgeInterface = pfd
        return true
    }

    /**
     * Hands the bridge to the give-up funnel: the fd it is already holding is byte-for-byte the
     * blackhole a give-up would build, so building a second one would strand this one.
     *
     * Moves the fd into [tunInterface] as [TunInterfaceKind.UNREAD_CONTAINMENT] and returns whether
     * traffic is contained. The caller reads [tunInterfaceKind] BEFORE calling this — that ordering
     * (plus keeping the kind honest after this write) is what keeps the adopted bridge classified
     * `CONTAINED_BY_BLACKHOLE` instead of inheriting the live tunnel's "still proxying" copy.
     *
     * Caller must hold `lock`, and must have checked [containmentForGiveUp].
     */
    private fun adoptRotationBridgeLocked(): Boolean {
        val pfd = rotationBridgeInterface ?: return false
        rotationBridgeInterface = null
        tunInterface = pfd
        tunInterfaceKind = TunInterfaceKind.UNREAD_CONTAINMENT
        LogRepository.append("Failover: give-up adopted the rotation bridge (fd=${pfd.fd})")
        return true
    }

    /**
     * Closes the bridge if one is open. Idempotent, and every exit from the rotation gap must reach
     * this or [adoptRotationBridgeLocked] — an unreleased bridge is a leaked VPN interface that goes
     * on capturing every tunneled app's traffic into an fd nobody reads.
     *
     * The field is cleared BEFORE the close so a throwing `close()` cannot leave a stale reference
     * behind, which would then block the next `establish()` on its own precondition check.
     *
     * Caller must hold `lock`.
     */
    private fun releaseRotationBridgeLocked(reason: String) {
        val pfd = rotationBridgeInterface ?: return
        rotationBridgeInterface = null
        try {
            pfd.close()
        } catch (error: Throwable) {
            LogRepository.append("Failover: rotation bridge close warning: ${error.message}")
        }
        LogRepository.append("Failover: rotation bridge released ($reason)")
    }

    /** Clears the paired service/repository line once no BLACKHOLED give-up describes the session. */
    private fun clearGiveUpLineLocked() {
        giveUpLine = null
        LogRepository.clearGiveUpLine()
    }

    private inner class KillSwitchListener(
        private val sessionEpoch: Long,
    ) : ForegroundAppMonitor.Listener {
        override fun onControlledAppForeground(packageName: String) {
            val label = runCatching {
                val pm = packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
            }.getOrElse { packageName }
            killTunnel(sessionEpoch, label)
        }

        override fun onControlledAppLeftForeground() {
            reviveTunnel(sessionEpoch)
        }
    }

    private fun applyKillSwitchPreferences(
        prefs: KillSwitchRepository.Preferences,
        sessionEpoch: Long,
    ) {
        synchronized(lock) {
            if (!isCurrentSessionLocked(sessionEpoch)) return
            val shouldRun = prefs.enabled && prefs.packages.isNotEmpty()

            if (!shouldRun) {
                val wasPaused = LogRepository.connectionState.value == VpnConnectionState.PAUSED
                killSwitchMonitor?.stop()
                killSwitchMonitor = null
                // Reconcile instead of unregistering outright: the receiver is now shared with the
                // failover monitor, and an unconditional unregister here would tear it out from
                // under a still-running failover session.
                reconcileScreenReceiverLocked(sessionEpoch)
                // Void any kill deferred during an in-flight revive. The feature is now OFF, so
                // replaying it when the revive commits would park the tunnel PAUSED for a feature the
                // user just disabled — and with the monitor gone, no left-foreground event would ever
                // revive it, stranding the connection until a manual stop/start.
                pendingKillLabel = null
                // If the user disabled the feature (or cleared all packages) while
                // the tunnel was paused, restore the tunnel. Without this the user
                // has to manually stop+restart the VPN to recover.
                if (wasPaused) {
                    reviveTunnel(sessionEpoch)
                }
                return
            }

            if (killSwitchMonitor == null) {
                val source = AndroidUsageStatsEventSource(this)
                val monitor = UsageStatsForegroundAppMonitor(source)
                killSwitchMonitor = monitor
                monitor.start(prefs.packages, KillSwitchListener(sessionEpoch))
                reconcileScreenReceiverLocked(sessionEpoch)
                LogRepository.append("Kill-switch monitor started with ${prefs.packages.size} package(s)")
            } else {
                killSwitchMonitor?.updatePackages(prefs.packages)
            }
        }
    }

    /**
     * Starts, rebuilds, or stops the health monitor to match the current preferences and tunnel
     * state. Mirrors [applyKillSwitchPreferences] — including its stale-session discipline: a
     * superseded epoch returns WITHOUT touching the monitor, so a late emission from an already
     * cancelled observer can never stop the CURRENT session's monitor.
     *
     * The monitor runs ONLY in CONNECTED: in PAUSED there is no tunnel, so every probe would fail
     * and we would "rotate" a tunnel the kill-switch deliberately tore down.
     *
     * **This no longer caches [settings] as "the current settings".** It used to, and every other
     * decision in this file then had a choice between that cache and the flow — one question with
     * two answers, in a class where the flow is updated synchronously by `save()` and the cache
     * asynchronously by the collector below. Everything that DECIDES now calls
     * [authoritativeFailoverSettings]; see its KDoc for the derivation. [settings] stays a parameter
     * because the collector's emission is the natural input to a reconciliation, and the seeded
     * `FailoverPreferences.load(...)` in `startVpn` is what makes the two agree on a process-fresh
     * connect.
     *
     * [failoverMonitorSettings] is NOT that cache and must stay: it records what the live monitor
     * was **constructed from**, which is the past, and is exactly what
     * [failoverMonitorNeedsRebuild] needs to tell a real timing edit from a no-op re-emission.
     */
    private fun applyFailoverPreferences(settings: FailoverSettings, sessionEpoch: Long) {
        synchronized(lock) {
            if (!isCurrentSessionLocked(sessionEpoch)) return

            if (!settings.enabled) {
                // ROOT FIX for a pending re-arm outliving the setting that authorised it. Gated on
                // `enabled` specifically, NOT on the shouldRun check below: shouldRun is also false
                // in PAUSED/ROTATING, and an unrelated settings save during a kill-switch pause
                // must not silently drop a legitimate pending retry. Disabling the feature must.
                //
                // UNCONDITIONAL, and that is CHOSEN rather than overlooked — including for the
                // UNPROTECTED retry, where this timer is now the only thing that can end a service
                // that owns no TUN (see unprotectedRetryAction). Two reasons, both settled
                // elsewhere on this branch:
                //   * a user who switches auto-failover OFF must not be handed an automatic server
                //     rotation, and least of all an automatic VPN shutdown — that is exactly what
                //     shouldFireFailoverRetry exists to veto;
                //   * because it vetoes at the firing point too, keeping the job alive here would
                //     buy nothing: it would fire, stand down, and leave the same state behind.
                // The give-up marker and its 1105 alert DO survive the disable (see
                // shouldReleaseGiveUpOnDisable), so the user is still told they are unprotected and
                // still has both Connect (via shouldRestartForRecovery) and Disconnect. The bound
                // is handed back to them by their own explicit action, not silently dropped.
                failoverRearmJob?.cancel()
                failoverRearmJob = null
                // Unconditional, for the same reason the cancel above is: the re-arm job is the
                // owner of this reset (scheduleFailoverRearmLocked clears the window when its
                // timer fires, so a re-armed episode starts with a clean budget) and we have just
                // cancelled it. Whoever disables the feature must therefore perform the reset the
                // cancelled job would have. Leaving a stale sliding window in place could only
                // ever DENY the first automatic rotation of the next episode — a spurious give-up
                // charged to attempts made before the user intervened. This is outside the release
                // branch below on purpose: it must also run for UNPROTECTED (which no longer
                // releases) and for a disable with no give-up showing at all.
                //
                // CHOSEN, not overlooked: because it is unconditional, **toggling auto-failover off
                // and back on hands the next episode a fresh thrash budget**, even when there was no
                // give-up to release. That is consistent with the other three reset sites
                // (clearGiveUpStateOnRecovery, the re-arm timer firing, session teardown) — each
                // fires on an event after which the recorded attempts no longer describe what the
                // next rotation faces, and an explicit disable is such an event. It does mean a user
                // who toggles the switch repeatedly can rotate more often than maxRotations allows
                // in one window; that takes deliberate repeated action in the settings screen, and
                // each toggle is an explicit "try again", so it is accepted rather than prevented.
                rotationAttempts = emptyList()

                val releasedOutcome = giveUpOutcome
                if (shouldReleaseGiveUpOnDisable(settings.enabled, releasedOutcome)) {
                    // The user switched the feature off, and the re-arm we just cancelled was the
                    // only automatic way out of a CONTAINED give-up. Drop the episode state so
                    // nothing stale survives, and stop the alert claiming a repair is pending.
                    //
                    // UNPROTECTED never reaches here (see shouldReleaseGiveUpOnDisable): its
                    // marker is what keeps shouldRestartForRecovery true, i.e. what keeps Connect
                    // alive at all, and its 1105 alert is the user's only remaining warning that
                    // they are on the clear network. Both must survive the disable.
                    // unprotectedRetryConsumed is therefore left alone too — it records that the
                    // single automatic recovery has been spent, which stays true, and it is
                    // meaningless for the two outcomes that do reach here.
                    //
                    // The TUN is deliberately NOT torn down and the connection state deliberately
                    // STAYS BLACKHOLED. Both are load-bearing:
                    //   * tearing the TUN down would drop the user onto the clear network as a
                    //     side effect of a settings change — the exact thing this feature exists
                    //     to prevent;
                    //   * BLACKHOLED is the honest state (traffic really is held), and it is what
                    //     makes connectAction() offer RECONNECT. Switching to ERROR here would
                    //     offer a plain Connect, which startVpn refuses with "VPN already running"
                    //     because the service is still up — a dead button, one state over.
                    // Reconnect (VpnViewModel.reconnect) is the way back to a live tunnel.
                    giveUpOutcome = null
                    unprotectedRetryConsumed = false
                    unprotectedEpisodeSinceMs = null
                    VpnNotifications.cancelFailoverBlackholed(this)
                    // Two outcomes share the BLACKHOLED state but not the truth: the blackhole
                    // really is holding traffic, while a live tunnel is still proxying and merely
                    // unhealthy. Selecting on the state would tell the second group their
                    // connection is paused when it is not. The UNPROTECTED/null arm is unreachable
                    // now that the predicate excludes it, and stays only for exhaustiveness.
                    when (releasedOutcome) {
                        FailoverGiveUpOutcome.CONTAINED_BY_BLACKHOLE ->
                            LogRepository.emitError(R.string.vpn_failover_disabled_while_blackholed)
                        FailoverGiveUpOutcome.CONTAINED_BY_LIVE_TUNNEL ->
                            LogRepository.emitError(R.string.vpn_failover_disabled_while_degraded)
                        FailoverGiveUpOutcome.UNPROTECTED, null -> Unit
                    }
                }
            }

            // Re-enable after an UNPROTECTED disable: the cancel above dropped the only automatic
            // recovery/stop for a no-TUN session. Restore the re-arm with the original episode
            // instant so unprotectedRetryAction's deferral deadline keeps running. The disable
            // half's reasoning (shouldFireFailoverRetry vetoes a kept-alive job) does not cover
            // this — see shouldRestoreUnprotectedRearm.
            if (shouldRestoreUnprotectedRearm(
                    failoverEnabled = settings.enabled,
                    giveUpOutcome = giveUpOutcome,
                    hasTunnel = tunInterface != null,
                    rearmJobActive = failoverRearmJob?.isActive == true,
                )
            ) {
                val since = unprotectedEpisodeSinceMs ?: System.currentTimeMillis()
                unprotectedEpisodeSinceMs = since
                LogRepository.append(
                    "Failover: restoring UNPROTECTED recovery re-arm after re-enable"
                )
                scheduleFailoverRearmLocked(
                    sessionEpoch,
                    retryByRotation = true,
                    unprotectedSinceMs = since,
                )
            }

            if (!shouldRunFailoverMonitor(
                    enabled = settings.enabled,
                    running = running,
                    tunnelState = sessionTunnelState,
                )
            ) {
                stopFailoverMonitorLocked()
                reconcileScreenReceiverLocked(sessionEpoch)
                return
            }

            // A live monitor bakes interval/timeout/threshold in at construction, so a timing edit
            // can only land by rebuilding it. An UNCHANGED emission must fall through untouched:
            // the settings StateFlow re-emits on every save, and rebuilding on each one would
            // restart the poll cycle continuously so the tunnel is never actually observed. The
            // non-null check is also what stops the observer stacking duplicate monitors — the fix
            // for the stale post-rotation monitor is to CLEAR the field (see rotateTunnel), never
            // to drop this guard.
            if (failoverMonitor != null) {
                if (!failoverMonitorNeedsRebuild(failoverMonitorSettings, settings)) return
                LogRepository.append("Failover: rebuilding monitor for updated probe timings")
                stopFailoverMonitorLocked()
            }

            failoverMonitorSettings = settings
            failoverMonitor = TunnelHealthMonitor(
                // FIXED target, deliberately NOT PingPreferences.targetUrl. It is half of a routing
                // rule: applyRouting carves this exact host through the proxy in every routing mode,
                // and a static rule cannot cover a user-editable target. Reading the Ping Test
                // setting here also inherited its validation gap — that target is only checked for
                // an http:// prefix, so any non-204 URL would fail every probe forever and drive a
                // rotation storm plus a give-up over perfectly healthy servers.
                probe = Http204HealthProbe(ConfigBuilder.HEALTH_PROBE_TARGET_URL, settings.probeTimeoutMs),
                availability = AndroidNetworkAvailability(applicationContext),
                intervalMs = settings.probeIntervalMs,
                failureThreshold = settings.failureThreshold,
            ).also { monitor ->
                // Named arguments deliberately: onHealthy is declared first so existing
                // trailing-lambda callers keep binding to onUnhealthy, which makes a positional
                // call here easy to mis-read.
                monitor.start(
                    onHealthy = { clearGiveUpStateOnRecovery(sessionEpoch) },
                    onUnhealthy = { rotateTunnel(sessionEpoch) },
                )
            }
            LogRepository.append(
                "Failover monitor started (interval=${settings.probeIntervalMs}ms, " +
                    "threshold=${settings.failureThreshold})"
            )
            reconcileScreenReceiverLocked(sessionEpoch)
        }
    }

    /**
     * Stops and forgets the health monitor. `stop()` (not `pausePolling()`) on purpose: pausing
     * preserves the consecutive-failure count, which would trip instantly the next time polling
     * resumes. Caller must hold `lock`.
     */
    private fun stopFailoverMonitorLocked() {
        failoverMonitor?.stop()
        failoverMonitor = null
        failoverMonitorSettings = null
    }

    /**
     * The screen receiver is shared by the kill-switch and failover monitors. Register while EITHER
     * is live, unregister only when NEITHER is — never let one feature's teardown strand the other.
     * [registerScreenReceiver] is already idempotent (`if (screenReceiver != null) return`) and
     * [unregisterScreenReceiver] already tolerates a non-registered receiver, so this is safe to
     * call on every preference change. Caller must hold `lock`.
     */
    private fun reconcileScreenReceiverLocked(sessionEpoch: Long) {
        if (shouldHoldScreenReceiver(
                killSwitchLive = killSwitchMonitor != null,
                failoverLive = failoverMonitor != null,
            )
        ) {
            registerScreenReceiver(sessionEpoch)
        } else {
            unregisterScreenReceiver()
        }
    }

    private fun registerScreenReceiver(sessionEpoch: Long) {
        if (screenReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                // onReceive runs on the main thread. Do NOT take the lifecycle lock here — a connect
                // in flight holds it across the blocking XrayBridge.startXray, which would stall the
                // main thread (ANR risk). Read the lock-free epoch mirror to cheaply reject a stale
                // session, then enqueue the actual monitor mutation onto tunnelOpScope, where it runs
                // under the lock and serializes behind any in-flight kill/revive. The monitor field is
                // re-read and re-checked under the lock there, so this stays race-free.
                if (activeEpochVolatile != sessionEpoch) return
                val action = intent?.action ?: return
                if (action != Intent.ACTION_SCREEN_OFF && action != Intent.ACTION_SCREEN_ON) return
                tunnelOpScope.launch {
                    synchronized(lock) {
                        if (!isCurrentSessionLocked(sessionEpoch)) return@launch
                        // Both monitors share this receiver; each nullable field is independently
                        // null when its feature is off, so this covers every on/off pairing.
                        when (action) {
                            Intent.ACTION_SCREEN_OFF -> {
                                killSwitchMonitor?.pausePolling()
                                failoverMonitor?.pausePolling()
                            }
                            Intent.ACTION_SCREEN_ON -> {
                                killSwitchMonitor?.resumePolling()
                                failoverMonitor?.resumePolling()
                            }
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(receiver, filter)
        screenReceiver = receiver
    }

    private fun unregisterScreenReceiver() {
        screenReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Throwable) {
                // not registered
            }
        }
        screenReceiver = null
    }

    /**
     * Tears the current session down.
     *
     * [stopService] is false for two in-service restart routes: `startVpn`'s UNPROTECTED recovery
     * and ReconnectFlow's marked stop. Both need the SESSION torn down but this service instance
     * kept alive so it can immediately start a fresh one. Calling `stopSelf()` there would schedule
     * our own destruction and `onDestroy` would then tear down the session we just started; skipping
     * `stopForeground` also keeps the FGS promotion continuous across the restart instead of
     * dropping and re-taking it.
     */
    private fun stopVpn(expectedSessionEpoch: Long? = null, stopService: Boolean = true) {
        synchronized(lock) {
            if (expectedSessionEpoch != null && !isCurrentSessionLocked(expectedSessionEpoch)) return

            val shouldStop = running
            running = false
            activeSessionEpoch = null
            activeEpochVolatile = null
            sessionTunnelState = SessionTunnelState.STOPPED
            // Drop any kill-switch event deferred during a revive so it can't replay into a later
            // session (epoch is invalidated above under the same lock). Cleared on every teardown
            // path, including the no-live-session early return below.
            pendingKillLabel = null
            // Same discipline for the failover episode state: a stale thrash count carried into a
            // new session would deny its very first rotation, and stale episode failures would skip
            // servers that are perfectly healthy now.
            rotationAttempts = emptyList()
            episodeFailedIds = emptySet()
            giveUpOutcome = null
            unprotectedRetryConsumed = false
            unprotectedEpisodeSinceMs = null
            // The session is over, so no BLACKHOLED line is true any more. Mirror onto
            // LogRepository so home/tile cannot keep showing a contained-outcome string after
            // Disconnect.
            clearGiveUpLineLocked()
            // Every OTHER exit from the rotation gap is a rotation that keeps going; this one ends
            // the session under it. Placed here, above the early return below, so it covers that
            // path too — and it is what covers ALL the stale-session exits, since losing ownership
            // mid-rotation means a stop ran: the bring-up-failure arm and the give-up funnel both
            // skip their own bridge handling when the ownership check fails.
            //
            // Adds nothing that awaits (RISK-1): this is one close() on a ParcelFileDescriptor, the
            // same call tearDownTunnelLocked already makes below.
            releaseRotationBridgeLocked("the session is stopping")
            // Keep stop, global TUN/Xray teardown, and the next start admission under one lock.
            // This prevents an old full stop from tearing down a newer session's resources.
            val tailerToStop = logTailer
            logTailer = null

            if (!shouldStop && tunInterface == null) {
                // No live session and no TUN yet — still stop any tailer we extracted
                // defensively and exit cleanly.
                tailerToStop?.stop()
                if (stopService) stopSelf()
                return
            }

            tailerToStop?.stop()

            killSwitchMonitor?.stop()
            killSwitchMonitor = null
            settingsObserverJob?.cancel()
            settingsObserverJob = null
            stopFailoverMonitorLocked()
            failoverSettingsJob?.cancel()
            failoverSettingsJob = null
            failoverRearmJob?.cancel()
            failoverRearmJob = null
            // Unconditional here (the whole session is ending), but it must follow the monitor
            // teardown above so the shared-receiver invariant still holds if anything re-enters.
            unregisterScreenReceiver()

            tearDownTunnelLocked()

            currentProfileId = -1L
            sessionLogFile = null
            sessionTuning = TuningSettings.NONE
            LogRepository.setConnectionState(VpnConnectionState.DISCONNECTED)
            LogRepository.append("VPN stopped")
            // These alerts each live under their own notification id; stopForeground removes none
            // of them.
            VpnNotifications.cancelExposed(this)
            VpnNotifications.cancelFailoverBlackholed(this)
            // 1106 asserts in the present tense that a listed app is still going through the VPN.
            // After a stop that is simply false, and its setAutoCancel(true) only clears it if the
            // user taps it.
            VpnNotifications.cancelKillSwitchNotApplied(this)
            if (stopService) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /**
     * Resolves a string in the user's chosen app locale. We can't rely on the
     * service's own getString because Service contexts don't pick up per-app locale
     * changes mid-session on API <33 — wrap via SupportedLanguage.localize each call.
     * (Notification channel name/description are still cached by the system at channel
     * creation time; that is an Android limitation and unavoidable here.)
     */
    private fun localizedString(@StringRes resId: Int, vararg args: Any): String =
        SupportedLanguage.localize(this).getString(resId, *args)

    // SDK_INT < O check below is dead at minSdk 29 (dead for any minSdk >= 26), but intentionally
    // retained as a guard should minSdk ever drop below 26; @SuppressLint keeps lint quiet without
    // removing the guard.
    @SuppressLint("ObsoleteSdkInt")
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            localizedString(R.string.vpn_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        channel.description = localizedString(R.string.vpn_channel_description)
        manager.createNotificationChannel(channel)

        val errorChannel = NotificationChannel(
            ERROR_CHANNEL_ID,
            localizedString(R.string.vpn_error_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        )
        errorChannel.description = localizedString(R.string.vpn_error_channel_description)
        manager.createNotificationChannel(errorChannel)

        VpnNotifications.createExposedChannel(this)
    }

    private fun buildNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.boykisser_notification_icon)
            .setContentTitle(localizedString(R.string.vpn_notification_title))
            .setContentText(contentText)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            // Show the FGS notification immediately. Android 12+ otherwise defers the
            // foreground-service notification up to 10s (the system decides per start), which
            // left the status notification missing on cold/first connects. specialUse is not a
            // deferral-exempt FGS type, so we must opt out of deferral explicitly.
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOnlyAlertOnce(true)
            .setDeleteIntent(notificationDismissIntent())
            // A Stop action on the ONGOING notification is the one surface that is present in every
            // running state, including the failover give-up states where the app UI may be closed
            // and the copy is actively telling the user to turn the VPN off.
            .addAction(
                R.drawable.boykisser_notification_icon,
                localizedString(R.string.vpn_notification_action_stop),
                notificationStopIntent()
            )
            .build()
    }

    private fun updateNotification(contentText: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(VpnNotifications.NOTIFICATION_ID, buildNotification(contentText))
    }

    /**
     * PendingIntent fired when the user swipes away the ongoing FGS notification.
     * Android 14+ makes ongoing FGS notifications user-dismissable with no opt-out
     * flag, so the deleteIntent lets us re-post and keep the status visible. Targets
     * this already-running foreground service, so getService is not background-blocked.
     */
    private fun notificationDismissIntent(): PendingIntent {
        val intent = Intent(this, XrayVpnService::class.java)
            .setAction(ACTION_NOTIFICATION_DISMISSED)
        return PendingIntent.getService(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /**
     * PendingIntent behind the ongoing notification's Stop action. Same shape as
     * [notificationDismissIntent] — an explicit service Intent, not background-blocked because the
     * target is this already-running foreground service. The distinct request code is defensive
     * rather than strictly required (PendingIntent matching runs `Intent.filterEquals`, which does
     * compare the action, so the differing actions already separate them); it keeps them separate
     * even if one of these Intents ever loses its action.
     */
    private fun notificationStopIntent(): PendingIntent {
        val intent = Intent(this, XrayVpnService::class.java)
            .setAction(ACTION_STOP)
            .putExtra(EXTRA_USER_INITIATED_STOP, true)
        return PendingIntent.getService(
            this,
            1,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /** Re-posts the notification matching the current state after a user dismissal. */
    private fun repostOngoingNotification() {
        when (LogRepository.connectionState.value) {
            VpnConnectionState.PAUSED -> {
                // Two notifications back the paused state (the quiet FGS line + the loud
                // exposed alert); restore both, since either could have been the one swiped.
                updateNotification(localizedString(R.string.vpn_status_paused, lastTriggerLabel))
                VpnNotifications.postExposed(this, lastTriggerLabel, notificationDismissIntent())
            }
            VpnConnectionState.CONNECTING ->
                updateNotification(localizedString(R.string.vpn_status_connecting))
            VpnConnectionState.CONNECTED ->
                updateNotification(localizedString(R.string.vpn_status_connected))
            VpnConnectionState.BLACKHOLED -> {
                // The give-up alert (id 1105) is setAutoCancel, so once the user dismisses it this
                // persistent line is their only remaining indication. Only 1101 is restored — 1105
                // was dismissed deliberately and re-posting it would fight the user.
                //
                // Read from giveUpLine, NOT re-derived from giveUpOutcome. The disable branch
                // clears that marker while deliberately keeping this state, and the old derivation
                // fell through to the blackhole copy after such a release — relabelling a
                // still-proxying tunnel as one holding the user's traffic. The two contained
                // outcomes have opposite packet truths and must never share a line.
                //
                // Null is unreachable (this state has exactly one producer, and it writes the
                // field in the same locked block), so it restores nothing rather than guessing a
                // line that could be the wrong one.
                when (giveUpLine) {
                    GiveUpOngoingLine.STILL_PROXYING ->
                        updateNotification(localizedString(R.string.vpn_status_no_response))
                    GiveUpOngoingLine.TRAFFIC_HELD ->
                        updateNotification(localizedString(R.string.vpn_status_blackholed))
                    // UNPROTECTED does not render as BLACKHOLED (connectionStateForGiveUp sends it
                    // to ERROR), so this arm is unreachable and must restore NOTHING rather than
                    // pick a containment line for a state whose packets are not contained.
                    GiveUpOngoingLine.UNPROTECTED, null -> Unit
                }
            }
            VpnConnectionState.ERROR -> {
                // ERROR normally means the session is dying, with nothing ongoing to restore. The
                // uncontained give-up is the exception: the service is still RUNNING, and the line
                // it needs is the honest "not protected" one, never the containment copy.
                //
                // Keyed on the RECORDED line, the same source home and the tile read, so all three
                // surfaces cannot disagree about which give-up a session is in. (Reading
                // giveUpOutcome gives the same answer today — the disable branch that clears it
                // excludes UNPROTECTED — but it is a second source for one question, which is the
                // shape that produced the mislabelled-BLACKHOLED defect one arm above.)
                if (giveUpLine == GiveUpOngoingLine.UNPROTECTED) {
                    updateNotification(localizedString(R.string.vpn_status_unprotected))
                }
            }
            VpnConnectionState.DISCONNECTED -> {
                // Nothing ongoing to restore.
            }
        }
    }

    private fun postReviveErrorNotification() {
        postErrorNotification(R.string.vpn_revive_error)
    }

    private fun postPermissionRevokedNotification() {
        postErrorNotification(R.string.vpn_permission_revoked_error)
    }

    private fun postErrorNotification(@StringRes messageRes: Int) {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, ERROR_CHANNEL_ID)
            .setSmallIcon(R.drawable.boykisser_notification_icon)
            .setContentTitle(localizedString(R.string.vpn_notification_title))
            .setContentText(localizedString(messageRes))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(ERROR_NOTIFICATION_ID, notification)
    }
}
