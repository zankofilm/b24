package ir.bazr.inspection

import android.util.Base64
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant

/** Encrypted by SecureStore. A signature becomes invalid if the report body changes. */
class SignedDraft(private val store: SecureStore) {
    private fun checksum(d: InspectionDraft): String = MessageDigest.getInstance("SHA-256")
        .digest(listOf(d.key, d.submissionKey, d.startedAt, d.answers.toString(), d.notes.toString(), d.attachments.toString(), d.acknowledgedIds().toString(), d.acknowledgedAt).joinToString("\u001f").toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun save(d: InspectionDraft, png: ByteArray) {
        require(png.size in 1..250_000)
        val record = JSONObject()
            .put("signature_png_base64", Base64.encodeToString(png, Base64.NO_WRAP))
            .put("draft_sha256", checksum(d))
            .put("signed_at", Instant.now().toString())
        store.save("signed_draft:${d.key}", record.toString())
    }
    fun get(d: InspectionDraft): JSONObject? = store.load("signed_draft:${d.key}")?.let {
        runCatching { JSONObject(it) }.getOrNull()?.takeIf { record ->
            record.optString("draft_sha256") == checksum(d) && record.optString("signature_png_base64").isNotBlank()
        }
    }
    fun clear(key: String) = store.clear("signed_draft:$key")
}
