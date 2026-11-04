package ir.bazr.inspection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.io.ByteArrayOutputStream

/** Native, touch-only handwriting capture. This is not a certified cryptographic signature. */
class SignaturePad(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val trace = Path()
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(8, 31, 56)
        strokeWidth = 5f * resources.displayMetrics.density
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private var marks = 0
    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        canvas.drawPath(trace, pen)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                trace.moveTo(event.x, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.historySize) trace.lineTo(event.getHistoricalX(i), event.getHistoricalY(i))
                trace.lineTo(event.x, event.y)
                marks++
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                trace.lineTo(event.x, event.y)
                marks++
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> { parent?.requestDisallowInterceptTouchEvent(false); return true }
        }
        return false
    }
    fun hasInk(): Boolean = marks >= 3
    fun clearSignature() { trace.reset(); marks = 0; invalidate() }
    fun toPng(): ByteArray {
        require(hasInk()) { "امضای قابل تشخیص ثبت نشده است" }
        val width = 900
        val height = 420
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            val target = Canvas(bmp)
            target.drawColor(Color.WHITE)
            target.save()
            target.scale(width.toFloat() / this.width.coerceAtLeast(1), height.toFloat() / this.height.coerceAtLeast(1))
            target.drawPath(trace, pen)
            target.restore()
            ByteArrayOutputStream().use {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                it.toByteArray().also { bytes -> require(bytes.size in 1..250_000) { "اندازه تصویر امضا نامعتبر است" } }
            }
        } finally { bmp.recycle() }
    }
}
