package ir.bazr.inspection

/** Pure rules shared by foreground sync and periodic notification delivery. */
object NoticeRules {
    const val POLL_INTERVAL_MINUTES = 15
    const val MAX_REMEMBERED_IDS = 400

    fun shouldNotify(id: String, isRead: Boolean, alreadyNotified: Set<String>): Boolean =
        id.isNotBlank() && !isRead && id !in alreadyNotified

    fun retainRecentIds(ids: Collection<String>, max: Int = MAX_REMEMBERED_IDS): List<String> {
        val bounded = max.coerceIn(1, MAX_REMEMBERED_IDS)
        return ids.asSequence().filter { it.isNotBlank() }.distinct().toList().takeLast(bounded)
    }
}
