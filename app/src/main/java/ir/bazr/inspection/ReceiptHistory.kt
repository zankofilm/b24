package ir.bazr.inspection

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Local, encrypted convenience copy; the server remains authoritative for status. */
class ReceiptHistory(private val store: SecureStore) {
    private fun keys(): JSONArray = runCatching { JSONArray(store.load("receipt_history_index") ?: "[]") }
        .getOrDefault(JSONArray())

    fun save(mission: JSONObject, draft: InspectionDraft, response: JSONObject) {
        val missionKey = draft.key
        // Preserve human-readable question labels from the server's mission snapshot.
        val titles = JSONObject()
        val questions = mission.optJSONObject("checklistSnapshot")?.optJSONArray("questions") ?: JSONArray()
        for (i in 0 until questions.length()) {
            val question = questions.optJSONObject(i) ?: continue
            val id = question.optString("id")
            if (id.isBlank()) continue
            titles.put(id, question.optString("text", question.optString("title", id)).take(300))
        }
        val copy = JSONObject()
            .put("question_titles", titles)
            .put("mission_key", missionKey)
            .put("organization", mission.optString("orgName", "دستگاه اجرایی"))
            .put("receipt_code", response.optString("receipt_code"))
            .put("submitted_at", Instant.now().toString())
            .put("answers", JSONObject(draft.answers.toString()))
            .put("notes", JSONObject(draft.notes.toString()))
            .put("attachment_count", EvidenceRefs.count(draft.attachments))
            .put("source", "local_copy")
        store.save("receipt_detail:$missionKey", copy.toString())
        val prev = keys()
        val next = JSONArray().put(missionKey)
        for (i in 0 until prev.length()) {
            val key = prev.optString(i)
            if (key.isNotBlank() && key != missionKey && next.length() < 150) next.put(key)
        }
        store.save("receipt_history_index", next.toString())
    }

    fun find(missionKey: String): JSONObject? = store.load("receipt_detail:$missionKey")?.let { raw ->
        runCatching { JSONObject(raw) }.getOrNull()
    }

    fun entries(): List<JSONObject> {
        val index = keys()
        return (0 until index.length()).mapNotNull { find(index.optString(it)) }
    }
}
