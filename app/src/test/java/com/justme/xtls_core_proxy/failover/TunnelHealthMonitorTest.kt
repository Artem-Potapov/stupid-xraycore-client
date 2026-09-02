package com.justme.xtls_core_proxy.failover

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class TunnelHealthMonitorTest {

    private class FakeProbe(var healthy: Boolean = true, var throws: Boolean = false) : HealthProbe {
        var calls = 0
        override suspend fun isHealthy(): Boolean {
            calls++
            if (throws) throw IllegalStateException("probe blew up")
            return healthy
        }
    }

    private class FakeAvailability(var online: Boolean = true, var throws: Boolean = false) : NetworkAvailability {
        var calls = 0
        override fun hasUnderlyingInternet(): Boolean {
            calls++
            if (throws) throw IllegalStateException("availability check blew up")
            return online
        }
    }

    /**
     * Holds the most recently created monitor so [disposeMonitor] can dispose its CoroutineScope.
     * Every test in this class creates exactly one monitor via [monitor]; a test that ever needs
     * more than one must dispose the extra instance(s) itself, since this field only tracks the
     * last one created.
     */
    private var monitorUnderTest: TunnelHealthMonitor? = null

    private fun TestScope.runFirstScheduledProbe() {
        advanceTimeBy(15_000L)
        runCurrent()
    }

    private fun monitor(
        probe: HealthProbe,
        availability: NetworkAvailability,
        dispatcher: TestDispatcher,
        threshold: Int = 2,
    ): TunnelHealthMonitor {
        val created = TunnelHealthMonitor(
            probe = probe,
            availability = availability,
            intervalMs = 15_000L,
            failureThreshold = threshold,
            dispatcher = dispatcher,
        )
        monitorUnderTest = created
        return created
    }

    @After
    fun disposeMonitor() {
        // Each test's own m.stop() call is left as-is (it exercises the public API under test);
        // this disposes the internal CoroutineScope so no polling job keeps runTest's scheduler
        // non-idle across the seven tests in this class.
        monitorUnderTest?.shutdownForTesting()
        monitorUnderTest = null
    }

    @Test
    fun belowThreshold_doesNotFire_atThreshold_fires() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val probe = FakeProbe(healthy = false)
        val fired = AtomicInteger(0)
        val m = monitor(probe, FakeAvailability(), dispatcher)

        m.start { fired.incrementAndGet() }
        runFirstScheduledProbe()            // first probe runs after one interval
        assertEquals("one failure must not fire", 0, fired.get())

        advanceTimeBy(15_001L)             // second probe -> threshold reached
        runCurrent()
        assertEquals(1, fired.get())
        m.stop()
    }

    @Test
    fun start_waitsOneIntervalBeforeItsFirstProbe() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val probe = FakeProbe(healthy = true)
        val m = monitor(probe, FakeAvailability(), dispatcher)

        m.start { }
        runCurrent()
        val callsBeforeInterval = probe.calls
        advanceTimeBy(15_000L)
        runCurrent()
        val callsAfterInterval = probe.calls
        m.stop()

        assertEquals("a newly started tunnel needs one interval to settle", 0, callsBeforeInterval)
        assertEquals("the first scheduled tick must still probe", 1, callsAfterInterval)
    }

    @Test
    fun firesOncePerTransition_notPerFailedProbe() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fired = AtomicInteger(0)
        val m = monitor(FakeProbe(healthy = false), FakeAvailability(), dispatcher)

        m.start { fired.incrementAndGet() }
        advanceTimeBy(100_000L)            // many intervals' worth of failures
        runCurrent()
        assertEquals("must latch after firing, like the kill-switch monitor", 1, fired.get())
        m.stop()
    }

    @Test
    fun successResetsTheFailureCounter() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val probe = FakeProbe(healthy = false)
        val fired = AtomicInteger(0)
        val m = monitor(probe, FakeAvailability(), dispatcher)

        m.start { fired.incrementAndGet() }
        runFirstScheduledProbe()            // fail 1
        probe.healthy = true
        advanceTimeBy(15_001L); runCurrent()   // success -> reset
        probe.healthy = false
        advanceTimeBy(15_001L); runCurrent()   // fail 1 again, not 2
        assertEquals(0, fired.get())
        // A regression that kills the loop after tick 1 would also leave fired == 0, so pin that
        // the loop is genuinely alive: three ticks ran (fail, success, fail), not one.
        assertEquals("loop must keep ticking, not die silently", 3, probe.calls)
        m.stop()
    }

    @Test
    fun offline_neverFires_evenAfterManyFailures() = runTest {
        // Regression guard: airplane mode must not be blamed on the server.
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fired = AtomicInteger(0)
        val availability = FakeAvailability(online = false)
        val m = monitor(FakeProbe(healthy = false), availability, dispatcher)

        m.start { fired.incrementAndGet() }
        advanceTimeBy(100_000L); runCurrent()
        assertEquals(0, fired.get())
        // fired == 0 alone can't tell "suppressed by the offline guard" from "loop died on tick
        // 1" — pin that the loop kept ticking across the whole 100s window (ticks at t = 15000,
        // ..., 90000 -> 6 ticks), not just once.
        assertEquals("loop must keep ticking while offline, not die silently", 6, availability.calls)
        m.stop()
    }

    @Test
    fun offline_resetsPartialFailureCount() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val probe = FakeProbe(healthy = false)
        val availability = FakeAvailability(online = true)
        val fired = AtomicInteger(0)
        val m = monitor(probe, availability, dispatcher)

        m.start { fired.incrementAndGet() }
        runFirstScheduledProbe()            // fail 1 while online
        availability.online = false
        advanceTimeBy(15_001L); runCurrent()   // offline tick resets the count
        availability.online = true
        advanceTimeBy(15_001L); runCurrent()   // fail 1 again, must not fire
        assertEquals(0, fired.get())
        // Pin that the offline tick actually ran (loop alive, not dead) and that it skipped the
        // probe rather than merely not firing: 3 ticks total (online, offline, online) but the
        // probe is only called on the 2 online ticks.
        assertEquals("offline tick must still occur, not be skipped by a dead loop", 3, availability.calls)
        assertEquals("probe must be skipped only on the offline tick", 2, probe.calls)
        m.stop()
    }

    @Test
    fun throwingProbe_countsAsFailure_andLoopSurvives() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val probe = FakeProbe(throws = true)
        val fired = AtomicInteger(0)
        val m = monitor(probe, FakeAvailability(), dispatcher)

        m.start { fired.incrementAndGet() }
        runFirstScheduledProbe()
        advanceTimeBy(15_001L); runCurrent()
        assertEquals("a throw IS the unhealthy signal, it must not kill the loop", 1, fired.get())
        m.stop()
    }

    @Test
    fun throwingAvailability_treatedAsOffline_resetsCounter_loopSurvives() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val probe = FakeProbe(healthy = false)
        val availability = FakeAvailability()
        val fired = AtomicInteger(0)
        val m = monitor(probe, availability, dispatcher)

        m.start { fired.incrementAndGet() }
        runFirstScheduledProbe()                     // fail 1 while online
        availability.throws = true
        advanceTimeBy(15_001L); runCurrent()         // throwing tick: treated as offline, not fatal
        availability.throws = false
        advanceTimeBy(15_001L); runCurrent()         // fail 1 again, not 2 (counter was reset)

        assertEquals("throwing availability must reset like offline, not fire", 0, fired.get())
        assertEquals("probe must be skipped on the throwing tick", 2, probe.calls)
        assertEquals("loop must survive a throwing availability check", 3, availability.calls)
        m.stop()
    }

    @Test
    fun firedMonitor_isTerminal_pauseAndResumeDoNotRevive() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val probe = FakeProbe(healthy = false)
        val fired = AtomicInteger(0)
        val m = monitor(probe, FakeAvailability(), dispatcher)

        m.start { fired.incrementAndGet() }
        runFirstScheduledProbe()                // fail 1
        advanceTimeBy(15_001L); runCurrent()   // fail 2 -> fires, monitor goes terminal
        assertEquals(1, fired.get())
        val callsAfterFire = probe.calls

        // A pause/resume landing after the fire — in either order — must never revive polling:
        // the monitor is terminal until a fresh start(). Without the isStarted/job reset on the
        // fire path, this either silently no-ops (looks resumed but isn't) or, worse, relaunches
        // a loop that can never fire again because reportedUnhealthy is still latched true.
        m.pausePolling()
        m.resumePolling()
        advanceTimeBy(100_000L); runCurrent()

        assertEquals("a terminal monitor must never fire twice", 1, fired.get())
        assertEquals("a terminal monitor must never poll again", callsAfterFire, probe.calls)
        m.stop()
    }

    @Test
    fun aFreshStartRevivesATerminalMonitor() = runTest {
        // The other half of the terminal contract: pause/resume must NOT revive (above), but a
        // fresh start() MUST — it is what applyFailoverPreferences runs after a rotation, and a
        // monitor that silently declines to poll leaves failover dead for the rest of the session
        // over a dead tunnel, with nothing anywhere to say so.
        //
        // It was untested, and the atomic install now makes it load-bearing: start() installs
        // through tryInstallJob, whose `stillLive` predicate is `isStarted`. The fire path leaves
        // isStarted = false, so start() re-arming it BEFORE the install is what lets the fresh job
        // be admitted at all.
        //
        // MUTATION-VERIFIED: moving `isStarted = true` below the install in start() makes the
        // second start() decline its own job and this assertion fails with fired == 1.
        val dispatcher = StandardTestDispatcher(testScheduler)
        val probe = FakeProbe(healthy = false)
        val fired = AtomicInteger(0)
        val m = monitor(probe, FakeAvailability(), dispatcher)

        m.start { fired.incrementAndGet() }
        runFirstScheduledProbe()                // fail 1
        advanceTimeBy(15_001L); runCurrent()   // fail 2 -> fires, monitor goes terminal
        assertEquals(1, fired.get())

        m.start { fired.incrementAndGet() }
        runFirstScheduledProbe()                // fail 1 of the new start
        advanceTimeBy(15_001L); runCurrent()   // fail 2 -> must fire again
        assertEquals("a fresh start() must revive a terminal monitor", 2, fired.get())
        m.stop()
    }

    @Test
    fun theUnhealthyListenerIsInvokedEvenWhenTheLoopWasAlreadyCancelled() = runTest {
        // Regression guard for the swallow hazard. pausePolling() runs on tunnelOpScope, a different
        // thread from this poll loop, and captures `job` before nulling it — so a screen-off landing
        // between `job = null` and the listener call used to make currentCoroutineContext().isActive
        // false and SKIP the rotation request, while isStarted = false made resumePolling() return
        // early. Failover then stayed dead for the whole session over a dead tunnel, with no signal.
        //
        // The cancellation is staged from INSIDE the probe, which is what makes this deterministic
        // on a single-threaded StandardTestDispatcher and, more importantly, what makes the test
        // load-bearing. `job.getAndSet(null)` clears only the REFERENCE — it does not cancel — so a
        // restored `isActive` gate stays true and a test that merely reaches the threshold cannot
        // fail for this regression. Calling pausePolling() from the probe cancels the poll
        // coroutine for real, and there is no suspension point between the probe returning and
        // `listener?.invoke()`, so cancellation never throws and the loop runs on with isActive
        // ALREADY false — precisely the interleaving the production code refuses to gate on.
        //
        // MUTATION-VERIFIED: wrapping the listener call in `if (currentCoroutineContext().isActive)`
        // makes this fail with fired == 0. Do not add such a gate.
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fired = AtomicInteger(0)
        lateinit var started: TunnelHealthMonitor
        val cancellingProbe = object : HealthProbe {
            var calls = 0
            override suspend fun isHealthy(): Boolean {
                calls++
                started.pausePolling()   // cancels THIS coroutine, mid-tick
                return false
            }
        }
        started = monitor(cancellingProbe, FakeAvailability(), dispatcher, threshold = 1)

        started.start(onHealthy = null) { fired.incrementAndGet() }
        runFirstScheduledProbe()
        started.stop()

        assertEquals("the probe must have run exactly once", 1, cancellingProbe.calls)
        assertEquals(
            "reaching the threshold must request a rotation even on a cancelled loop",
            1,
            fired.get(),
        )
    }

    @Test
    fun theHealthyListenerIsInvokedEvenWhenTheLoopWasAlreadyCancelled() {
        // The recovery twin of the test above, pinning the SAME rule on the other listener. Its
        // absence was the gap: the unhealthy path was pinned, the healthy path was not, so
        // restoring an isActive gate around the recovery invocation — the natural-looking "fix" —
        // left the whole suite green.
        //
        // The swallow is WORSE here than on the unhealthy path, because it is permanent rather than
        // per-session-terminal. reportedHealthy is latched to true immediately BEFORE the
        // invocation, and pausePolling() preserves it, so a gated call is not retried by the
        // relaunched loop or by any later tick: clearGiveUpStateOnRecovery never runs again for the
        // session, and the user is left staring at BLACKHOLED over a tunnel that demonstrably works.
        //
        // Same staging as the unhealthy twin, for the same reason: `job.getAndSet(null)` clears only
        // the REFERENCE, so a test that merely reaches a healthy probe leaves isActive true and
        // cannot fail for this regression. Cancelling from INSIDE the probe cancels the poll
        // coroutine for real, and there is no suspension point between the probe returning and
        // h.invoke(), so cancellation never throws and the loop runs on with isActive ALREADY false.
        //
        // MUTATION-VERIFIED: wrapping the healthy listener call in
        // `if (currentCoroutineContext().isActive)` makes this fail with recovered == 0.
        // Do not add such a gate.
        //
        // No threshold override, unlike the unhealthy twin: recovery fires on the FIRST successful
        // probe, so there is no counter to reach.
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val recovered = AtomicInteger(0)
            lateinit var started: TunnelHealthMonitor
            val cancellingProbe = object : HealthProbe {
                var calls = 0
                override suspend fun isHealthy(): Boolean {
                    calls++
                    started.pausePolling()   // cancels THIS coroutine, mid-tick
                    return true
                }
            }
            started = monitor(cancellingProbe, FakeAvailability(), dispatcher)

            started.start(onHealthy = { recovered.incrementAndGet() }) { }
            runFirstScheduledProbe()
            started.stop()

            assertEquals("the probe must have run exactly once", 1, cancellingProbe.calls)
            assertEquals(
                "the first healthy probe must report recovery even on a cancelled loop",
                1,
                recovered.get(),
            )
        }
    }

    // NOTE for the six tests below: they call m.stop() BEFORE asserting. A monitor that never
    // reaches its threshold polls forever, and runTest only returns once the scheduler is idle — so
    // an assertion that throws before stop() strands the loop and HANGS the whole test task rather
    // than reporting a failure. Stopping first costs nothing (every value asserted is already
    // captured in an AtomicInteger / call counter) and keeps a genuine RED failure legible.

    @Test
    fun onHealthy_firesOnTheFirstSuccessfulProbe() = runTest {
        // The recovery signal the service needs: TunnelHealthMonitor only ever reported failure, so
        // a give-up state written over a tunnel that later works had no way to clear itself.
        val dispatcher = StandardTestDispatcher(testScheduler)
        val recovered = AtomicInteger(0)
        val m = monitor(FakeProbe(healthy = true), FakeAvailability(), dispatcher)

        m.start(onHealthy = { recovered.incrementAndGet() }) { }
        runFirstScheduledProbe()
        m.stop()
        assertEquals(1, recovered.get())
    }

    @Test
    fun onHealthy_firesOnlyOncePerStart() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val recovered = AtomicInteger(0)
        val m = monitor(FakeProbe(healthy = true), FakeAvailability(), dispatcher)

        m.start(onHealthy = { recovered.incrementAndGet() }) { }
        advanceTimeBy(100_000L); runCurrent()
        m.stop()
        assertEquals("must latch, not fire once per healthy probe", 1, recovered.get())
    }

    @Test
    fun onHealthy_doesNotFire_whileEveryProbeFails() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val probe = FakeProbe(healthy = false)
        val recovered = AtomicInteger(0)
        val m = monitor(probe, FakeAvailability(), dispatcher, threshold = 100)

        m.start(onHealthy = { recovered.incrementAndGet() }) { }
        advanceTimeBy(100_000L); runCurrent()
        m.stop()
        assertEquals(0, recovered.get())
        assertEquals("loop must be alive, so 0 is a real answer", 6, probe.calls)
    }

    @Test
    fun onHealthy_firesOnRecovery_afterEarlierFailures() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val probe = FakeProbe(healthy = false)
        val recovered = AtomicInteger(0)
        val m = monitor(probe, FakeAvailability(), dispatcher, threshold = 100)

        m.start(onHealthy = { recovered.incrementAndGet() }) { }
        runFirstScheduledProbe()
        val beforeRecovery = recovered.get()
        probe.healthy = true
        advanceTimeBy(15_001L); runCurrent()
        m.stop()
        assertEquals("no recovery to report while the probe still fails", 0, beforeRecovery)
        assertEquals(1, recovered.get())
    }

    @Test
    fun onHealthy_reArmsOnAFreshStart() = runTest {
        // The service re-starts the monitor after a give-up; that fresh start is what must be able
        // to report the tunnel healthy again and clear the stale give-up state.
        val dispatcher = StandardTestDispatcher(testScheduler)
        val recovered = AtomicInteger(0)
        val m = monitor(FakeProbe(healthy = true), FakeAvailability(), dispatcher)

        m.start(onHealthy = { recovered.incrementAndGet() }) { }
        runFirstScheduledProbe()
        m.stop()
        m.start(onHealthy = { recovered.incrementAndGet() }) { }
        runFirstScheduledProbe()
        m.stop()
        assertEquals(2, recovered.get())
    }

    @Test
    fun onHealthy_isOptional_andAbsenceDoesNotBreakPolling() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val probe = FakeProbe(healthy = false)
        val fired = AtomicInteger(0)
        val m = monitor(probe, FakeAvailability(), dispatcher)

        m.start { fired.incrementAndGet() }   // trailing lambda must still bind to onUnhealthy
        runFirstScheduledProbe()
        advanceTimeBy(15_001L); runCurrent()
        m.stop()
        assertEquals("existing single-lambda callers must keep firing onUnhealthy", 1, fired.get())
    }

    @Test
    fun pauseStopsProbing_resumeProbesImmediately() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val probe = FakeProbe(healthy = true)
        val m = monitor(probe, FakeAvailability(), dispatcher)

        m.start { }
        runCurrent()
        val afterStart = probe.calls
        assertEquals("normal start must wait for the first interval", 0, afterStart)
        m.pausePolling()
        advanceTimeBy(100_000L); runCurrent()
        assertEquals("paused monitor must not probe", afterStart, probe.calls)

        m.resumePolling()
        runCurrent()
        assertEquals("resume must probe immediately, not wait an interval", afterStart + 1, probe.calls)
        m.stop()
    }
}
