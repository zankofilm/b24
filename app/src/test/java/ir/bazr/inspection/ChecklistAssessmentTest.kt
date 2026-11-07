package ir.bazr.inspection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChecklistAssessmentTest {
    private fun question(index: Int, id: String, section: String = "s1", critical: Boolean = false) =
        ChecklistQuestionSpec(index, id, "سؤال $id", section, critical)

    @Test fun groupsProgressAndFindsFirstActionableItem() {
        val questions = listOf(
            question(0, "a", "s1"),
            question(1, "b", "s1"),
            question(2, "c", "s2")
        )
        val sections = listOf(
            ChecklistSectionSpec("s1", "حضور و انضباط", "اداری", listOf(0, 1)),
            ChecklistSectionSpec("s2", "خدمات", "عمومی", listOf(2))
        )
        val result = ChecklistAssessment.evaluate(
            questions,
            sections,
            rawScores = mapOf("a" to "۹۰", "b" to "۵۵", "c" to ""),
            notes = emptyMap(),
            evidenceCounts = emptyMap()
        )

        assertEquals(3, result.total)
        assertEquals(2, result.answered)
        assertEquals(1, result.unscored)
        assertEquals(1, result.missingNotes)
        assertEquals(66, result.coveragePercent)
        assertEquals(1, result.firstIncompleteIndex)
        assertEquals(1, result.section("s1")?.missingNotes)
        assertEquals(1, result.section("s2")?.unscored)
        assertFalse(result.readyForReview)
    }

    @Test fun criticalAndVeryLowScoresRequireEvidenceAndLowScoresRequireNotes() {
        val questions = listOf(
            question(0, "critical", critical = true),
            question(1, "low", section = "s1")
        )
        val section = listOf(ChecklistSectionSpec("s1", "شاخص", "عمومی", listOf(0, 1)))
        val withoutEvidence = ChecklistAssessment.evaluate(
            questions, section,
            rawScores = mapOf("critical" to "۷۵", "low" to "۲۵"),
            notes = emptyMap(),
            evidenceCounts = emptyMap()
        )
        assertEquals(1, withoutEvidence.missingNotes)
        assertEquals(2, withoutEvidence.missingEvidence)
        assertEquals(3, withoutEvidence.blockingIssues.size)

        val corrected = ChecklistAssessment.evaluate(
            questions, section,
            rawScores = mapOf("critical" to "۷۵", "low" to "۲۵"),
            notes = mapOf("low" to "مستندات بررسی شد"),
            evidenceCounts = mapOf("critical" to 1, "low" to 1)
        )
        assertTrue(corrected.blockingIssues.isEmpty())
        assertTrue(corrected.readyForReview)
    }

    @Test fun invalidAndOutOfRangeScoresAreNotCountedAsAnswered() {
        val result = ChecklistAssessment.evaluate(
            listOf(question(0, "a"), question(1, "b")),
            listOf(ChecklistSectionSpec("s1", "شاخص", "عمومی", listOf(0, 1))),
            rawScores = mapOf("a" to "۱۰۱", "b" to "نامعتبر"),
            notes = emptyMap(),
            evidenceCounts = emptyMap()
        )
        assertEquals(0, result.answered)
        assertEquals(2, result.invalidScores)
        assertEquals(0, result.unscored)
        assertEquals(2, result.blockingIssues.size)
        assertEquals(0, result.firstIncompleteIndex)
    }

    @Test fun unscoredItemsRemainSeparateFromBlockingValidation() {
        val result = ChecklistAssessment.evaluate(
            listOf(question(0, "a")),
            listOf(ChecklistSectionSpec("s1", "شاخص", "عمومی", listOf(0))),
            rawScores = mapOf("a" to ""),
            notes = emptyMap(),
            evidenceCounts = emptyMap()
        )
        assertEquals(1, result.unscored)
        assertTrue(result.blockingIssues.isEmpty())
        assertTrue(result.readyForReview)
        assertFalse(result.sections.first().complete)
    }

    @Test fun quickAssessmentScaleRetainsExistingNumericValues() {
        assertEquals(listOf("0", "25", "50", "75", "100"), AssessmentScale.quickLevels.map { it.value })
        assertEquals(listOf("خیلی‌ضعیف", "ضعیف", "متوسط", "خوب", "عالی"), AssessmentScale.quickLevels.map { it.label })
    }

    @Test fun scoreOfSixtyDoesNotRequireNoteButTwentyNineDoes() {
        val questions = listOf(question(0, "sixty"), question(1, "twentyNine"))
        val result = ChecklistAssessment.evaluate(
            questions,
            listOf(ChecklistSectionSpec("s1", "شاخص", "عمومی", listOf(0, 1))),
            rawScores = mapOf("sixty" to "۶۰", "twentyNine" to "۲۹"),
            notes = emptyMap(),
            evidenceCounts = mapOf("twentyNine" to 1)
        )
        assertEquals(1, result.missingNotes)
        assertEquals("تکمیل‌شده", result.questionAt(0)?.statusLabel)
        assertEquals("نیازمند توضیح", result.questionAt(1)?.statusLabel)
    }
}
