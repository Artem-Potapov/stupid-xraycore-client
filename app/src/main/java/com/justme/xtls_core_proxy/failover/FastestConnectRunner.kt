package com.justme.xtls_core_proxy.failover

import com.justme.xtls_core_proxy.db.Profile
import com.justme.xtls_core_proxy.state.PingCoordinator
import com.justme.xtls_core_proxy.state.PingPreferences
import com.justme.xtls_core_proxy.state.PingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Why a "Connect fastest" run finished with no server to connect to, for the caller to report to
 * the user rather than dropping the run silently (Task 10 review Minor 3/4, Important 1).
 */
internal enum class FastestConnectOutcome {
    /** No pool id's probe resolved to [PingState.Success] before `runGroup` returned. */
    NO_RESPONSE,

    /**
     * At least one pool id was already [PingState.Testing] before this run even started (another
     * active run — auto-ping, a manual ping test, or an overlapping connect-fastest pool — already
     * owns it), so `PingCoordinator.runGroup`'s cross-run `inFlight` de-dup silently skipped it
     * instead of probing it fresh. Reporting [NO_RESPONSE] in this case would be misleading — the
     * servers may be fine, this run just never got a fresh read on some of them.
     */
    BUSY,

    /**
     * A winner was found, but [FastestConnectRunner]'s `canConnect` check failed at delivery time —
     * the connection state entered one of the states that REFUSE a connect (`CONNECTED`,
     * `CONNECTING`, `PAUSED` — the three `state/ConnectAction` maps to `UNAVAILABLE`) while the
     * multi-minute probe was running. A give-up state (`BLACKHOLED`) is **not** one of those and so
     * still DELIVERS: `ConnectAction` maps it to `RECONNECT`, since choosing another server is
     * exactly the remedy a give-up offers. The winner is discarded rather than delivered: connecting
     * it now would either no-op ("VPN already running") while `ActiveProfileRepository`'s active id
     * still gets overwritten to the new (never-actually-connected) profile, misreporting which
     * server traffic is on.
     */
    STATE_CHANGED,
}

/**
 * Orchestrates one "Connect fastest" run: resolve [profile]'s candidate pool, probe it through the
 * existing, UNMODIFIED [PingCoordinator], and publish the fastest successful responder as [winnerId]
 * — or, if none qualifies, report why via `onOutcome` (constructor parameter). Framework-free (no
 * `AndroidViewModel`/`Context`/Room types in this file) so the sequencing this class owns — job
 * replacement via [generation], the delivery-time re-gate, cross-run "busy" detection, and
 * cancellation cleanup — is directly unit-testable with `kotlinx-coroutines-test`, the same way
 * `PingCoordinatorTest` exercises [PingCoordinator] itself without any Android framework class.
 *
 * `VpnViewModel` owns exactly one instance and supplies its ViewModel-scoped dependencies
 * ([resolvePool] closes over its `dao`, [probe] closes over `probeProfile`, [canConnect] closes over
 * its `connectionState`) as constructor closures — see `VpnViewModel.connectFastest` for the
 * production wiring, and this class's own KDoc sections below for what each closure is trusted to do.
 *
 * ### Delivery-time re-gate (Task 10 review Important 1)
 * A run can take up to `timeout * ceil(n / concurrency)` — minutes, at the ping-test preference
 * bounds. [canConnect] (a snapshot-read closure, NOT a one-time value) is checked again immediately
 * before [winnerId] is set, not only at whatever moment the caller decided to start the run. Without
 * this, a winner could be delivered after the connection state changed underneath the probe:
 * `VpnViewModel.connect()` would silently keep the OLD tunnel up (`XrayVpnService.startVpn`'s "VPN
 * already running" no-op) while unconditionally overwriting `ActiveProfileRepository`'s active
 * profile id to the NEW one — the UI would then report the WRONG server as connected while traffic
 * kept flowing through the old one.
 *
 * ### Pool source (Task 10 review Important 2)
 * [resolvePool] is expected to be backed by [FailoverPoolResolver] in production — the same object
 * auto-failover itself rotates through (see that object's KDoc: "the single place that changes when
 * user-curated pools land"). This class does not hardcode that dependency so it stays injectable for
 * tests, but it must not be given a different pool-selection policy in production without updating
 * both KDocs to cross-reference the coupling.
 */
