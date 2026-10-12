package com.dichmanhinh.screentranslator

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var spinnerSource: Spinner
    private lateinit var spinnerTarget: Spinner
    private lateinit var btnStart: MaterialButton
    private lateinit var sources: List<Language>
    private val targets get() = Languages.targets

    /**
     * Không dùng registerForActivityResult: khi chọn "Một ứng dụng", hệ thống mở ứng dụng được chọn
     * và gửi kết quả trong lúc màn hình này đã ở nền (STOPPED). Launcher gắn với lifecycle sẽ giữ
     * kết quả cho tới khi người dùng quay lại app -> nút nổi không xuất hiện.
     */
    private lateinit var projectionLauncher: ActivityResultLauncher<Intent>

    private fun onProjectionResult(result: ActivityResult) {
        val data = result.data
        if (result.resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, R.string.capture_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, ScreenTranslateService::class.java)
            .putExtra(ScreenTranslateService.EXTRA_RESULT_CODE, result.resultCode)
            .putExtra(ScreenTranslateService.EXTRA_DATA, data)
        try {
            ContextCompat.startForegroundService(this, intent)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.fgs_start_failed, Toast.LENGTH_LONG).show()
            return
        }
        btnStart.postDelayed({ updateButton() }, 500)
        // Chia sẻ toàn màn hình: đưa app xuống nền để người dùng mở ứng dụng cần dịch.
        // Chia sẻ một ứng dụng: hệ thống đã tự mở ứng dụng được chọn, không cần làm gì.
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) moveTaskToBack(true)
    }

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            requestProjection()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        projectionLauncher = activityResultRegistry.register(
            "media_projection", ActivityResultContracts.StartActivityForResult()
        ) { onProjectionResult(it) }

        spinnerSource = findViewById(R.id.spinnerSource)
        spinnerTarget = findViewById(R.id.spinnerTarget)
        btnStart = findViewById(R.id.btnStart)

        sources = Languages.sources(getString(R.string.auto_detect))
        spinnerSource.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, sources)
        spinnerTarget.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, targets)

        selectSaved()

        val listener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = save()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        spinnerSource.onItemSelectedListener = listener
        spinnerTarget.onItemSelectedListener = listener

        findViewById<View>(R.id.btnSwap).setOnClickListener { swap() }
        findViewById<MaterialSwitch>(R.id.swFullScreen).apply {
            isChecked = Prefs.forceFullScreen(this@MainActivity)
            visibility = if (Build.VERSION.SDK_INT >= 34) View.VISIBLE else View.GONE
            setOnCheckedChangeListener { _, c -> Prefs.setForceFullScreen(this@MainActivity, c) }
        }
        findViewById<View>(R.id.btnDisplaySettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        btnStart.setOnClickListener {
            if (ScreenTranslateService.isRunning) {
                startService(
                    Intent(this, ScreenTranslateService::class.java)
                        .setAction(ScreenTranslateService.ACTION_STOP)
                )
                btnStart.postDelayed({ updateButton() }, 300)
            } else {
                startFlow()
            }
        }

        val input = findViewById<EditText>(R.id.editInput)
        val output = findViewById<TextView>(R.id.textOutput)
        findViewById<View>(R.id.btnTranslateText).setOnClickListener {
            val text = input.text.toString()
            if (text.isBlank()) return@setOnClickListener
            output.text = "…"
            lifecycleScope.launch {
                output.text = runCatching {
                    GoogleTranslator.translate(text, Prefs.source(this@MainActivity), Prefs.target(this@MainActivity))
                }.getOrElse { "Lỗi: ${it.message}" }
            }
        }
    }

    override fun onDestroy() {
        projectionLauncher.unregister()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        updateButton()
    }

    private fun selectSaved() {
        val src = Prefs.source(this)
        val tgt = Prefs.target(this)
        sources.indexOfFirst { it.code == src }.takeIf { it >= 0 }?.let { spinnerSource.setSelection(it) }
        targets.indexOfFirst { it.code == tgt }.takeIf { it >= 0 }?.let { spinnerTarget.setSelection(it) }
    }

    private fun save() {
        val src = (spinnerSource.selectedItem as? Language)?.code ?: Languages.AUTO
        val tgt = (spinnerTarget.selectedItem as? Language)?.code ?: "vi"
        Prefs.save(this, src, tgt)
    }

    private fun swap() {
        val src = (spinnerSource.selectedItem as? Language)?.code ?: return
        val tgt = (spinnerTarget.selectedItem as? Language)?.code ?: return
        if (src == Languages.AUTO) return
        sources.indexOfFirst { it.code == tgt }.takeIf { it >= 0 }?.let { spinnerSource.setSelection(it) }
        targets.indexOfFirst { it.code == src }.takeIf { it >= 0 }?.let { spinnerTarget.setSelection(it) }
        Prefs.save(this, tgt, src)
    }

    private fun updateButton() {
        btnStart.setText(if (ScreenTranslateService.isRunning) R.string.stop else R.string.start)
    }

    private fun startFlow() {
        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setMessage(R.string.overlay_permission_msg)
                .setPositiveButton(R.string.open_settings) { _, _ ->
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    )
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        requestProjection()
    }

    private fun requestProjection() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = if (Build.VERSION.SDK_INT >= 34 && Prefs.forceFullScreen(this)) {
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        try {
            projectionLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.capture_failed, Toast.LENGTH_SHORT).show()
        }
    }
}
