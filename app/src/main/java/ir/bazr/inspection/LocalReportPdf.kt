package ir.bazr.inspection

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import org.json.JSONObject
import java.io.OutputStream

/** Native, non-certified A4 PDF export from the encrypted on-device receipt copy.
 *  No WebView, HTML conversion, network upload or storage permissions are used.
 */
class LocalReportPdf {
    fun write(output: OutputStream, receipt: JSONObject) {
        val pdf = PdfDocument()
        try {
            val composer = Composer(pdf)
            composer.paragraph("گزارش محلی بازرس", 20f, Color.rgb(8, 31, 56), true, 13)
            composer.paragraph("نسخه غیررسمی ـ فاقد امضای دیجیتال و تأیید مدیریت", 12f, Color.rgb(153, 65, 44), true, 16)
            composer.paragraph("نام اداره: ${receipt.optString("organization", "نامشخص")}", 13f, NAVY, true)
            composer.paragraph("شماره رسید: ${receipt.optString("receipt_code", "ثبت نشده")}", 12f, INK)
            composer.paragraph("تاریخ ثبت: ${receipt.optString("submitted_at", "نامشخص")}", 12f, INK)
            composer.rule()
            composer.paragraph("پاسخ‌های بازرسی", 16f, NAVY, true, 12)
            val answers = receipt.optJSONObject("answers") ?: JSONObject()
            val notes = receipt.optJSONObject("notes") ?: JSONObject()
            val titles = receipt.optJSONObject("question_titles") ?: JSONObject()
            val keys = answers.keys().asSequence().toList().sorted()
            if (keys.isEmpty()) composer.paragraph("اطلاعات پاسخ‌های این گزارش روی دستگاه موجود نیست.", 12f, INK)
            for ((index, key) in keys.withIndex()) {
                val title = titles.optString(key, "سؤال $key")
                composer.paragraph("${index + 1}. $title | امتیاز: ${answers.optString(key)}", 12f, NAVY, true, 5)
                val note = notes.optString(key)
                if (note.isNotBlank()) composer.paragraph("توضیحات: $note", 11f, INK, false, 12)
                else composer.space(8)
            }
            composer.rule()
            composer.paragraph("تعداد مستندات ثبت‌شده: ${receipt.optInt("attachment_count")}", 12f, INK)
            composer.paragraph("وضعیت تأیید نهایی باید از سرور سامانه بررسی شود؛ این فایل جایگزین سند رسمی نیست.", 11f,
                Color.rgb(153, 65, 44), true, 12)
            composer.finish()
            pdf.writeTo(output)
        } finally {
            pdf.close()
        }
    }

    private class Composer(private val document: PdfDocument) {
        private val pageWidth = 595
        private val pageHeight = 842
        private val margin = 44
        private val bottom = 775
        private var pageNumber = 0
        private var page: PdfDocument.Page? = null
        private var y = 102

        init { startPage() }

        private fun startPage() {
            pageNumber += 1
            page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
            y = 102
            val canvas = page!!.canvas
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = NAVY }
            canvas.drawRect(0f, 0f, pageWidth.toFloat(), 72f, p)
            canvas.drawRect(0f, 72f, pageWidth.toFloat(), 76f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(197, 161, 90) })
            val title = layout("سامانه بازرسی ادارات | نسخه ویژه بازرس", 15f, Color.WHITE, true)
            canvas.save()
            canvas.translate(margin.toFloat(), 23f)
            title.draw(canvas)
            canvas.restore()
        }

        private fun finishPage() {
            val current = page ?: return
            val canvas = current.canvas
            canvas.drawLine(margin.toFloat(), 801f, (pageWidth - margin).toFloat(), 801f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(220, 227, 234); strokeWidth = 1f })
            val footer = layout("صفحه $pageNumber | نسخه محلی غیررسمی", 9f, Color.DKGRAY)
            canvas.save()
            canvas.translate(margin.toFloat(), 809f)
            footer.draw(canvas)
            canvas.restore()
            document.finishPage(current)
            page = null
        }

        private fun layout(value: String, size: Float, color: Int, bold: Boolean = false): StaticLayout {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size
                this.color = color
                typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
            }
            val clean = value.replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), " ").trim()
            return StaticLayout.Builder.obtain(clean, 0, clean.length, paint, pageWidth - 2 * margin)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setTextDirection(TextDirectionHeuristics.RTL)
                .setIncludePad(false)
                .build()
        }

        /** Paginate at line boundaries. Long Persian notes are never truncated. */
        fun paragraph(value: String, size: Float, color: Int, bold: Boolean = false, gap: Int = 9) {
            val paragraph = layout(value, size, color, bold)
            var startLine = 0
            while (startLine < paragraph.lineCount) {
                val top = paragraph.getLineTop(startLine)
                val available = bottom - y
                var endLine = startLine
                while (endLine < paragraph.lineCount && paragraph.getLineBottom(endLine) - top <= available) {
                    endLine += 1
                }
                if (endLine == startLine) {
                    finishPage()
                    startPage()
                    continue
                }
                val drawnHeight = paragraph.getLineBottom(endLine - 1) - top
                val canvas = page!!.canvas
                canvas.save()
                canvas.clipRect(margin, y, pageWidth - margin, y + drawnHeight)
                canvas.translate(margin.toFloat(), (y - top).toFloat())
                paragraph.draw(canvas)
                canvas.restore()
                y += drawnHeight
                startLine = endLine
                if (startLine < paragraph.lineCount) {
                    finishPage()
                    startPage()
                }
            }
            space(gap)
        }

        fun space(points: Int) {
            if (y + points >= bottom) { finishPage(); startPage() }
            else y += points
        }

        fun rule() {
            if (y + 17 > bottom) { finishPage(); startPage() }
            page!!.canvas.drawLine(margin.toFloat(), y.toFloat(), (pageWidth - margin).toFloat(), y.toFloat(),
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(220, 227, 234); strokeWidth = 1f })
            y += 17
        }
        fun finish() = finishPage()
    }

    companion object {
        private val NAVY = Color.rgb(8, 31, 56)
        private val INK = Color.rgb(31, 50, 71)
    }
}
