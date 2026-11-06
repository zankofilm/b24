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
        val j = JSONObject().put("answers", answers).put("notes", notes)
            .put("attachments", attachments).put("started_at", startedAt)
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
    /** Shared count/omission rules, validated again independently by the PHP API. */
    fun assessment(mission: JSONObject): AssessmentRules.Summary {
        val questions = mission.optJSONObject("checklistSnapshot")?.optJSONArray("questions") ?: JSONArray()
        val ids = (0 until questions.length()).mapNotNull { idx ->
            questions.optJSONObject(idx)?.optString("id")?.trim()?.takeIf { it.isNotBlank() }
        }
        val scores = ids.associateWith { answers.optString(it) }
        return AssessmentRules.evaluate(ids, scores)
    }
    fun unscoredIds(mission: JSONObject): List<String> = assessment(mission).unscored
    fun answeredCount(mission: JSONObject): Int = assessment(mission).answered
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
        val issues = mutableListOf<String>()
        val questions = mission.optJSONObject("checklistSnapshot")?.optJSONArray("questions") ?: JSONArray()
        if (questions.length() == 0) return listOf("چک‌لیست مأموریت خالی است")
        val seenIds = mutableSetOf<String>()
        for (i in 0 until questions.length()) {
            val q = questions.optJSONObject(i)
            if (q == null) {
                issues.add("بند ${i + 1}: ساختار سؤال در اطلاعات سرور معتبر نیست")
                continue
            }
            val id = q.optString("id").trim()
            if (id.isBlank()) {
                issues.add("بند ${i + 1}: شناسه سؤال از سرور دریافت نشده است")
                continue
            }
            if (!seenIds.add(id)) {
                issues.add("بند ${i + 1}: شناسه سؤال تکراری است")
                continue
            }
            val raw = answers.optString(id)
            // Empty score is allowed. It must be acknowledged before signing.
            if (raw.isBlank()) continue
            val number = ScoreInput.parse(raw)
            if (number == null || number !in 0.0..100.0) {
                issues.add("بند ${i + 1}: امتیاز باید عددی بین ۰ تا ۱۰۰ باشد")
                continue
            }
            if (number < 60 && notes.optString(id).isBlank()) issues.add("بند ${i + 1}: برای امتیاز کمتر از ۶۰ توضیح لازم است")
            if ((number < 30 || q.optBoolean("critical")) && EvidenceRefs.references(attachments, id).isEmpty()) {
                issues.add("بند ${i + 1}: مستند برای پاسخ بحرانی لازم است")
            }
        }
        return issues
    }

}
