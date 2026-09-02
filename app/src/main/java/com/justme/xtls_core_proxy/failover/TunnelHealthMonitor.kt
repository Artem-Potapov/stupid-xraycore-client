package com.justme.xtls_core_proxy.failover

import com.justme.xtls_core_proxy.log.LogRepository
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Atomically installs [candidate] into [slot] when the slot is empty and [stillLive] still holds.
 * Returns true when [candidate] is the live occupant. Returns false when the caller must cancel
 * [candidate] — another install won the slot, or [stillLive] flipped false under us (the
 * `stop()`/`resumePolling()` race that used to leave an orphaned poll loop).
 */
internal fun tryInstallJob(
    slot: AtomicReference<Job?>,
    stillLive: () -> Boolean,
    candidate: Job,
): Boolean {
    if (!stillLive()) return false
    if (!slot.compareAndSet(null, candidate)) return false
    if (!stillLive()) {
        // stop() raced after our CAS: retire ourselves if we still own the slot. If stop already
        // getAndSet'd us out, compareAndSet fails and stop has cancelled the candidate.
        slot.compareAndSet(candidate, null)
        return false
    }
    return true
}

/**
 * Polls [probe] on a fixed cadence and reports the tunnel unhealthy after [failureThreshold]
 * consecutive failures. **Terminal after firing:** it invokes the listener at most once per
 * [start] call, then stops polling on its own ([isStarted] and the internal job are cleared
 * before the listener runs) — [pausePolling]/[resumePolling] can never revive it after that,
 * regardless of which order they land in relative to the fire. Only a fresh [start] call resets
 * state and begins polling again.
 *
 * Screen on/off is handled by the caller (XrayVpnService) wiring a BroadcastReceiver to
 * pausePolling()/resumePolling(), exactly as the kill-switch monitor does — so this class stays
 * unit-testable without registering receivers.
 *
 * Notable properties, relative to UsageStatsForegroundAppMonitor:
 *  - A throwing source there ABORTS the loop; here a throw IS the signal, so the loop must survive
 *    it instead (both the probe and the availability check are guarded per-tick).
 *  - start() waits one interval before its first probe so a newly started Xray outbound can settle;
 *    resumePolling() still probes immediately so picking the phone back up recovers fast at zero
 *    idle cost.
 */
