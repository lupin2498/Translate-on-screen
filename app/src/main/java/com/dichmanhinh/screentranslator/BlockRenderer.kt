package com.dichmanhinh.screentranslator

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue

/**
 * Bố trí và vẽ các khối bản dịch theo [OverlayStyle].
 * Dùng chung cho lớp phủ trên màn hình và phần xem trước trong Cài đặt.
 */
class BlockRenderer(private val res: Resources, private val cfg: OverlayStyle) {

    class Laid(val rect: RectF, val layout: StaticLayout, val paint: TextPaint)

    private val typeface = cfg.typeface()
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cfg.effectiveBgColor }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 26, 115, 232)
        this.style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val minText = sp(OverlayStyle.MIN_SIZE_SP.toFloat())
    private val maxAuto = sp(28f)
    private val pad = dp(2f)
    private val radius = dp(3f)
    private val outlineWidth = dp(2.5f)

    /**
     * @param lineCounts số dòng của chữ gốc trong mỗi khối (để ước lượng cỡ chữ khi tự động).
     * @param maxBottom giới hạn dưới (chiều cao màn hình) để khối không tràn ra ngoài.
     */
    fun layout(rects: List<RectF>, texts: List<String>, lineCounts: List<Int>, maxBottom: Float): List<Laid> {
        return rects.indices.map { i ->
            val r = RectF(rects[i])
            val minW = dp(40f)
            if (r.width() < minW + pad * 2) r.right = r.left + minW + pad * 2
            val w = (r.width() - pad * 2).toInt().coerceAtLeast(1)
            val text = texts[i]
            val paint = newPaint()
            val avail = r.height() - pad * 2
            val layout: StaticLayout
            if (cfg.autoSize) {
                val lines = lineCounts.getOrElse(i) { 1 }.coerceAtLeast(1)
                var size = (avail / lines / 1.2f).coerceIn(minText, maxAuto)
                var l = build(text, w, size, paint)
                while (l.height > avail && size > minText) {
                    size = (size - sp(0.5f)).coerceAtLeast(minText)
                    l = build(text, w, size, paint)
                }
                layout = l
            } else {
                layout = build(text, w, sp(cfg.textSizeSp.toFloat()), paint)
            }
            val needH = layout.height + pad * 2
            if (needH > r.height()) {
                r.bottom = r.top + needH
                // Nếu tràn khỏi đáy màn hình thì đẩy khối lên trên
                if (maxBottom > 0 && r.bottom > maxBottom) {
                    val shift = (r.bottom - maxBottom).coerceAtMost(r.top)
                    r.offset(0f, -shift)
                }
            }
            Laid(r, layout, paint)
        }
    }

    private fun newPaint() = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cfg.textColor
        typeface = this@BlockRenderer.typeface
    }

    private fun build(text: String, width: Int, size: Float, paint: TextPaint): StaticLayout {
        paint.textSize = size
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.0f)
            .setIncludePad(false)
            .build()
    }

    fun draw(canvas: Canvas, items: List<Laid>) {
        for (it in items) {
            if (!cfg.transparentBg && Color.alpha(bgPaint.color) > 0) {
                canvas.drawRoundRect(it.rect, radius, radius, bgPaint)
            }
            if (cfg.border) canvas.drawRoundRect(it.rect, radius, radius, borderPaint)
            canvas.save()
            canvas.translate(it.rect.left + pad, it.rect.top + pad)
            if (cfg.outline) {
                val p = it.paint
                p.style = Paint.Style.STROKE
                p.strokeWidth = outlineWidth
                p.strokeJoin = Paint.Join.ROUND
                p.color = cfg.outlineColor
                it.layout.draw(canvas)
                p.style = Paint.Style.FILL
                p.color = cfg.textColor
            }
            it.layout.draw(canvas)
            canvas.restore()
        }
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, res.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, res.displayMetrics)
}
