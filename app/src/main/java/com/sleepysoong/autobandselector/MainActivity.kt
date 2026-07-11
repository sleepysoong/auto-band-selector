package com.sleepysoong.autobandselector

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    private lateinit var tvAppTitle: TextView
    private lateinit var tvScanStatus: TextView
    private lateinit var tvScanCountdown: TextView
    private lateinit var tvScanResults: TextView
    private lateinit var btnStopScan: Button

    private var scanJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize Views
        tvAppTitle = findViewById(R.id.tvAppTitle)
        cardScanProgress = findViewById(R.id.cardScanProgress)
        cardAccessibility = findViewById(R.id.cardAccessibility)
        cardCarrier = findViewById(R.id.cardCarrier)
        cardExecute = findViewById(R.id.cardExecute)

        tvScanStatus = findViewById(R.id.tvScanStatus)
        tvScanCountdown = findViewById(R.id.tvScanCountdown)
        tvScanResults = findViewById(R.id.tvScanResults)
        btnStopScan = findViewById(R.id.btnStopScan)

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
            startDialerOrApp()
        }

        btnStopScan.setOnClickListener {
            stopScan()
        }
    }

    override fun onResume() {
        super.onResume()
        checkScanState()
    }

    private fun logProgress(message: String) {
        val currentTime = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val currentLog = tvScanResults.text.toString()
        val newLog = if (currentLog.isEmpty()) {
            "[$currentTime] $message"
        } else {
            "$currentLog\n[$currentTime] $message"
        }
        tvScanResults.text = newLog
        Log.d("BandSelectorLog", "[$currentTime] $message")
    }

    private fun startScanCountdown() {
        setUiScanning(true)
        tvScanStatus.text = "주파수 스캔 초기화 중"
        tvScanResults.text = "" // Clear log
        logProgress("자동 주파수 스캔 작업을 대기열에 등록했습니다.")
        
        scanJob = lifecycleScope.launch {
            try {
                // Enter PIP mode to show live progress
                enterPipMode()
                
                for (i in 3 downTo 1) {
                    tvScanCountdown.text = "${i}초 뒤 스캔을 시작합니다..."
                    logProgress("스캔 대기 중... (${i}초)")
                    delay(1000)
                }
                
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

                tvScanCountdown.text = "히든 메뉴 진입 시도 중..."
                delay(1000)
                startDialerOrApp()
            } catch (e: Exception) {
                logProgress("카운트다운 중 오류 발생: ${e.message}")
            }
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
                // Done scanning
                scanJob = lifecycleScope.launch {
                    tvScanStatus.text = "주파수 스캔 완료"
                    tvScanCountdown.text = "최적의 주파수를 분석하고 있습니다..."
                    logProgress("스캔 완료. 모든 데이터 분석을 시작합니다.")
                    
                    val speedList = parseSpeeds(speedsStr)
                    displayResults(speedList)
                    
                    val bestBand = speedList.maxByOrNull { it.second }?.first ?: "Automatic"
                    logProgress("최고 속도 주파수 분석 완료: $bestBand")
                    
                    tvScanCountdown.text = "최적 주파수: $bestBand. 최종 적용 중..."
                    delay(3000)
                    
                    prefs.edit().apply {
                        putString("macro_mode", "APPLY_BEST")
                        putString("target_band_to_set", bestBand)
                        apply()
                    }
                    logProgress("$bestBand 대역 최종 적용을 위해 히든 메뉴에 재진입합니다.")
                    startDialerOrApp()
                }
                return
            }

            // Perform test for current band
            val currentBand = bands[step]
            scanJob = lifecycleScope.launch {
                tvScanStatus.text = "대역폭 테스트: $currentBand"
                logProgress("$currentBand 대역폭으로 통신망이 설정되었습니다.")
                
                val speedList = parseSpeeds(speedsStr)
                displayResults(speedList)
                
                // 1. Wait for network to reconnect (settle)
                for (i in 5 downTo 1) {
                    tvScanCountdown.text = "네트워크 안정화 대기 중... ${i}초"
                    logProgress("네트워크 연결 대기 중... (${i}초)")
                    delay(1000)
                }
                
                tvScanCountdown.text = "다운로드 속도 측정 중..."
                logProgress("Cloudflare CDN 기준 인터넷 속도 테스트 시작...")
                val speed = runSpeedTest()
                logProgress("$currentBand 대역 속도 측정 완료: ${String.format("%.2f", speed)} Mbps")
                
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
                    tvScanStatus.text = "주파수 전환: $nextBand"
                    tvScanCountdown.text = "시스템 히든 메뉴 진입 중..."
                    delay(2000)
                    startDialerOrApp()
                } else {
                    checkScanState()
                }
            }
        } else if (macroMode == "APPLY_BEST" || macroMode == "REVERT_AUTOMATIC") {
            prefs.edit().apply {
                putString("macro_mode", "")
                putString("target_band_to_set", "")
                putInt("scan_step", 0)
                putString("scan_bands", "")
                putString("scan_speeds", "")
                apply()
            }
            logProgress("모든 최적화 프로세스가 안전하게 완수되었습니다.")
            Toast.makeText(this, "주파수 최적화 설정이 완료되었습니다!", Toast.LENGTH_LONG).show()
            finish()
        } else {
            setUiScanning(false)
        }
    }

    private fun stopScan() {
        scanJob?.cancel()
        val prefs = getSharedPreferences("BandSelectorPrefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("macro_mode", "")
            putString("target_band_to_set", "")
            putInt("scan_step", 0)
            putString("scan_bands", "")
            putString("scan_speeds", "")
            apply()
        }
        logProgress("사용자에 의해 주파수 고정 스캔 작업이 강제 중단되었습니다.")
        Toast.makeText(this, "스캔이 중단되었습니다.", Toast.LENGTH_SHORT).show()
        setUiScanning(false)
    }

    private fun displayResults(speeds: List<Pair<String, Double>>) {
        val sb = StringBuilder()
        sb.append("측정 결과 목록:\n")
        if (speeds.isEmpty()) {
            sb.append("(측정 완료된 결과가 아직 없습니다)")
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
        val url = URL("https://speed.cloudflare.com/__down?bytes=3000000")
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

    private fun startDialerOrApp() {
        val prefs = getSharedPreferences("BandSelectorPrefs", Context.MODE_PRIVATE)
        val carrier = prefs.getString("carrier", "SKT") ?: "SKT"
        
        logProgress("시스템 히든 메뉴 진입 시도 중 (통신사: $carrier)")
        
        val launched = startHiddenMenuApp(carrier)
        if (launched) {
            logProgress("통신사 전용 히든 메뉴 앱을 직접 실행했습니다.")
        } else {
            logProgress("히든 메뉴 앱 직접 실행 실패. 다이얼러 우회 실행 중...")
            startDialer()
        }
    }

    private fun startHiddenMenuApp(carrier: String): Boolean {
        val packageName = when (carrier) {
            "SKT" -> "com.samsung.hidden.SKT"
            "KT" -> "com.samsung.hidden.KT"
            "LGU+" -> "com.samsung.hidden.LGT"
            else -> "com.samsung.hidden.SKT"
        }

        val pm = packageManager
        try {
            pm.getPackageInfo(packageName, 0)
        } catch (e: Exception) {
            logProgress("해당 패키지가 디바이스에 존재하지 않습니다: $packageName")
            return false
        }

        val launchIntent = pm.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            try {
                startActivity(launchIntent)
                return true
            } catch (e: Exception) {
                logProgress("Launch Intent 실행 실패: ${e.message}")
            }
        }

        try {
            val packageInfo = pm.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
            val activities = packageInfo.activities
            if (!activities.isNullOrEmpty()) {
                val mainActivity = activities[0].name
                logProgress("액티비티 검색 성공: $mainActivity")
                val intent = Intent().apply {
                    setClassName(packageName, mainActivity)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                return true
            }
        } catch (e: Exception) {
            logProgress("액티비티 명시적 실행 실패: ${e.message}")
        }

        return false
    }

    private fun startDialer() {
        val intent = Intent(Intent.ACTION_DIAL).apply {
            data = Uri.parse("tel:319712358")
        }
        startActivity(intent)
    }

    private fun enterPipMode() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder().build()
            enterPictureInPictureMode(params)
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            // PIP UI
            tvAppTitle.visibility = View.GONE
            cardScanProgress.setPadding(8, 8, 8, 8)
            tvScanStatus.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            tvScanCountdown.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            tvScanResults.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10f)
            btnStopScan.visibility = View.GONE
        } else {
            // Restore UI
            tvAppTitle.visibility = View.VISIBLE
            cardScanProgress.setPadding(20, 20, 20, 20)
            tvScanStatus.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
            tvScanCountdown.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            tvScanResults.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            btnStopScan.visibility = View.VISIBLE
        }
    }
}
