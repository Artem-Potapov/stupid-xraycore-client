package com.justme.xtls_core_proxy.killswitch

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

/**
 * Real UsageStatsEventSource backed by the system service. Returns
 * ACTIVITY_RESUMED events in the requested time window.
 */
class AndroidUsageStatsEventSource(context: Context) : UsageStatsEventSource {

    private val manager: UsageStatsManager =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    override fun queryForegroundEvents(
        beginMs: Long,
        endMs: Long
    ): List<UsageStatsEventSource.ForegroundEvent> {
        val events = manager.queryEvents(beginMs, endMs) ?: return emptyList()
        val out = mutableListOf<UsageStatsEventSource.ForegroundEvent>()
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                val pkg = event.packageName ?: continue
                out.add(UsageStatsEventSource.ForegroundEvent(pkg, event.timeStamp))
            }
        }
        return out
    }
}
