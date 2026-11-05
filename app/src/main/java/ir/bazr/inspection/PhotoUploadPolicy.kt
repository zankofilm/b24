package ir.bazr.inspection

/**
 * Upload budget for photographic evidence. The original is always retained in
 * the encrypted vault; this policy applies only to the transport copy.
 */
object PhotoUploadPolicy {
    // 200 KiB (204,800 bytes), rather than decimal 200,000 bytes.
    const val TARGET_BYTES: Int = 200 * 1024

    // Longest edge, descending. Keep enough pixels for inspection evidence while
    // allowing high-resolution phone photos to fit the transport budget.
    val MAX_EDGES: List<Int> = listOf(
        2560, 2304, 2048, 1920, 1792, 1664, 1536, 1408,
        1280, 1152, 1024, 960, 896, 832, 768, 704, 640
    )

    // The encoder probes these quality levels and then binary-searches the
    // interval where the best candidate first fits the byte budget.
    val QUALITY_PROBES: List<Int> = listOf(96, 90, 84, 78, 72, 66, 60)
    const val MIN_QUALITY: Int = 60

    fun needsOptimization(mime: String, size: Int): Boolean =
        mime.startsWith("image/") && size > TARGET_BYTES

    fun dimensions(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> {
        require(width > 0 && height > 0 && maxEdge > 0)
        val sourceEdge = maxOf(width, height)
        if (sourceEdge <= maxEdge) return Pair(width, height)
        val factor = maxEdge.toDouble() / sourceEdge
        return Pair(
            (width * factor).toInt().coerceAtLeast(1),
            (height * factor).toInt().coerceAtLeast(1)
        )
    }
}
