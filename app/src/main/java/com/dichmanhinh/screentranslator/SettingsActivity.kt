package com.dichmanhinh.screentranslator

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider

/** Màn hình chỉnh kiểu hiển thị chữ sau khi dịch: nền, màu chữ, cỡ chữ, font. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var style: OverlayStyle
    private lateinit var preview: StylePreviewView
    private lateinit var swTransparent: MaterialSwitch
    private lateinit var swBorder: MaterialSwitch
    private lateinit var swAutoSize: MaterialSwitch
    private lateinit var swBold: MaterialSwitch
    private lateinit var swOutline: MaterialSwitch
    private lateinit var sliderOpacity: Slider
    private lateinit var sliderSize: Slider
    private lateinit var lblOpacity: TextView
    private lateinit var lblSize: TextView
    private lateinit var lblBgColor: TextView
    private lateinit var rowBgColor: LinearLayout
    private lateinit var rowTextColor: LinearLayout
    private lateinit var spinnerFont: Spinner
    private var binding = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            title = getString(R.string.settings_title)
            setNavigationIcon(R.drawable.ic_arrow_back)
            setNavigationOnClickListener { finish() }
        }

        style = OverlayStyle.load(this)
        preview = findViewById(R.id.preview)
        swTransparent = findViewById(R.id.swTransparent)
        swBorder = findViewById(R.id.swBorder)
        swAutoSize = findViewById(R.id.swAutoSize)
        swBold = findViewById(R.id.swBold)
        swOutline = findViewById(R.id.swOutline)
        sliderOpacity = findViewById(R.id.sliderOpacity)
        sliderSize = findViewById(R.id.sliderSize)
        lblOpacity = findViewById(R.id.lblOpacity)
        lblSize = findViewById(R.id.lblSize)
        lblBgColor = findViewById(R.id.lblBgColor)
        rowBgColor = findViewById(R.id.rowBgColor)
        rowTextColor = findViewById(R.id.rowTextColor)
        spinnerFont = findViewById(R.id.spinnerFont)

        spinnerFont.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            OverlayStyle.FONTS.map { it.label }
        )

        swTransparent.setOnCheckedChangeListener { _, c -> update(style.copy(transparentBg = c)) }
        swBorder.setOnCheckedChangeListener { _, c -> update(style.copy(border = c)) }
        swAutoSize.setOnCheckedChangeListener { _, c -> update(style.copy(autoSize = c)) }
        swBold.setOnCheckedChangeListener { _, c -> update(style.copy(bold = c)) }
        swOutline.setOnCheckedChangeListener { _, c -> update(style.copy(outline = c)) }
        sliderOpacity.addOnChangeListener { _, v, fromUser ->
            if (fromUser) update(style.copy(bgOpacity = v.toInt()))
        }
        sliderSize.addOnChangeListener { _, v, fromUser ->
            if (fromUser) update(style.copy(textSizeSp = v.toInt()))
        }
        spinnerFont.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val key = OverlayStyle.FONTS[pos].key
                if (key != style.font) update(style.copy(font = key))
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        findViewById<MaterialButton>(R.id.btnReset).setOnClickListener { update(OverlayStyle()) }

        bind()
    }

    private fun update(s: OverlayStyle) {
        if (binding || s == style) return
        style = s
        OverlayStyle.save(this, s)
        bind()
    }

    /** Đồng bộ giao diện với [style] hiện tại. */
    private fun bind() {
        binding = true
        try {
            preview.overlayStyle = style
            swTransparent.isChecked = style.transparentBg
            swBorder.isChecked = style.border
            swAutoSize.isChecked = style.autoSize
            swBold.isChecked = style.bold
            swOutline.isChecked = style.outline
            sliderOpacity.value = (style.bgOpacity / 5 * 5).toFloat().coerceIn(0f, 100f)
            sliderSize.value = style.textSizeSp.toFloat()
                .coerceIn(OverlayStyle.MIN_SIZE_SP.toFloat(), OverlayStyle.MAX_SIZE_SP.toFloat())
            lblOpacity.text = getString(R.string.set_bg_opacity, style.bgOpacity)
            lblSize.text = getString(R.string.set_text_size, style.textSizeSp)

            val bgEnabled = !style.transparentBg
            sliderOpacity.isEnabled = bgEnabled
            lblOpacity.alpha = if (bgEnabled) 1f else 0.4f
            lblBgColor.alpha = if (bgEnabled) 1f else 0.4f
            rowBgColor.alpha = if (bgEnabled) 1f else 0.4f
            sliderSize.isEnabled = !style.autoSize
            lblSize.alpha = if (style.autoSize) 0.4f else 1f

            val fontIndex = OverlayStyle.FONTS.indexOfFirst { it.key == style.font }.coerceAtLeast(0)
            if (spinnerFont.selectedItemPosition != fontIndex) spinnerFont.setSelection(fontIndex)

            fillColors(rowBgColor, style.bgColor, bgEnabled) { update(style.copy(bgColor = it)) }
            fillColors(rowTextColor, style.textColor, true) { update(style.copy(textColor = it)) }
        } finally {
            binding = false
        }
    }

    private fun fillColors(row: LinearLayout, selected: Int, enabled: Boolean, onPick: (Int) -> Unit) {
        row.removeAllViews()
        val size = dp(36f).toInt()
        val margin = dp(6f).toInt()
        for (c in OverlayStyle.COLORS) {
            val isSel = (c or 0xFF000000.toInt()) == (selected or 0xFF000000.toInt())
            val v = View(this)
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(c)
                setStroke(
                    if (isSel) dp(4f).toInt() else dp(1f).toInt(),
                    if (isSel) getColor(R.color.primary) else Color.rgb(160, 160, 160)
                )
            }
            v.contentDescription = getString(R.string.color_desc, String.format("#%06X", c and 0xFFFFFF))
            v.isEnabled = enabled
            v.setOnClickListener { onPick(c) }
            row.addView(v, LinearLayout.LayoutParams(size, size).apply {
                setMargins(margin, margin, margin, margin)
            })
        }
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
}
