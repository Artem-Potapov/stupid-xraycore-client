package com.justme.xtls_core_proxy.state

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.justme.xtls_core_proxy.BuildConfig
import com.justme.xtls_core_proxy.R
import com.justme.xtls_core_proxy.bridge.XrayBridge
import com.justme.xtls_core_proxy.config.ConfigBuilder
import com.justme.xtls_core_proxy.db.AppDatabase
import com.justme.xtls_core_proxy.db.Profile
import com.justme.xtls_core_proxy.db.Subscription
import com.justme.xtls_core_proxy.failover.FailoverPoolResolver
import com.justme.xtls_core_proxy.failover.FastestConnectOutcome
import com.justme.xtls_core_proxy.failover.FastestConnectRunner
import com.justme.xtls_core_proxy.i18n.SupportedLanguage
import com.justme.xtls_core_proxy.log.LogRepository
import com.justme.xtls_core_proxy.log.VpnConnectionState
import com.justme.xtls_core_proxy.subs.SubscriptionRefreshCoordinator
import com.justme.xtls_core_proxy.vpn.XrayVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SubGroup(val subscription: Subscription, val profiles: List<Profile>)
data class ProfilesView(val manual: List<Profile>, val groups: List<SubGroup>) {
    companion object {
        val EMPTY = ProfilesView(emptyList(), emptyList())
    }
}

/** Auto-ping fires once per app launch: only when enabled and not yet consumed this process. */
fun shouldAutoPing(autoOnOpen: Boolean, alreadyConsumed: Boolean): Boolean =
    autoOnOpen && !alreadyConsumed

/**
 * The set of servers the launch-time auto-ping probes: every stored profile, taken straight from the
 * single flat `profiles` query (`ProfileDao.getAll` returns manual and subscription-imported rows
 * alike, in one atomic emission), ordered to match the list UI — manual ("My profiles") servers
 * first, then subscription servers grouped by subscription, id-ascending within each partition.
 *
 * It deliberately does NOT rebuild the set from the subscription-*grouped* view. That view assembles
 * its groups from the separate `subscriptions` Room query, which loads on its own schedule; keying
 * the once-per-launch auto-ping on that union raced the subscriptions load and, ~30% of launches,
 * spent the [AutoPingLatch] on a manual-only partial set — silently skipping every subscription
 * server. Ordering here is likewise derived only from per-row fields (`subscriptionId`, `id`), NOT
 * from the `subscriptions` list, so the probe order can't flake on whether that list has loaded yet;
 * because subscription rows import as contiguous id blocks and subscription ids ascend with creation
 * time, this reproduces the visual top-to-bottom order. The `subscriptions` list is accepted only to
 * make explicit at the call site that auto-ping is intentionally independent of it; a CASCADE foreign
 * key guarantees no orphaned profiles, so the flat set already equals the rendered union.
 */
@Suppress("UNUSED_PARAMETER")
fun autoPingServers(profiles: List<Profile>, subscriptions: List<Subscription>): List<Profile> =
    profiles.sortedWith(compareBy({ it.subscriptionId != null }, { it.subscriptionId }, { it.id }))

/**
 * What the connect affordances should offer for a given connection state.
 *
 * Replaces the former boolean `canConnect`. Three values rather than two because a give-up state
 * is neither "connectable" nor "nothing to do": the tunnel is still there, the engine simply
 * stopped trying, and the honest affordance is **Reconnect**.
 *
 * This is the single home for the **connect-affordance** rule — nothing else may re-enumerate which
 * states offer a connect, and [connectLabelRes] keeps the label half under the same exhaustive
 * `when` so it cannot drift from the enablement half.
 *
 * It is NOT the single home for knowledge about [VpnConnectionState]'s partitions. Four other
 * hand-written enumerations over the same enum survive this one, each encoding a DIFFERENT rule:
 * `MainActivity.isActive` (which profile renders as the active one), `MainActivity`'s Disconnect
 * gate (which states are stoppable), `tile/TileClickDecision.decideTileClick` and
 * `tile/XrayVpnTileService`'s active check (what a QS-tile tap and its render mean). They are plain
 * boolean chains the compiler cannot flag, and one of them drifted once already and shipped a dead
 * QS-tile control. **Widening `VpnConnectionState` still requires the grep sweep `AGENTS.md`
 * mandates** — this enum removes one member of that class, not the class.
 *
 * It lives here (not in `MainActivity`) so [FastestConnectRunner] can re-check it at delivery time,
 * not only at the menu tap that started the probe — see that class's KDoc ("Delivery-time re-gate")
 * for why a point-in-time-only check let a winner misreport which server traffic was actually on.
 */
