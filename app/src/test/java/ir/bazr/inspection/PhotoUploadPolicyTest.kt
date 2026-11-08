package ir.bazr.inspection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoUploadPolicyTest {
    @Test fun optimizesOnlyLargeImages() {
        assertTrue(PhotoUploadPolicy.needsOptimization("image/jpeg", 250_000))
        assertFalse(PhotoUploadPolicy.needsOptimization("image/png", 200_000))
        assertFalse(PhotoUploadPolicy.needsOptimization("application/pdf", 900_000))
    }

    @Test fun dimensionsRespectLongestEdgeAndAspectRatio() {
        assertEquals(Pair(2048, 1024), PhotoUploadPolicy.dimensions(4000, 2000, 2048))
        assertEquals(Pair(600, 1200), PhotoUploadPolicy.dimensions(600, 1200, 2048))
    }

    @Test fun qualitySearchFindsHighestFittingValue() {
        // 2000 bytes per quality point: everything up to the ceiling (95) fits in 200 KB.
        assertEquals(95, PhotoUploadPolicy.highestFittingQuality(40, 95) { it * 2_000 })
    }

    @Test fun qualitySearchReturnsNullWhenNothingFits() {
        assertEquals(null, PhotoUploadPolicy.highestFittingQuality(60, 95) { 500_000 })
    }

    @Test fun qualitySearchHonoursLimit() {
        assertEquals(50, PhotoUploadPolicy.highestFittingQuality(10, 95) { it * 4_000 })
    }
}
