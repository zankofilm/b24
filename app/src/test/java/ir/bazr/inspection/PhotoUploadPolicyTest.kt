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
}
