package ir.bazr.inspection

import org.json.JSONArray
import org.json.JSONObject

/** Stable, server-compatible description of a checklist question. */

data class AssessmentScaleOption(val value: String, val localizedValue: String, val label: String)

/** Quick-entry labels use the existing numeric scale; they do not change server scoring. */
object AssessmentScale {
    val quickLevels = listOf(
        AssessmentScaleOption("0", "۰", "خیلی‌ضعیف"),
        AssessmentScaleOption("25", "۲۵", "ضعیف"),
        AssessmentScaleOption("50", "۵۰", "متوسط"),
        AssessmentScaleOption("75", "۷۵", "خوب"),
        AssessmentScaleOption("100", "۱۰۰", "عالی")
    )
}

data class ChecklistQuestionSpec(
    val index: Int,
    val id: String,
    val title: String,
    val sectionId: String,
    val critical: Boolean,
    val structurallyInvalid: Boolean = false
)

data class ChecklistSectionSpec(
    val id: String,
    val title: String,
    val category: String,
    val questionIndices: List<Int>
)

data class ChecklistQuestionResult(
    val question: ChecklistQuestionSpec,
    val score: Double?,
    val unscored: Boolean,
    val invalidScore: Boolean,
    val missingNote: Boolean,
    val missingEvidence: Boolean,
    val structuralIssue: Boolean
) {
    val answered: Boolean get() = score != null && !invalidScore && !structuralIssue
    val complete: Boolean get() = answered && !missingNote && !missingEvidence
    val needsAttention: Boolean get() = !complete

    val statusLabel: String get() = when {
        structuralIssue -> "ساختار نامعتبر"
        invalidScore -> "امتیاز نامعتبر"
        unscored -> "بدون امتیاز"
        missingNote && missingEvidence -> "نیازمند توضیح و مستند"
        missingNote -> "نیازمند توضیح"
        missingEvidence -> "نیازمند مستند"
        else -> "تکمیل‌شده"
    }
}

data class ChecklistSectionResult(
    val section: ChecklistSectionSpec,
    val answered: Int,
    val unscored: Int,
    val invalidScores: Int,
    val missingNotes: Int,
    val missingEvidence: Int,
    val structuralIssues: Int,
    val firstIncompleteIndex: Int?
) {
    val total: Int get() = section.questionIndices.size
    val coveragePercent: Int get() = if (total == 0) 0 else (answered * 100.0 / total).toInt()
    val complete: Boolean get() = total > 0 && answered == total && unscored == 0 && invalidScores == 0 &&
        missingNotes == 0 && missingEvidence == 0 && structuralIssues == 0
    val attentionCount: Int get() = invalidScores + missingNotes + missingEvidence + structuralIssues
}

data class ChecklistOverview(
    val total: Int,
    val answered: Int,
    val unscored: Int,
    val invalidScores: Int,
    val missingNotes: Int,
    val missingEvidence: Int,
    val structuralIssues: Int,
    val questions: List<ChecklistQuestionResult>,
    val sections: List<ChecklistSectionResult>,
    val blockingIssues: List<String>
) {
    val coveragePercent: Int get() = if (total == 0) 0 else (answered * 100.0 / total).toInt()
    val firstIncompleteIndex: Int? get() = questions.firstOrNull { it.needsAttention }?.question?.index
    val attentionCount: Int get() = invalidScores + missingNotes + missingEvidence + structuralIssues
    val readyForReview: Boolean get() = total > 0 && blockingIssues.isEmpty()
    fun section(id: String): ChecklistSectionResult? = sections.firstOrNull { it.section.id == id }
    fun questionAt(index: Int): ChecklistQuestionResult? = questions.firstOrNull { it.question.index == index }
}

/**
 * Single source of truth for client-side checklist progress and pre-signature validation.
 * It deliberately does not calculate a final administrative score: weighting and final
 * scoring remain server-authoritative and are not inferred from the mobile snapshot.
 */
object ChecklistAssessment {
    fun fromSnapshot(
        snapshot: JSONObject,
        answers: JSONObject,
        notes: JSONObject,
        attachments: JSONObject
    ): ChecklistOverview {
        val questionArray = snapshot.optJSONArray("questions") ?: JSONArray()
        val specs = mutableListOf<ChecklistQuestionSpec>()
        val structuralIssues = mutableListOf<String>()
        val seenIds = mutableSetOf<String>()
        val duplicateIndices = mutableSetOf<Int>()

        for (index in 0 until questionArray.length()) {
            val item = questionArray.optJSONObject(index)
            if (item == null) {
                structuralIssues.add("بند ${index + 1}: ساختار سؤال در اطلاعات سرور معتبر نیست")
                specs.add(ChecklistQuestionSpec(index, "", "بند ${index + 1}", "", false, true))
                continue
            }
            val id = item.optString("id").trim()
            if (id.isBlank()) {
                structuralIssues.add("بند ${index + 1}: شناسه سؤال از سرور دریافت نشده است")
            } else if (!seenIds.add(id)) {
                structuralIssues.add("بند ${index + 1}: شناسه سؤال تکراری است")
                duplicateIndices.add(index)
            }
            specs.add(
                ChecklistQuestionSpec(
                    index = index,
                    id = id,
                    title = item.optString("text", item.optString("question", item.optString("title", id.ifBlank { "بند ${index + 1}" }))),
                    sectionId = item.optString("sectionId").trim(),
                    critical = item.optBoolean("critical", false),
                    structurallyInvalid = id.isBlank() || index in duplicateIndices
                )
            )
        }
        if (questionArray.length() == 0) structuralIssues.add("چک‌لیست مأموریت خالی است")

        val sectionSpecs = buildSections(snapshot, specs)
        val scoreMap = linkedMapOf<String, String>()
        val noteMap = linkedMapOf<String, String>()
        val evidenceMap = linkedMapOf<String, Int>()
        specs.filter { it.id.isNotBlank() }.forEach { spec ->
            scoreMap[spec.id] = answers.optString(spec.id, "")
            noteMap[spec.id] = notes.optString(spec.id, "")
            evidenceMap[spec.id] = EvidenceRefs.references(attachments, spec.id).size
        }
        return evaluate(specs, sectionSpecs, scoreMap, noteMap, evidenceMap, structuralIssues)
    }

