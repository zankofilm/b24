package ir.bazr.inspection

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Evidence imported from camera/SAF is copied into an app-private, AES-GCM encrypted vault.
 * Photos are never written into public gallery storage. Android backup is disabled for this app.
 * Legacy content:// references from 0.6 drafts remain readable when their grant still exists.
 */
class EvidenceVault(private val context: Context, private val store: SecureStore) {
    data class Evidence(val bytes: ByteArray, val mime: String, val name: String)

    private val folder = File(context.filesDir, "inspection_evidence").apply { mkdirs() }
    private val keyAlias = "bazr_evidence_aes_v1"
    private val allowed = setOf("image/jpeg", "image/png", "image/webp", "application/pdf")
    private val maxImageSize = 24 * 1024 * 1024
    private val maxDocumentSize = 12 * 1024 * 1024
    private val idPattern = Regex("[a-f0-9-]{36}")

    private fun encryptionKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
            generateKey()
        }
    }

    private fun limitFor(mime: String): Int = if (mime == "application/pdf") maxDocumentSize else maxImageSize

    private fun readLimited(resolver: ContentResolver, uri: Uri, limit: Int): ByteArray {
        return resolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n == -1) break
                if (n == 0) continue
                if (output.size() + n > limit) {
                    throw IOException(if (limit == maxDocumentSize) "حجم PDF نباید بیش از ۱۲ مگابایت باشد" else "حجم عکس برای ذخیره امن بیش از ۲۴ مگابایت است")
                }
                output.write(buf, 0, n)
            }
            output.toByteArray()
        } ?: throw IOException("فایل انتخابی قابل خواندن نیست")
    }

    private fun sniffMime(bytes: ByteArray): String? {
        if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) return "image/jpeg"
        if (bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(
                byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))) return "image/png"
        if (bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP") return "image/webp"
        if (bytes.size >= 5 && String(bytes, 0, 5, Charsets.US_ASCII) == "%PDF-") return "application/pdf"
        return null
    }

    private fun canonicalMime(raw: String?): String = when (raw?.lowercase()?.substringBefore(';')?.trim()) {
        "image/jpg" -> "image/jpeg"
        "image/jpeg" -> "image/jpeg"
        "image/png" -> "image/png"
        "image/webp" -> "image/webp"
        "application/pdf" -> "application/pdf"
        else -> ""
    }

    fun importUri(resolver: ContentResolver, uri: Uri, camera: Boolean = false): String {
        val declaredMime = if (camera) "image/jpeg" else canonicalMime(resolver.getType(uri))
        // Read with the larger image cap first: some document providers report octet-stream
        // for PDFs. The file signature below determines the real accepted MIME type.
        val initialLimit = if (declaredMime == "application/pdf") maxDocumentSize else maxImageSize
        val bytes = readLimited(resolver, uri, initialLimit)
        if (bytes.isEmpty()) throw IOException("فایل خالی است")
        val actualMime = sniffMime(bytes) ?: throw IOException("محتوای فایل با فرمت‌های مجاز JPEG، PNG، WEBP یا PDF مطابقت ندارد")
        if (declaredMime.isNotBlank() && declaredMime != actualMime) {
            throw IOException("نوع اعلام‌شده فایل با محتوای واقعی آن مطابقت ندارد")
        }
        if (actualMime !in allowed) throw IOException("نوع فایل پشتیبانی نمی‌شود")
        if (actualMime == "application/pdf" && bytes.size > maxDocumentSize) {
            throw IOException("حجم PDF نباید بیش از ۱۲ مگابایت باشد")
        }

        var name = if (camera) "inspection-${System.currentTimeMillis()}.jpg" else "evidence"
        if (!camera) resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) name = cursor.getString(0) ?: name
        }
        name = name.replace(Regex("[\\r\\n\\\\/\"\u0000-\u001F]"), "_").take(90)
        val id = UUID.randomUUID().toString()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, encryptionKey()) }
        val ciphertext = cipher.doFinal(bytes)
        val target = File(folder, "$id.bin")
        val temporary = File(folder, "$id.tmp")
        try {
            temporary.outputStream().use { output ->
                output.write(byteArrayOf(1, cipher.iv.size.toByte()))
                output.write(cipher.iv)
                output.write(ciphertext)
            }
            if (!temporary.renameTo(target)) throw IOException("ذخیره امن مستند ناموفق بود")
            store.saveDurable("evidence:$id", JSONObject().put("mime", actualMime).put("name", name)
                .put("size", bytes.size).toString())
            return "vault:$id"
        } catch (error: Exception) {
            temporary.delete()
            target.delete()
            throw error
        }
    }

    fun read(reference: String, resolver: ContentResolver): Evidence {
        if (!reference.startsWith("vault:")) {
            // Backward compatibility with drafts created by versions <= 0.6.
            val uri = Uri.parse(reference)
            val declaredMime = canonicalMime(resolver.getType(uri))
            if (declaredMime !in allowed) throw IOException("نوع فایل قدیمی نامشخص است؛ دوباره انتخاب کنید")
            val bytes = readLimited(resolver, uri, limitFor(declaredMime))
            if (sniffMime(bytes) != declaredMime) throw IOException("محتوای فایل قدیمی معتبر نیست؛ دوباره انتخاب کنید")
            return Evidence(bytes, declaredMime, "evidence")
        }
        val id = reference.removePrefix("vault:")
        if (!idPattern.matches(id)) throw IOException("شناسه مستند نامعتبر است")
        val metaRaw = try { store.loadStrict("evidence:$id") }
            catch (error: Exception) { throw IOException("اطلاعات رمزنگاری‌شده مستند قابل بازیابی نیست", error) }
            ?: throw IOException("اطلاعات مستند موجود نیست")
        val meta = try { JSONObject(metaRaw) }
            catch (error: Exception) { throw IOException("ساختار اطلاعات مستند معتبر نیست", error) }
        val mime = meta.optString("mime")
        if (mime !in allowed) throw IOException("نوع مستند ذخیره‌شده معتبر نیست")
        val file = File(folder, "$id.bin")
        if (!file.isFile || file.length() > maxImageSize + 64L) throw IOException("مستند رمزنگاری‌شده یافت نشد")
        val source = file.readBytes()
        if (source.size < 30 || source[0] != 1.toByte()) throw IOException("ساختار مستند نامعتبر است")
        val length = source[1].toInt() and 255
        if (length != 12 || source.size < 2 + length + 16) throw IOException("سرآیند مستند نامعتبر است")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, source.copyOfRange(2, 2 + length)))
        val decoded = try { cipher.doFinal(source.copyOfRange(2 + length, source.size)) }
            catch (_: Exception) { throw IOException("اعتبارسنجی رمزنگاری مستند ناموفق بود") }
        if (decoded.size > limitFor(mime) || sniffMime(decoded) != mime) throw IOException("محتوای مستند رمزنگاری‌شده معتبر نیست")
        return Evidence(decoded, mime, meta.optString("name", "evidence"))
    }

    fun metadata(reference: String): String {
        if (!reference.startsWith("vault:")) return "پیوست نسخه قبلی · برای ارسال، دسترسی فایل باید معتبر باشد"
        val id = reference.removePrefix("vault:")
        val recordName = "evidence:$id"
        val j = try { store.loadStrict(recordName)?.let(::JSONObject) }
            catch (_: Exception) { return "خطای بازیابی اطلاعات رمزنگاری‌شده مستند" }
        return j?.let { "${it.optString("name", "مستند")} · ${it.optLong("size") / 1024} کیلوبایت" }
            ?: if (store.hasEncryptedRecord(recordName)) "خطای بازیابی اطلاعات مستند" else "مستند موجود نیست"
    }

    fun imagePreview(evidence: Evidence, maxDimension: Int = 640): Bitmap? {
        if (!evidence.mime.startsWith("image/")) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(evidence.bytes, 0, evidence.bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxDimension * 2 || bounds.outHeight / sample > maxDimension * 2) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(evidence.bytes, 0, evidence.bytes.size, options)
    }

    fun imagePreview(reference: String, resolver: ContentResolver, maxDimension: Int = 640): Bitmap? =
        imagePreview(read(reference, resolver), maxDimension)

    /** Remove only crash leftovers that have no metadata record; active vault items are untouched. */
    fun cleanupOrphans(olderThanMillis: Long = 86_400_000L) {
        val cutoff = System.currentTimeMillis() - olderThanMillis
        folder.listFiles()?.forEach { file ->
            if (file.name.endsWith(".tmp") && file.lastModified() < cutoff) file.delete()
            else if (file.name.endsWith(".bin")) {
                val id = file.name.removeSuffix(".bin")
                if (idPattern.matches(id) && !store.hasEncryptedRecord("evidence:$id")) file.delete()
            }
        }
    }

    fun delete(reference: String) {
        if (!reference.startsWith("vault:")) return // Never modify a gallery or SAF file.
        val id = reference.removePrefix("vault:")
        if (!idPattern.matches(id)) return
        File(folder, "$id.bin").delete()
        store.clearDurable("evidence:$id")
    }
}
