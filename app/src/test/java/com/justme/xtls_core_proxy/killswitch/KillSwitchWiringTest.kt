package com.justme.xtls_core_proxy.killswitch

import com.justme.xtls_core_proxy.killswitch.UsageStatsEventSource.ForegroundEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies that the end-to-end wiring of monitor -> listener -> recorded calls
 * fires the expected sequence for the canonical "open controlled app, then
 * leave it" flow. This is the test that catches integration bugs that a pure
 * unit test of the monitor alone could not.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KillSwitchWiringTest {

    @Test
    fun fullCycle_controlledAppForeground_thenLeft_firesKillThenRevive() = runTest {
        val killCalls = mutableListOf<String>()
        var reviveCalls = 0

        val listener = object : ForegroundAppMonitor.Listener {
            override fun onControlledAppForeground(packageName: String) {
                killCalls.add(packageName)
            }
            override fun onControlledAppLeftForeground() {
                reviveCalls += 1
            }
        }

        val source = object : UsageStatsEventSource {
            private val script = ArrayDeque(listOf(
                listOf(ForegroundEvent("com.example.controlled", 1_000L)),
                listOf(ForegroundEvent("com.example.benign", 2_000L))
            ))
            override fun queryForegroundEvents(beginMs: Long, endMs: Long): List<ForegroundEvent> =
                if (script.isEmpty()) emptyList() else script.removeFirst()
        }

        val dispatcher = StandardTestDispatcher(testScheduler)
        val monitor = UsageStatsForegroundAppMonitor(source, pollIntervalMs = 1_000L, dispatcher = dispatcher)

        monitor.start(packages = setOf("com.example.controlled"), listener = listener)
        advanceTimeBy(1_100L)
        advanceTimeBy(1_000L)

        assertEquals(listOf("com.example.controlled"), killCalls)
        assertEquals(1, reviveCalls)
        monitor.stop()
        monitor.shutdownForTesting()
        runCurrent()
        advanceUntilIdle()
    }
}
