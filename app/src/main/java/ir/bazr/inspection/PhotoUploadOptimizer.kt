package ir.bazr.inspection

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Makes a transport-only copy (<= 200 KB) of image evidence. The original image in the
 * AES-GCM vault is never modified, so a failed upload never destroys evidence.
 *
 * Search order (never fails for a decodable image):
 *  1. Lossless WebP at full size for small/flat images (scans, screenshots).
 *  2. For each resolution from large to small: binary-search the highest quality that fits.
 *     The first resolution that fits with quality >= 82 wins; otherwise the floor is relaxed.
 *  3. As a last resort the image is shrunk further until it fits.
 *
 * [Format.WEBP] is the default; [Format.JPEG] is the compatibility fallback used when a
 * server rejects WebP uploads.
 */
object PhotoUploadOptimizer {
    enum class Format { WEBP, JPEG }

    data class Prepared(
        val bytes: ByteArray,
        val mime: String,
        val name: String,
        val originalBytes: Int,
        val optimized: Boolean
    )

    fun prepare(evidence: EvidenceVault.Evidence, format: Format = Format.WEBP): Prepared {
        val original = Prepared(evidence.bytes, evidence.mime, evidence.name, evidence.bytes.size, false)
        val forceJpeg = format == Format.JPEG && evidence.mime == "image/webp"
        if (!forceJpeg && !PhotoUploadPolicy.needsOptimization(evidence.mime, evidence.bytes.size)) return original

        val info = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(evidence.bytes, 0, evidence.bytes.size, info)
        if (info.outWidth <= 0 || info.outHeight <= 0) {
            throw UserActionException("تصویر مستند قابل پردازش نیست؛ فایل دیگری انتخاب کنید")
        }
        // Bound peak memory: decode at most ~3072 pixels on the longest side.
        var sample = 1
        while (maxOf(info.outWidth, info.outHeight) / sample > 3072) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val decoded = BitmapFactory.decodeByteArray(evidence.bytes, 0, evidence.bytes.size, options)
            ?: throw UserActionException("تصویر انتخاب‌شده قابل بازکردن نیست")

        var oriented: Bitmap = decoded
        try {
            oriented = applyOrientation(decoded, evidence)
            // JPEG has no alpha: flatten transparent PNGs on white so they do not turn black.
            val flat = if (oriented.hasAlpha() && (format == Format.JPEG || evidence.mime == "image/png"))
                flatten(oriented) else oriented
            if (flat !== oriented) { if (oriented !== decoded) oriented.recycle(); oriented = flat }

            findBest(oriented, format)?.let { return result(original, it, format) }

            // Last resort: keep shrinking by 15% until it fits (practically never reached).
            var edge = (PhotoUploadPolicy.MAX_EDGES.last() * 0.85).toInt()
            while (edge >= 320) {
                val dims = PhotoUploadPolicy.dimensions(oriented.width, oriented.height, edge)
                val working = Bitmap.createScaledBitmap(oriented, dims.first, dims.second, true)
                try {
                    val q = PhotoUploadPolicy.highestFittingQuality(30, 90) { sizeOf(working, format, it) }
                    if (q != null) encode(working, format, q)?.let { return result(original, it, format) }
                } finally { if (working !== oriented) working.recycle() }
                edge = (edge * 0.85).toInt()
            }
            throw UserActionException(
                "عکس برای ارسال بیش از حد پیچیده است؛ آن را دوباره و با نور بهتر ثبت کنید."
            )
        } finally {
            if (oriented !== decoded) oriented.recycle()
            decoded.recycle()
        }
    }

    private fun findBest(oriented: Bitmap, format: Format): ByteArray? {
        val ladder = PhotoUploadPolicy.MAX_EDGES.map { edge ->
            PhotoUploadPolicy.dimensions(oriented.width, oriented.height, edge)
        }.distinct()

        // 1) Lossless attempt at full size for small, flat images.
        if (format == Format.WEBP &&
            oriented.width.toLong() * oriented.height <= PhotoUploadPolicy.LOSSLESS_MAX_PIXELS) {
            encode(oriented, format, 100, lossless = true)?.let {
                if (it.size <= PhotoUploadPolicy.TARGET_BYTES) return it
            }
        }

        // 2) Highest resolution that fits at good quality; relax the floor if needed.
        for (floor in PhotoUploadPolicy.QUALITY_FLOORS) {
            for (dims in ladder) {
                val working = if (dims.first == oriented.width && dims.second == oriented.height) oriented
                    else Bitmap.createScaledBitmap(oriented, dims.first, dims.second, true)
                try {
                    val quality = PhotoUploadPolicy.highestFittingQuality(
                        floor, PhotoUploadPolicy.QUALITY_CEILING
                    ) { sizeOf(working, format, it) }
                    if (quality != null) encode(working, format, quality)?.let { return it }
                } finally {
                    if (working !== oriented) working.recycle()
                }
            }
        }
        return null
    }

    private fun applyOrientation(decoded: Bitmap, evidence: EvidenceVault.Evidence): Bitmap {
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
        return if (transform.isIdentity) decoded
        else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, transform, true)
    }

    private fun flatten(source: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply { drawColor(Color.WHITE); drawBitmap(source, 0f, 0f, null) }
        return out
    }

    private fun result(original: Prepared, bytes: ByteArray, format: Format): Prepared {
        val stem = original.name.replace(Regex("(?i)\\.(jpg|jpeg|png|webp)$"), "")
        val (mime, ext) = if (format == Format.JPEG) "image/jpeg" to "jpg" else "image/webp" to "webp"
        return Prepared(bytes, mime, "${stem.take(75)}.$ext", original.originalBytes, true)
    }

    private fun compressFormat(format: Format, lossless: Boolean): Bitmap.CompressFormat = when {
        format == Format.JPEG -> Bitmap.CompressFormat.JPEG
        lossless -> Bitmap.CompressFormat.WEBP_LOSSLESS
        else -> Bitmap.CompressFormat.WEBP_LOSSY
    }

    private fun sizeOf(bitmap: Bitmap, format: Format, quality: Int): Int? =
        encode(bitmap, format, quality)?.size

    private fun encode(bitmap: Bitmap, format: Format, quality: Int, lossless: Boolean = false): ByteArray? {
        val stream = ByteArrayOutputStream()
        if (!bitmap.compress(compressFormat(format, lossless), quality, stream)) return null
        return stream.toByteArray()
    }
}
