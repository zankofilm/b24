package ir.bazr.inspection

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Explicit user-controlled outbox; retry never silently submits without an unlocked session. */
class SubmissionQueue(private val store: SecureStore) {
    private fun index(): JSONArray {
        val saved = try { store.loadStrict("outbox_index")?.let(::JSONArray) }
        catch (_: Exception) { null }
        val keys = linkedSetOf<String>()
        if (saved != null) {
            for (i in 0 until saved.length()) {
                val key = saved.optString(i)
                if (key.isNotBlank() && store.hasEncryptedRecord(recordKey(key))) keys.add(key)
            }
        }
        // Recover records that were committed just before an interrupted index write.
        store.encryptedRecordNames("outbox:").forEach { name ->
            name.removePrefix("outbox:").takeIf { it.isNotBlank() }?.let(keys::add)
        }
        val recoveredKeys = keys.toList()
        val recovered = JSONArray().also { array -> recoveredKeys.forEach(array::put) }
        val savedKeys = saved?.let { array -> (0 until array.length()).map { array.optString(it) } }
        if (savedKeys != recoveredKeys && (recovered.length() > 0 || saved != null)) {
            runCatching { store.saveDurable("outbox_index", recovered.toString()) }
        }
        return recovered
    }

    private fun recordKey(key: String) = "outbox:$key"

    fun get(key: String): JSONObject? {
        val name = recordKey(key)
        if (!store.hasEncryptedRecord(name)) return null
        val raw = try { store.loadStrict(name) }
        catch (_: Exception) {
            return JSONObject().put("mission_key", key).put("state", "failed")
                .put("integrity_error", true)
                .put("error", "رکورد صف ارسال قابل رمزگشایی نیست؛ ارسال خودکار متوقف شد")
        } ?: return null
        return try { JSONObject(raw) }
        catch (_: Exception) {
            JSONObject().put("mission_key", key).put("state", "failed")
                .put("integrity_error", true)
                .put("error", "ساختار رکورد صف ارسال معتبر نیست؛ ارسال خودکار متوقف شد")
        }
    }

    fun entries(): List<JSONObject> {
        val keys = index()
        return (0 until keys.length()).mapNotNull { get(keys.optString(it)) }
    }

    private fun saveRecord(key: String, item: JSONObject) { store.saveDurable(recordKey(key), item.toString()) }

    fun enqueue(mission: JSONObject, draft: InspectionDraft): JSONObject {
        val key = draft.key
        get(key)?.let { existing ->
            if (!existing.optBoolean("integrity_error")) {
                val current = index()
                if ((0 until current.length()).none { current.optString(it) == key }) {
                    current.put(key)
                    store.saveDurable("outbox_index", current.toString())
                }
            }
            return existing
        }
        val record = JSONObject()
            .put("mission_key", key)
            .put("mission", JSONObject(mission.toString()))
            .put("organization", mission.optString("orgName", "دستگاه اجرایی"))
            .put("queued_at", Instant.now().toString())
            .put("finished_at", Instant.now().toString())
            .put("state", "pending")
            .put("attempts", 0)
            .put("error", "")
            .put("uploaded", JSONObject())
        saveRecord(key, record)
        val current = index()
        if ((0 until current.length()).none { current.optString(it) == key }) {
            current.put(key)
            store.saveDurable("outbox_index", current.toString())
        }
        return record
    }

    fun setState(key: String, state: String, error: String = "") {
        require(state in setOf("pending", "uploading", "failed"))
        get(key)?.takeIf { !it.optBoolean("integrity_error") }?.let { item ->
            if (state == "uploading") item.put("attempts", item.optInt("attempts", 0) + 1)
            item.put("state", state)
                .put("error", error.take(600))
                .put("updated_at", Instant.now().toString())
            saveRecord(key, item)
        }
    }

    /** Fingerprint-bound receipt prevents reusing a file ID after attachment replacement. */
    fun rememberUpload(key: String, question: String, sha256: String, fileId: Int) {
        require(sha256.matches(Regex("[a-f0-9]{64}")) && fileId > 0)
        get(key)?.takeIf { !it.optBoolean("integrity_error") }?.let { item ->
            val uploads = item.optJSONObject("uploaded") ?: JSONObject()
            uploads.put("$question:$sha256", fileId)
            saveRecord(key, item.put("uploaded", uploads))
        }
    }

    /** Server rejected cached upload IDs: allow the next explicit retry to re-upload files. */
    fun forgetUploadedReferences(key: String) {
        get(key)?.takeIf { !it.optBoolean("integrity_error") }?.let { item ->
            item.put("uploaded", JSONObject())
            saveRecord(key, item)
        }
    }

    fun remove(key: String) {
        store.clearDurable(recordKey(key))
        val next = JSONArray()
        val previous = index()
        for (i in 0 until previous.length()) if (previous.optString(i) != key) next.put(previous.optString(i))
        store.saveDurable("outbox_index", next.toString())
    }
}
