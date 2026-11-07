package ir.bazr.inspection

/** Inspector-side state machine. Final approval/rejection is server/supervisor-owned. */
object DeficiencyWorkflowRules {
    const val OPEN = "open"
    const val IN_PROGRESS = "in_progress"
    const val AWAITING_VERIFICATION = "awaiting_verification"
    const val REJECTED = "rejected"
    const val APPROVED = "approved"

    const val CRITICAL = "critical"
    const val HIGH = "high"
    const val MEDIUM = "medium"
    const val LOW = "low"

    val statuses = listOf(OPEN, IN_PROGRESS, AWAITING_VERIFICATION, REJECTED, APPROVED)
    val inspectorWritableStatuses = setOf(OPEN, IN_PROGRESS, AWAITING_VERIFICATION)
    val severities = listOf(CRITICAL, HIGH, MEDIUM, LOW)

    fun statusLabel(value: String): String = when (value) {
        OPEN -> "ثبت‌شده"
        IN_PROGRESS -> "در حال پیگیری"
        AWAITING_VERIFICATION -> "در انتظار تأیید سربازرس"
        REJECTED -> "رفع نقص رد شده؛ نیازمند اقدام مجدد"
        APPROVED -> "رفع نقص تأیید شده"
        else -> "وضعیت نامشخص"
    }

    fun serverStatus(value: String): String = when (value) {
        OPEN -> "باز"
        IN_PROGRESS -> "در حال پیگیری"
        AWAITING_VERIFICATION -> "در انتظار تأیید رفع"
        REJECTED -> "رد رفع"
        APPROVED -> "تأیید رفع"
        else -> "باز"
    }

    fun fromServerStatus(value: String): String = when (value.trim()) {
        "باز", "open" -> OPEN
        "در حال پیگیری", "in_progress" -> IN_PROGRESS
        "در انتظار تأیید رفع", "awaiting_verification" -> AWAITING_VERIFICATION
        "رد رفع", "رد شده", "rejected" -> REJECTED
        "تأیید رفع", "تایید رفع", "رفع شده", "مختومه", "closed", "resolved", "approved" -> APPROVED
        else -> OPEN
    }

    fun severityLabel(value: String): String = when (value) {
        CRITICAL -> "بحرانی"
        HIGH -> "زیاد"
        MEDIUM -> "متوسط"
        LOW -> "کم"
        else -> "نامشخص"
    }

    fun serverSeverity(value: String): String = when (value) {
        CRITICAL -> "بحرانی"
        HIGH -> "زیاد"
        MEDIUM -> "متوسط"
        LOW -> "کم"
        else -> "متوسط"
    }

    fun fromServerSeverity(value: String): String = when (value.trim()) {
        "بحرانی", "critical" -> CRITICAL
        "زیاد", "مهم", "high" -> HIGH
        "متوسط", "medium" -> MEDIUM
        "کم", "low" -> LOW
        else -> MEDIUM
    }

    /** Mirrors mobile_finding.php ownership: only the originating inspector may mutate a finding. */
    fun canInspectorManage(source: String, recordInspectorRef: String, currentInspectorRef: String): Boolean =
        source == "android_inspector" && recordInspectorRef.isNotBlank() && currentInspectorRef.isNotBlank() &&
            recordInspectorRef.trim() == currentInspectorRef.trim()

    fun canTransition(from: String, to: String): Boolean {
        if (from == to) return false
        return when (from) {
            OPEN -> to == IN_PROGRESS
            IN_PROGRESS -> to == AWAITING_VERIFICATION
            REJECTED -> to == IN_PROGRESS
            // A pending verification can only be approved/rejected by the supervisor.
            AWAITING_VERIFICATION, APPROVED -> false
            else -> false
        }
    }

    fun requiresActionNote(status: String): Boolean =
        status == IN_PROGRESS || status == AWAITING_VERIFICATION

    fun isOverdue(dueDateJalali: String, status: String, todayJalali: String = PersianCalendar.today()): Boolean {
        if (status !in setOf(OPEN, IN_PROGRESS, AWAITING_VERIFICATION, REJECTED)) return false
        val due = PersianCalendar.normalize(dueDateJalali) ?: return false
        val today = PersianCalendar.normalize(todayJalali) ?: return false
        return due < today
    }

    fun validDueDate(value: String): Boolean = PersianCalendar.isValid(value)

    fun validateDescription(value: String): String? = when {
        value.trim().length < 8 -> "شرح نقص باید حداقل ۸ نویسه باشد"
        value.trim().length > 3000 -> "شرح نقص نباید بیشتر از ۳۰۰۰ نویسه باشد"
        else -> null
    }

    fun severityRank(value: String): Int = when (value) {
        CRITICAL -> 4
        HIGH -> 3
        MEDIUM -> 2
        LOW -> 1
        else -> 0
    }
}