    /** Pure evaluator; this overload is easy to exercise with JVM unit tests. */
    fun evaluate(
        questions: List<ChecklistQuestionSpec>,
        sections: List<ChecklistSectionSpec>,
        rawScores: Map<String, String>,
        notes: Map<String, String>,
        evidenceCounts: Map<String, Int>,
        initialStructuralIssues: List<String> = emptyList()
    ): ChecklistOverview {
        val issues = initialStructuralIssues.toMutableList()
        val results = questions.map { spec ->
            if (spec.structurallyInvalid || spec.id.isBlank()) {
                ChecklistQuestionResult(spec, null, false, false, false, false, true)
            } else {
                val raw = rawScores[spec.id].orEmpty()
                if (raw.isBlank()) {
                    ChecklistQuestionResult(spec, null, true, false, false, false, false)
                } else {
                    val parsed = ScoreInput.parse(raw)
                    if (parsed == null || parsed !in 0.0..100.0) {
                        issues.add("بند ${spec.index + 1}: امتیاز باید عددی بین ۰ تا ۱۰۰ باشد")
                        ChecklistQuestionResult(spec, null, false, true, false, false, false)
                    } else {
                        val missingNote = parsed < 60.0 && notes[spec.id].orEmpty().isBlank()
                        val missingEvidence = (parsed < 30.0 || spec.critical) && (evidenceCounts[spec.id] ?: 0) == 0
                        if (missingNote) issues.add("بند ${spec.index + 1}: برای امتیاز کمتر از ۶۰ توضیح لازم است")
                        if (missingEvidence) issues.add("بند ${spec.index + 1}: مستند برای پاسخ بحرانی لازم است")
                        ChecklistQuestionResult(spec, parsed, false, false, missingNote, missingEvidence, false)
                    }
                }
            }
        }

        val sectionResults = sections.map { section ->
            val members = section.questionIndices.mapNotNull { index -> results.firstOrNull { it.question.index == index } }
            ChecklistSectionResult(
                section = section,
                answered = members.count { it.answered },
                unscored = members.count { it.unscored },
                invalidScores = members.count { it.invalidScore },
                missingNotes = members.count { it.missingNote },
                missingEvidence = members.count { it.missingEvidence },
                structuralIssues = members.count { it.structuralIssue },
                firstIncompleteIndex = members.firstOrNull { it.needsAttention }?.question?.index
            )
        }
        return ChecklistOverview(
            total = questions.size,
            answered = results.count { it.answered },
            unscored = results.count { it.unscored },
            invalidScores = results.count { it.invalidScore },
            missingNotes = results.count { it.missingNote },
            missingEvidence = results.count { it.missingEvidence },
            structuralIssues = results.count { it.structuralIssue },
            questions = results,
            sections = sectionResults,
            blockingIssues = issues.distinct()
        )
    }

    private fun buildSections(snapshot: JSONObject, questions: List<ChecklistQuestionSpec>): List<ChecklistSectionSpec> {
        val sectionArray = snapshot.optJSONArray("sections") ?: JSONArray()
        if (sectionArray.length() == 0) {
            return if (questions.isEmpty()) emptyList() else listOf(
                ChecklistSectionSpec("__all__", "شاخص ارزیابی", "عمومی", questions.indices.toList())
            )
        }
        val result = mutableListOf<ChecklistSectionSpec>()
        val assigned = mutableSetOf<Int>()
        val seenSectionIds = mutableSetOf<String>()
        for (index in 0 until sectionArray.length()) {
            val section = sectionArray.optJSONObject(index) ?: continue
            val id = section.optString("id").trim()
            if (id.isBlank() || !seenSectionIds.add(id)) continue
            val indices = questions.filter { it.sectionId == id }.map { it.index }
            if (indices.isNotEmpty()) {
                result.add(
                    ChecklistSectionSpec(
                        id,
                        section.optString("title").ifBlank { section.optString("name").ifBlank { id } },
                        section.optString("category", "عمومی"),
                        indices
                    )
                )
                assigned.addAll(indices)
            }
        }
        val unassigned = questions.indices.filter { it !in assigned }
        if (unassigned.isNotEmpty()) result.add(ChecklistSectionSpec("__other__", "سایر موارد", "عمومی", unassigned))
        if (result.isEmpty() && questions.isNotEmpty()) {
            result.add(ChecklistSectionSpec("__all__", "شاخص ارزیابی", "عمومی", questions.indices.toList()))
        }
        return result
    }
}
