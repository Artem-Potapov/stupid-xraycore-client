package com.justme.xtls_core_proxy.failover

import com.justme.xtls_core_proxy.db.Profile
import com.justme.xtls_core_proxy.state.PingCoordinator
import com.justme.xtls_core_proxy.state.PingPreferences
import com.justme.xtls_core_proxy.state.PingState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 10 review Important 1 (delivery-time re-gate), Important 2 (pool source), Minor 3/4
 * (distinct no-winner messaging), and Minor 7 (proportionate coverage where the risk actually
 * lives): [FastestConnectRunner] owns the sequencing none of the pure functions
 * (`pickFastest`/`clearStaleTesting`) exercise on their own — job replacement, the generation-
 * counter cancellation guard, the delivery-time re-gate, and busy-vs-no-response messaging.
 *
 * Framework-free, exactly like [PingCoordinator] itself, so this drives the REAL coordinator (not a
 * fake) with `kotlinx-coroutines-test`, the same pattern `PingCoordinatorTest` uses.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FastestConnectRunnerTest {

    private fun profile(id: Long, subscriptionId: Long? = null) =
        Profile(id = id, name = "s$id", config = "{}", subscriptionId = subscriptionId)

    @Test
    fun start_supersedingARun_cancelsTheOldOne_withoutClearingTheNewRunsActiveFlag() = runTest {
        val coordinator = PingCoordinator(nativeCeiling = 5)
        val pingStates = MutableStateFlow<Map<Long, PingState>>(emptyMap())
        val releaseA = CompletableDeferred<Unit>()
        val releaseB = CompletableDeferred<Unit>()
        val outcomes = mutableListOf<FastestConnectOutcome>()
        val runner = FastestConnectRunner(
            scope = this,
            pingCoordinator = coordinator,
            pingStates = pingStates,
            resolvePool = { p -> listOf(p) },
            loadPreferences = { PingPreferences.DEFAULT },
            probe = { p, _ ->
                when (p.id) {
                    1L -> { releaseA.await(); PingState.Success(10L) }
                    else -> { releaseB.await(); PingState.Success(20L) }
                }
            },
            canConnect = { true },
            onOutcome = { outcomes += it },
        )

        runner.start(profile(1, subscriptionId = 1L))
        runCurrent()
        assertTrue("run A must be active immediately after starting", runner.active.value)

        // Supersede A with B before A's probe ever returns. A's cancellation-driven finally will
        // run at some point after this — the defect this test pins is that finally incorrectly
        // clearing `active` for the NEW run.
        runner.start(profile(2, subscriptionId = 2L))
        runCurrent()
        assertTrue(
            "a superseded run's finally must not clear the new run's still-in-flight active flag",
            runner.active.value
        )
        assertNull("run B has not resolved yet", runner.winnerId.value)

        releaseB.complete(Unit)
        advanceUntilIdle()

        assertEquals(2L, runner.winnerId.value)
        assertFalse("run B's own finally must clear active once IT finishes", runner.active.value)
        assertTrue("no outcome message on the winning path", outcomes.isEmpty())

        // A's orphaned probe coroutine was cancelled when A was superseded; unblocking it here (if
        // it is still suspended) is just cleanup, not part of the assertion.
        releaseA.complete(Unit)
    }

    /**
     * Task 10 review round 2, Minor 4: the previous supersede test above uses DISJOINT pools ({1}
     * then {2}). The realistic production case is two long-presses on the SAME subscription in
     * quick succession — an IDENTICAL pool both times — where the superseded run's
     * `clearStaleTesting`/`PingCoordinator.inFlight` release races the new run's own `runGroup`
     * admission of the SAME ids. This is not a defect hunt (the review traced this race to benign in
     * production); this test pins that already-correct behaviour so a future change cannot silently
     * regress it.
     */
    @Test
    fun start_supersedingWithAnIdenticalPool_stillResolvesTheNewRunCorrectly_noFalseBusy() = runTest {
        val coordinator = PingCoordinator(nativeCeiling = 5)
        val pingStates = MutableStateFlow<Map<Long, PingState>>(emptyMap())
        val pool = listOf(profile(1), profile(2))
        val releaseSlowId = CompletableDeferred<Unit>()
        val outcomes = mutableListOf<FastestConnectOutcome>()
        val runner = FastestConnectRunner(
            scope = this,
            pingCoordinator = coordinator,
            pingStates = pingStates,
            // The SAME pool object both times — e.g. two long-presses on profiles in one subscription
            // before the first run finished — as opposed to the disjoint-pool supersede test above.
            resolvePool = { pool },
            loadPreferences = { PingPreferences.DEFAULT },
            probe = { p, _ ->
                if (p.id == 1L) releaseSlowId.await()
                PingState.Success(p.id) // lower id = lower (== faster) latency
            },
            canConnect = { true },
            onOutcome = { outcomes += it },
        )

        runner.start(profile(1))
        runCurrent() // id=2 resolves immediately; id=1 stays in flight, gated on releaseSlowId
        assertEquals(PingState.Success(2L), pingStates.value[2L])
        assertEquals(PingState.Testing, pingStates.value[1L])

        // Second long-press on the SAME subscription while id=1's probe is still in flight from the
        // first run — id=1 is still admitted (in PingCoordinator's cross-run inFlight set) at the
        // exact moment this call resolves the (identical) pool and is about to re-admit it.
        runner.start(profile(1))
        runCurrent()

        releaseSlowId.complete(Unit)
        advanceUntilIdle()

        assertFalse(
            "superseding with the IDENTICAL pool must not make the new run see its own ids as " +
                "falsely busy",
            outcomes.contains(FastestConnectOutcome.BUSY)
        )
        assertEquals(1L, runner.winnerId.value) // profile(1)'s latency (1) beats profile(2)'s (2)
        assertFalse(runner.active.value)
        assertEquals(PingState.Success(1L), pingStates.value[1L])
        assertEquals(PingState.Success(2L), pingStates.value[2L])
    }

    @Test
    fun start_sameSubscriptionSupersede_keepsTheExistingNativeProbesInsteadOfPaintingThePoolUnavailable() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val runnerScope = CoroutineScope(dispatcher)
        val nativeScope = CoroutineScope(dispatcher)
        val coordinator = PingCoordinator(nativeCeiling = 5)
        val pingStates = MutableStateFlow<Map<Long, PingState>>(emptyMap())
        val nativeGate = CompletableDeferred<Unit>()
        val pool = (1L..5L).map { profile(it, subscriptionId = 42L) }
        val outcomes = mutableListOf<FastestConnectOutcome>()
        var nativeCalls = 0
        val runner = FastestConnectRunner(
            scope = runnerScope,
            pingCoordinator = coordinator,
            pingStates = pingStates,
            resolvePool = { pool },
            loadPreferences = { PingPreferences.DEFAULT.copy(concurrency = 5) },
            probe = { profile, _ ->
                coordinator.probeWithBackstop(
                    scope = nativeScope,
                    backstopMs = 60_000L,
                    context = dispatcher,
                    nativeCall = {
                        nativeCalls++
                        nativeGate.await()
                        Result.success(profile.id)
                    },
                )
            },
            canConnect = { true },
            onOutcome = { outcomes += it },
        )

        runner.start(pool.first())
        assertEquals(0, coordinator.availableNativeSlots())
        assertEquals(5, nativeCalls)

        // This is Main.immediate-shaped: cancelling a runner waiter does not cancel the sibling
        // native calls, which still own every slot when the same-subscription request arrives.
        runner.start(pool.last())

        assertTrue("the equivalent run must remain active while its native probes own the slots", runner.active.value)
        assertTrue("the supersede must not turn every in-flight row into N/A", pingStates.value.values.none {
            it == PingState.Unavailable
        })
        assertTrue("the superseded generation must not report a no-response outcome", outcomes.isEmpty())
        assertEquals("a same-pool request must not launch another native probe set", 5, nativeCalls)

        nativeGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1L, runner.winnerId.value)
        assertFalse(runner.active.value)
        assertTrue(outcomes.isEmpty())
        assertEquals(5, coordinator.availableNativeSlots())
    }

    @Test
    fun cancel_resetsInFlightPoolIdsBackToIdle_andClearsActive() = runTest {
        val coordinator = PingCoordinator(nativeCeiling = 5)
        val pingStates = MutableStateFlow<Map<Long, PingState>>(emptyMap())
        val gate = CompletableDeferred<Unit>()
        val runner = FastestConnectRunner(
            scope = this,
            pingCoordinator = coordinator,
            pingStates = pingStates,
            resolvePool = { listOf(profile(1), profile(2)) },
            loadPreferences = { PingPreferences.DEFAULT.copy(concurrency = 2) },
            probe = { _, _ -> gate.await(); PingState.Success(1L) },
            canConnect = { true },
            onOutcome = {},
        )

        runner.start(profile(1))
        runCurrent()
        assertEquals(PingState.Testing, pingStates.value[1L])
        assertEquals(PingState.Testing, pingStates.value[2L])

        runner.cancel()
        advanceUntilIdle()

        assertEquals(
            "a cancelled run's still-in-flight ids must reset to Idle, not spin on Testing forever",
            PingState.Idle,
            pingStates.value[1L]
        )
        assertEquals(PingState.Idle, pingStates.value[2L])
        assertFalse(runner.active.value)
        assertNull(runner.winnerId.value)

        gate.complete(Unit) // cleanup only; the orphaned probe coroutine may still be pending
    }

    @Test
    fun start_winnerFound_butStateNoLongerConnectable_discardsWinner_reportsStateChanged() = runTest {
        val coordinator = PingCoordinator(nativeCeiling = 5)
        val pingStates = MutableStateFlow<Map<Long, PingState>>(emptyMap())
        val outcomes = mutableListOf<FastestConnectOutcome>()
        val runner = FastestConnectRunner(
            scope = this,
            pingCoordinator = coordinator,
            pingStates = pingStates,
            resolvePool = { listOf(profile(1)) },
            loadPreferences = { PingPreferences.DEFAULT },
            probe = { _, _ -> PingState.Success(5L) },
            canConnect = { false }, // connection state left the connectable set mid-probe
            onOutcome = { outcomes += it },
        )

        runner.start(profile(1))
        advanceUntilIdle()

        assertNull(
            "a winner found after the state stopped being connectable must never be delivered",
            runner.winnerId.value
        )
        assertEquals(listOf(FastestConnectOutcome.STATE_CHANGED), outcomes)
    }

    @Test
    fun start_winnerFound_stateStillConnectable_setsWinnerId() = runTest {
        val coordinator = PingCoordinator(nativeCeiling = 5)
        val pingStates = MutableStateFlow<Map<Long, PingState>>(emptyMap())
        val outcomes = mutableListOf<FastestConnectOutcome>()
        val runner = FastestConnectRunner(
            scope = this,
            pingCoordinator = coordinator,
            pingStates = pingStates,
            resolvePool = { listOf(profile(1)) },
            loadPreferences = { PingPreferences.DEFAULT },
            probe = { _, _ -> PingState.Success(5L) },
            canConnect = { true },
            onOutcome = { outcomes += it },
        )

        runner.start(profile(1))
        advanceUntilIdle()

        assertEquals(1L, runner.winnerId.value)
        assertTrue(outcomes.isEmpty())
    }

    @Test
    fun start_noWinner_noPreExistingInFlightId_reportsNoResponse() = runTest {
        val coordinator = PingCoordinator(nativeCeiling = 5)
        val pingStates = MutableStateFlow<Map<Long, PingState>>(emptyMap())
        val outcomes = mutableListOf<FastestConnectOutcome>()
        val runner = FastestConnectRunner(
            scope = this,
            pingCoordinator = coordinator,
            pingStates = pingStates,
            resolvePool = { listOf(profile(1)) },
            loadPreferences = { PingPreferences.DEFAULT },
            probe = { _, _ -> PingState.Unavailable },
            canConnect = { true },
            onOutcome = { outcomes += it },
        )

        runner.start(profile(1))
        advanceUntilIdle()

        assertEquals(listOf(FastestConnectOutcome.NO_RESPONSE), outcomes)
    }

    @Test
    fun start_noWinner_poolIdAlreadyInFlightElsewhere_reportsBusyNotNoResponse() = runTest {
        val coordinator = PingCoordinator(nativeCeiling = 5)
        // Simulates another active run (auto-ping, a manual ping test) already owning id=1 when
        // this run starts — PingCoordinator's own cross-run de-dup would silently skip re-probing
        // it, so "no server responded" would be a misleading explanation.
        val pingStates = MutableStateFlow<Map<Long, PingState>>(mapOf(1L to PingState.Testing))
        val outcomes = mutableListOf<FastestConnectOutcome>()
        val runner = FastestConnectRunner(
            scope = this,
            pingCoordinator = coordinator,
            pingStates = pingStates,
            resolvePool = { listOf(profile(1)) },
            loadPreferences = { PingPreferences.DEFAULT },
            probe = { _, _ -> PingState.Unavailable },
            canConnect = { true },
            onOutcome = { outcomes += it },
        )

        runner.start(profile(1))
        advanceUntilIdle()

        assertEquals(
            "a pool id already Testing before this run started must be reported as BUSY, not a false NO_RESPONSE",
            listOf(FastestConnectOutcome.BUSY),
            outcomes
        )
    }

    @Test
    fun anEmptyResolvedPoolReportsRatherThanVanishing() = runTest {
        // Reachable when the backing subscription is deleted mid-run. The class promises in its own
        // KDoc that every no-winner exit reports via onOutcome; this one silently dropped the run,
        // leaving the progress row to disappear with no explanation.
        val outcomes = mutableListOf<FastestConnectOutcome>()
        val runner = FastestConnectRunner(
            scope = this,
            pingCoordinator = PingCoordinator(nativeCeiling = 5),
            pingStates = MutableStateFlow(emptyMap()),
            resolvePool = { emptyList() },
            loadPreferences = { PingPreferences.DEFAULT },
            probe = { _, _ -> PingState.Unavailable },
            canConnect = { true },
            onOutcome = { outcomes += it },
        )

        runner.start(profile(1L))
        advanceUntilIdle()

        assertEquals(listOf(FastestConnectOutcome.NO_RESPONSE), outcomes)
    }
}
