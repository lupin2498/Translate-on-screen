package com.dichmanhinh.screentranslator

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var spinnerSource: Spinner
    private lateinit var spinnerTarget: Spinner
    private lateinit var btnStart: MaterialButton
    private lateinit var sources: List<Language>
    private val targets get() = Languages.targets

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                val intent = Intent(this, ScreenTranslateService::class.java)
                    .putExtra(ScreenTranslateService.EXTRA_RESULT_CODE, result.resultCode)
                    .putExtra(ScreenTranslateService.EXTRA_DATA, data)
                ContextCompat.startForegroundService(this, intent)
                btnStart.postDelayed({ updateButton() }, 500)
                moveTaskToBack(true)
            } else {
                Toast.makeText(this, R.string.capture_failed, Toast.LENGTH_SHORT).show()
            }
        }

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            requestProjection()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

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
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }
}
