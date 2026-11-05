package ir.bazr.inspection

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID

class ApiException(
    val status: Int, val error: String, val details: String,
    val requestId: String = "", val apiVersion: String = "", val submitVersion: String = ""
) : IOException("HTTP $status: $error")

/** Network calls are synchronous: invoke from the Activity's background executor. */
class ApiClient(private val base: String) {
    init { require(base.startsWith("https://") && base.endsWith("/")) }

    fun post(
        path: String, body: JSONObject, bearer: String? = null,
        idempotencyKey: String? = null, onProgress: ((Long, Long) -> Unit)? = null,
        onAwaitingResponse: (() -> Unit)? = null
    ): JSONObject {
        val conn = open(path, "POST", bearer)
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        idempotencyKey?.let { conn.setRequestProperty("Idempotency-Key", it) }
        conn.doOutput = true
        val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
        conn.setFixedLengthStreamingMode(bytes.size)
        conn.outputStream.use { stream ->
            var offset = 0
            var lastPercent = -1
            while (offset < bytes.size) {
                val size = minOf(8192, bytes.size - offset)
                stream.write(bytes, offset, size)
                offset += size
                val pct = (100L * offset / bytes.size.coerceAtLeast(1)).toInt()
                if (pct != lastPercent) { onProgress?.invoke(offset.toLong(), bytes.size.toLong()); lastPercent = pct }
            }
        }
        onAwaitingResponse?.invoke()
        return read(conn)
    }

    fun get(path: String, bearer: String): JSONObject = read(open(path, "GET", bearer))

