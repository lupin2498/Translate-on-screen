package com.dichmanhinh.screentranslator

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View

data class TranslatedBlock(val original: String, val translated: String, val rect: Rect)

/** Vẽ bản dịch đè lên đúng vị trí chữ gốc trên màn hình. */
@SuppressLint("ViewConstructor")
class TranslationOverlayView(
    context: Context,
    private val blocks: List<TranslatedBlock>,
    private val dimRect: Rect?,
    private val onClose: () -> Unit,
    private val onCopy: () -> Unit
) : View(context) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(242, 255, 255, 255) }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 26, 115, 232)
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val dimPaint = Paint().apply { color = Color.argb(70, 0, 0, 0) }
    private val regionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 26, 115, 232)
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val hintBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 32, 33, 36) }
    private val hintPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = sp(13f)
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(20, 20, 20) }

    private val minText = sp(8f)
    private val maxText = sp(22f)
    private val pad = dp(2f)
    private val loc = IntArray(2)

    private class Laid(val rect: RectF, val layout: StaticLayout)

    private var laid: List<Laid>? = null

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            onClose(); return true
        }
        override fun onLongPress(e: MotionEvent) {
            onCopy()
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = gestures.onTouchEvent(event)

    private fun layoutBlocks(): List<Laid> {
        return blocks.map { b ->
            val r = RectF(b.rect)
            val w = (r.width() - pad * 2).toInt().coerceAtLeast(dp(40f).toInt())
            if (r.width() < w + pad * 2) r.right = r.left + w + pad * 2
            val lineCount = estimateLines(b.original)
            var size = ((r.height() - pad * 2) / lineCount / 1.25f).coerceIn(minText, maxText)
            var layout = build(b.translated, w, size)
            while (layout.height > r.height() - pad * 2 && size > minText) {
                size = (size - sp(0.5f)).coerceAtLeast(minText)
                layout = build(b.translated, w, size)
            }
            if (layout.height > r.height() - pad * 2) {
                r.bottom = r.top + layout.height + pad * 2
            }
            Laid(r, layout)
        }
    }

    private fun estimateLines(s: String): Int = (s.count { it == '\n' } + 1).coerceAtLeast(1)

    private fun build(text: String, width: Int, size: Float): StaticLayout {
        textPaint.textSize = size
        return StaticLayout.Builder.obtain(text, 0, text.length, textPaint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.0f)
            .setIncludePad(false)
            .build()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        getLocationOnScreen(loc)
        canvas.save()
        canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())

        if (dimRect != null) {
            canvas.drawRect(RectF(dimRect), regionPaint)
        } else {
            canvas.drawRect(0f, 0f, (width + loc[0]).toFloat(), (height + loc[1]).toFloat(), dimPaint)
        }

        val items = laid ?: layoutBlocks().also { laid = it }
        val radius = dp(3f)
        for (it in items) {
            canvas.drawRoundRect(it.rect, radius, radius, bgPaint)
            canvas.drawRoundRect(it.rect, radius, radius, borderPaint)
            canvas.save()
            canvas.translate(it.rect.left + pad, it.rect.top + pad)
            it.layout.draw(canvas)
            canvas.restore()
        }
        canvas.restore()

        // Gợi ý thao tác ở cuối màn hình
        val hint = context.getString(R.string.tap_to_close)
        val tw = hintPaint.measureText(hint)
        val hx = (width - tw) / 2f
        val hy = height - dp(48f)
        canvas.drawRoundRect(
            hx - dp(12f), hy - sp(13f) - dp(6f), hx + tw + dp(12f), hy + dp(8f),
            dp(16f), dp(16f), hintBg
        )
        canvas.drawText(hint, hx, hy, hintPaint)
    }

    private fun dp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private fun sp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
}
