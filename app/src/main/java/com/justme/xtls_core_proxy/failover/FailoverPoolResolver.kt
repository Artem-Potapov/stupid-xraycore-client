package com.justme.xtls_core_proxy.failover

import com.justme.xtls_core_proxy.db.Profile
import com.justme.xtls_core_proxy.db.ProfileDao

/**
 * Resolves the set of servers failover may rotate between.
 *
 * SPEC 2 SEAM: this is the single place that changes when user-curated pools land. Keep the
 * signature stable and keep the logic here rather than inlining it into the service.
 *
 * The pool is derived at runtime and never persisted, which sidesteps the fact that Room profile
 * IDs churn — replaceProfilesForSubscription deletes and re-inserts rows on every refresh.
 */
object FailoverPoolResolver {

    /**
     * Whether two profiles select the same pool under [resolve]'s current partitioning rule.
     *
     * Keep this beside [resolve]: when curated pools change that rule, Connect-to-fastest's
     * equivalent-run coalescing must change with it rather than duplicating stale partition logic.
     */
    internal fun samePool(first: Profile, second: Profile): Boolean =
        first.subscriptionId == second.subscriptionId

    suspend fun resolve(dao: ProfileDao, current: Profile): List<Profile> {
        val subId = current.subscriptionId
        return if (subId == null) dao.getManualList() else dao.getBySubscriptionId(subId)
    }
}
