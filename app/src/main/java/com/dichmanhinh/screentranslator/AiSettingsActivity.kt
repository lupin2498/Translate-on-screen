package com.dichmanhinh.screentranslator

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.Formatter
import android.util.TypedValue
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.dichmanhinh.screentranslator.ai.AiTranslator
import com.dichmanhinh.screentranslator.ai.LocalAi
import com.dichmanhinh.screentranslator.ai.ModelCatalog
import com.dichmanhinh.screentranslator.ai.ModelManager
import com.dichmanhinh.screentranslator.ai.ModelSpec
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Cài đặt AI dịch trên máy: chọn bộ dịch, tải/xóa mô hình, tùy chọn đọc ảnh, thử dịch. */
class AiSettingsActivity : AppCompatActivity() {

    private lateinit var listModels: LinearLayout
    private lateinit var groupEngine: MaterialButtonToggleGroup
    private val handler = Handler(Looper.getMainLooper())
    private val cards = HashMap<String, CardViews>()
    private var testJob: Job? = null

    /** Mô hình vừa bấm tải: tải xong thì tự chọn dùng. */
    private var pendingSelect: String? = null

    private class CardViews(
        val radio: RadioButton,
        val status: TextView,
        val progress: LinearProgressIndicator,
        val action: MaterialButton,
        val delete: MaterialButton,
    )

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importModel(uri)
    }

    private val poll = object : Runnable {
        override fun run() {
            refreshStatuses()
            handler.postDelayed(this, 800)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai)
        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            title = getString(R.string.ai_title)
            setNavigationIcon(R.drawable.ic_arrow_back)
            setNavigationOnClickListener { finish() }
        }
        listModels = findViewById(R.id.listModels)
        groupEngine = findViewById(R.id.groupEngine)

        groupEngine.check(if (Prefs.useAi(this)) R.id.btnEngineAi else R.id.btnEngineGoogle)
        groupEngine.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val ai = id == R.id.btnEngineAi
            Prefs.setEngine(this, if (ai) Prefs.ENGINE_AI else Prefs.ENGINE_GOOGLE)
            val st = ModelManager.status(this, LocalAi.selected(this)).state
            if (ai && st != ModelManager.State.READY && st != ModelManager.State.DOWNLOADING) {
                Toast.makeText(this, R.string.ai_download_first, Toast.LENGTH_LONG).show()
            }
        }

        showDeviceInfo()
        buildModelList()

        findViewById<MaterialButton>(R.id.btnImport).setOnClickListener {
            importLauncher.launch(arrayOf("application/octet-stream", "*/*"))
        }

        findViewById<MaterialSwitch>(R.id.swVision).apply {
            isChecked = Prefs.aiVision(this@AiSettingsActivity)
            setOnCheckedChangeListener { _, c -> Prefs.setAiVision(this@AiSettingsActivity, c) }
        }
        findViewById<MaterialSwitch>(R.id.swVisionFull).apply {
            isChecked = Prefs.aiVisionFull(this@AiSettingsActivity)
            setOnCheckedChangeListener { _, c -> Prefs.setAiVisionFull(this@AiSettingsActivity, c) }
        }
        findViewById<MaterialSwitch>(R.id.swFallback).apply {
            isChecked = Prefs.aiFallback(this@AiSettingsActivity)
            setOnCheckedChangeListener { _, c -> Prefs.setAiFallback(this@AiSettingsActivity, c) }
        }
        findViewById<MaterialButton>(R.id.btnTest).setOnClickListener { runTest() }
    }

    override fun onResume() {
        super.onResume()
        handler.post(poll)
    }

    override fun onPause() {
        handler.removeCallbacks(poll)
        super.onPause()
    }

    override fun onDestroy() {
        testJob?.cancel()
        // Không giữ mô hình trong bộ nhớ khi chỉ thử ở màn hình này
        if (!ScreenTranslateService.isRunning) LocalAi.release()
        super.onDestroy()
    }

    private fun showDeviceInfo() {
        val ram = LocalAi.totalRamGb(this)
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "?"
        val text = if (!LocalAi.is64Bit()) getString(R.string.ai_device_32bit)
        else getString(R.string.ai_device_info, ram, abi, LocalAi.threads(),
            Formatter.formatShortFileSize(this, ModelManager.freeSpace(this)))
        findViewById<TextView>(R.id.txtDevice).text = text
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun buildModelList() {
        listModels.removeAllViews()
        cards.clear()
        val selected = Prefs.aiModel(this)
        // Máy đủ RAM: Gemma 4 (dịch hay hơn, đọc được ảnh); máy yếu hơn: HY-MT (nhẹ, nhanh)
        val ram = LocalAi.totalRamGb(this)
        val recommended = if (ram >= 7f) ModelCatalog.GEMMA4_E2B.id else ModelCatalog.HY_MT.id
        for (spec in ModelManager.allModels(this)) {
            val card = MaterialCardView(this).apply {
                useCompatPadding = true
                radius = dp(12f).toFloat()
            }
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            }
            val radio = RadioButton(this).apply {
                text = spec.name
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                isChecked = spec.id == selected
                setOnClickListener { select(spec) }
            }
            val desc = TextView(this).apply {
                text = if (spec.id == recommended && LocalAi.is64Bit()) {
                    getString(R.string.ai_recommended) + "\n" + spec.description
                } else spec.description
                textSize = 13f
            }
            val status = TextView(this).apply { textSize = 13f; setPadding(0, dp(4f), 0, 0) }
            val progress = LinearProgressIndicator(this).apply {
                max = 100
                visibility = View.GONE
            }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val action = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
            val delete = MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                text = getString(R.string.ai_delete)
                setOnClickListener { confirmDelete(spec) }
            }
            row.addView(action)
            row.addView(delete)
            box.addView(radio)
            box.addView(desc)
            box.addView(status)
            box.addView(progress, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4f) })
            box.addView(row)
            card.addView(box)
            listModels.addView(card)
            cards[spec.id] = CardViews(radio, status, progress, action, delete)
        }
        refreshStatuses()
    }

    private fun select(spec: ModelSpec) {
        Prefs.setAiModel(this, spec.id)
        for ((id, v) in cards) v.radio.isChecked = id == spec.id
        if (ModelManager.isReady(this, spec)) {
            Prefs.setEngine(this, Prefs.ENGINE_AI)
            groupEngine.check(R.id.btnEngineAi)
        }
    }

    private fun refreshStatuses() {
        for (spec in ModelManager.allModels(this)) {
            val v = cards[spec.id] ?: continue
            val st = ModelManager.status(this, spec)
            val size = Formatter.formatShortFileSize(this, spec.totalSize)
            when (st.state) {
                ModelManager.State.READY -> {
                    v.status.text = getString(R.string.ai_status_ready, size) +
                        if (LocalAi.isLoaded(spec)) " · " + getString(R.string.ai_status_loaded) else ""
                    v.progress.visibility = View.GONE
                    v.action.visibility = View.GONE
                    v.delete.visibility = View.VISIBLE
                    if (pendingSelect == spec.id) {
                        pendingSelect = null
                        select(spec)
                        Toast.makeText(this, getString(R.string.ai_download_done, spec.name), Toast.LENGTH_LONG).show()
                    }
                }
                ModelManager.State.DOWNLOADING -> {
                    v.status.text = getString(R.string.ai_status_downloading, st.percent,
                        Formatter.formatShortFileSize(this, st.downloaded), size)
                    v.progress.visibility = View.VISIBLE
                    v.progress.setProgressCompat(st.percent, true)
                    v.action.visibility = View.VISIBLE
                    v.action.text = getString(R.string.ai_cancel_download)
                    v.action.setOnClickListener { ModelManager.cancelDownload(this, spec); refreshStatuses() }
                    v.delete.visibility = View.GONE
                }
                ModelManager.State.FAILED, ModelManager.State.NOT_DOWNLOADED -> {
                    v.status.text = if (st.state == ModelManager.State.FAILED) {
                        getString(R.string.ai_status_failed, st.error ?: "")
                    } else getString(R.string.ai_status_size, size)
                    v.progress.visibility = View.GONE
                    v.action.visibility = if (spec.custom) View.GONE else View.VISIBLE
                    v.action.text = getString(R.string.ai_download)
                    v.action.setOnClickListener { confirmDownload(spec) }
                    v.delete.visibility = if (st.downloaded > 0 || spec.custom) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun confirmDownload(spec: ModelSpec) {
        if (!LocalAi.is64Bit()) {
            Toast.makeText(this, R.string.ai_device_32bit, Toast.LENGTH_LONG).show(); return
        }
        val need = ModelManager.missingBytes(this, spec)
        val free = ModelManager.freeSpace(this)
        if (free < need + 200_000_000L) {
            AlertDialog.Builder(this)
                .setMessage(getString(R.string.ai_no_space, Formatter.formatShortFileSize(this, need),
                    Formatter.formatShortFileSize(this, free)))
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        val ram = LocalAi.totalRamGb(this)
        val msg = StringBuilder(getString(R.string.ai_download_confirm, spec.name, Formatter.formatShortFileSize(this, need)))
        if (ram > 0 && ram + 0.5f < spec.minRamGb) msg.append("\n\n").append(getString(R.string.ai_low_ram, ram, spec.minRamGb))
        AlertDialog.Builder(this)
            .setTitle(R.string.ai_download)
            .setMessage(msg)
            .setPositiveButton(R.string.ai_download) { _, _ ->
                try {
                    ModelManager.download(this, spec)
                    pendingSelect = spec.id
                    Prefs.setAiModel(this, spec.id)
                    for ((id, v) in cards) v.radio.isChecked = id == spec.id
                    // Bật luôn AI: tải xong (kể cả khi đã thoát màn hình này) là dùng ngay,
                    // trong lúc chờ thì tạm dùng Google Dịch
                    Prefs.setEngine(this, Prefs.ENGINE_AI)
                    groupEngine.check(R.id.btnEngineAi)
                } catch (e: Exception) {
                    Toast.makeText(this, "Lỗi: ${e.message}", Toast.LENGTH_LONG).show()
                }
                refreshStatuses()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(spec: ModelSpec) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.ai_delete_confirm, spec.name))
            .setPositiveButton(R.string.ai_delete) { _, _ ->
                if (LocalAi.isLoaded(spec)) LocalAi.release()
                ModelManager.delete(this, spec)
                if (spec.custom) buildModelList() else refreshStatuses()
                showDeviceInfo()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun importModel(uri: Uri) {
        val dlg = AlertDialog.Builder(this)
            .setTitle(R.string.ai_import)
            .setMessage(R.string.ai_importing)
            .setCancelable(false)
            .show()
        lifecycleScope.launch {
            try {
                val spec = ModelManager.importGguf(this@AiSettingsActivity, uri) { copied, total ->
                    runOnUiThread {
                        dlg.setMessage(getString(R.string.ai_importing) + "\n" +
                            Formatter.formatShortFileSize(this@AiSettingsActivity, copied) +
                            if (total > 0) " / " + Formatter.formatShortFileSize(this@AiSettingsActivity, total) else "")
                    }
                }
                buildModelList()
                select(spec)
                showDeviceInfo()
                Toast.makeText(this@AiSettingsActivity, getString(R.string.ai_download_done, spec.name), Toast.LENGTH_LONG).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(this@AiSettingsActivity, "Lỗi: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                dlg.dismiss()
            }
        }
    }

    private fun runTest() {
        val text = findViewById<EditText>(R.id.editTest).text.toString().trim()
        val out = findViewById<TextView>(R.id.txtTestOut)
        val stats = findViewById<TextView>(R.id.txtTestStats)
        if (text.isEmpty()) return
        if (testJob?.isActive == true) {
            testJob?.cancel(); LocalAi.cancel(); return
        }
        if (!LocalAi.isReady(this)) {
            Toast.makeText(this, R.string.ai_download_first, Toast.LENGTH_LONG).show(); return
        }
        val btn = findViewById<MaterialButton>(R.id.btnTest)
        btn.setText(R.string.cancel)
        out.text = "…"
        stats.text = getString(R.string.ai_loading, LocalAi.selected(this).name)
        testJob = lifecycleScope.launch {
            try {
                val t0 = SystemClock.uptimeMillis()
                val ai = LocalAi.obtain(this@AiSettingsActivity)
                val t1 = SystemClock.uptimeMillis()
                stats.text = getString(R.string.ai_translating)
                val src = Prefs.source(this@AiSettingsActivity)
                val tgt = Prefs.target(this@AiSettingsActivity)
                val segs = text.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }
                val res = AiTranslator(ai).translate(segs, segs.map { src }, tgt)
                val t2 = SystemClock.uptimeMillis()
                out.text = res.joinToString("\n\n") { it ?: "—" }
                stats.text = getString(R.string.ai_test_stats, t1 - t0, t2 - t1, ai.lastStats?.toString() ?: "")
            } catch (e: CancellationException) {
                out.text = getString(R.string.ai_cancelled)
                throw e
            } catch (e: Exception) {
                out.text = "Lỗi: ${e.message}"
                stats.text = ""
            } finally {
                btn.setText(R.string.ai_test_run)
                refreshStatuses()
            }
        }
    }
}
