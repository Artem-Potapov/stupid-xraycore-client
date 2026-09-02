package com.justme.xtls_core_proxy.failover

import com.justme.xtls_core_proxy.db.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FailoverEpisodeDecisionTest {

    private fun profile(id: Long) = Profile(id = id, name = "s$id", config = "{}", subscriptionId = 7L)
    private val pool = listOf(profile(1), profile(2), profile(3), profile(4))

    @Test
    fun probeFailedCoreStarts_walksEveryPoolMemberThenExhausts() {
        var currentId = 1L
        var failedIds = emptySet<Long>()
        val attempted = mutableListOf<Long>()

        repeat(pool.size) {
            failedIds = FailoverEpisodeDecision.recordProbeFailedCurrent(failedIds, currentId)
            val next = FailoverDecision.nextCandidate(pool, currentId, failedIds) ?: return@repeat
            attempted += next.id
            currentId = next.id // Xray started, but the following health probe still failed.
        }

        assertEquals(listOf(2L, 3L, 4L), attempted)
        assertEquals(setOf(1L, 2L, 3L, 4L), failedIds)
    }

    @Test
    fun probeFailedCoreStarts_fromNonFirstServerKeepsFirstEligibleListOrderThenExhausts() {
        var currentId = 3L
        var failedIds = emptySet<Long>()
        val attempted = mutableListOf<Long>()

        repeat(pool.size) {
            failedIds = FailoverEpisodeDecision.recordProbeFailedCurrent(failedIds, currentId)
            val next = FailoverDecision.nextCandidate(pool, currentId, failedIds) ?: return@repeat
            attempted += next.id
            currentId = next.id
        }

        assertEquals(listOf(1L, 2L, 4L), attempted)
        assertEquals(setOf(1L, 2L, 3L, 4L), failedIds)
    }

    @Test
    fun healthyReplacement_clearsTheEpisodeSoAnEarlierServerCanBeConsideredLater() {
        val afterAFailedProbe = setOf(1L)
        assertEquals(2L, FailoverDecision.nextCandidate(pool, currentId = 1L, recentlyFailed = afterAFailedProbe)?.id)

        val afterBHealthy = FailoverEpisodeDecision.clearOnHealthyProbe(afterAFailedProbe)
        val afterBLaterFailedProbe =
            FailoverEpisodeDecision.recordProbeFailedCurrent(afterBHealthy, currentId = 2L)

        assertEquals(1L, FailoverDecision.nextCandidate(pool, currentId = 2L, recentlyFailed = afterBLaterFailedProbe)?.id)
    }

    @Test
    fun bringUpFailure_recursionStillExcludesCandidateThatNeverBecameCurrent() {
        val afterAFailedProbe = FailoverEpisodeDecision.recordProbeFailedCurrent(emptySet(), currentId = 1L)
        val firstCandidate = FailoverDecision.nextCandidate(pool, currentId = 1L, recentlyFailed = afterAFailedProbe)
        assertEquals(2L, firstCandidate?.id)

        val afterBFailedBringUp =
            FailoverEpisodeDecision.recordBringUpFailure(afterAFailedProbe, candidateId = firstCandidate!!.id)

        assertEquals(
            3L,
            FailoverDecision.nextCandidate(pool, currentId = 1L, recentlyFailed = afterBFailedBringUp)?.id,
        )
    }

    @Test
    fun episodeTraversal_doesNotChangeTheSlidingThrashCap() {
        var attempts = emptyList<Long>()
        repeat(3) { index ->
            val admission = FailoverDecision.admitRotation(
                attempts = attempts,
                now = 1_000L + index,
                maxRotations = 3,
                windowMs = 600_000L,
            )
            assertTrue(admission is RotationAdmission.Admitted)
            attempts = (admission as RotationAdmission.Admitted).attempts
        }

        assertTrue(
            FailoverDecision.admitRotation(
                attempts = attempts,
                now = 2_000L,
                maxRotations = 3,
                windowMs = 600_000L,
            ) is RotationAdmission.Denied,
        )
    }

    @Test
    fun probeFailedCoreStarts_reportsNoCandidateAfterAllMembersAreRecorded() {
        val allFailed = setOf(1L, 2L, 3L, 4L)
        assertNull(FailoverDecision.nextCandidate(pool, currentId = 4L, recentlyFailed = allFailed))
    }
}
