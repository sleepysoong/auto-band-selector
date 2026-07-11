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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
            logProgress("사용자가 접근성 설정 페이지 진입 버튼을 클릭했습니다.")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // Initialize Device Carrier Selection
        val rgDeviceCarrier = findViewById<RadioGroup>(R.id.rgDeviceCarrier)
        val savedDeviceCarrier = prefs.getString("device_carrier", "SKT")
        logProgress("저장된 기기 원통신사 설정을 불러왔습니다: $savedDeviceCarrier")
        when (savedDeviceCarrier) {
            "SKT" -> rgDeviceCarrier.check(R.id.rbDevSkt)
            "KT" -> rgDeviceCarrier.check(R.id.rbDevKt)
            "LGU+" -> rgDeviceCarrier.check(R.id.rbDevUplus)
        }

        rgDeviceCarrier.setOnCheckedChangeListener { _, checkedId ->
            val carrier = when (checkedId) {
                R.id.rbDevSkt -> "SKT"
                R.id.rbDevKt -> "KT"
                R.id.rbDevUplus -> "LGU+"
                else -> "SKT"
            }
            prefs.edit().putString("device_carrier", carrier).apply()
            logProgress("기기 원통신사 설정이 저장되었습니다: $carrier")
        }

        // Initialize SIM Carrier Selection
        val rgSimCarrier = findViewById<RadioGroup>(R.id.rgSimCarrier)
        val savedSimCarrier = prefs.getString("sim_carrier", "SKT")
        logProgress("저장된 유심 통신사 설정을 불러왔습니다: $savedSimCarrier")
        when (savedSimCarrier) {
            "SKT" -> rgSimCarrier.check(R.id.rbSimSkt)
            "KT" -> rgSimCarrier.check(R.id.rbSimKt)
            "LGU+" -> rgSimCarrier.check(R.id.rbSimUplus)
        }

        rgSimCarrier.setOnCheckedChangeListener { _, checkedId ->
            val carrier = when (checkedId) {
                R.id.rbSimSkt -> "SKT"
                R.id.rbSimKt -> "KT"
                R.id.rbSimUplus -> "LGU+"
                else -> "SKT"
            }
            prefs.edit().putString("sim_carrier", carrier).apply()
            logProgress("장착 유심 통신사 설정이 저장되었습니다: $carrier")
        }

        findViewById<Button>(R.id.btnRunMacro).setOnClickListener {
            startScanCountdown()
        }

        findViewById<Button>(R.id.btnRevertAutomatic).setOnClickListener {
            logProgress("원상 복구(Automatic) 명령이 접수되었습니다.")
            prefs.edit().apply {
                putString("macro_mode", "REVERT_AUTOMATIC")
                putString("target_band_to_set", "Automatic")
                putBoolean("band_setting_applied", false)
                apply()
            }
            startDialerOrApp()
        }

        btnStopScan.setOnClickListener {
            stopScan()
        }

        findViewById<Button>(R.id.btnShowLogs).setOnClickListener {
            showLogsDialog()
        }
    }

    override fun onResume() {
        super.onResume()
        checkScanState()
    }

    private fun logProgress(message: String) {
        val currentTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val currentLog = tvScanResults.text.toString()
        val newLog = if (currentLog.isEmpty()) {
            "[$currentTime] $message"
        } else {
            "$currentLog\n[$currentTime] $message"
        }
        tvScanResults.text = newLog
        
        // Write to cumulative local file
        writeLogToFile(message)
    }

    private fun writeLogToFile(message: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val formattedLine = "[$timestamp] $message\n"
        try {
            openFileOutput("logs.txt", Context.MODE_APPEND).use {
                it.write(formattedLine.toByteArray())
            }
        } catch (e: Exception) {
            Log.e("BandSelectorLog", "로그 파일 저장 실패: ${e.message}")
        }
    }

    private fun readLogsFromFile(): String {
        return try {
            openFileInput("logs.txt").use {
                it.bufferedReader().readText()
            }
        } catch (e: Exception) {
            "저장된 로그 기록이 없습니다."
        }
    }

    private fun clearLogFile() {
        try {
            deleteFile("logs.txt")
            logProgress("로그 파일이 디바이스에서 완전히 제거되었습니다.")
        } catch (e: Exception) {
            Log.e("BandSelectorLog", "로그 삭제 오류: ${e.message}")
        }
    }

    private fun showLogsDialog() {
        val logsContent = readLogsFromFile()
        
        val dialog = AlertDialog.Builder(this).create()
        dialog.setTitle("누적 시스템 로그 기록")

        // Root vertical layout
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
        }

        // Scrollable container for text
        val scrollView = android.widget.ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        
        val textView = TextView(this).apply {
            text = logsContent
            setTextIsSelectable(true) // Enable selectable text
            textSize = 12f
            setTextColor(android.graphics.Color.BLACK)
        }
        scrollView.addView(textView)
        rootLayout.addView(scrollView)

        // Buttons container
        val btnContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 24
            }
        }

        val btnParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = 8
        }

        // Copy button
        val btnCopy = Button(this).apply {
            text = "복사"
            layoutParams = btnParams
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("System Logs", textView.text)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this@MainActivity, "로그가 클립보드에 복사되었습니다.", Toast.LENGTH_SHORT).show()
            }
        }

        // Share button
        val btnShare = Button(this).apply {
            text = "공유"
            layoutParams = btnParams
            setOnClickListener {
                shareLogFile()
            }
        }

        // Delete button
        val btnDelete = Button(this).apply {
            text = "삭제"
            layoutParams = btnParams
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("로그 삭제")
                    .setMessage("정말로 누적 로그를 삭제하시겠습니까?")
                    .setPositiveButton("삭제") { _, _ ->
                        clearLogFile()
                        textView.text = "저장된 로그 기록이 없습니다."
                        Toast.makeText(this@MainActivity, "로그가 삭제되었습니다.", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("취소", null)
                    .show()
            }
        }

        // Close button
        val btnClose = Button(this).apply {
            text = "닫기"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                dialog.dismiss()
            }
        }

        btnContainer.addView(btnCopy)
        btnContainer.addView(btnShare)
        btnContainer.addView(btnDelete)
        btnContainer.addView(btnClose)
        rootLayout.addView(btnContainer)

        dialog.setView(rootLayout)
        dialog.show()
    }

    private fun shareLogFile() {
        val logFile = File(filesDir, "logs.txt")
        if (!logFile.exists() || logFile.length() == 0L) {
            Toast.makeText(this, "공유할 로그 파일이 비어 있습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(
                this,
                "com.sleepysoong.autobandselector.fileprovider",
                logFile
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "로그 파일 공유"))
        } catch (e: Exception) {
            logProgress("로그 파일 공유 실패: ${e.message}")
            Toast.makeText(this, "공유 오류: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startScanCountdown() {
        setUiScanning(true)
        tvScanStatus.text = "주파수 스캔 초기화 중"
        tvScanResults.text = "" // Clear log
        logProgress("사용자가 자동 스캔 버튼을 클릭했습니다. 작업을 시작합니다.")
        
        scanJob = lifecycleScope.launch {
            try {
                enterPipMode()
                
                for (i in 3 downTo 1) {
                    tvScanCountdown.text = "${i}초 뒤 스캔을 시작합니다..."
                    logProgress("스캔 자동 진입 대기 중... (${i}초)")
                    delay(1000)
                }
                
                val prefs = getSharedPreferences("BandSelectorPrefs", Context.MODE_PRIVATE)
                val deviceCarrier = prefs.getString("device_carrier", "SKT") ?: "SKT"
                val simCarrier = prefs.getString("sim_carrier", "SKT") ?: "SKT"
                val bands = getBandsForCarrier(simCarrier)
                
                logProgress("설정정보 로딩 - 기기: $deviceCarrier, 유심: $simCarrier, 측정대역: ${bands.joinToString(", ")}")
                
                prefs.edit().apply {
                    putString("macro_mode", "SCANNING")
                    putInt("scan_step", 0)
                    putString("scan_bands", bands.joinToString(","))
                    putString("scan_speeds", "")
                    putString("target_band_to_set", bands[0])
                    putBoolean("band_setting_applied", false)
                    apply()
                }

                tvScanCountdown.text = "히든 메뉴 진입 시도 중..."
                logProgress("매크로 최초 진입을 위해 전용 시스템 앱을 호출합니다.")
                delay(1000)
                startDialerOrApp()
            } catch (e: Exception) {
                logProgress("카운트다운 스레드 치명적인 오류 발생: ${e.message}")
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
                scanJob = lifecycleScope.launch {
                    tvScanStatus.text = "주파수 스캔 완료"
                    tvScanCountdown.text = "최적의 주파수를 분석하고 있습니다..."
                    logProgress("전체 대역폭 측정이 끝났습니다. 수집된 속도 데이터를 대조 분석합니다.")
                    
                    val speedList = parseSpeeds(speedsStr)
                    displayResults(speedList)
                    
                    val bestBand = speedList.maxByOrNull { it.second }?.first ?: "Automatic"
                    logProgress("통계적 최고 처리속도 대역폭 탐색 완료: $bestBand")
                    
                    tvScanCountdown.text = "최적 주파수: $bestBand. 최종 적용 중..."
                    delay(3000)
                    
                    prefs.edit().apply {
                        putString("macro_mode", "APPLY_BEST")
                        putString("target_band_to_set", bestBand)
                        putBoolean("band_setting_applied", false)
                        apply()
                    }
                    logProgress("기기를 최고 속도 주파수($bestBand) 대역으로 영구 고정하기 위해 히든 메뉴에 진입합니다.")
                    startDialerOrApp()
                }
                return
            }

            // Check if accessibility service has applied the current band setting
            val applied = prefs.getBoolean("band_setting_applied", false)
            if (!applied) {
                logProgress("네트워크 제어 신호를 대기하고 있습니다... (접근성 자동화 활성화 확인)")
                return
            }

            val currentBand = bands[step]
            scanJob = lifecycleScope.launch {
                tvScanStatus.text = "대역폭 테스트: $currentBand"
                logProgress("성공적으로 $currentBand 대역폭으로 기기 네트워크망이 설정되었습니다.")
                
                val speedList = parseSpeeds(speedsStr)
                displayResults(speedList)
                
                for (i in 5 downTo 1) {
                    tvScanCountdown.text = "네트워크 안정화 대기 중... ${i}초"
                    logProgress("LTE 무선 기지국 재접속 대기 시간... (${i}초)")
                    delay(1000)
                }
                
                tvScanCountdown.text = "다운로드 속도 측정 중..."
                logProgress("Cloudflare CDN 백본 네트워크를 이용한 속도 테스트를 전송합니다...")
                val speed = runSpeedTest()
                logProgress("$currentBand 대역 평균 데이터 전송 속도: ${String.format("%.2f", speed)} Mbps")
                
                val newSpeedsStr = if (speedsStr.isEmpty()) "$currentBand:$speed" else "$speedsStr,$currentBand:$speed"
                val nextStep = step + 1
                
                prefs.edit().apply {
                    putInt("scan_step", nextStep)
                    putString("scan_speeds", newSpeedsStr)
                    putBoolean("band_setting_applied", false)
                    apply()
                }

                if (nextStep < bands.size) {
                    val nextBand = bands[nextStep]
                    prefs.edit().putString("target_band_to_set", nextBand).apply()
                    tvScanStatus.text = "주파수 전환: $nextBand"
                    tvScanCountdown.text = "시스템 히든 메뉴 진입 중..."
                    logProgress("다음 주파수($nextBand) 측정을 위해 히든 메뉴 재설정을 호출합니다.")
                    delay(2000)
                    startDialerOrApp()
                } else {
                    checkScanState()
                }
            }
        } else if (macroMode == "APPLY_BEST" || macroMode == "REVERT_AUTOMATIC") {
            val applied = prefs.getBoolean("band_setting_applied", false)
            if (!applied) {
                logProgress("최종 주파수 적용 또는 자동 복구 신호를 대기 중입니다...")
                return
            }
            prefs.edit().apply {
                putString("macro_mode", "")
                putString("target_band_to_set", "")
                putInt("scan_step", 0)
                putString("scan_bands", "")
                putString("scan_speeds", "")
                putBoolean("band_setting_applied", false)
                apply()
            }
            logProgress("자동 제어 매크로 프로세스가 정상 종료되었습니다. 사용이 완료되었습니다.")
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
            putBoolean("band_setting_applied", false)
            apply()
        }
        logProgress("사용자의 요청으로 즉시 속도 비교 측정 시퀀스를 폭파 중단했습니다.")
        Toast.makeText(this, "스캔이 중단되었습니다.", Toast.LENGTH_SHORT).show()
        setUiScanning(false)
    }

    private fun displayResults(speeds: List<Pair<String, Double>>) {
        val sb = StringBuilder()
        sb.append("실시간 속도 리포트:\n")
        if (speeds.isEmpty()) {
            sb.append("(테스트 데이터 수집 대기 중...)")
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
        val deviceCarrier = prefs.getString("device_carrier", "SKT") ?: "SKT"
        
        logProgress("히든 메뉴 진입 시퀀스 시작 (기기 캐리어: $deviceCarrier)")
        
        val launched = startHiddenMenuApp(deviceCarrier)
        if (launched) {
            logProgress("명시적 시스템 패키지 매칭 및 화면 강제 호출에 성공했습니다.")
        } else {
            logProgress("시스템 다이렉트 런처 탐색 실패. 기존 다이얼러 기반 딜레이 실행을 개시합니다.")
            startDialer()
        }
    }

    private fun startHiddenMenuApp(deviceCarrier: String): Boolean {
        val packageName = when (deviceCarrier) {
            "SKT" -> "com.samsung.hidden.SKT"
            "KT" -> "com.samsung.hidden.KT"
            "LGU+" -> "com.samsung.hidden.LGT"
            else -> "com.samsung.hidden.SKT"
        }

        val pm = packageManager
        try {
            pm.getPackageInfo(packageName, 0)
        } catch (e: Exception) {
            logProgress("대상 통신사 히든 패키지가 단말에 부재합니다: $packageName")
            return false
        }

        val launchIntent = pm.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            try {
                startActivity(launchIntent)
                return true
            } catch (e: Exception) {
                logProgress("Launch Intent 바인딩 실패: ${e.message}")
            }
        }

        try {
            val packageInfo = pm.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
            val activities = packageInfo.activities
            if (!activities.isNullOrEmpty()) {
                val mainActivity = activities[0].name
                logProgress("패키지 액티비티 추출 완료: $mainActivity")
                val intent = Intent().apply {
                    setClassName(packageName, mainActivity)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                return true
            }
        } catch (e: Exception) {
            logProgress("명시적 컴포넌트 호출 오류: ${e.message}")
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
            tvAppTitle.visibility = View.GONE
            cardScanProgress.setPadding(8, 8, 8, 8)
            tvScanStatus.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            tvScanCountdown.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            tvScanResults.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10f)
            btnStopScan.visibility = View.GONE
        } else {
            tvAppTitle.visibility = View.VISIBLE
            cardScanProgress.setPadding(20, 20, 20, 20)
            tvScanStatus.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
            tvScanCountdown.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            tvScanResults.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            btnStopScan.visibility = View.VISIBLE
        }
    }
}
