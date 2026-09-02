package com.justme.xtls_core_proxy.state

import com.justme.xtls_core_proxy.log.VpnConnectionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Task 7: Reconnect out of a give-up is a stop-then-start SEQUENCE, not a plain start. The
 * sequencing lives in [ReconnectFlow] precisely so it can be driven here, framework-free, the same
 * way `FastestConnectRunnerTest` drives the other piece of connect orchestration.
 *
 * Every assertion below is about ORDER or COUNT — co-occurrence is not enough. The defects they pin
 * are millisecond races against `XrayVpnService`'s teardown, so they would otherwise present as
 * "works on my device, not theirs".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReconnectFlowTest {

    /** One flow under test, recording every dispatch into [calls] in the order it happened. */
    private fun flowFor(
        state: MutableStateFlow<VpnConnectionState>,
        calls: MutableList<String>,
        scheduler: TestCoroutineScheduler,
        userStopGeneration: MutableStateFlow<Long> = MutableStateFlow(0L),
    ) = ReconnectFlow(
        connectionState = state,
        stop = { calls += "stop" },
        start = { calls += "start:$it" },
        onTimeout = { calls += "timeout" },
        onSuperseded = { calls += "superseded" },
        dispatcher = StandardTestDispatcher(scheduler),
        userStopGeneration = userStopGeneration,
    )

    @Test
    fun reconnectStartsOnlyAfterTheStopHasSettled() = runTest {
        // Dispatching ACTION_START while the service is still running would hit startVpn's
        // "VPN already running" early return and, worse, leave ActiveProfileRepository naming a
        // server the tunnel never carried.
        val state = MutableStateFlow(VpnConnectionState.BLACKHOLED)
        val calls = mutableListOf<String>()
        val flow = flowFor(state, calls, testScheduler)

        flow.run(profileId = 7L, scope = this)
        runCurrent()
        assertEquals(listOf("stop"), calls)

        state.value = VpnConnectionState.DISCONNECTED
        runCurrent()
        assertEquals(listOf("stop", "start:7"), calls)

        // Let the start-verification await settle so this test ends on a completed flow rather than
        // on runTest draining it — the re-dispatch it guards has its own tests below.
        state.value = VpnConnectionState.CONNECTING
        runCurrent()
    }

    @Test
    fun aStopThatNeverSettlesReportsRatherThanHanging() = runTest {
        val state = MutableStateFlow(VpnConnectionState.BLACKHOLED)
        val calls = mutableListOf<String>()
        val flow = flowFor(state, calls, testScheduler)

        flow.run(profileId = 7L, scope = this)
        advanceTimeBy(ReconnectFlow.STOP_TIMEOUT_MS + 1); runCurrent()

        assertEquals("must never dispatch a start it could not sequence", listOf("stop", "timeout"), calls)
    }

    @Test
    fun aStartSwallowedByTheServiceDestructionWindowIsReDispatchedOnce() = runTest {
        // stopVpn publishes DISCONNECTED about a dozen lines BEFORE it calls stopSelf(), so a start
        // dispatched the instant that state lands can reach AMS inside the service's own
        // destruction window: onStartCommand runs, then the pending stopSelf() tears the new
        // session down along with the old one. The observable symptom is the state never leaving
        // DISCONNECTED — the user taps Reconnect and the VPN ends up off, silently.
        val state = MutableStateFlow(VpnConnectionState.BLACKHOLED)
        val calls = mutableListOf<String>()
        val flow = flowFor(state, calls, testScheduler)

        flow.run(profileId = 7L, scope = this)
        runCurrent()
        state.value = VpnConnectionState.DISCONNECTED
        runCurrent()
        assertEquals(listOf("stop", "start:7"), calls)

        advanceTimeBy(ReconnectFlow.START_VERIFY_MS + 1); runCurrent()
        assertEquals(
            "a start swallowed by the destruction window must be re-dispatched",
            listOf("stop", "start:7", "start:7"),
            calls
        )

        // Bounded at exactly one: a start that fails for a REAL reason (no profile, permission
        // revoked) fails the same way twice, and must then stop rather than loop forever.
        advanceTimeBy(ReconnectFlow.START_VERIFY_MS * 4); runCurrent()
        assertEquals(
            "the re-dispatch must not become a retry loop",
            listOf("stop", "start:7", "start:7"),
            calls
        )
    }

    @Test
    fun aStartThatTookIsNeverReDispatched() = runTest {
        val state = MutableStateFlow(VpnConnectionState.BLACKHOLED)
        val calls = mutableListOf<String>()
        val flow = flowFor(state, calls, testScheduler)

        flow.run(profileId = 7L, scope = this)
        runCurrent()
        state.value = VpnConnectionState.DISCONNECTED
        runCurrent()
        // startVpn announces CONNECTING almost immediately: the start took, and a second one would
        // be a pointless intent into a healthy bring-up. Keep observing through the verification
        // window: CONNECTING is only an announcement, not proof that the session survived.
        state.value = VpnConnectionState.CONNECTING
        runCurrent()
        assertEquals(
            "CONNECTING is provisional until the destruction window has passed",
            7L,
            flow.reconnectingProfileId.value,
        )

        advanceTimeBy(ReconnectFlow.START_VERIFY_MS + 1); runCurrent()
        assertEquals(listOf("stop", "start:7"), calls)
        assertNull(flow.reconnectingProfileId.value)
    }

    @Test
    fun aConnectingThenDisconnectedBounceIsReDispatchedExactlyOnce() = runTest {
        // The former destruction window could publish CONNECTING before returning to DISCONNECTED.
        // CONNECTING must therefore not end verification.
        val state = MutableStateFlow(VpnConnectionState.BLACKHOLED)
        val calls = mutableListOf<String>()
        val flow = flowFor(state, calls, testScheduler)

        flow.run(profileId = 7L, scope = this)
        runCurrent()
        state.value = VpnConnectionState.DISCONNECTED
        runCurrent()
        state.value = VpnConnectionState.CONNECTING
        runCurrent()
        state.value = VpnConnectionState.DISCONNECTED
        runCurrent()

        assertEquals(
            "a start that bounces back to DISCONNECTED must be re-dispatched",
            listOf("stop", "start:7", "start:7"),
            calls,
        )

        // The recovery start itself is checked once more, but cannot create a third start.
        advanceTimeBy(ReconnectFlow.START_VERIFY_MS + 1); runCurrent()
        assertEquals(listOf("stop", "start:7", "start:7"), calls)
    }

    @Test
    fun anErrorDuringStartVerificationIsRetriedRatherThanCountedAsSuccess() = runTest {
        val state = MutableStateFlow(VpnConnectionState.BLACKHOLED)
        val calls = mutableListOf<String>()
        val flow = flowFor(state, calls, testScheduler)

        flow.run(profileId = 7L, scope = this)
        runCurrent()
        state.value = VpnConnectionState.DISCONNECTED
        runCurrent()
        state.value = VpnConnectionState.ERROR
        runCurrent()

        assertEquals(
            "ERROR is a failed start, so the one bounded recovery dispatch is used",
            listOf("stop", "start:7", "start:7"),
            calls,
        )
    }

    @Test
    fun aSecondReconnectWhileOneIsInFlightIsRefusedAndReported() = runTest {
        // The teardown window can last seconds (CONTAINED_BY_LIVE_TUNNEL closes a real Xray core in
        // it) while the state stays BLACKHOLED, so a re-tap is the expected user response. A second
        // stop() landing after the first flow's start has set `running = true` takes the FULL
        // teardown and kills the session the user just asked for.
        val state = MutableStateFlow(VpnConnectionState.BLACKHOLED)
        val calls = mutableListOf<String>()
        val flow = flowFor(state, calls, testScheduler)

        assertNotNull(flow.run(profileId = 7L, scope = this))
        runCurrent()
        assertEquals(listOf("stop"), calls)

        assertNull(
            "a contending reconnect must be refused, not queued and not swapped in",
            flow.run(profileId = 9L, scope = this)
        )
        runCurrent()
        assertEquals(
            "the refusal must be reported, never a silent drop, and must not re-dispatch stop",
            listOf("stop", "superseded"),
            calls
        )

        state.value = VpnConnectionState.DISCONNECTED
        runCurrent()
        assertEquals(
            "the FIRST request wins the slot, matching this branch's parked-connect rule",
            listOf("stop", "superseded", "start:7"),
            calls
        )
    }

    @Test
    fun aCancelledReconnectNeverDispatchesItsStart() = runTest {
        // The in-app Disconnect must outrank a reconnect the user asked for a moment earlier: the
        // flow's settle signal is source-blind, so without this it reads the user's OWN stop as its
        // own teardown completing and starts the VPN back up — inverting an explicit "off".
        val state = MutableStateFlow(VpnConnectionState.BLACKHOLED)
        val calls = mutableListOf<String>()
        val flow = flowFor(state, calls, testScheduler)

        flow.run(profileId = 7L, scope = this)
        runCurrent()
        assertEquals(listOf("stop"), calls)

        flow.cancel()
        runCurrent()

        // The stop the user asked for still lands; the cancelled flow must not act on it.
        state.value = VpnConnectionState.DISCONNECTED
        runCurrent()
        advanceTimeBy(ReconnectFlow.START_VERIFY_MS + 1); runCurrent()
        assertEquals("a cancelled reconnect must never dispatch its start", listOf("stop"), calls)
    }

    @Test
    fun aCancelledReconnectReleasesTheGuardForALaterOne() = runTest {
        val state = MutableStateFlow(VpnConnectionState.BLACKHOLED)
        val calls = mutableListOf<String>()
        val flow = flowFor(state, calls, testScheduler)

        flow.run(profileId = 7L, scope = this)
        runCurrent()
        flow.cancel()
        assertNull(
            "the guard must be released by the time cancel() returns — the caller's very next "
                + "action can be another reconnect, and a posting dispatcher has not run the "
                + "cancelled job's own cleanup yet",
            flow.reconnectingProfileId.value
        )

        // Deliberately NO runCurrent() before this: the cancelled job's cleanup is still pending,
        // so the successor claims the slot first and the stale cleanup lands afterwards. That is
        // the interleaving the generation guard exists for.
        assertNotNull(
            "a cancel must not wedge the guard shut",
            flow.run(profileId = 9L, scope = this)
        )
        runCurrent()
        assertEquals(
            "a cancelled job's cleanup must not clear its successor's slot",
            9L,
            flow.reconnectingProfileId.value
        )
        state.value = VpnConnectionState.DISCONNECTED
        runCurrent()
        assertEquals(listOf("stop", "stop", "start:9"), calls)
    }

    @Test
    fun theReconnectTargetIsPublishedWhileInFlightAndClearedAfter() = runTest {
        // A bare Boolean would make every row in the list render "Connecting…" while only one
        // server is actually being reconnected. The id is what scopes the label to its own row.
        val state = MutableStateFlow(VpnConnectionState.BLACKHOLED)
        val calls = mutableListOf<String>()
        val flow = flowFor(state, calls, testScheduler)

        assertNull(flow.reconnectingProfileId.value)

        flow.run(profileId = 7L, scope = this)
        assertEquals(
            "published synchronously, so the very next recomposition already renders the target",
            7L,
            flow.reconnectingProfileId.value
        )
        runCurrent()

        state.value = VpnConnectionState.DISCONNECTED
        runCurrent()
        state.value = VpnConnectionState.CONNECTING
        runCurrent()
        assertEquals(
            "CONNECTING stays guarded until the destruction window closes",
            7L,
            flow.reconnectingProfileId.value,
        )
        advanceTimeBy(ReconnectFlow.START_VERIFY_MS + 1); runCurrent()
        assertNull("cleared after the verified start window", flow.reconnectingProfileId.value)
    }

    @Test
    fun theInFlightGuardReleasesSoALaterReconnectIsAdmitted() = runTest {
        // Without this, Reconnect would work exactly once per ReconnectFlow instance — i.e. once
        // per ViewModel, since VpnViewModel owns exactly one. NOT once per process: a new
        // ViewModel (a fresh Activity after the old one was finished) builds a fresh flow with a
        // released guard, which is what made the wedge look intermittent rather than permanent.
        val state = MutableStateFlow(VpnConnectionState.BLACKHOLED)
        val calls = mutableListOf<String>()
        val flow = flowFor(state, calls, testScheduler)

        flow.run(profileId = 7L, scope = this)
        runCurrent()
        state.value = VpnConnectionState.DISCONNECTED
        runCurrent()
        state.value = VpnConnectionState.CONNECTING
        runCurrent()
        assertEquals(listOf("stop", "start:7"), calls)

        advanceTimeBy(ReconnectFlow.START_VERIFY_MS + 1); runCurrent()
        state.value = VpnConnectionState.BLACKHOLED
        assertNotNull(
            "the guard must release once the flow completes",
            flow.run(profileId = 9L, scope = this)
        )
        runCurrent()
        assertEquals(listOf("stop", "start:7", "stop"), calls)
    }

    @Test
    fun aUserInitiatedStopDuringSettleAbandonsWithoutStarting() = runTest {
        // QS tile / notification Stop must outrank an in-flight reconnect the same way in-app
        // Disconnect does via cancel(). The flow's own stop does NOT bump userStopGeneration —
        // only those surfaces do — so a generation bump mid-settle is never "our" teardown.
        val state = MutableStateFlow(VpnConnectionState.BLACKHOLED)
        val userStops = MutableStateFlow(0L)
        val calls = mutableListOf<String>()
        val flow = flowFor(state, calls, testScheduler, userStops)

        flow.run(profileId = 7L, scope = this)
        runCurrent()
        assertEquals(listOf("stop"), calls)

        userStops.value = 1L
        runCurrent()
        // The external stop also publishes DISCONNECTED; if the flow only watched connectionState
        // it would treat that as settle and start. Watching the generation is what keeps Off off.
        state.value = VpnConnectionState.DISCONNECTED
        runCurrent()
        advanceTimeBy(ReconnectFlow.START_VERIFY_MS + 1); runCurrent()
        assertEquals(
            "a tile/notification Stop must abandon the reconnect, not complete it",
            listOf("stop"),
            calls,
        )
        assertNull(flow.reconnectingProfileId.value)
    }

    @Test
    fun aUserStopAlreadyVisibleWhenTheSettleAwaitArmsStillAbandons() = runTest {
        // I-M. Both signals are StateFlows, so BOTH replay the instant collection starts. The test
        // above feeds them one at a time; this one lands them together, which is what happens when
        // the tile Stop and the resulting DISCONNECTED both arrive in the window between the
        // generation snapshot and the await arming. The await then sees two terminal values at once
        // and `merge` decides which one lands first — so the abort must not be able to LOSE that
        // race, or I-A's own fix completes the reconnect and turns the user's Off back into On.
        //
        // The window is modelled inside `stop()` because that is the only code that runs in it.
        val state = MutableStateFlow(VpnConnectionState.BLACKHOLED)
        val userStops = MutableStateFlow(0L)
        val calls = mutableListOf<String>()
        val flow = ReconnectFlow(
            connectionState = state,
            stop = {
                calls += "stop"
                userStops.value = 1L
                state.value = VpnConnectionState.DISCONNECTED
            },
            start = { calls += "start:$it" },
            onTimeout = { calls += "timeout" },
            onSuperseded = { calls += "superseded" },
            dispatcher = StandardTestDispatcher(testScheduler),
            userStopGeneration = userStops,
        )

        flow.run(profileId = 7L, scope = this)
        runCurrent()
        advanceTimeBy(ReconnectFlow.START_VERIFY_MS + 1); runCurrent()

        assertEquals(
            "a Stop already visible when the await arms must abandon, never settle",
            listOf("stop"),
            calls,
        )
        assertNull(flow.reconnectingProfileId.value)
    }

    @Test
    fun aUserInitiatedStopDuringStartVerifyDoesNotReDispatchStart() = runTest {
        val state = MutableStateFlow(VpnConnectionState.BLACKHOLED)
        val userStops = MutableStateFlow(0L)
        val calls = mutableListOf<String>()
        val flow = flowFor(state, calls, testScheduler, userStops)

        flow.run(profileId = 7L, scope = this)
        runCurrent()
        state.value = VpnConnectionState.DISCONNECTED
        runCurrent()
        assertEquals(listOf("stop", "start:7"), calls)

        // User Stop after the first start was dispatched: the verify await would otherwise time
        // out on DISCONNECTED and re-dispatch start — inverting Off into On a second time.
        userStops.value = 1L
        state.value = VpnConnectionState.DISCONNECTED
        runCurrent()
        advanceTimeBy(ReconnectFlow.START_VERIFY_MS + 1); runCurrent()
        assertEquals(
            "a user Stop during start-verify must not re-dispatch start",
            listOf("stop", "start:7"),
            calls,
        )
    }

    @Test
    fun theFlowsOwnStopDoesNotCountAsAUserInitiatedAbort() = runTest {
        // Regression: if the flow aborted on every stop signal, its own ACTION_STOP would cancel
        // every reconnect before start. Only a generation bump AFTER arming aborts.
        val state = MutableStateFlow(VpnConnectionState.BLACKHOLED)
        val userStops = MutableStateFlow(0L)
        val calls = mutableListOf<String>()
        val flow = flowFor(state, calls, testScheduler, userStops)

        flow.run(profileId = 7L, scope = this)
        runCurrent()
        state.value = VpnConnectionState.DISCONNECTED
        runCurrent()
        state.value = VpnConnectionState.CONNECTING
        runCurrent()
        assertEquals(listOf("stop", "start:7"), calls)
        assertEquals(0L, userStops.value)
    }
}
