package ir.bazr.inspection

/**
 * A quality-first budget for mobile evidence photos. The transport copy must fit in
 * 200 KB so the report can always be sent, but the encoder searches for the *highest*
 * quality / resolution that fits instead of failing: resolution is reduced only when a
 * good-quality encoding at the larger size does not fit.
 * PDFs and signatures are deliberately excluded from image optimization.
 */
object PhotoUploadPolicy {
    const val TARGET_BYTES: Int = 200_000

    /** Longest-edge ladder, tried from the largest (best detail) to the smallest. */
    val MAX_EDGES: List<Int> = listOf(2560, 2240, 2048, 1792, 1600, 1440, 1280, 1152, 1024, 896, 768)

    /** Minimum acceptable quality per pass: first "visually clean", then relaxed, then anything that fits. */
    val QUALITY_FLOORS: List<Int> = listOf(82, 70, 45)
    const val QUALITY_CEILING: Int = 95

    /** Lossless is only attempted for small bitmaps (screenshots/scans) to bound time and memory. */
    const val LOSSLESS_MAX_PIXELS: Long = 4_000_000L

    fun needsOptimization(mime: String, size: Int): Boolean =
        mime.startsWith("image/") && size > TARGET_BYTES

    fun dimensions(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> {
        require(width > 0 && height > 0 && maxEdge > 0)
        val sourceEdge = maxOf(width, height)
        if (sourceEdge <= maxEdge) return Pair(width, height)
        val factor = maxEdge.toDouble() / sourceEdge
        return Pair((width * factor).toInt().coerceAtLeast(1),
            (height * factor).toInt().coerceAtLeast(1))
    }

    /**
     * Highest quality in [low, high] whose encoded size is <= [limit], assuming size grows
     * with quality. [sizeAt] returns null when encoding fails. Returns null if even [low] is too big.
     */
    fun highestFittingQuality(low: Int, high: Int, limit: Int = TARGET_BYTES, sizeAt: (Int) -> Int?): Int? {
        require(low in 1..100 && high in low..100)
        var lo = low
        var hi = high
        var best: Int? = null
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val size = sizeAt(mid)
            if (size != null && size <= limit) { best = mid; lo = mid + 1 } else hi = mid - 1
        }
        return best
    }
}