class TunnelHealthMonitor(
    private val probe: HealthProbe,
    private val availability: NetworkAvailability,
    private val intervalMs: Long,
    private val failureThreshold: Int,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

/**
 * The live poll-loop coroutine, or null when the monitor is stopped, paused, or terminal.
 *
 * An `AtomicReference` rather than a `@Volatile var` because creation and retirement both cross
 * threads: the poll loop nulls it from its own coroutine when it goes terminal,
 * `stop`/`pausePolling` null it from the service's lifecycle lock, and `resumePolling` may install
 * from the screen-receiver main thread while `stop` runs on `tunnelOpScope`. Retirement goes
 * through `getAndSet(null)`; creation goes through [launchAndInstallPollLoop] → [tryInstallJob]
 * (`compareAndSet` + a post-CAS `isStarted` re-check) so an install racing a stop cannot park an
 * orphaned loop in the slot after stop cleared it. The old
 * `if (job.get() != null) return; … job.set(launched)` was a non-atomic check-then-act on the
 * creation half.
 *
 * **BOTH creation paths go through it.** [start] used to finish with a plain `job.set(launched)`
 * while only [resumePolling] used the CAS, so the claim above was true of half the class. That is
 * unreachable today — `XrayVpnService` calls `start`/`stop` under one lock — but the asymmetry had
 * two orphan shapes if a caller ever stepped outside it: a `stop` landing before the `set` parked a
 * live loop behind `isStarted = false`, and a `resumePolling` winning the emptied slot had its job
 * silently **overwritten** (not cancelled) and leaked. The cost of closing it is one CAS on a slot
 * `start` has just emptied.
 *
 * NOTE this does NOT make the fire path race-free on its own — a cancel arriving between the
 * threshold check and the listener call would still land, whoever won the swap. That is why
 * the unhealthy listener is invoked unconditionally below, with no liveness gate.
 */
private val job = AtomicReference<Job?>(null)

    @Volatile private var listener: (() -> Unit)? = null
    @Volatile private var healthyListener: (() -> Unit)? = null
    @Volatile private var consecutiveFailures: Int = 0
    /** Latched after firing so we report once per transition, not once per failed probe. */
    @Volatile private var reportedUnhealthy: Boolean = false
    /** Latched after the first healthy probe of this [start], for the same once-per-transition reason. */
    @Volatile private var reportedHealthy: Boolean = false
    @Volatile private var isStarted: Boolean = false

    /**
     * [onHealthy] is optional and fires AT MOST ONCE per [start] call, on the first probe that
     * succeeds — the "the tunnel is working again" signal. Without it this class could only ever
     * report failure, so a caller that recorded a failure state had no way to learn it was stale.
     *
     * Note the parameter ORDER: [onHealthy] deliberately comes first so that the mandatory
     * [onUnhealthy] stays last and every existing `start { ... }` trailing-lambda call site keeps
     * binding to it. Swapping them would silently re-bind those callers to the optional listener.
     */
    fun start(onHealthy: (() -> Unit)? = null, onUnhealthy: () -> Unit) {
        isStarted = true
        listener = onUnhealthy
        healthyListener = onHealthy
        consecutiveFailures = 0
        reportedUnhealthy = false
        reportedHealthy = false
        // isStarted is set FIRST, above, and must stay there: it is the `stillLive` predicate the
        // install below re-checks, so assigning it after would make every start decline its own job
        // and leave the monitor silently non-polling.
        job.getAndSet(null)?.cancel()
        launchAndInstallPollLoop(probeImmediately = false)
    }

    /**
     * The one launch site for the poll loop, shared by [start] and [resumePolling].
     *
     * Shared rather than duplicated because both halves are load-bearing and were previously stated
     * twice: the `catch (Throwable)` is what stops an escaping throw reaching a scope with no
     * `CoroutineExceptionHandler` and killing the process while the VPN is up, and the
     * [tryInstallJob] install is what stops a loop being orphaned in — or overwritten out of — the
     * slot. `CancellationException` is rethrown so structured concurrency still works.
     */
    private fun launchAndInstallPollLoop(probeImmediately: Boolean) {
        val launched = scope.launch {
            try {
                runPollLoop(probeImmediately)
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                // The listener is the last unguarded throw source inside runPollLoop(); this
                // scope has no CoroutineExceptionHandler, so letting it escape would crash the
                // process while the VPN is up. Log and let the loop end, same as the kill-switch
                // monitor's launch-site guard.
                LogRepository.append("TunnelHealthMonitor poll loop aborted: ${t.message}")
            }
        }
        if (!tryInstallJob(job, stillLive = { isStarted }, launched)) {
            launched.cancel()
        }
    }

    fun stop() {
        isStarted = false
        job.getAndSet(null)?.cancel()
        listener = null
        healthyListener = null
        consecutiveFailures = 0
        reportedUnhealthy = false
        reportedHealthy = false
    }

    fun pausePolling() {
        job.getAndSet(null)?.cancel()
        // consecutiveFailures / reportedUnhealthy intentionally preserved across a pause.
    }

    fun resumePolling() {
        if (!isStarted) return
        launchAndInstallPollLoop(probeImmediately = true)
    }

    private suspend fun runPollLoop(probeImmediately: Boolean) {
        var firstTick = probeImmediately
        while (currentCoroutineContext().isActive) {
            if (!firstTick) delay(intervalMs)
            firstTick = false
            if (!currentCoroutineContext().isActive) return

            // NetworkAvailability makes no non-throw promise the way HealthProbe does (Task 2 only
            // hardened the concrete AndroidNetworkAvailability, not the interface). This scope has
            // no CoroutineExceptionHandler, so an escaping throw here would reach the default
            // handler and kill the process. Treat a throwing check the same as "offline": skip
            // this tick and reset the failure counter rather than blaming the server for it.
            val online = try {
                availability.hasUnderlyingInternet()
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                false
            }

            if (!online) {
                // Device is offline: not the server's fault. Reset so returning signal cannot trip
                // an instant rotation off a stale count.
                consecutiveFailures = 0
                continue
            }

            val healthy = try {
                probe.isHealthy()
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                false
            }

            if (healthy) {
                consecutiveFailures = 0
                reportedUnhealthy = false
                if (!reportedHealthy) {
                    reportedHealthy = true
                    // Invoked unconditionally, for the same reason as the unhealthy listener
                    // below: the latch above is already set, so a liveness gate here would swallow
                    // the recovery signal PERMANENTLY. pausePolling() preserves reportedHealthy,
                    // so the relaunched loop never retries, and clearGiveUpStateOnRecovery — whose
                    // own KDoc warns the user would otherwise be "left staring at an error state
                    // over a working connection until they stopped and restarted the VPN by hand"
                    // — would never run again for this session. Safe on a cancelled coroutine: it
                    // re-checks session ownership, giveUpOutcome, tunnel state and tunInterface
                    // under `lock` before it acts, so a stale call is a no-op.
                    //
                    // Caught locally, UNLIKE the unhealthy listener below: this scope has no
                    // CoroutineExceptionHandler, and the enclosing launch-site catch would end the
                    // whole poll loop over a throw from a caller's recovery handler. The unhealthy
                    // path can rely on that launch-site catch precisely because it is terminal —
                    // the loop ends either way. This path continues, so it must survive.
                    val h = healthyListener
                    if (h != null) {
                        try {
                            h.invoke()
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (t: Throwable) {
                            LogRepository.append("TunnelHealthMonitor recovery listener failed: ${t.message}")
                        }
                    }
                }
                continue
            }

            consecutiveFailures++
            if (consecutiveFailures >= failureThreshold && !reportedUnhealthy) {
                reportedUnhealthy = true
                // Go fully terminal BEFORE invoking the listener: a pause landing either side of
                // this point must never leave resumePolling() able to relaunch into a loop that
                // still carries a stale consecutiveFailures/reportedUnhealthy — that loop would
                // poll forever without ever being able to fire again. Clearing isStarted here
                // (not just job) makes resumePolling()'s `if (!isStarted) return` guard fire in
                // BOTH orderings, and also protects against the listener itself synchronously
                // calling resumePolling() before this coroutine has returned.
                isStarted = false
                job.getAndSet(null)
                LogRepository.append(
                    "Failover: tunnel unhealthy after $consecutiveFailures consecutive probe failures"
                )
                // NO isActive GATE. pausePolling() runs on another thread (tunnelOpScope) and
                // captures `job` before nulling it, so a screen-off landing between `job = null`
                // above and this line would make isActive false and SWALLOW the rotation request
                // — while isStarted = false makes resumePolling() early-return, leaving failover
                // dead for the whole session over a dead tunnel, with no signal anywhere.
                // Invoking on a cancelled coroutine is the safe direction: rotateTunnel re-checks
                // epoch and CONNECTED under `lock`, so a stale request is a no-op, whereas a
                // swallowed one is a permanently dead feature. The invocation is already guarded
                // against throwing by the launch-site catch.
                listener?.invoke()
                return // terminal: only a fresh start() revives this monitor
            }
        }
    }

    /** Test-only: dispose of the internal CoroutineScope. */
    internal fun shutdownForTesting() {
        scope.cancel()
    }
}
