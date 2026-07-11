package com.sleepysoong.autobandselector

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences("BandSelectorPrefs", Context.MODE_PRIVATE)

        findViewById<Button>(R.id.btnEnableAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        val rgCarrier = findViewById<RadioGroup>(R.id.rgCarrier)
        // Restore saved selection
        val savedCarrier = prefs.getString("carrier", "SKT")
        when (savedCarrier) {
            "SKT" -> rgCarrier.check(R.id.rbSkt)
            "KT" -> rgCarrier.check(R.id.rbKt)
            "LGU+" -> rgCarrier.check(R.id.rbUplus)
        }

        rgCarrier.setOnCheckedChangeListener { _, checkedId ->
            val carrier = when (checkedId) {
                R.id.rbSkt -> "SKT"
                R.id.rbKt -> "KT"
                R.id.rbUplus -> "LGU+"
                else -> "SKT"
            }
            prefs.edit().putString("carrier", carrier).apply()
        }

        findViewById<Button>(R.id.btnRunMacro).setOnClickListener {
            prefs.edit().putString("macro_mode", "FORCE_BANDS").apply()
            startMacro()
        }

        findViewById<Button>(R.id.btnRevertAutomatic).setOnClickListener {
            prefs.edit().putString("macro_mode", "REVERT_AUTOMATIC").apply()
            startMacro()
        }
    }

    private fun startMacro() {
        Toast.makeText(this, "Starting Macro...", Toast.LENGTH_SHORT).show()
        val intent = Intent(Intent.ACTION_DIAL).apply {
            data = Uri.parse("tel:319712358")
        }
        startActivity(intent)
    }
}
