package ir.bazr.inspection

import org.junit.Assert.*
import org.junit.Test

class DeficiencyWorkflowRulesTest {
    @Test fun onlyOriginatingInspectorCanManageMobileFindings() {
        assertTrue(DeficiencyWorkflowRules.canInspectorManage("android_inspector", "INS-100", "INS-100"))
        assertFalse(DeficiencyWorkflowRules.canInspectorManage("android_inspector", "INS-100", "INS-200"))
        assertFalse(DeficiencyWorkflowRules.canInspectorManage("web_admin", "INS-100", "INS-100"))
        assertFalse(DeficiencyWorkflowRules.canInspectorManage("android_inspector", "", "INS-100"))
        assertFalse(DeficiencyWorkflowRules.canInspectorManage("android_inspector", "INS-100", ""))
    }

    @Test fun inspectorTransitionsStopAtSupervisorVerification() {
        assertTrue(DeficiencyWorkflowRules.canTransition(DeficiencyWorkflowRules.OPEN, DeficiencyWorkflowRules.IN_PROGRESS))
        assertTrue(DeficiencyWorkflowRules.canTransition(DeficiencyWorkflowRules.IN_PROGRESS, DeficiencyWorkflowRules.AWAITING_VERIFICATION))
        assertTrue(DeficiencyWorkflowRules.canTransition(DeficiencyWorkflowRules.REJECTED, DeficiencyWorkflowRules.IN_PROGRESS))
        assertFalse(DeficiencyWorkflowRules.canTransition(DeficiencyWorkflowRules.AWAITING_VERIFICATION, DeficiencyWorkflowRules.APPROVED))
        assertFalse(DeficiencyWorkflowRules.canTransition(DeficiencyWorkflowRules.AWAITING_VERIFICATION, DeficiencyWorkflowRules.IN_PROGRESS))
        assertFalse(DeficiencyWorkflowRules.canTransition(DeficiencyWorkflowRules.APPROVED, DeficiencyWorkflowRules.IN_PROGRESS))
    }

    @Test fun inspectorCannotApproveOwnFinding() {
        assertFalse(DeficiencyWorkflowRules.inspectorWritableStatuses.contains(DeficiencyWorkflowRules.APPROVED))
        assertFalse(DeficiencyWorkflowRules.inspectorWritableStatuses.contains(DeficiencyWorkflowRules.REJECTED))
    }

    @Test fun verificationTransitionsRequireAnActionNote() {
        assertFalse(DeficiencyWorkflowRules.requiresActionNote(DeficiencyWorkflowRules.OPEN))
        assertTrue(DeficiencyWorkflowRules.requiresActionNote(DeficiencyWorkflowRules.IN_PROGRESS))
        assertTrue(DeficiencyWorkflowRules.requiresActionNote(DeficiencyWorkflowRules.AWAITING_VERIFICATION))
    }

    @Test fun overdueOnlyAppliesToUnresolvedItems() {
        assertTrue(DeficiencyWorkflowRules.isOverdue("1405/08/15", DeficiencyWorkflowRules.OPEN, "1405/08/16"))
        assertFalse(DeficiencyWorkflowRules.isOverdue("1405/08/15", DeficiencyWorkflowRules.APPROVED, "1405/08/16"))
        assertFalse(DeficiencyWorkflowRules.isOverdue("bad-date", DeficiencyWorkflowRules.OPEN, "1405/08/16"))
    }

    @Test fun descriptionAndSeverityValidationAreBounded() {
        assertNotNull(DeficiencyWorkflowRules.validateDescription("کوتاه"))
        assertNull(DeficiencyWorkflowRules.validateDescription("شرح نقص با جزئیات کافی"))
        assertEquals(4, DeficiencyWorkflowRules.severityRank(DeficiencyWorkflowRules.CRITICAL))
        assertEquals(1, DeficiencyWorkflowRules.severityRank(DeficiencyWorkflowRules.LOW))
        assertTrue(DeficiencyWorkflowRules.severities.contains(DeficiencyWorkflowRules.HIGH))
    }
}
