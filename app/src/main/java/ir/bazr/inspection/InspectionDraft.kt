package ir.bazr.inspection

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/** Persist only draft edits, not server-issued receipts or authority. */
class InspectionDraft(val key: String, private val store: SecureStore) {
    var answers: JSONObject = JSONObject()
    var notes: JSONObject = JSONObject()
    var attachments: JSONObject = JSONObject() // question -> legacy String or JSON array of encrypted evidence refs
    var startedAt: String = Instant.now().toString()
    /** Local last-save timestamp; older records fall back to started_at without migration loss. */
    var updatedAt: String = startedAt
    // Explicit, per-version acknowledgement of omitted scores. Stored encrypted.
    var acknowledgedUnscored: JSONArray = JSONArray()
    var acknowledgedAt: String = ""
    // Stable across retries: the server can deduplicate a submission after a network timeout.
    var submissionKey: String = UUID.randomUUID().toString()
    var integrityError: Boolean = false
        private set

    init {
        val encoded = try { store.loadStrict("draft:$key") }
        catch (_: Exception) { integrityError = true; null }
        if (!integrityError && encoded != null) {
            try {
                val j = JSONObject(encoded)
                answers = j.optJSONObject("answers") ?: JSONObject()
                notes = j.optJSONObject("notes") ?: JSONObject()
                attachments = j.optJSONObject("attachments") ?: JSONObject()
                startedAt = j.optString("started_at", startedAt)
                updatedAt = j.optString("updated_at", startedAt)
                acknowledgedUnscored = j.optJSONArray("acknowledged_unscored_ids") ?: JSONArray()
                acknowledgedAt = j.optString("acknowledged_at", "")
                submissionKey = j.optString("submission_key").takeIf { it.isNotBlank() } ?: submissionKey
            } catch (_: Exception) {
                integrityError = true
            }
        }
    }

    fun save() {
        if (integrityError) throw java.io.IOException("پیش‌نویس رمزنگاری‌شده قابل بازیابی نیست؛ از بازنویسی آن جلوگیری شد")
        updatedAt = Instant.now().toString()
        val j = JSONObject().put("answers", answers).put("notes", notes)
            .put("attachments", attachments).put("started_at", startedAt)
            .put("updated_at", updatedAt)
            .put("submission_key", submissionKey)
            .put("acknowledged_unscored_ids", acknowledgedUnscored)
            .put("acknowledged_at", acknowledgedAt)
        try { store.saveDurable("draft:$key", j.toString()) }
        catch (error: Exception) {
            integrityError = true
            throw java.io.IOException("ذخیره امن پیش‌نویس انجام نشد؛ برای جلوگیری از ارسال ناقص، این نسخه قفل شد", error)
        }
    }
    fun clear() = store.clearDurable("draft:$key")
    /** One shared evaluation powers progress cards, section navigation and signature validation. */
    fun checklistOverview(mission: JSONObject): ChecklistOverview {
        val snapshot = mission.optJSONObject("checklistSnapshot") ?: JSONObject()
        return ChecklistAssessment.fromSnapshot(snapshot, answers, notes, attachments)
    }

    /** Backward-compatible summary shape used by existing screens. */
    fun assessment(mission: JSONObject): AssessmentRules.Summary {
        val overview = checklistOverview(mission)
        val unscored = overview.questions.filter { it.unscored }.map { it.question.id }.filter { it.isNotBlank() }.distinct()
        val invalid = overview.questions.filter { it.invalidScore }.map { it.question.id }.filter { it.isNotBlank() }.distinct()
        return AssessmentRules.Summary(overview.total, overview.answered, unscored, invalid)
    }
    fun unscoredIds(mission: JSONObject): List<String> = checklistOverview(mission).questions
        .filter { it.unscored }.map { it.question.id }.filter { it.isNotBlank() }.distinct()
    fun answeredCount(mission: JSONObject): Int = checklistOverview(mission).answered
    fun acknowledgeUnscored(mission: JSONObject) {
        acknowledgedUnscored = JSONArray(unscoredIds(mission))
        acknowledgedAt = Instant.now().toString()
    }
    fun hasAcknowledgedUnscored(mission: JSONObject): Boolean {
        val current = unscoredIds(mission)
        if (current.isEmpty()) return true
        if (acknowledgedAt.isBlank()) return false
        val recorded = (0 until acknowledgedUnscored.length()).map { acknowledgedUnscored.optString(it) }.sorted()
        return recorded == current
    }
    fun clearAcknowledgement() {
        acknowledgedUnscored = JSONArray()
        acknowledgedAt = ""
    }
    fun acknowledgedIds(): JSONArray = JSONArray((0 until acknowledgedUnscored.length()).map { acknowledgedUnscored.optString(it) })
    fun validate(mission: JSONObject): List<String> {
        if (integrityError) return listOf("پیش‌نویس رمزنگاری‌شده قابل بازیابی نیست؛ برای جلوگیری از حذف اطلاعات، امکان ویرایش یا ارسال آن وجود ندارد")
        return checklistOverview(mission).blockingIssues
    }

}
