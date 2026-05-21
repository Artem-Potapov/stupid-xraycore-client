package com.justme.xtls_core_proxy.subs

import android.content.Context
import com.justme.xtls_core_proxy.R
import com.justme.xtls_core_proxy.db.AppDatabase
import com.justme.xtls_core_proxy.db.Profile
import com.justme.xtls_core_proxy.i18n.SupportedLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

object SubscriptionRefreshCoordinator {

    private val inFlight = ConcurrentHashMap<Long, Job>()

    fun refresh(
        scope: CoroutineScope,
        context: Context,
        subId: Long,
        activeProfileIdProvider: () -> Long?,
        db: AppDatabase,
        defaultUserAgent: String
    ): Job {
        inFlight[subId]?.let { existing ->
            if (existing.isActive) return existing
        }
        val localizedContext = SupportedLanguage.localize(context)
        val job = scope.launch(Dispatchers.IO) {
            try {
                runRefresh(localizedContext, subId, activeProfileIdProvider, db, defaultUserAgent)
            } finally {
                inFlight.remove(subId)
            }
        }
        inFlight[subId] = job
        return job
    }

    private suspend fun runRefresh(
        context: Context,
        subId: Long,
        activeProfileIdProvider: () -> Long?,
        db: AppDatabase,
        defaultUserAgent: String
    ) {
        val subDao = db.subscriptionDao()
        val profileDao = db.profileDao()
        val sub = subDao.getById(subId) ?: return

        when (val result = SubscriptionFetcher.fetch(context, sub, defaultUserAgent)) {
            is FetchResult.Failure -> {
                subDao.markError(subId, result.message)
            }
            is FetchResult.Success -> {
                val outcome = SubscriptionBodyParser.parseBody(result.body)
                val newProfiles = outcome.parsed.map { p ->
                    Profile(name = p.displayName, config = p.config, subscriptionId = subId)
                }

                val activeId = activeProfileIdProvider()
                val keepProfileId = activeId
                    ?.let { profileDao.getById(it) }
                    ?.takeIf { it.subscriptionId == subId }
                    ?.id

                profileDao.replaceProfilesForSubscription(subId, keepProfileId, newProfiles)

                val warning = if (outcome.parseErrorCount > 0) {
                    context.getString(R.string.subs_error_parse_lines_prefix, outcome.parseErrorCount)
                } else {
                    null
                }
                subDao.markFetchResult(
                    id = subId,
                    lastFetchedAt = System.currentTimeMillis(),
                    lastSeenIntervalHours = result.intervalHoursFromHeader,
                    lastError = warning
                )
            }
        }
    }
}
