package com.justme.xtls_core_proxy.geo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoAssetPreparerTest {
    @Test
    fun shouldCopy_returnsTrue_whenDestinationMissing() {
        assertTrue(GeoAssetPreparer.shouldCopy(existingLength = null, assetLength = 128))
    }

    @Test
    fun shouldCopy_returnsTrue_whenLengthsDiffer() {
        assertTrue(GeoAssetPreparer.shouldCopy(existingLength = 64, assetLength = 128))
    }

    @Test
    fun shouldCopy_returnsFalse_whenLengthsMatch() {
        assertFalse(GeoAssetPreparer.shouldCopy(existingLength = 128, assetLength = 128))
    }

    @Test
    fun geoAssetList_containsRequiredGeoipFile() {
        assertTrue(GeoAssetPreparer.GEO_ASSET_FILES.contains(GeoAssetPreparer.REQUIRED_GEO_FILE))
    }
}
