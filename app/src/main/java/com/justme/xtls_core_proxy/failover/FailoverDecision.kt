package com.justme.xtls_core_proxy.failover

import com.justme.xtls_core_proxy.db.Profile

/** Result of asking whether one more rotation may start. */
sealed interface RotationAdmission {
    /** Admitted; [attempts] is the pruned timestamp list with this attempt appended. */
    data class Admitted(val attempts: List<Long>) : RotationAdmission
    data object Denied : RotationAdmission
}

/**
 * Pure failover decisions. No Android, no I/O, no clock — `now` is always passed in, so the
 * sliding thrash window is testable without waiting.
 */
object FailoverDecision {

    /**
     * The next server to try, in the same deterministic order the UI lists them.
     *
     * List order rather than latency order is deliberate for v1: ping results live in
     * VpnViewModel and are unreachable from the service. For failover, "any working server" beats
     * "the fastest server" — a poor pick simply rotates again. See the spec's Deferred Changes for
     * the process-scoped ping repository that would unlock latency ordering.
     */
    fun nextCandidate(pool: List<Profile>, currentId: Long, recentlyFailed: Set<Long>): Profile? =
        pool.firstOrNull { it.id != currentId && it.id !in recentlyFailed }

    /**
     * Sliding-window thrash cap. Prunes attempts older than [windowMs] before counting, so a burst
     * long ago cannot permanently lock out failover.
     */
    fun admitRotation(
        attempts: List<Long>,
        now: Long,
        maxRotations: Int,
        windowMs: Long,
    ): RotationAdmission {
        val live = attempts.filter { now - it < windowMs }
        return if (live.size >= maxRotations) {
            RotationAdmission.Denied
        } else {
            RotationAdmission.Admitted(live + now)
        }
    }
}

object FailoverEpisodeDecision {
    /** The health probe proved the currently connected server failed this episode. */
    fun recordProbeFailedCurrent(failedIds: Set<Long>, currentId: Long): Set<Long> =
        failedIds + currentId

    /** A candidate failed before it became current, so recursive selection must skip it too. */
    fun recordBringUpFailure(failedIds: Set<Long>, candidateId: Long): Set<Long> =
        failedIds + candidateId

    /** A passing live-tunnel probe is the only successful completion of a failure episode. */
    fun clearOnHealthyProbe(failedIds: Set<Long>): Set<Long> = emptySet()
}
