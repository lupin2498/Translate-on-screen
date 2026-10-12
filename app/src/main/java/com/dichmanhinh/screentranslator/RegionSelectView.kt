package com.dichmanhinh.screentranslator

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.text.TextPaint
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Lớp phủ cho phép người dùng kéo để chọn vùng cần dịch (tọa độ màn hình). */
@SuppressLint("ViewConstructor")
class RegionSelectView(
    context: Context,
    private val onSelected: (Rect) -> Unit,
    private val onCancel: () -> Unit
) : View(context) {

    private val dim = Paint().apply { color = Color.argb(120, 0, 0, 0) }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(26, 115, 232)
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
    }
    private val hintBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 32, 33, 36) }
    private val hintPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics)
    }

    init {
        id = R.id.regionSelect
        contentDescription = context.getString(R.string.select_region_hint)
    }

    private var startX = 0f
    private var startY = 0f
    private var curX = 0f
    private var curY = 0f
    private var dragging = false
    private val loc = IntArray(2)

    private fun selRect() = RectF(min(startX, curX), min(startY, curY), max(startX, curX), max(startY, curY))

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = e.x; startY = e.y; curX = e.x; curY = e.y; dragging = true
            }
            MotionEvent.ACTION_MOVE -> {
                curX = e.x; curY = e.y; invalidate()
            }
            MotionEvent.ACTION_UP -> {
                dragging = false
                val r = selRect()
                if (r.width() < dp(24f) || r.height() < dp(16f)) {
                    onCancel()
                } else {
                    getLocationOnScreen(loc)
                    onSelected(
                        Rect(
                            (r.left + loc[0]).toInt(), (r.top + loc[1]).toInt(),
                            (r.right + loc[0]).toInt(), (r.bottom + loc[1]).toInt()
                        )
                    )
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false; invalidate()
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (dragging) {
            val r = selRect()
            val path = Path().apply {
                fillType = Path.FillType.EVEN_ODD
                addRect(0f, 0f, w, h, Path.Direction.CW)
                addRect(r, Path.Direction.CW)
            }
            canvas.drawPath(path, dim)
            canvas.drawRect(r, stroke)
        } else {
            canvas.drawRect(0f, 0f, w, h, dim)
        }
        val hint = context.getString(R.string.select_region_hint)
        val tw = hintPaint.measureText(hint)
        val x = (w - tw) / 2f
        val y = dp(96f)
        canvas.drawRoundRect(
            x - dp(14f), y - hintPaint.textSize - dp(8f), x + tw + dp(14f), y + dp(10f),
            dp(18f), dp(18f), hintBg
        )
        canvas.drawText(hint, x, y, hintPaint)
    }

    private fun dp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
}
