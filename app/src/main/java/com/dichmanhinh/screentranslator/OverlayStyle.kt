package com.dichmanhinh.screentranslator

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface

/** Kiểu hiển thị của chữ sau khi dịch (người dùng chỉnh trong màn hình Cài đặt). */
data class OverlayStyle(
    val transparentBg: Boolean = false,
    val bgColor: Int = Color.WHITE,
    val bgOpacity: Int = 95,          // 0..100 (%)
    val textColor: Int = Color.rgb(20, 20, 20),
    val autoSize: Boolean = true,
    val textSizeSp: Int = 16,         // dùng khi autoSize = false
    val font: String = FONT_DEFAULT,
    val bold: Boolean = false,
    val outline: Boolean = false,
    val border: Boolean = true,
) {
    /** Màu nền thực tế (đã áp độ mờ), trong suốt nếu bật "Nền trong suốt". */
    val effectiveBgColor: Int
        get() = if (transparentBg) Color.TRANSPARENT
        else Color.argb((bgOpacity.coerceIn(0, 100) * 255) / 100, Color.red(bgColor), Color.green(bgColor), Color.blue(bgColor))

    /** Màu viền chữ tương phản với màu chữ. */
    val outlineColor: Int
        get() = if (luminance(textColor) > 0.5f) Color.argb(230, 0, 0, 0) else Color.argb(230, 255, 255, 255)

    fun typeface(): Typeface {
        val base = FONTS.firstOrNull { it.key == font }?.family ?: "sans-serif"
        return Typeface.create(base, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    data class FontOption(val key: String, val family: String, val label: String)

    companion object {
        const val FONT_DEFAULT = "default"

        val FONTS = listOf(
            FontOption(FONT_DEFAULT, "sans-serif", "Mặc định (Sans)"),
            FontOption("serif", "serif", "Serif (có chân)"),
            FontOption("mono", "monospace", "Monospace (đơn cách)"),
            FontOption("condensed", "sans-serif-condensed", "Sans hẹp (Condensed)"),
            FontOption("light", "sans-serif-light", "Sans mảnh (Light)"),
            FontOption("thin", "sans-serif-thin", "Sans siêu mảnh (Thin)"),
            FontOption("medium", "sans-serif-medium", "Sans vừa (Medium)"),
            FontOption("black", "sans-serif-black", "Sans đậm (Black)"),
            FontOption("smallcaps", "sans-serif-smallcaps", "Chữ hoa nhỏ (Small caps)"),
            FontOption("serif-mono", "serif-monospace", "Serif đơn cách"),
            FontOption("casual", "casual", "Thân thiện (Casual)"),
            FontOption("cursive", "cursive", "Viết tay (Cursive)"),
        )

        /** Bảng màu cho nền và chữ. */
        val COLORS = intArrayOf(
            Color.WHITE, Color.BLACK, Color.rgb(32, 33, 36), Color.rgb(255, 241, 118),
            Color.rgb(255, 235, 59), Color.rgb(255, 152, 0), Color.rgb(244, 67, 54),
            Color.rgb(233, 30, 99), Color.rgb(156, 39, 176), Color.rgb(26, 115, 232),
            Color.rgb(3, 169, 244), Color.rgb(0, 150, 136), Color.rgb(76, 175, 80),
            Color.rgb(121, 85, 72), Color.rgb(158, 158, 158), Color.rgb(20, 20, 20),
        )

        const val MIN_SIZE_SP = 8
        const val MAX_SIZE_SP = 40

        private const val FILE = "overlay_style"

        fun load(ctx: Context): OverlayStyle {
            val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            val d = OverlayStyle()
            return OverlayStyle(
                transparentBg = p.getBoolean("transparentBg", d.transparentBg),
                bgColor = p.getInt("bgColor", d.bgColor),
                bgOpacity = p.getInt("bgOpacity", d.bgOpacity).coerceIn(0, 100),
                textColor = p.getInt("textColor", d.textColor),
                autoSize = p.getBoolean("autoSize", d.autoSize),
                textSizeSp = p.getInt("textSizeSp", d.textSizeSp).coerceIn(MIN_SIZE_SP, MAX_SIZE_SP),
                font = p.getString("font", d.font) ?: d.font,
                bold = p.getBoolean("bold", d.bold),
                outline = p.getBoolean("outline", d.outline),
                border = p.getBoolean("border", d.border),
            )
        }

        fun save(ctx: Context, s: OverlayStyle) {
            ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putBoolean("transparentBg", s.transparentBg)
                .putInt("bgColor", s.bgColor)
                .putInt("bgOpacity", s.bgOpacity)
                .putInt("textColor", s.textColor)
                .putBoolean("autoSize", s.autoSize)
                .putInt("textSizeSp", s.textSizeSp)
                .putString("font", s.font)
                .putBoolean("bold", s.bold)
                .putBoolean("outline", s.outline)
                .putBoolean("border", s.border)
                .apply()
        }

        fun luminance(c: Int): Float =
            (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f
    }
}
