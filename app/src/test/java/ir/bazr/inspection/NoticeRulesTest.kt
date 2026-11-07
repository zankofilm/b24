package ir.bazr.inspection

import org.junit.Assert.*
import org.junit.Test

class NoticeRulesTest {
    @Test fun onlyNewUnreadNoticesTriggerANotification() {
        val known = setOf("n-1", "n-2")
        assertTrue(NoticeRules.shouldNotify("n-3", false, known))
        assertFalse(NoticeRules.shouldNotify("n-1", false, known))
        assertFalse(NoticeRules.shouldNotify("n-3", true, known))
        assertFalse(NoticeRules.shouldNotify("", false, known))
    }

    @Test fun rememberedNoticeIdsAreUniqueAndBounded() {
        val ids = (1..450).map { "n-$it" } + listOf("n-450", "", "n-451")
        val retained = NoticeRules.retainRecentIds(ids)
        assertEquals(NoticeRules.MAX_REMEMBERED_IDS, retained.size)
        assertEquals(retained.size, retained.toSet().size)
        assertEquals("n-451", retained.last())
        assertFalse(retained.contains("n-1"))
    }

    @Test fun pollingIntervalIsAndroidWorkManagerMinimum() {
        assertEquals(15, NoticeRules.POLL_INTERVAL_MINUTES)
    }
}