internal class FastestConnectRunner(
    private val scope: CoroutineScope,
    private val pingCoordinator: PingCoordinator,
    private val pingStates: MutableStateFlow<Map<Long, PingState>>,
    private val resolvePool: suspend (Profile) -> List<Profile>,
    private val loadPreferences: suspend () -> PingPreferences,
    private val probe: suspend (Profile, PingPreferences) -> PingState,
    private val canConnect: () -> Boolean,
    private val onOutcome: (FastestConnectOutcome) -> Unit,
) {
    // Neither of the two fields below is synchronized: [start] and [cancel] must only ever be
    // called from the confined thread [scope] runs on (production: the main thread, via Compose
    // callbacks through `viewModelScope`, which is `Dispatchers.Main.immediate`). That confinement
    // is exactly what makes the ordering argument in [generation]'s comment hold — a concurrent
    // caller from another thread would race both fields. This class accepts an arbitrary
    // [CoroutineScope] for testability (see `FastestConnectRunnerTest`, which drives it from a
    // single-threaded `TestScope`), but production callers must preserve main-thread confinement.
    private var runningJob: Job? = null
    private var runningProfile: Profile? = null
    private var runningIds: Set<Long> = emptySet()

    // Guards the finally-block "am I still the active run" check below rather than comparing
    // against `runningJob` itself: with a `Dispatchers.Main.immediate`-backed scope (production:
    // viewModelScope), calling `.cancel()` from a Compose callback already on the main thread can
    // resume — and run the `finally` of — the job being replaced SYNCHRONOUSLY, inside this very
    // `start()` call, before `runningJob = job` below is even assigned. A captured `Int` local has
    // no such ordering hazard: it is fully assigned before the coroutine literal is even created.
    private var generation = 0

    private val _active = MutableStateFlow(false)
    /** True while a run is in flight. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _winnerId = MutableStateFlow<Long?>(null)
    /** The winning profile id from the most recent completed run, or null once consumed/discarded. */
    val winnerId: StateFlow<Long?> = _winnerId.asStateFlow()

    /** Consumes [winnerId] so it does not re-fire once the caller has acted on it. */
    fun consumeWinner() {
        _winnerId.value = null
    }

    /**
     * Starts probing [profile]'s pool, replacing (cancelling) any run already in flight rather than
     * stacking a second one. A second request for the same [FailoverPoolResolver] partition instead
     * coalesces onto the existing run: cancelling its waiters would leave its uninterruptible native
     * probes holding [PingCoordinator]'s slots, so immediately re-probing the same ids can turn the
     * whole pool into prompt [PingState.Unavailable] results. Any winner from a genuinely
     * superseded/earlier run that was never consumed is discarded — a new run must never let a stale
     * winner fire later.
     */
    fun start(profile: Profile) {
        if (
            _active.value &&
                runningProfile?.let { FailoverPoolResolver.samePool(it, profile) } == true
        ) {
            return
        }

        // Clear the run being replaced before advancing [generation]. Its cancelled finally must
        // not clear a state the new run may already have marked Testing.
        pingStates.update { clearStaleTesting(it, runningIds) }
        runningIds = emptySet()
        val myGeneration = ++generation
        runningJob?.cancel()
        _winnerId.value = null
        runningProfile = profile
        val job = scope.launch {
            _active.value = true
            var ids: Set<Long> = emptySet()
            try {
                val pool = resolvePool(profile)
                ids = pool.mapTo(HashSet()) { it.id }
                if (generation != myGeneration) return@launch
                runningIds = ids
                if (pool.isEmpty()) {
                    // Reachable when the backing subscription is deleted mid-run. Every other
                    // no-winner exit reports; this one must too, or the progress row simply
                    // vanishes with no explanation.
                    if (generation == myGeneration) {
                        onOutcome(FastestConnectOutcome.NO_RESPONSE)
                    }
                    return@launch
                }
                // Best-effort snapshot, read before runGroup's own cross-run de-dup would apply —
                // see FastestConnectOutcome.BUSY's doc for why this distinction matters.
                val alreadyInFlight = pool.any { pingStates.value[it.id] is PingState.Testing }
                val byId = pool.associateBy { it.id }
                val prefs = loadPreferences()
                pingCoordinator.runGroup(
                    ids = byId.keys.toList(),
                    concurrency = prefs.concurrency,
                    onUpdate = { id, state ->
                        if (generation == myGeneration) {
                            pingStates.update { it + (id to state) }
                        }
                    },
                    probe = { id -> probe(byId.getValue(id), prefs) },
                )
                if (generation != myGeneration) return@launch
                val winner = pickFastest(pingStates.value, pool)
                if (winner == null) {
                    onOutcome(if (alreadyInFlight) FastestConnectOutcome.BUSY else FastestConnectOutcome.NO_RESPONSE)
                    return@launch
                }
                if (!canConnect()) {
                    onOutcome(FastestConnectOutcome.STATE_CHANGED)
                    return@launch
                }
                if (generation == myGeneration) {
                    _winnerId.value = winner.id
                }
            } finally {
                // PingCoordinator.runGroup rethrows a caller-cancellation CancellationException from
                // inside its per-id `finally`, ahead of onUpdate, so an id still in flight when this
                // Job is cancelled never gets a terminal PingState of its own — without this reset it
                // would spin on Testing forever. Run for both cancellation and normal completion (a
                // no-op on the happy path, since runGroup already resolved every id by then).
                // Only the most recently started run may report itself finished: a superseded run
                // reaching this finally (its own cancellation unwinding after a newer run started)
                // must not stomp the newer run's state, outcome, winner, or still-in-flight `true`.
                if (generation == myGeneration) {
                    pingStates.update { clearStaleTesting(it, ids) }
                    runningIds = emptySet()
                    runningProfile = null
                    _active.value = false
                }
            }
        }
        runningJob = job
    }

    /** Stops an in-flight run. See [start]'s doc for cancellation semantics. */
    fun cancel() {
        // An explicit Cancel is not a same-pool coalesce request: a following tap must begin a
        // fresh run even while this job's cancellation cleanup is waiting to run.
        runningProfile = null
        runningJob?.cancel()
    }
}
