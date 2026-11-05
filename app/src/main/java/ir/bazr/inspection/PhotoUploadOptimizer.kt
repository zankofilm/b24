package ir.bazr.inspection

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Creates a transport-only WebP copy for photo evidence.
 *
 * The source bytes are never modified: EvidenceVault keeps the original encrypted.
 * Optimization tries lossless WebP first, then selects the highest WebP quality
 * that fits 200 KiB at each progressively smaller resolution. If the budget can
 * only be met by making evidence unreasonably small, it fails safely and leaves
 * the encrypted original available for the inspector.
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

        // Bound peak decode memory. A later resize loop controls the final edge.
        var sample = 1
        while (maxOf(info.outWidth, info.outHeight) / sample > 3072) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(evidence.bytes, 0, evidence.bytes.size, options)
            ?: throw IOException("تصویر انتخاب‌شده قابل بازکردن نیست")

        var oriented: Bitmap = decoded
        try {
            val orientation = try {
                if (evidence.mime.equals("image/jpeg", ignoreCase = true)) {
                    ExifInterface(ByteArrayInputStream(evidence.bytes))
                        .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                } else ExifInterface.ORIENTATION_NORMAL
            } catch (_: Exception) { ExifInterface.ORIENTATION_NORMAL }

            val transform = Matrix()
            when (orientation) {
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
            for ((edgeIndex, edge) in PhotoUploadPolicy.MAX_EDGES.withIndex()) {
                val dims = PhotoUploadPolicy.dimensions(oriented.width, oriented.height, edge)
                if (!usedSizes.add(dims)) continue
                val working = if (dims.first == oriented.width && dims.second == oriented.height) oriented
                    else Bitmap.createScaledBitmap(oriented, dims.first, dims.second, true)
                try {
                    // First candidate is lossless: no additional quantization.
                    if (edgeIndex == 0) {
                        val lossless = encode(working, Bitmap.CompressFormat.WEBP_LOSSLESS, 100)
                        if (lossless != null && lossless.size <= PhotoUploadPolicy.TARGET_BYTES) {
                            return result(original, lossless)
                        }
                    }

                    val probes = PhotoUploadPolicy.QUALITY_PROBES
                    var previousTooLarge = 101
                    for (quality in probes) {
                        val candidate = encode(working, Bitmap.CompressFormat.WEBP_LOSSY, quality)
                            ?: continue
                        if (candidate.size <= PhotoUploadPolicy.TARGET_BYTES) {
                            // Refine between the first fitting quality and the last
                            // quality that was too large, keeping the best fitting one.
                            var bestBytes = candidate
                            var low = quality + 1
                            var high = minOf(100, previousTooLarge - 1)
                            var attempts = 0
                            while (low <= high && attempts < 4) {
                                val middle = (low + high) / 2
                                val refined = encode(working, Bitmap.CompressFormat.WEBP_LOSSY, middle)
                                if (refined != null && refined.size <= PhotoUploadPolicy.TARGET_BYTES) {
                                    bestBytes = refined
                                    low = middle + 1
                                } else {
                                    high = middle - 1
                                }
                                attempts++
                            }
                            return result(original, bestBytes)
                        }
                        previousTooLarge = quality
                    }
                } finally {
                    if (working !== oriented) working.recycle()
                }
            }

            throw IOException(
                "این عکس با حفظ خوانایی به سقف ۲۰۰ کیلوبایت نمی‌رسد. " +
                    "نسخه اصلی در گوشی محفوظ است؛ لطفاً از سوژه نزدیک‌تر عکس بگیرید یا تصویر را برش دهید."
            )
        } finally {
            if (oriented !== decoded) oriented.recycle()
            decoded.recycle()
        }
    }

    private fun result(original: Prepared, bytes: ByteArray): Prepared {
        if (bytes.size > PhotoUploadPolicy.TARGET_BYTES) {
            throw IOException("حجم نسخه ارسالی از سقف ۲۰۰ کیلوبایت بیشتر است")
        }
        val stem = original.name.replace(Regex("(?i)\\.(jpg|jpeg|png|webp)$"), "")
        return Prepared(bytes, "image/webp", "${stem.take(75)}.webp", original.originalBytes, true)
    }

    private fun encode(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int): ByteArray? {
        val stream = ByteArrayOutputStream()
        if (!bitmap.compress(format, quality, stream)) return null
        return stream.toByteArray()
    }
}
