package com.sleepysoong.autobandselector

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 64, 64, 64)
        }

        val btnEnableAccessibility = Button(this).apply {
            text = "1. Enable Accessibility Service"
            setOnClickListener {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
            }
        }

        val btnStartMacro = Button(this).apply {
            text = "2. Run Band Selection Macro"
            setOnClickListener {
                Toast.makeText(context, "Starting macro...", Toast.LENGTH_SHORT).show()
                // Launch dialer with the secret code
                val intent = Intent(Intent.ACTION_DIAL).apply {
                    data = Uri.parse("tel:319712358")
                }
                startActivity(intent)
            }
        }

        layout.addView(btnEnableAccessibility)
        layout.addView(btnStartMacro)
        
        setContentView(layout)
    }
}
