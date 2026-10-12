package com.dichmanhinh.screentranslator

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.TextPaint
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View

data class TranslatedBlock(val original: String, val translated: String, val rect: Rect, val lines: Int = 1)

/** Vẽ bản dịch đè lên đúng vị trí chữ gốc trên màn hình (tọa độ màn hình). */
@SuppressLint("ViewConstructor")
class TranslationOverlayView(
    context: Context,
    private val blocks: List<TranslatedBlock>,
    private val regionRect: Rect?,
    private val cfg: OverlayStyle,
    private val onClose: () -> Unit,
    private val onCopy: () -> Unit
) : View(context) {

    private val renderer = BlockRenderer(resources, cfg)
    private val dimPaint = Paint().apply { color = Color.argb(if (cfg.transparentBg) 110 else 70, 0, 0, 0) }
    private val regionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 26, 115, 232)
        this.style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val hintBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 32, 33, 36) }
    private val hintPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = sp(13f)
    }
    private val loc = IntArray(2)

    init {
        id = R.id.translationOverlay
        // Chữ được vẽ trên Canvas: mô tả cho trình đọc màn hình / kiểm thử tự động
        contentDescription = blocks.joinToString("\n") { it.translated }
    }

    private var laid: List<BlockRenderer.Laid>? = null
    private var laidForHeight = -1

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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        laid = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        getLocationOnScreen(loc)
        val screenBottom = (height + loc[1]).toFloat()
        val items = laid?.takeIf { laidForHeight == height } ?: renderer.layout(
            blocks.map { RectF(it.rect) },
            blocks.map { it.translated },
            blocks.map { it.lines },
            screenBottom
        ).also { laid = it; laidForHeight = height }

        canvas.save()
        canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
        if (regionRect != null) {
            canvas.drawRect(RectF(regionRect), regionPaint)
        } else {
            canvas.drawRect(
                loc[0].toFloat(), loc[1].toFloat(),
                (width + loc[0]).toFloat(), (height + loc[1]).toFloat(), dimPaint
            )
        }
        renderer.draw(canvas, items)
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
