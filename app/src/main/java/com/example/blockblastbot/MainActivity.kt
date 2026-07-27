package com.example.blockblastbot

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            BotState.projectionResultCode = result.resultCode
            BotState.projectionData = result.data
            BotState.shouldRun = true
            sendBroadcast(Intent(BotState.ACTION_START))
            statusText.text = "Bot démarré. Ouvre Block Blast maintenant !"
        } else {
            statusText.text = "Permission de capture d'écran refusée."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btnCalibrate).setOnClickListener {
            startActivity(Intent(this, CalibrationActivity::class.java))
        }

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            if (!Prefs.isCalibrated(this)) {
                statusText.text = "Calibre d'abord (étape 2) !"
                return@setOnClickListener
            }
            val mpm = getSystemService(MediaProjectionManager::class.java)
            screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
        }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            BotState.shouldRun = false
            sendBroadcast(Intent(BotState.ACTION_STOP))
            statusText.text = "Bot arrêté."
        }
    }
}
