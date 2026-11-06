package ir.bazr.inspection

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Local, encrypted convenience copy; the server remains authoritative for status. */
class ReceiptHistory(private val store: SecureStore) {
    private fun keys(): JSONArray {
        val saved = try { store.loadStrict("receipt_history_index")?.let(::JSONArray) }
        catch (_: Exception) { null }
        val keys = linkedSetOf<String>()
        if (saved != null) for (i in 0 until saved.length()) {
            val key = saved.optString(i)
            if (key.isNotBlank() && store.hasEncryptedRecord("receipt_detail:$key")) keys.add(key)
        }
        store.encryptedRecordNames("receipt_detail:").forEach { name ->
            name.removePrefix("receipt_detail:").takeIf { it.isNotBlank() }?.let(keys::add)
        }
        val recoveredKeys = keys.toList()
        val recovered = JSONArray().also { array -> recoveredKeys.forEach(array::put) }
        val savedKeys = saved?.let { array -> (0 until array.length()).map { array.optString(it) } }
        if (savedKeys != recoveredKeys && (recovered.length() > 0 || saved != null)) {
            runCatching { store.saveDurable("receipt_history_index", recovered.toString()) }
        }
        return recovered
    }

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
        store.saveDurable("receipt_detail:$missionKey", copy.toString())
        val prev = keys()
        val next = JSONArray().put(missionKey)
        for (i in 0 until prev.length()) {
            val key = prev.optString(i)
            if (key.isNotBlank() && key != missionKey && next.length() < 150) next.put(key)
        }
        store.saveDurable("receipt_history_index", next.toString())
    }

    fun find(missionKey: String): JSONObject? {
        val recordName = "receipt_detail:$missionKey"
        if (!store.hasEncryptedRecord(recordName)) return null
        val raw = try { store.loadStrict(recordName) }
        catch (_: Exception) {
            return JSONObject().put("mission_key", missionKey).put("integrity_error", true)
                .put("integrity_message", "نسخه محلی رسید قابل بازیابی نیست")
        } ?: return null
        return try { JSONObject(raw) }
        catch (_: Exception) {
            JSONObject().put("mission_key", missionKey).put("integrity_error", true)
                .put("integrity_message", "ساختار نسخه محلی رسید معتبر نیست")
        }
    }

    fun entries(): List<JSONObject> {
        val index = keys()
        return (0 until index.length()).mapNotNull { find(index.optString(it)) }
    }
}
