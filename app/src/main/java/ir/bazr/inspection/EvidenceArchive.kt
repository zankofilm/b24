package ir.bazr.inspection

import org.json.JSONArray
import org.json.JSONObject

/** A local archive row. The encrypted vault remains the source of local file bytes. */
data class EvidenceArchiveEntry(
    val reference: String,
    val name: String,
    val mime: String,
    val sizeBytes: Long,
    val createdAtMillis: Long,
    val missionKey: String,
    val organization: String,
    val questionId: String,
    val questionTitle: String,
    val sourceLabel: String,
    val integrityError: Boolean = false
)

/**
 * Builds a read-only index from encrypted draft and deficiency records.
 * It never mutates, migrates, or deletes a draft/evidence record.
 */
class EvidenceArchive(
    private val store: SecureStore,
    private val vault: EvidenceVault,
    private val deficiencies: DeficiencyStore
) {
    private data class MutableEntry(
        val reference: String,
        var name: String,
        var mime: String,
        var sizeBytes: Long,
        var createdAtMillis: Long,
        var missionKey: String,
        var organization: String,
        var questionId: String,
        var questionTitle: String,
        val sources: LinkedHashSet<String>,
        var integrityError: Boolean
    )

    fun localEntries(missions: JSONArray): List<EvidenceArchiveEntry> {
        val missionByKey = linkedMapOf<String, JSONObject>()
        for (i in 0 until missions.length()) {
            val mission = missions.optJSONObject(i) ?: continue
            val key = mission.optString("_server_key").ifBlank { mission.optString("id") }
            if (key.isNotBlank()) missionByKey[key] = mission
        }
        val merged = linkedMapOf<String, MutableEntry>()

        fun questionTitle(mission: JSONObject?, questionId: String): String {
            val questions = mission?.optJSONObject("checklistSnapshot")?.optJSONArray("questions") ?: JSONArray()
            for (i in 0 until questions.length()) {
                val question = questions.optJSONObject(i) ?: continue
                if (question.optString("id") == questionId) {
                    return question.optString("text", question.optString("title", questionId)).ifBlank { questionId }
                }
            }
            return questionId.ifBlank { "شاخص نامشخص" }
        }

        fun add(reference: String, missionKey: String, organization: String, questionId: String,
                title: String, source: String) {
            if (reference.isBlank()) return
            val details = vault.metadataDetails(reference)
            val existing = merged[reference]
            if (existing != null) {
                existing.sources.add(source)
                if (existing.organization.isBlank() && organization.isNotBlank()) existing.organization = organization
                if (existing.questionTitle == existing.questionId && title.isNotBlank()) existing.questionTitle = title
                existing.integrityError = existing.integrityError || details.optBoolean("integrity_error")
                return
            }
            merged[reference] = MutableEntry(
                reference = reference,
                name = details.optString("name", "مستند"),
                mime = details.optString("mime", "application/octet-stream"),
                sizeBytes = details.optLong("size", 0L),
                createdAtMillis = details.optLong("created_at", 0L),
                missionKey = missionKey,
                organization = organization,
                questionId = questionId,
                questionTitle = title,
                sources = linkedSetOf(source),
                integrityError = details.optBoolean("integrity_error")
            )
        }

        // Drafts are discovered from encrypted record names, including records not in the latest sync.
        store.encryptedRecordNames("draft:").forEach { recordName ->
            val key = recordName.removePrefix("draft:")
            if (key.isBlank()) return@forEach
            val draft = InspectionDraft(key, store)
            if (draft.integrityError) return@forEach
            val mission = missionByKey[key]
            val org = mission?.optString("orgName", "") ?: ""
            val keys = draft.attachments.keys()
            while (keys.hasNext()) {
                val questionId = keys.next()
                val title = questionTitle(mission, questionId)
                EvidenceRefs.references(draft.attachments, questionId).forEach { ref ->
                    add(ref, key, org, questionId, title, "پیش‌نویس بازرسی")
                }
            }
        }

        // Findings can retain a file after the inspection draft has been submitted or removed.
        deficiencies.entries().forEach { finding ->
            if (finding.optBoolean("integrity_error")) return@forEach
            val refs = finding.optJSONArray("linked_evidence_refs") ?: JSONArray()
            val missionKey = finding.optString("mission_key")
            val questionId = finding.optString("question_id")
            val mission = missionByKey[missionKey]
            val title = finding.optString("question_title").ifBlank { questionTitle(mission, questionId) }
            for (i in 0 until refs.length()) {
                add(refs.optString(i), missionKey, finding.optString("organization"), questionId,
                    title, "مستند نقص")
            }
        }

        return merged.values.map { item ->
            EvidenceArchiveEntry(
                reference = item.reference,
                name = item.name,
                mime = item.mime,
                sizeBytes = item.sizeBytes,
                createdAtMillis = item.createdAtMillis,
                missionKey = item.missionKey,
                organization = item.organization,
                questionId = item.questionId,
                questionTitle = item.questionTitle,
                sourceLabel = item.sources.joinToString(" · "),
                integrityError = item.integrityError
            )
        }.sortedByDescending { it.createdAtMillis }
    }
}

/** Pure filters shared by the native archive UI and unit tests. */
object EvidenceArchiveRules {
    const val ALL = "all"
    const val IMAGES = "images"
    const val DOCUMENTS = "documents"
    const val LOCAL = "local"
    const val SERVER = "server"

    fun matches(filter: String, isLocal: Boolean, mime: String, query: String, searchable: List<String>): Boolean {
        val typeMatch = when (filter) {
            IMAGES -> mime.startsWith("image/")
            DOCUMENTS -> mime == "application/pdf" || mime == "application/octet-stream"
            LOCAL -> isLocal
            SERVER -> !isLocal
            else -> true
        }
        if (!typeMatch) return false
        val q = query.trim()
        return q.isBlank() || searchable.any { it.contains(q, ignoreCase = true) }
    }

    fun formatSize(bytes: Long): String = when {
        bytes <= 0 -> "حجم نامشخص"
        bytes < 1024 -> "$bytes بایت"
        bytes < 1024 * 1024 -> "${bytes / 1024} کیلوبایت"
        else -> String.format(java.util.Locale.US, "%.1f مگابایت", bytes / (1024.0 * 1024.0))
    }
}