    /** Secure evidence download for the assigned mobile supervisor review inbox. */
    fun downloadSupervisorEvidence(uploadId: Int, bearer: String): Pair<ByteArray, String> {
        require(uploadId > 0)
        val conn = (URL(base + "mobile_supervisor_file.php?upload_id=$uploadId")
            .openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = 45_000
            conn.setRequestProperty("Authorization", "Bearer $bearer")
            conn.setRequestProperty("Accept", "image/*,application/pdf,application/octet-stream")
            if (conn.responseCode != 200) throw ApiException(conn.responseCode, "supervisor_evidence_unavailable", "")
            val mime = conn.contentType.orEmpty().substringBefore(';').ifBlank { "application/octet-stream" }
            if (!(mime.startsWith("image/") || mime == "application/pdf" || mime == "application/octet-stream")) {
                throw UserActionException("نوع فایل پیوست پشتیبانی نمی‌شود")
            }
            val declared = conn.contentLengthLong
            if (declared > 12L * 1024 * 1024) throw UserActionException("حجم مستند بیش از حد مجاز است")
            val out = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (out.size() + read > 12 * 1024 * 1024) throw UserActionException("حجم مستند بیش از حد مجاز است")
                    out.write(buffer, 0, read)
                }
            }
            val bytes = out.toByteArray()
            if (bytes.isEmpty()) throw UserActionException("فایل پیوست خالی است")
            return Pair(bytes, mime)
        } finally { conn.disconnect() }
    }

    /** PDF is downloaded as binary from an authenticated, fixed endpoint (never an arbitrary URL). */
    fun downloadOfficialPdf(submissionId: Int, kind: String, bearer: String): ByteArray {
        require(submissionId > 0 && kind in setOf("summary", "detailed"))
        val conn = (URL(base + "mobile_report_pdf.php?submission_id=$submissionId&kind=$kind")
            .openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("Authorization", "Bearer $bearer")
            conn.setRequestProperty("Accept", "application/pdf")
            if (conn.responseCode != 200 || !conn.contentType.orEmpty().lowercase().startsWith("application/pdf")) {
                throw ApiException(conn.responseCode, "official_pdf_unavailable", "")
            }
            val declared = conn.contentLengthLong
            if (declared > 20L * 1024 * 1024) throw UserActionException("حجم PDF بیش از حد مجاز است")
            val out = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (out.size() + read > 20 * 1024 * 1024) throw UserActionException("PDF بزرگ‌تر از حد مجاز است")
                    out.write(buffer, 0, read)
                }
            }
            val pdf = out.toByteArray()
            if (pdf.size < 5 || !String(pdf, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")) {
                throw UserActionException("پاسخ سرور فایل PDF معتبر نیست")
            }
            return pdf
        } finally { conn.disconnect() }
    }

    private fun open(path: String, method: String, bearer: String?): HttpURLConnection {
        require(path.matches(Regex("[A-Za-z0-9_/-]+\\.php(?:\\?[A-Za-z0-9_=&.-]+)?")))
        val url = URL(base + path)
        val conn = (url.openConnection() as HttpURLConnection)
        conn.instanceFollowRedirects = false
        conn.requestMethod = method
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("Accept", "application/json")
        if (!bearer.isNullOrBlank()) conn.setRequestProperty("Authorization", "Bearer $bearer")
        return conn
    }

    /** Multipart upload of a decrypted, size-bounded in-memory evidence blob. */
    fun uploadEvidence(
        bearer: String, bytes: ByteArray, mime: String, displayName: String,
        mission: String, question: String,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): JSONObject {
        if (bytes.isEmpty() || bytes.size > 12 * 1024 * 1024) throw UserActionException("حجم مستند نامعتبر است")
        if (mime !in setOf("image/jpeg", "image/png", "image/webp", "application/pdf")) {
            throw UserActionException("نوع فایل پشتیبانی نمی‌شود")
        }
        val fileName = displayName.replace(Regex("[\\r\\n\\\\\\\"]"), "_").take(100)
        val boundary = "BAZR-" + UUID.randomUUID().toString()
        val conn = open("mobile_upload.php", "POST", bearer)
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        fun part(name: String, value: String): ByteArray =
            ("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
                .toByteArray(StandardCharsets.UTF_8)
        val start = ByteArrayOutputStream()
        start.write(part("mission_key", mission))
        start.write(part("question_key", question))
        start.write(part("file_kind", if (mime.startsWith("image/")) "image" else "document"))
        start.write(("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\nContent-Type: $mime\r\n\r\n").toByteArray(StandardCharsets.UTF_8))
        val end = "\r\n--$boundary--\r\n".toByteArray()
        conn.setFixedLengthStreamingMode(start.size() + bytes.size + end.size)
        conn.outputStream.use { stream ->
            stream.write(start.toByteArray())
            var offset = 0
            var lastPercent = -1
            while (offset < bytes.size) {
                val size = minOf(8192, bytes.size - offset)
                stream.write(bytes, offset, size)
                offset += size
                val pct = (100L * offset / bytes.size.coerceAtLeast(1)).toInt()
                if (pct != lastPercent) { onProgress?.invoke(offset.toLong(), bytes.size.toLong()); lastPercent = pct }
            }
            stream.write(end)
        }
        return read(conn)
    }

    /** Bounded response parsing: do not expose server HTML or internal errors in the UI. */
    private fun read(conn: HttpURLConnection): JSONObject {
        try {
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val response = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { reader ->
                val buffer = CharArray(8192)
                val result = StringBuilder()
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (result.length + count > 4 * 1024 * 1024) {
                        throw ApiException(status, "response_too_large", "")
                    }
                    result.append(buffer, 0, count)
                }
                result.toString()
            } ?: ""
            val obj = try { JSONObject(response) } catch (_: Exception) { null }
            val requestId = conn.getHeaderField("X-BAZR-Request").orEmpty()
                .takeIf { it.matches(Regex("[A-Za-z0-9._:-]{8,128}")) }.orEmpty()
            val apiVersion = conn.getHeaderField("X-BAZR-API-Version").orEmpty().take(32)
            val submitVersion = conn.getHeaderField("X-BAZR-Mobile-Submit-Version").orEmpty().take(32)
            if (obj == null) throw ApiException(status, "invalid_server_response", "", requestId, apiVersion, submitVersion)
            val rawError = obj.opt("error")
            val error = if (rawError == null || rawError == JSONObject.NULL) "" else rawError.toString()
            if (status !in 200..299 || error.isNotBlank()) {
                throw ApiException(status, error.ifBlank { "request_failed" }, "", requestId, apiVersion, submitVersion)
            }
            return obj
        } finally { conn.disconnect() }
    }
}
