package com.justme.xtls_core_proxy.failover

import com.justme.xtls_core_proxy.db.Profile
import com.justme.xtls_core_proxy.db.ProfileDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain-JVM dispatch coverage for [FailoverPoolResolver]. Drives `resolve` against a
 * hand-written fake [ProfileDao] that records which method was called and with what argument,
 * so these tests prove dispatch (which query fires, with which subId, and that the other query
 * is never touched) without needing real Room/SQLite — that half is covered separately by the
 * instrumented FailoverPoolResolverTest.
 */
class FailoverPoolResolverDispatchTest {

    private class RecordingProfileDao(
        private val subscriptionResult: List<Profile> = emptyList(),
        private val manualResult: List<Profile> = emptyList(),
    ) : ProfileDao {
        var bySubscriptionIdCalledWith: Long? = null
            private set
        var bySubscriptionIdCallCount = 0
            private set
        var getManualListCallCount = 0
            private set

        override fun getAll(): Flow<List<Profile>> = throw UnsupportedOperationException("not used")
        override fun getManual(): Flow<List<Profile>> = throw UnsupportedOperationException("not used")
        override suspend fun getById(id: Long): Profile? = throw UnsupportedOperationException("not used")
        override suspend fun getFirst(): Profile? = throw UnsupportedOperationException("not used")
        override suspend fun insert(profile: Profile): Long = throw UnsupportedOperationException("not used")
        override suspend fun insertAll(profiles: List<Profile>) = throw UnsupportedOperationException("not used")
        override suspend fun update(profile: Profile) = throw UnsupportedOperationException("not used")
        override suspend fun delete(profile: Profile) = throw UnsupportedOperationException("not used")
        override suspend fun deleteForSub(subId: Long) = throw UnsupportedOperationException("not used")
        override suspend fun deleteForSubExceptId(subId: Long, keepId: Long) =
            throw UnsupportedOperationException("not used")

        override suspend fun getBySubscriptionId(subId: Long): List<Profile> {
            bySubscriptionIdCalledWith = subId
            bySubscriptionIdCallCount++
            return subscriptionResult
        }

        override suspend fun getManualList(): List<Profile> {
            getManualListCallCount++
            return manualResult
        }
    }

    private fun profile(id: Long, subscriptionId: Long? = null) =
        Profile(id = id, name = "s$id", config = "{}", subscriptionId = subscriptionId)

    @Test
    fun subscriptionProfile_dispatchesToGetBySubscriptionId_andReturnsItsRows() = runBlocking {
        val expected = listOf(profile(10L, subscriptionId = 7L), profile(11L, subscriptionId = 7L))
        val dao = RecordingProfileDao(subscriptionResult = expected)
        val current = profile(10L, subscriptionId = 7L)

        val pool = FailoverPoolResolver.resolve(dao, current)

        assertEquals(expected, pool)
        assertEquals(1, dao.bySubscriptionIdCallCount)
        assertEquals(0, dao.getManualListCallCount)
    }

    @Test
    fun manualProfile_dispatchesToGetManualList_andReturnsItsRows() = runBlocking {
        val expected = listOf(profile(20L, subscriptionId = null), profile(21L, subscriptionId = null))
        val dao = RecordingProfileDao(manualResult = expected)
        val current = profile(20L, subscriptionId = null)

        val pool = FailoverPoolResolver.resolve(dao, current)

        assertEquals(expected, pool)
        assertEquals(1, dao.getManualListCallCount)
        assertEquals(0, dao.bySubscriptionIdCallCount)
        assertNull(dao.bySubscriptionIdCalledWith)
    }

    @Test
    fun subscriptionProfile_passesThroughTheActualSubscriptionId_notAHardcodedOne() = runBlocking {
        val dao = RecordingProfileDao()
        val current = profile(30L, subscriptionId = 99L)

        FailoverPoolResolver.resolve(dao, current)

        assertEquals(99L, dao.bySubscriptionIdCalledWith)
    }

    @Test
    fun manualProfile_neverCallsGetBySubscriptionId() = runBlocking {
        val dao = RecordingProfileDao()
        val current = profile(40L, subscriptionId = null)

        FailoverPoolResolver.resolve(dao, current)

        assertEquals(0, dao.bySubscriptionIdCallCount)
    }

    @Test
    fun samePool_manualProfilesAreEquivalent() {
        assertTrue(
            FailoverPoolResolver.samePool(
                profile(50L, subscriptionId = null),
                profile(51L, subscriptionId = null),
            )
        )
    }

    @Test
    fun samePool_profilesFromTheSameSubscriptionAreEquivalent() {
        assertTrue(
            FailoverPoolResolver.samePool(
                profile(60L, subscriptionId = 7L),
                profile(61L, subscriptionId = 7L),
            )
        )
    }

    @Test
    fun samePool_manualAndSubscriptionProfilesAreNotEquivalent() {
        assertFalse(
            FailoverPoolResolver.samePool(
                profile(70L, subscriptionId = null),
                profile(71L, subscriptionId = 7L),
            )
        )
    }

    @Test
    fun samePool_profilesFromDifferentSubscriptionsAreNotEquivalent() {
        assertFalse(
            FailoverPoolResolver.samePool(
                profile(80L, subscriptionId = 7L),
                profile(81L, subscriptionId = 8L),
            )
        )
    }
}
