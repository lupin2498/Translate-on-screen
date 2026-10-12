package com.dichmanhinh.screentranslator

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Hiển thị bản dịch dạng danh sách – dùng khi không biết chính xác vị trí của ứng dụng được chia
 * sẻ trên màn hình (chia đôi màn hình, cửa sổ nổi...).
 */
@SuppressLint("ViewConstructor")
class TranslationListView(
    context: Context,
    items: List<TranslatedBlock>,
    cfg: OverlayStyle,
    onClose: () -> Unit,
    onCopy: () -> Unit,
) : FrameLayout(context) {

    init {
        setBackgroundColor(Color.argb(140, 0, 0, 0))
        setOnClickListener { onClose() }
        setOnLongClickListener { onCopy(); true }

        val bg = if (cfg.transparentBg || Color.alpha(cfg.effectiveBgColor) < 40) {
            Color.argb(235, 32, 33, 36)
        } else cfg.effectiveBgColor
        val textColor = if (cfg.transparentBg) Color.WHITE else cfg.textColor

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(16f)
            setPadding(p, p, p, p)
            background = GradientDrawable().apply {
                setColor(bg)
                cornerRadius = dp(12f).toFloat()
            }
        }
        val sizeSp = if (cfg.autoSize) 16f else cfg.textSizeSp.toFloat()
        for ((i, it) in items.withIndex()) {
            val tv = TextView(context).apply {
                text = it.translated
                setTextColor(textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
                typeface = cfg.typeface()
                if (cfg.outline) setShadowLayer(dp(2f).toFloat(), 0f, 0f, cfg.outlineColor)
            }
            card.addView(tv, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { if (i > 0) topMargin = dp(10f) })
        }
        // Chạm vào thẻ cũng đóng, giữ để sao chép
        card.setOnClickListener { onClose() }
        card.setOnLongClickListener { onCopy(); true }

        val scroll = ScrollView(context).apply {
            isFillViewport = false
            addView(card)
        }
        val m = dp(24f)
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            setMargins(m, m * 3, m, m * 3)
        })

        val hint = TextView(context).apply {
            setText(R.string.tap_to_close)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        addView(hint, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(40f) })
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private fun dp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()
}