internal enum class ConnectAction {
    /** No session: offer a normal Connect. */
    CONNECT,

    /** A give-up left a tunnel in place; offer Reconnect rather than a disabled Connect. */
    RECONNECT,

    /** A live session owns the tunnel; offer nothing. */
    UNAVAILABLE,
}

internal fun connectAction(state: VpnConnectionState): ConnectAction = when (state) {
    VpnConnectionState.DISCONNECTED,
    VpnConnectionState.ERROR -> ConnectAction.CONNECT
    VpnConnectionState.BLACKHOLED -> ConnectAction.RECONNECT
    VpnConnectionState.CONNECTED,
    VpnConnectionState.CONNECTING,
    VpnConnectionState.PAUSED -> ConnectAction.UNAVAILABLE
}

/**
 * The label for the connect affordance. Lives beside [connectAction] and switches on it with an
 * exhaustive `when`, so a fourth [ConnectAction] is a compile error here as well as at the
 * enablement site — the labelling half of the rule must not be able to drift from the enablement
 * half. [isConnecting] is passed separately because it is a transient progress state, not a fourth
 * action: the affordance is [ConnectAction.UNAVAILABLE] throughout it.
 */
@StringRes
internal fun connectLabelRes(action: ConnectAction, isConnecting: Boolean): Int = when {
    isConnecting -> R.string.main_button_connecting
    else -> when (action) {
        ConnectAction.RECONNECT -> R.string.main_button_reconnect
        ConnectAction.CONNECT, ConnectAction.UNAVAILABLE -> R.string.main_button_connect
    }
}

/**
 * Whether the connect affordance accepts a tap. Lives beside [connectLabelRes] and takes the same
 * *shape* of inputs so the two halves of one rule cannot be restated by hand at each call site —
 * the defect that let a control render "Connecting…" and still be tappable.
 *
 * **The two are deliberately NOT given identical arguments, and must not be "restored" to it.**
 * Every call site passes the NARROW flag to [connectLabelRes] and the WIDENED one to this function:
 *
 * ```
 * label   = connectLabelRes(action, isConnecting)
 * enabled = connectEnabled(action, isConnecting || requestInFlight)
 * ```
 *
 * because the two halves scope to different rows. Only the reconnect TARGET should read
 * "Connecting…" (`isConnecting` = "this profile is the one being reconnected"), while EVERY control
 * must be disabled (`requestInFlight` = "some reconnect is running"), since a contending tap would
 * be refused anyway — a single flag made thirty unrelated servers claim to be connecting. The
 * invariant "never enabled while it reads Connecting…" is therefore enforced by the `||` at each
 * call site, not by argument identity: the widened set is a superset of the narrow one, so anything
 * relabelled is also disabled. Collapsing the two arguments back together reintroduces the defect.
 *
 * `isConnecting` disables as well as relabels because it covers a case the state alone cannot
 * express: during a Reconnect the connection state stays `BLACKHOLED` until the teardown lands, so
 * [connectAction] still says RECONNECT while the request is already in flight. It is behaviour-
 * preserving for the pre-existing case — `connectAction(CONNECTING)` is already
 * [ConnectAction.UNAVAILABLE].
 */
internal fun connectEnabled(action: ConnectAction, isConnecting: Boolean): Boolean =
    action != ConnectAction.UNAVAILABLE && !isConnecting

class VpnViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.get(application)
    private val dao = db.profileDao()
    private val subDao = db.subscriptionDao()

    val profiles: StateFlow<List<Profile>> = dao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val subscriptions: StateFlow<List<Subscription>> = subDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val groupedProfiles: StateFlow<ProfilesView> =
        combine(profiles, subscriptions) { allProfiles, subs ->
            val bySubId = allProfiles.groupBy { it.subscriptionId }
            ProfilesView(
                manual = bySubId[null].orEmpty(),
                groups = subs.map { sub -> SubGroup(sub, bySubId[sub.id].orEmpty()) }
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfilesView.EMPTY)

    /**
     * Server set for the launch-time auto-ping, routed through [autoPingServers] so it is sourced
     * from the atomic flat `profiles` query and never races the subscription groups (which the
     * grouped [groupedProfiles] view assembles from the separately-loaded `subscriptions` query).
     * Keying auto-ping on this rather than the grouped union fixes the ~30% "subscriptions skipped"
     * flake. See [autoPingServers].
     */
    val autoPingProfiles: StateFlow<List<Profile>> =
        combine(profiles, subscriptions, ::autoPingServers)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeProfileId: StateFlow<Long?> = ActiveProfileRepository.activeProfileIdFlow
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            ActiveProfileRepository.getActiveProfileId(application)
        )

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /**
     * Owns the one rule about when [error] is wiped. Stateful, so it is a field rather than a call —
     * a per-call instance would forget which message is on screen and lose the rule entirely. See
     * [ConnectErrorRetention] for why a refusal must outlive the winning request's CONNECTING.
     */
    private val errorRetention = ConnectErrorRetention()

    data class DnsWarning(val name: String, val rawConfig: String)

    private val _dnsWarning = MutableStateFlow<DnsWarning?>(null)
    val dnsWarning: StateFlow<DnsWarning?> = _dnsWarning.asStateFlow()

    // Single, stable admission owner for the ViewModel lifetime: created once and NEVER swapped, so
    // its cross-run in-flight de-dup and fixed native-admission ceiling survive concurrency changes
    // (the per-run concurrency is passed into each runGroup call instead of rebuilding the owner).
    private val pingCoordinator = PingCoordinator()

    private val _pingStates = MutableStateFlow<Map<Long, PingState>>(emptyMap())
    val pingStates: StateFlow<Map<Long, PingState>> = _pingStates.asStateFlow()

    val connectionState = LogRepository.connectionState

    /**
     * Owns the "Connect fastest" orchestration (job replacement/cancellation, the delivery-time
     * re-gate, busy-vs-no-response messaging) — see [FastestConnectRunner]'s KDoc for the full
     * reasoning. Wiring here is ViewModel-scoped production plumbing only; the sequencing itself is
     * framework-free and unit-tested directly against [FastestConnectRunner] (see
     * `FastestConnectRunnerTest`), not through this ViewModel.
     *
     * [FastestConnectRunner.resolvePool] is backed by [FailoverPoolResolver.resolve] — the SAME pool
     * auto-failover itself rotates through (see that object's KDoc: "the single place that changes
     * when user-curated pools land"), not a separately-derived view of the on-screen group. This
     * keeps Connect-fastest and auto-failover from silently diverging on which servers count as "the
     * pool" if/when curated pools land.
     */
    private val fastestConnectRunner = FastestConnectRunner(
        scope = viewModelScope,
        pingCoordinator = pingCoordinator,
        pingStates = _pingStates,
        resolvePool = { profile -> FailoverPoolResolver.resolve(dao, profile) },
        loadPreferences = { PingPreferences.load(getApplication()) },
        probe = { profile, prefs -> probeProfile(profile, prefs.targetUrl, prefs.timeoutMs) },
        canConnect = { connectAction(connectionState.value) != ConnectAction.UNAVAILABLE },
        onOutcome = { outcome ->
            LogRepository.emitError(
                when (outcome) {
                    FastestConnectOutcome.NO_RESPONSE -> R.string.failover_connect_fastest_no_response_error
                    FastestConnectOutcome.BUSY -> R.string.failover_connect_fastest_busy_error
                    FastestConnectOutcome.STATE_CHANGED -> R.string.failover_connect_fastest_state_changed_error
                }
            )
        },
    )

    /** True while a "Connect fastest" run (see [connectFastest]) is in flight. */
    val connectFastestActive: StateFlow<Boolean> = fastestConnectRunner.active

    /**
     * The winning profile id from the most recent [connectFastest] run, or null once consumed. The
     * ViewModel deliberately does NOT call [connect] itself here: every other Connect action in this
     * app routes through MainActivity's permission-checked flow (notification permission, then
     * `VpnService.prepare()` consent) before ever calling [connect], and the ViewModel has no access
     * to that Activity-owned `ActivityResultLauncher` machinery. Surfacing the winner as state and
     * letting the Compose layer consume it through the SAME `onConnect` callback every other row uses
     * (see MainActivity's `MainScreen`) keeps that invariant intact and — because it is ViewModel
     * state observed fresh on every recomposition, not a callback captured for the life of the
     * coroutine — survives an Activity recreation (rotation) mid-probe without capturing a stale
     * Activity instance.
     *
     * [FastestConnectRunner]'s own `canConnect` re-check only guards the moment this is PRODUCED —
     * it bounds the probe's window, but not the gap after. This value can then sit unconsumed
     * indefinitely (the Compose frame clock pauses below `STARTED` while the app is backgrounded),
     * so `MainScreen` re-checks [connectAction] a SECOND time at CONSUMPTION, right before calling
     * `onConnect`, and calls [discardFastestWinner] instead when that fails (Task 10 review round 2,
     * Important). Two checks around this unbounded gap are correct, not redundant.
     */
    val fastestWinnerId: StateFlow<Long?> = fastestConnectRunner.winnerId

    /** Consumes [fastestWinnerId] so it does not re-fire on the next recomposition. */
    fun consumeFastestWinner() {
        fastestConnectRunner.consumeWinner()
    }

    /**
     * Consumes a pending [fastestWinnerId] that failed the CONSUMPTION-side re-gate — see that
     * property's doc. Reports the same `STATE_CHANGED` message [FastestConnectRunner]'s own
     * production-side re-gate uses via `onOutcome`, since from the user's perspective this is the
     * identical outcome: a winner was found but never delivered because the connection state
     * changed. Keeps that messaging in one place rather than duplicating the string lookup in
     * `MainActivity`.
     */
    fun discardFastestWinner() {
        consumeFastestWinner()
        LogRepository.emitError(R.string.failover_connect_fastest_state_changed_error)
    }

    /**
     * A later connect request contested a slot an earlier one already held, and lost. One message
     * for two shapes of the same rule — the earlier choice is honoured, the later one is reported
     * rather than silently dropped:
     *
     * - **The parked `pendingProfileId` slot.** Three sources can write it — connect-to-fastest's
     *   winner delivery, the QS-tile hand-off, or a second manual tap — and whichever parked first
     *   raised the system dialog still on screen, so answering it must complete that request.
     * - **A reconnect already in flight** ([reconnect]). This one writes no slot at all — the
     *   reconnect branch clears `pendingProfileId` because it raises no dialog — but it is the same
     *   contention and the same resolution, so it reuses the same source-neutral copy.
     */
    fun reportConnectRequestSuperseded() {
        LogRepository.emitError(R.string.connect_request_superseded)
    }

    private val defaultUserAgent = "XTLSCoreProxy/${BuildConfig.VERSION_NAME}"

    init {
        // Mirror LogRepository.errorEvents into the VM's StateFlow, resolved
        // against an Application context so the message picks up the per-app
        // locale at observation time. Cancels with viewModelScope on
        // onCleared() — no manual cleanup needed.
        viewModelScope.launch {
            LogRepository.errorEvents.collect { resId ->
                _error.value = SupportedLanguage.localize(getApplication())
                    .getString(resId)
                errorRetention.onErrorShown(resId)
            }
        }
        // Clear the latest error on every transition to CONNECTING. This is
        // the "user (or tile) tried to start again" signal. Mirrors the
        // semantics of the deleted `_error.value = null` in connect().
        //
        // Not unconditional: a REFUSAL survives the winning request's own CONNECTING, or the user
        // is told nothing about the request they just made. ConnectErrorRetention owns that rule
        // and its exactly-one-transition bound; both collectors run on Dispatchers.Main.immediate,
        // so its field needs no synchronization.
        viewModelScope.launch {
            LogRepository.connectionState
                .filter { it == VpnConnectionState.CONNECTING }
                .collect { if (errorRetention.onConnectingTransition()) _error.value = null }
        }
        // Prune ephemeral ping results when the profile set changes (e.g. a subscription refresh
        // replaces rows with new ids) so stale id -> PingState entries don't accumulate for ids
        // that no longer exist. The UI only reads ids present in `view`, so this is housekeeping.
        viewModelScope.launch {
            profiles.collect { current ->
                val ids = current.mapTo(HashSet()) { it.id }
                _pingStates.update { states -> states.filterKeys { it in ids } }
            }
        }
    }

    fun clearError() {
        clearVpnError(clearBanner = { _error.value = null }, retention = errorRetention)
    }

    fun addProfile(name: String, config: String) {
        viewModelScope.launch {
            val storage = ConfigBuilder.toProfileStorageConfig(config)
            if (runCatching { ConfigBuilder.dnsDiagnosis(storage) }.getOrNull() ==
                ConfigBuilder.DnsStatus.DIRTY
            ) {
                _dnsWarning.value = DnsWarning(name = name, rawConfig = config)
                return@launch
            }
            val stored = runCatching { ConfigBuilder.makeSecureDns(storage) }.getOrDefault(storage)
            dao.insert(Profile(name = name, config = stored, sanitizedDns = false))
        }
    }

    /**
     * DEBUG-only unrestricted insert (see DebugUnrestrictedAddProfileActivity): stores [raw] verbatim
     * — NO toProfileStorageConfig, NO makeSecureDns, NO validation — then activates the new row via the
     * sanctioned ActiveProfileRepository writer so Config Sanitization / Connect target it. Lets a
     * maintainer reproduce arbitrary (incl. malformed) profile state the fail-closed ingest gates reject.
     */
    fun addRawProfile(name: String, raw: String): Job = viewModelScope.launch {
        val id = dao.insert(Profile(name = name, config = raw, sanitizedDns = false))
        ActiveProfileRepository.setActiveProfileId(getApplication(), id)
    }

    fun confirmDnsFixAndAdd() {
        val warning = _dnsWarning.value ?: return
        viewModelScope.launch {
            try {
                val secured = ConfigBuilder.makeSecureDns(
                    ConfigBuilder.toProfileStorageConfig(warning.rawConfig)
                )
                dao.insert(Profile(name = warning.name, config = secured, sanitizedDns = true))
            } catch (t: Throwable) {
                LogRepository.append("confirmDnsFixAndAdd: failed to insert secured profile: ${t.message}")
            } finally {
                _dnsWarning.value = null
            }
        }
    }

    fun dismissDnsWarning() {
        _dnsWarning.value = null
    }

    fun updateProfile(profile: Profile) {
        viewModelScope.launch {
            val storedConfig = ConfigBuilder.toProfileStorageConfig(profile.config)
            dao.update(profile.copy(config = storedConfig))
        }
    }

    fun deleteProfile(profile: Profile) {
        viewModelScope.launch {
            dao.delete(profile)
            if (ActiveProfileRepository.getActiveProfileId(getApplication()) == profile.id) {
                ActiveProfileRepository.setActiveProfileId(getApplication(), null)
            }
        }
    }

    fun addSubscription(
        name: String,
        url: String,
        userAgentOverride: String? = null,
        allowInsecureTls: Boolean = false,
        userIntervalHours: Int? = null,
        refreshAfterInsert: Boolean = false
    ): Job = viewModelScope.launch {
        val newId = subDao.insert(
            Subscription(
                name = name,
                url = url,
                userAgentOverride = userAgentOverride,
                allowInsecureTls = allowInsecureTls,
                userIntervalHours = userIntervalHours
            )
        )
        if (refreshAfterInsert) {
            SubscriptionRefreshCoordinator.refresh(
                scope = viewModelScope,
                context = getApplication(),
                subId = newId,
                activeProfileIdProvider = { ActiveProfileRepository.getActiveProfileId(getApplication()) },
                db = db,
                defaultUserAgent = defaultUserAgent
            )
        }
    }

    fun updateSubscription(sub: Subscription, refreshAfterUpdate: Boolean = false): Job =
        viewModelScope.launch {
            subDao.update(sub)
            if (refreshAfterUpdate) {
                SubscriptionRefreshCoordinator.refresh(
                    scope = viewModelScope,
                    context = getApplication(),
                    subId = sub.id,
                    activeProfileIdProvider = { ActiveProfileRepository.getActiveProfileId(getApplication()) },
                    db = db,
                    defaultUserAgent = defaultUserAgent
                )
            }
        }

    /**
     * Deletes [sub], stopping the session first when it would otherwise strand it.
     *
     * The teardown goes through [disconnectAbandoningReconnect], not [disconnect]: a delete is an
     * EXTERNAL stop, so a Reconnect in flight must be abandoned rather than left to read the
     * resulting `DISCONNECTED` as its own settle and start a profile this delete is about to
     * CASCADE away. Which profiles to examine — including the reconnect target, which is the only
     * one visible while the flow has already cleared the active id — is
     * [shouldStopSessionForDeletedSubscription].
     */
    fun deleteSubscription(context: Context, sub: Subscription): Job = viewModelScope.launch {
        val activeId = ActiveProfileRepository.getActiveProfileId(getApplication())
        val reconnectTargetId = reconnectingProfileId.value
        val shouldStop = shouldStopSessionForDeletedSubscription(
            deletedSubscriptionId = sub.id,
            activeProfileSubscriptionId = activeId?.let { dao.getById(it)?.subscriptionId },
            reconnectTargetSubscriptionId = reconnectTargetId?.let { dao.getById(it)?.subscriptionId },
        )
        if (shouldStop) disconnectAbandoningReconnect(context)
        subDao.delete(sub)
    }

    fun refreshSubscription(context: Context, subId: Long): Job =
        SubscriptionRefreshCoordinator.refresh(
            scope = viewModelScope,
            context = context.applicationContext,
            subId = subId,
            activeProfileIdProvider = { ActiveProfileRepository.getActiveProfileId(getApplication()) },
            db = db,
            defaultUserAgent = defaultUserAgent
        )

    fun refreshAllStaleSubscriptions(context: Context): Job = viewModelScope.launch {
        val appContext = context.applicationContext
        val now = System.currentTimeMillis()
        val candidates = subscriptions.value.filter { sub ->
            val last = sub.lastFetchedAt
            if (last == null) return@filter true
            val intervalMs = sub.effectiveIntervalHours().toLong() * 3_600_000L
            now - last >= intervalMs
        }
        candidates.forEach { sub ->
            SubscriptionRefreshCoordinator.refresh(
                scope = viewModelScope,
                context = appContext,
                subId = sub.id,
                activeProfileIdProvider = { ActiveProfileRepository.getActiveProfileId(getApplication()) },
                db = db,
                defaultUserAgent = defaultUserAgent
            )
        }
    }

    fun connect(context: Context, profileId: Long) {
        ActiveProfileRepository.setActiveProfileId(context, profileId)

        val appContext = context.applicationContext
        val startIntent = Intent(appContext, XrayVpnService::class.java).apply {
            action = XrayVpnService.ACTION_START
            putExtra(XrayVpnService.EXTRA_PROFILE_ID, profileId)
        }

        // startForegroundService can throw (e.g. ForegroundServiceStartNotAllowedException if the
        // app has lost foreground state by dispatch time). Surface failures through the same
        // LogRepository error channel the UI/tile already render rather than crashing the caller.
        try {
            appContext.startForegroundService(startIntent)
        } catch (e: Exception) {
            LogRepository.emitError(R.string.vpn_start_failed_error)
            LogRepository.append("connect() failed to start service: ${e.message}")
        }
    }

    fun disconnect(context: Context) {
        signalStop(context, isReconnectStop = false)
    }

    /**
     * Reconnect must tear down the current tunnel without scheduling service destruction behind the
     * following start. It is deliberately private: every user-facing Disconnect remains a full stop.
     */
    private fun disconnectForReconnect(context: Context) {
        signalStop(context, isReconnectStop = true)
    }

    private fun signalStop(context: Context, isReconnectStop: Boolean) {
        val appContext = context.applicationContext
        val stopIntent = Intent(appContext, XrayVpnService::class.java).apply {
            action = XrayVpnService.ACTION_STOP
            if (isReconnectStop) putExtra(XrayVpnService.EXTRA_RECONNECT_STOP, true)
        }
        // The UI gate is CONNECTED/CONNECTING/PAUSED/BLACKHOLED/ERROR. In the first four the
        // service is running and this is a plain stop. ERROR is the exception and covers two very
        // different situations: a failover give-up that could not contain traffic (service RUNNING,
        // a real stop), and a dying session that has already stopSelf()'d — where this
        // startForegroundService CREATES the service rather than signalling one. That is safe only
        // because ACTION_STOP reaches stopVpn's `!shouldStop && tunInterface == null` early return,
        // and stopVpn does not await anything — onStartCommand dispatches it via
        // tunnelOpScope.launch, but stopVpn has no suspension points, so it runs and calls
        // stopSelf() well inside Android's ~5 s startForeground deadline; if the stop path ever
        // awaits work before that, this becomes ForegroundServiceDidNotStartInTimeException.
        // startForegroundService (not startService) is also what keeps us clear of the API 31+
        // background-start restriction if the activity loses foreground state between gate and
        // dispatch.
        // Guarded like connect()'s dispatch: startForegroundService can throw (e.g.
        // ForegroundServiceStartNotAllowedException on the ERROR path above, where this CREATES the
        // service rather than signalling a running one). ReconnectFlow calls its keep-alive form
        // from a coroutine, so an uncaught throw would reach viewModelScope's handler instead of the
        // caller — surface it through the same error channel every other failure uses. The active
        // profile is still cleared: the user asked to disconnect either way.
        try {
            appContext.startForegroundService(stopIntent)
        } catch (e: Exception) {
            LogRepository.emitError(R.string.vpn_start_failed_error)
            LogRepository.append("disconnect() failed to signal the service: ${e.message}")
        }
        ActiveProfileRepository.setActiveProfileId(context, null)
    }

    /**
     * Single, stable owner of Reconnect sequencing — created once and NEVER swapped, exactly like
     * [pingCoordinator] and [fastestConnectRunner]. That is load-bearing here rather than tidy:
     * [ReconnectFlow.reconnectingProfileId] is what refuses a contending second tap, and a per-call instance
     * would give every tap its own fresh guard, i.e. no guard at all.
     *
     * The closures take the APPLICATION context because they run seconds after the tap;
     * `connect`/`disconnect`/`ActiveProfileRepository` all reduce to it internally anyway, so this
     * is identical behaviour without holding an Activity across the window.
     */
    private val reconnectFlow = ReconnectFlow(
        connectionState = LogRepository.connectionState,
        stop = { disconnectForReconnect(getApplication()) },
        start = { connect(getApplication(), it) },
        onTimeout = { LogRepository.emitError(R.string.vpn_reconnect_timeout_error) },
        onSuperseded = { reportConnectRequestSuperseded() },
        dispatcher = Dispatchers.Main.immediate,
    )

    /**
     * The profile a [reconnect] is sequencing, or null when none is. The UI feeds this into the
     * `isConnecting` parameter of [connectLabelRes] and [connectEnabled] — but **not as the same
     * argument**: the label gets `reconnectingId == thisProfile.id` and the enablement gets that
     * `||` "any reconnect is in flight". See [connectEnabled]'s KDoc for why the asymmetry is the
     * rule rather than a slip. The connection state stays
     * `BLACKHOLED` for the whole teardown — which can run for seconds when a live Xray core has to
     * be closed — so without this the affordance would keep rendering an enabled "Reconnect" that
     * looks dead, and re-tapping it is the natural response.
     *
     * An id rather than a flag because the two halves of that rule cover different rows: only the
     * target says "Connecting…", while every control is disabled. See
     * [ReconnectFlow.reconnectingProfileId].
     */
    val reconnectingProfileId: StateFlow<Long?> = reconnectFlow.reconnectingProfileId

    /**
     * Abandons a reconnect in flight so an in-app Disconnect wins against it.
     *
     * Deliberately NOT called from [disconnect]: that method is the flow's own first step, so
     * cancelling there would make every reconnect cancel itself and silently degrade Reconnect into
     * Disconnect. It belongs to the user's Disconnect surface — see [ReconnectFlow.cancel], which
     * also documents which stop surfaces this does NOT cover.
     */
    fun cancelReconnect() {
        reconnectFlow.cancel()
    }

    /**
     * **The single home for an EXTERNAL teardown**: abandon any reconnect in flight, then stop.
     *
     * Every caller that stops a session for a reason of its own — the in-app Disconnect button, a
     * subscription delete that would strand the session — belongs here rather than calling
     * [cancelReconnect] and [disconnect] as a hand-written pair. Getting only half of the pair
     * right is invisible: the survivor still compiles, still stops the VPN, and the in-flight
     * [ReconnectFlow] simply reads the resulting `DISCONNECTED` as its own settle and starts the
     * VPN straight back up, inverting the command it was given. `deleteSubscription` was written
     * without the cancel exactly that way.
     *
     * [disconnect] stays the raw dispatch **and must not gain the cancel**: it is the reconnect
     * flow's own first step, so cancelling inside it would make every reconnect cancel itself and
     * silently degrade Reconnect into Disconnect. The ordering here is cancel-then-stop so the flow
     * is already abandoned before the state it watches can move.
     */
    fun disconnectAbandoningReconnect(context: Context) {
        cancelReconnect()
        disconnect(context)
    }

    /**
     * Stops the live session, waits for it to settle, then starts [profileId] — the mechanism behind
     * [ConnectAction.RECONNECT], the one affordance a give-up state offers, and the reason a plain
     * [connect] cannot serve it (the service is still running, so `startVpn` refuses with "VPN
     * already running").
     *
     * **[ReconnectFlow]'s KDoc is the canonical explanation** of why this is a dispatched
     * stop-then-start rather than an in-service restart, which give-up outcomes reach it, and the
     * two races it survives. Do not restate it here.
     *
     * Needs no permission prompt, unlike [connect]'s call sites: a session is already running, so
     * `VpnService.prepare()` consent has already been granted for this app.
     */
    fun reconnect(profileId: Long) {
        reconnectFlow.run(profileId, viewModelScope)
    }

    fun pingTestGroup(profiles: List<Profile>) {
        if (profiles.isEmpty()) return
        val prefs = PingPreferences.load(getApplication())
        val byId = profiles.associateBy { it.id }
        viewModelScope.launch {
            // pingCoordinator is a stable val (never swapped), so capturing it inside the launched
            // coroutine is safe — the R5 hazard (dereferencing a mutable tester field) is gone.
            pingCoordinator.runGroup(
                ids = byId.keys.toList(),
                concurrency = prefs.concurrency,
                onUpdate = { id, state -> _pingStates.update { it + (id to state) } },
                // byId.getValue is safe: runGroup only invokes probe with ids from the list we
                // passed (byId.keys). Even a contract violation is caught by runGroup (Throwable ->
                // Unavailable), so a missing key cannot leave a row stuck on Testing.
                probe = { id ->
                    probeProfile(byId.getValue(id), prefs.targetUrl, prefs.timeoutMs)
                }
            )
        }
    }

    fun pingTestProfile(profile: Profile) = pingTestGroup(listOf(profile))

    /**
     * Probes [profile]'s pool (resolved via [FailoverPoolResolver.resolve], see
     * [fastestConnectRunner]'s doc) and, once the fastest successful responder is known and the
     * connection is still in a connectable state, surfaces it via [fastestWinnerId] rather than
     * connecting directly (see that property's doc for why). All sequencing — job replacement, the
     * delivery-time re-gate, cancellation cleanup, busy-vs-no-response messaging — lives in
     * [FastestConnectRunner]; this is a thin delegation.
     */
    fun connectFastest(profile: Profile) {
        fastestConnectRunner.start(profile)
    }

    /** Stops an in-flight [connectFastest] run. See [FastestConnectRunner.start]'s doc for cancellation semantics. */
    fun cancelConnectFastest() {
        fastestConnectRunner.cancel()
    }

    private suspend fun probeProfile(
        profile: Profile,
        targetUrl: String,
        timeoutMs: Long,
    ): PingState {
        val config = runCatching { ConfigBuilder.toPingTestConfig(profile.config) }.getOrElse {
            LogRepository.append("ping: config build failed for ${profile.name}: ${it.message}")
            return PingState.Unavailable
        }
        // The coordinator launches the blocking JNI probe on viewModelScope (NOT a child of this
        // call) under a fixed native-admission ceiling, so the wall-clock backstop stops WAITING on
        // an uninterruptible native call without cancelling it, and orphans that outlive the backstop
        // stay bounded (each holds a native slot until measureLatency actually returns). Go already
        // bounds the dial at timeoutMs; the derived backstop guards the unbounded setup path
        // (core.New/Start) so a row can't hang on Testing forever.
        val backstop = PingTester.backstopFor(timeoutMs)
        return pingCoordinator.probeWithBackstop(
            scope = viewModelScope,
            backstopMs = backstop,
            context = Dispatchers.IO,
            onAdmissionRejected = {
                LogRepository.append("ping: native admission full, skipping ${profile.name}")
            },
            onBackstop = {
                LogRepository.append("ping: probe exceeded ${backstop}ms backstop for ${profile.name}")
            },
            nativeCall = { XrayBridge.measureLatency(config, targetUrl, timeoutMs) },
        )
    }

}
