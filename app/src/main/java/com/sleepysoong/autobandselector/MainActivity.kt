package com.sleepysoong.autobandselector

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var cardScanProgress: LinearLayout
    private lateinit var cardAccessibility: LinearLayout
    private lateinit var cardCarrier: LinearLayout
    private lateinit var cardExecute: LinearLayout

    private lateinit var tvScanStatus: TextView
    private lateinit var tvScanCountdown: TextView
    private lateinit var tvScanResults: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize Views
        cardScanProgress = findViewById(R.id.cardScanProgress)
        cardAccessibility = findViewById(R.id.cardAccessibility)
        cardCarrier = findViewById(R.id.cardCarrier)
        cardExecute = findViewById(R.id.cardExecute)

        tvScanStatus = findViewById(R.id.tvScanStatus)
        tvScanCountdown = findViewById(R.id.tvScanCountdown)
        tvScanResults = findViewById(R.id.tvScanResults)

        val prefs = getSharedPreferences("BandSelectorPrefs", Context.MODE_PRIVATE)

        findViewById<Button>(R.id.btnEnableAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        val rgCarrier = findViewById<RadioGroup>(R.id.rgCarrier)
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
            startScanCountdown()
        }

        findViewById<Button>(R.id.btnRevertAutomatic).setOnClickListener {
            prefs.edit().putString("macro_mode", "REVERT_AUTOMATIC").apply()
            prefs.edit().putString("target_band_to_set", "Automatic").apply()
            startDialer()
        }
    }

    override fun onResume() {
        super.onResume()
        checkScanState()
    }

    private fun startScanCountdown() {
        // Hide config cards, show progress
        setUiScanning(true)
        tvScanStatus.text = "Initializing Scan"
        
        lifecycleScope.launch {
            for (i in 3 downTo 1) {
                tvScanCountdown.text = "Starting scan in $i seconds..."
                delay(1000)
            }
            
            // Setup scan state in SharedPreferences
            val prefs = getSharedPreferences("BandSelectorPrefs", Context.MODE_PRIVATE)
            val carrier = prefs.getString("carrier", "SKT") ?: "SKT"
            val bands = getBandsForCarrier(carrier)
            
            prefs.edit().apply {
                putString("macro_mode", "SCANNING")
                putInt("scan_step", 0)
                putString("scan_bands", bands.joinToString(","))
                putString("scan_speeds", "")
                putString("target_band_to_set", bands[0])
                apply()
            }

            tvScanCountdown.text = "Launching Hidden Menu..."
            delay(1000)
            startDialer()
        }
    }

    private fun checkScanState() {
        val prefs = getSharedPreferences("BandSelectorPrefs", Context.MODE_PRIVATE)
        val macroMode = prefs.getString("macro_mode", "") ?: ""
        
        if (macroMode == "SCANNING") {
            setUiScanning(true)
            val step = prefs.getInt("scan_step", 0)
            val bandsStr = prefs.getString("scan_bands", "") ?: ""
            val bands = if (bandsStr.isNotEmpty()) bandsStr.split(",") else emptyList()
            val speedsStr = prefs.getString("scan_speeds", "") ?: ""
            
            if (bands.isEmpty() || step >= bands.size) {
                // Done scanning or error
                lifecycleScope.launch {
                    tvScanStatus.text = "Scan Completed"
                    tvScanCountdown.text = "Determining best band..."
                    
                    // Parse speeds
                    val speedList = parseSpeeds(speedsStr)
                    displayResults(speedList)
                    
                    val bestBand = speedList.maxByOrNull { it.second }?.first ?: "Automatic"
                    
                    tvScanCountdown.text = "Best Band: $bestBand. Applying..."
                    delay(3000)
                    
                    prefs.edit().apply {
                        putString("macro_mode", "APPLY_BEST")
                        putString("target_band_to_set", bestBand)
                        apply()
                    }
                    startDialer()
                }
                return
            }

            // Perform test for current band
            val currentBand = bands[step]
            lifecycleScope.launch {
                tvScanStatus.text = "Testing $currentBand"
                
                // Show current accumulated results
                val speedList = parseSpeeds(speedsStr)
                displayResults(speedList)
                
                // 1. Wait for network to reconnect (settle)
                for (i in 5 downTo 1) {
                    tvScanCountdown.text = "Waiting for network settle... ${i}s"
                    delay(1000)
                }
                
                tvScanCountdown.text = "Testing speed (Downloading)..."
                val speed = runSpeedTest()
                
                // Save speed result
                val newSpeedsStr = if (speedsStr.isEmpty()) "$currentBand:$speed" else "$speedsStr,$currentBand:$speed"
                val nextStep = step + 1
                
                prefs.edit().apply {
                    putInt("scan_step", nextStep)
                    putString("scan_speeds", newSpeedsStr)
                    apply()
                }

                if (nextStep < bands.size) {
                    val nextBand = bands[nextStep]
                    prefs.edit().putString("target_band_to_set", nextBand).apply()
                    tvScanStatus.text = "Switching to $nextBand"
                    tvScanCountdown.text = "Launching Hidden Menu..."
                    delay(2000)
                    startDialer()
                } else {
                    // Trigger evaluation
                    checkScanState()
                }
            }
        } else if (macroMode == "APPLY_BEST" || macroMode == "REVERT_AUTOMATIC") {
            // Finished applying or reverting. Reset and close.
            prefs.edit().apply {
                putString("macro_mode", "")
                putString("target_band_to_set", "")
                putInt("scan_step", 0)
                putString("scan_bands", "")
                putString("scan_speeds", "")
                apply()
            }
            Toast.makeText(this, "Optimization Complete!", Toast.LENGTH_LONG).show()
            finish()
        } else {
            setUiScanning(false)
        }
    }

    private fun displayResults(speeds: List<Pair<String, Double>>) {
        val sb = StringBuilder()
        sb.append("Tested Speeds:\n")
        if (speeds.isEmpty()) {
            sb.append("(No tests completed yet)")
        } else {
            for (p in speeds) {
                sb.append("• ${p.first}: ${String.format("%.2f", p.second)} Mbps\n")
            }
        }
        tvScanResults.text = sb.toString()
    }

    private fun parseSpeeds(str: String): List<Pair<String, Double>> {
        if (str.isEmpty()) return emptyList()
        val list = mutableListOf<Pair<String, Double>>()
        val items = str.split(",")
        for (item in items) {
            val parts = item.split(":")
            if (parts.size == 2) {
                list.add(Pair(parts[0], parts[1].toDoubleOrNull() ?: 0.0))
            }
        }
        return list
    }

    private suspend fun runSpeedTest(): Double = withContext(Dispatchers.IO) {
        // Download a file from Cloudflare Speed Test CDN to measure throughput
        val url = URL("https://speed.cloudflare.com/__down?bytes=3000000") // 3MB
        var bytesRead = 0
        val startTime = System.currentTimeMillis()
        try {
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            val inputStream: InputStream = connection.inputStream
            val buffer = ByteArray(4096)
            while (true) {
                val read = inputStream.read(buffer)
                if (read == -1) break
                bytesRead += read
                // Cap speed test at 3.5 seconds to avoid hanging
                if (System.currentTimeMillis() - startTime > 3500) {
                    break
                }
            }
            inputStream.close()
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext 0.0
        }
        val durationSec = (System.currentTimeMillis() - startTime) / 1000.0
        if (durationSec <= 0) return@withContext 0.0
        val bits = bytesRead * 8.0
        val megabits = bits / 1_000_000.0
        return@withContext megabits / durationSec
    }

    private fun setUiScanning(scanning: Boolean) {
        if (scanning) {
            cardScanProgress.visibility = View.VISIBLE
            cardAccessibility.visibility = View.GONE
            cardCarrier.visibility = View.GONE
            cardExecute.visibility = View.GONE
        } else {
            cardScanProgress.visibility = View.GONE
            cardAccessibility.visibility = View.VISIBLE
            cardCarrier.visibility = View.VISIBLE
            cardExecute.visibility = View.VISIBLE
        }
    }

    private fun getBandsForCarrier(carrier: String): List<String> {
        return when (carrier) {
            "SKT" -> listOf("LTE B1", "LTE B3", "LTE B5", "LTE B7")
            "KT" -> listOf("LTE B1", "LTE B3", "LTE B8")
            "LGU+" -> listOf("LTE B1", "LTE B5", "LTE B7")
            else -> listOf("LTE B1", "LTE B5", "LTE B7")
        }
    }

    private fun startDialer() {
        val intent = Intent(Intent.ACTION_DIAL).apply {
            data = Uri.parse("tel:319712358")
        }
        startActivity(intent)
    }
}
