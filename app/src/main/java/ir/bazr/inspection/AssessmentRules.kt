package ir.bazr.inspection

/** Pure model used by the Native inspector UI; no Android or network dependency. */
object AssessmentRules {
    data class Summary(
        val total: Int,
        val answered: Int,
        val unscored: List<String>,
        val invalid: List<String>
    ) {
        val coveragePercent: Double get() = if (total == 0) 0.0 else answered * 100.0 / total
    }

    fun evaluate(questionIds: List<String>, rawScores: Map<String, String>): Summary {
        val ids = questionIds.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val unanswered = mutableListOf<String>()
        val invalid = mutableListOf<String>()
        var answered = 0
        for (id in ids) {
            val raw = rawScores[id].orEmpty()
            if (raw.isBlank()) { unanswered.add(id); continue }
            val number = ScoreInput.parse(raw)
            if (number == null || number !in 0.0..100.0) invalid.add(id)
            else answered++
        }
        return Summary(ids.size, answered, unanswered.sorted(), invalid.sorted())
    }
}
