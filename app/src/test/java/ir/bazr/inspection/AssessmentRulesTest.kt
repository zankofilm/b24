package ir.bazr.inspection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssessmentRulesTest {
    @Test fun acceptsLocalizedScoresAndCountsUnscoredSeparately() {
        val result = AssessmentRules.evaluate(
            listOf("a", "b", "c"),
            mapOf("a" to "۹۰", "b" to "۷۵٫۵", "c" to "")
        )
        assertEquals(3, result.total)
        assertEquals(2, result.answered)
        assertEquals(listOf("c"), result.unscored)
        assertTrue(result.invalid.isEmpty())
    }

    @Test fun rejectsOutOfRangeAndMalformedScores() {
        val result = AssessmentRules.evaluate(
            listOf("a", "b", "c"),
            mapOf("a" to "۱۰۱", "b" to "-1", "c" to "x")
        )
        assertEquals(0, result.answered)
        assertTrue(result.unscored.isEmpty())
        assertEquals(listOf("a", "b", "c"), result.invalid)
    }

    @Test fun acceptsBoundaryScores() {
        val result = AssessmentRules.evaluate(listOf("low", "high"), mapOf("low" to "۰", "high" to "۱۰۰"))
        assertEquals(2, result.answered)
        assertTrue(result.invalid.isEmpty())
    }
}
