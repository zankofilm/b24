package ir.bazr.inspection

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Makes a transport-only WebP copy for image evidence. The original image in the
 * AES-GCM vault remains untouched so a failed upload never destroys evidence.
 *
 * Lossless WebP is attempted first. Next, high-quality lossy WebP candidates
 * use progressively smaller *bounded* dimensions and quality >= 82.
 * If 200 KB cannot be met with these safeguards, the caller must show an
 * actionable warning rather than silently degrading fine document text.
 */
object PhotoUploadOptimizer {
    data class Prepared(
        val bytes: ByteArray,
        val mime: String,
        val name: String,
        val originalBytes: Int,
        val optimized: Boolean
    )

    fun prepare(evidence: EvidenceVault.Evidence): Prepared {
        val original = Prepared(evidence.bytes, evidence.mime, evidence.name, evidence.bytes.size, false)
        if (!PhotoUploadPolicy.needsOptimization(evidence.mime, evidence.bytes.size)) return original

        val info = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(evidence.bytes, 0, evidence.bytes.size, info)
        if (info.outWidth <= 0 || info.outHeight <= 0) {
            throw IOException("تصویر مستند قابل پردازش نیست؛ فایل دیگری انتخاب کنید")
        }
        // Bound peak memory: decode at most ~3072 pixels on the longest side.
        var sample = 1
        while (maxOf(info.outWidth, info.outHeight) / sample > 3072) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val decoded = BitmapFactory.decodeByteArray(evidence.bytes, 0, evidence.bytes.size, options)
            ?: throw IOException("تصویر انتخاب‌شده قابل بازکردن نیست")

        var oriented: Bitmap = decoded
        try {
            val angle = try {
                if (evidence.mime == "image/jpeg") ExifInterface(ByteArrayInputStream(evidence.bytes))
                    .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                else ExifInterface.ORIENTATION_NORMAL
            } catch (_: Exception) { ExifInterface.ORIENTATION_NORMAL }
            val transform = Matrix()
            when (angle) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> transform.setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> transform.setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> transform.setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { transform.setRotate(90f); transform.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> transform.setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { transform.setRotate(270f); transform.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> transform.setRotate(270f)
            }
            if (!transform.isIdentity()) {
                oriented = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, transform, true)
            }

            val usedSizes = mutableSetOf<Pair<Int, Int>>()
            for (edge in PhotoUploadPolicy.MAX_EDGES) {
                val dims = PhotoUploadPolicy.dimensions(oriented.width, oriented.height, edge)
                if (!usedSizes.add(dims)) continue
                val working = if (dims.first == oriented.width && dims.second == oriented.height) oriented
                    else Bitmap.createScaledBitmap(oriented, dims.first, dims.second, true)
                try {
                    // Lossless candidate preserves colors/detail (resizing only when necessary).
                    if (dims == PhotoUploadPolicy.dimensions(oriented.width, oriented.height, PhotoUploadPolicy.MAX_EDGES.first())) {
                        encode(working, Bitmap.CompressFormat.WEBP_LOSSLESS, 100)?.let { bytes ->
                            if (bytes.size <= PhotoUploadPolicy.TARGET_BYTES) return result(original, bytes)
                        }
                    }
                    for (quality in PhotoUploadPolicy.WEBP_QUALITIES) {
                        val bytes = encode(working, Bitmap.CompressFormat.WEBP_LOSSY, quality) ?: continue
                        if (bytes.size <= PhotoUploadPolicy.TARGET_BYTES) return result(original, bytes)
                    }
                } finally {
                    if (working !== oriented) working.recycle()
                }
            }
            throw IOException(
                "این عکس با حفظ وضوح قابل‌قبول به ۲۰۰ کیلوبایت نمی‌رسد. " +
                    "برای جلوگیری از ناخوانا شدن مستند، لطفاً فقط قسمت موردنیاز را برش دهید یا عکس واضح‌تری ثبت کنید."
            )
        } finally {
            if (oriented !== decoded) oriented.recycle()
            decoded.recycle()
        }
    }

    private fun result(original: Prepared, bytes: ByteArray): Prepared {
        val stem = original.name.replace(Regex("(?i)\\.(jpg|jpeg|png|webp)$"), "")
        return Prepared(bytes, "image/webp", "${stem.take(75)}.webp", original.originalBytes, true)
    }

    private fun encode(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int): ByteArray? {
        val stream = ByteArrayOutputStream()
        if (!bitmap.compress(format, quality, stream)) return null
        return stream.toByteArray()
    }
}
