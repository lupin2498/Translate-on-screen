package com.dichmanhinh.screentranslator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View

/** Xem trước kiểu chữ dịch trên nền giả lập một màn hình có chữ gốc. */
class StylePreviewView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var overlayStyle: OverlayStyle = OverlayStyle()
        set(value) {
            field = value; invalidate()
        }

    private val bg = Paint().apply { color = Color.rgb(236, 239, 241) }
    private val stripe = Paint().apply { color = Color.rgb(207, 216, 220) }
    private val original = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(55, 71, 79)
        textSize = sp(15f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bg)
        var y = dp(14f)
        while (y < h) {
            canvas.drawRect(0f, y, w, y + dp(1f), stripe); y += dp(28f)
        }
        val left = dp(16f)
        canvas.drawText("The quick brown fox jumps", left, dp(36f), original)
        canvas.drawText("over the lazy dog.", left, dp(56f), original)
        canvas.drawText("Settings  •  Privacy  •  Help", left, dp(104f), original)

        val renderer = BlockRenderer(resources, overlayStyle)
        val rects = listOf(
            RectF(left - dp(2f), dp(20f), w - dp(16f), dp(62f)),
            RectF(left - dp(2f), dp(88f), w - dp(16f), dp(110f)),
        )
        val items = renderer.layout(
            rects,
            listOf("Con cáo nâu nhanh nhẹn nhảy qua con chó lười.", "Cài đặt  •  Quyền riêng tư  •  Trợ giúp"),
            listOf(2, 1),
            h
        )
        renderer.draw(canvas, items)
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
}
