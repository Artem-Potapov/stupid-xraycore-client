package com.justme.xtls_core_proxy.killswitch

/**
 * Thin abstraction over UsageStatsManager.queryEvents so the monitor can be
 * unit-tested with scripted event sequences (no real UsageStatsManager).
 */
interface UsageStatsEventSource {

    data class ForegroundEvent(val packageName: String, val timestampMs: Long)

    /**
     * Return all ACTIVITY_RESUMED events in [beginMs, endMs), oldest first.
     * Returns empty list when no events available, the device is locked, or the
     * permission has been revoked. Throws if a non-recoverable error occurs
     * (the monitor will catch and shut itself down).
     */
    fun queryForegroundEvents(beginMs: Long, endMs: Long): List<ForegroundEvent>
}
