package ir.bazr.inspection

/**
 * A quality-first budget for mobile evidence photos. 200 KB is a target, not a
 * justification to make the text on a photographed document unreadable.
 * PDFs and signatures are deliberately excluded from image optimization.
 */
object PhotoUploadPolicy {
    const val TARGET_BYTES: Int = 200_000
    val MAX_EDGES: List<Int> = listOf(2048, 1792, 1600, 1440, 1280)
    val WEBP_QUALITIES: List<Int> = listOf(94, 90, 86, 82)

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
}
