package com.sleepysoong.autobandselector

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sleepysoong.autobandselector.automation.BandProbe
import com.sleepysoong.autobandselector.automation.BandScanOrchestrator
import com.sleepysoong.autobandselector.automation.BandScanRuntime
import com.sleepysoong.autobandselector.automation.BandScanState
import com.sleepysoong.autobandselector.automation.RuntimeBandAutomation
import com.sleepysoong.autobandselector.automation.RuntimeStart
import com.sleepysoong.autobandselector.automation.SamsungPhoneEntry
import com.sleepysoong.autobandselector.automation.PackageManagerSamsungPhoneActivityResolver
import com.sleepysoong.autobandselector.automation.SamsungProfiles
import com.sleepysoong.autobandselector.data.Carrier
import com.sleepysoong.autobandselector.data.SettingsConfiguration
import com.sleepysoong.autobandselector.data.SettingsRepository
import com.sleepysoong.autobandselector.network.CellularSpeedProbe
import com.sleepysoong.autobandselector.network.KtSubscriptionResolver
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

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

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var runtime: BandScanRuntime

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvAppTitle = findViewById(R.id.tvAppTitle)
        cardScanProgress = findViewById(R.id.cardScanProgress)
        cardAccessibility = findViewById(R.id.cardAccessibility)
        cardCarrier = findViewById(R.id.cardCarrier)
        cardExecute = findViewById(R.id.cardExecute)
        tvScanStatus = findViewById(R.id.tvScanStatus)
        tvScanCountdown = findViewById(R.id.tvScanCountdown)
        tvScanResults = findViewById(R.id.tvScanResults)
        btnStopScan = findViewById(R.id.btnStopScan)

        settingsRepository = SettingsRepository(this)
        val appScope = (application as BandSelectorApp).appScope
        val parser = SamsungProfiles.production()
        runtime = BandScanRuntime(
            scope = appScope,
            resolver = KtSubscriptionResolver(this),
            settings = settingsRepository,
            automationFactory = {
                RuntimeBandAutomation(
                    appScope, parser, PackageManagerSamsungPhoneActivityResolver(packageManager),
                    ::startActivity
                )
            },
            probeFactory = {
                BandProbe { subId, _, _ -> CellularSpeedProbe(this).measureSelected(subId) }
            }
        )

        findViewById<Button>(R.id.btnEnableAccessibility).setOnClickListener {
            logProgress("사용자가 접근성 설정 페이지 진입 버튼을 클릭했습니다.")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        val rgDeviceCarrier = findViewById<RadioGroup>(R.id.rgDeviceCarrier)
        when (settingsRepository.loadConfiguration().deviceCarrier) {
            Carrier.SKT -> rgDeviceCarrier.check(R.id.rbDevSkt)
            Carrier.KT -> rgDeviceCarrier.check(R.id.rbDevKt)
            Carrier.LGU_PLUS -> rgDeviceCarrier.check(R.id.rbDevUplus)
        }
        rgDeviceCarrier.setOnCheckedChangeListener { _, checkedId ->
            persistCarriers(deviceCarrierFrom(checkedId), simCarrierFrom(rgSimCarrierCheckedId()))
        }
        val rgSimCarrier = findViewById<RadioGroup>(R.id.rgSimCarrier)
        when (settingsRepository.loadConfiguration().simCarrier) {
            Carrier.SKT -> rgSimCarrier.check(R.id.rbSimSkt)
            Carrier.KT -> rgSimCarrier.check(R.id.rbSimKt)
            Carrier.LGU_PLUS -> rgSimCarrier.check(R.id.rbSimUplus)
        }
        rgSimCarrier.setOnCheckedChangeListener { _, checkedId ->
            persistCarriers(deviceCarrierFrom(rgDeviceCarrier.checkedRadioButtonId), simCarrierFrom(checkedId))
        }

        findViewById<Button>(R.id.btnRunMacro).setOnClickListener { startScan() }
        findViewById<Button>(R.id.btnRevertAutomatic).setOnClickListener { startRestore() }
        btnStopScan.setOnClickListener {
            if (runtime.stop()) logProgress("사용자가 실행을 중지했습니다.")
        }
        findViewById<Button>(R.id.btnShowLogs).setOnClickListener { showLogsDialog() }
    }

    private fun startScan() {
        when (val started = runtime.startScan()) {
            is RuntimeStart.Blocked -> {
                logProgress("시작이 차단되었습니다: " + started.reason)
                tvScanStatus.text = "시작할 수 없습니다: " + started.reason
            }
            RuntimeStart.AlreadyRunning -> logProgress("이미 실행 중입니다.")
            is RuntimeStart.Started -> {
                tvScanResults.text = ""
                logProgress("매번 새로운 KT B1/B3/B8 비교를 시작합니다. (runId=${started.runId})")
                observeRun(runtime.orchestrator())
            }
        }
    }

    private fun startRestore() {
        when (val started = runtime.restore()) {
            is RuntimeStart.Blocked -> logProgress("자동 복구 시작이 차단되었습니다: " + started.reason)
            RuntimeStart.AlreadyRunning -> logProgress("이미 실행 중입니다.")
            is RuntimeStart.Started -> {
                logProgress("명시적 자동 복구를 시작합니다. (runId=${started.runId})")
                observeRun(runtime.orchestrator())
            }
        }
    }

    private fun observeRun(orchestrator: BandScanOrchestrator) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                orchestrator.state.collectLatest { state ->
                    render(state)
                    if (state is BandScanState.Completed || state is BandScanState.Failed ||
                        state is BandScanState.Restored || state is BandScanState.Cancelled
                    ) setUiScanning(false)
                }
            }
        }
    }

    private fun render(state: BandScanState) {
        when (state) {
            BandScanState.Idle -> Unit
            is BandScanState.Running -> {
                setUiScanning(true)
                tvScanStatus.text = "실행 중: " + state.phase.name
                tvScanCountdown.text = state.candidate?.let { "대상 대역: LTE B" + it.number } ?: ""
                tvScanResults.text = state.results.joinToString("\n") { result ->
                    "LTE B" + result.band.number + ": " + result.outcome::class.simpleName
                }
            }
            is BandScanState.Completed -> {
                tvScanStatus.text = "최적 대역 적용 완료: LTE B" + state.winner.band.number
                tvScanCountdown.text = ""
                logProgress("검증된 최적 대역 LTE B" + state.winner.band.number + " 적용 완료")
                try { enterPipMode() } catch (error: Exception) {
                    Log.d("BandSelector", "PiP unavailable: " + error.message)
                }
            }
            is BandScanState.Restored -> {
                tvScanStatus.text = "자동 모드로 복구되었습니다"
                logProgress("자동 모드가 확인되었습니다.")
            }
            is BandScanState.Failed -> {
                tvScanStatus.text = "오류: " + state.reason
                val recovery = state.recovery.toString()
                tvScanCountdown.text = "복구 상태: " + recovery
                logProgress("실패: " + state.reason + " (복구 상태: " + recovery + ")")
            }
            is BandScanState.Cancelled -> {
                tvScanStatus.text = "중지되었습니다"
                tvScanCountdown.text = ""
                logProgress("사용자 요청으로 다음 조치 없이 중지되었습니다.")
            }
        }
    }

    private fun persistCarriers(device: Carrier, sim: Carrier) {
        settingsRepository.saveConfiguration(SettingsConfiguration(device, sim))
        logProgress("통신사 설정 저장: 기기 " + device.storedValue + ", 유심 " + sim.storedValue)
    }

    private fun deviceCarrierFrom(id: Int): Carrier = when (id) {
        R.id.rbDevKt -> Carrier.KT
        R.id.rbDevUplus -> Carrier.LGU_PLUS
        else -> Carrier.SKT
    }

    private fun simCarrierFrom(id: Int): Carrier = when (id) {
        R.id.rbSimKt -> Carrier.KT
        R.id.rbSimUplus -> Carrier.LGU_PLUS
        else -> Carrier.SKT
    }

    private fun rgSimCarrierCheckedId(): Int =
        findViewById<RadioGroup>(R.id.rgSimCarrier).checkedRadioButtonId

    private fun logProgress(message: String) {
        val currentTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val currentLog = tvScanResults.text.toString()
        tvScanResults.text = if (currentLog.isEmpty()) "[" + currentTime + "] " + message
        else currentLog + "\n[" + currentTime + "] " + message
        writeLogToFile(message)
    }

    private fun writeLogToFile(message: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "[" + timestamp + "] " + message + "\n"
        try {
            val file = File(filesDir, "logs.txt")
            if (file.length() > 1024 * 1024) {
                // 1 MiB rotation: keep the newest tail within the bound.
                val tail = file.readText().takeLast(768 * 1024)
                file.writeText(tail)
            }
            openFileOutput("logs.txt", Context.MODE_APPEND).use { it.write(line.toByteArray()) }
        } catch (error: Exception) {
            Log.e("BandSelectorLog", "로그 파일 저장 실패: " + error.message)
        }
    }

    private fun readLogsFromFile(): String = try {
        openFileInput("logs.txt").use { it.bufferedReader().readText() }
    } catch (error: Exception) {
        "저장된 로그 기록이 없습니다."
    }

    private fun clearLogFile() {
        try {
            deleteFile("logs.txt")
            logProgress("로그 파일이 디바이스에서 완전히 제거되었습니다.")
        } catch (error: Exception) {
            Log.e("BandSelectorLog", "로그 삭제 오류: " + error.message)
        }
    }

    private fun showLogsDialog() {
        val logsContent = readLogsFromFile()
        val dialog = AlertDialog.Builder(this).create()
        dialog.setTitle("누적 시스템 로그 기록")

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
        }
        val scrollView = android.widget.ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val textView = TextView(this).apply {
            text = logsContent
            setTextIsSelectable(true)
            textSize = 12f
            setTextColor(android.graphics.Color.BLACK)
        }
        scrollView.addView(textView)
        rootLayout.addView(scrollView)

        val btnContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val btnCopy = Button(this).apply {
            text = "복사"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(
                    android.content.ClipData.newPlainText("logs", logsContent)
                )
                Toast.makeText(this@MainActivity, "로그가 클립보드에 복사되었습니다.", Toast.LENGTH_SHORT).show()
            }
        }
        val btnShare = Button(this).apply {
            text = "공유"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { shareLogFile() }
        }
        val btnDelete = Button(this).apply {
            text = "삭제"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { clearLogFile(); dialog.dismiss() }
        }
        btnContainer.addView(btnCopy)
        btnContainer.addView(btnShare)
        btnContainer.addView(btnDelete)
        rootLayout.addView(btnContainer)
        dialog.setView(rootLayout)
        dialog.show()
    }

    private fun shareLogFile() {
        try {
            val logFile = File(filesDir, "logs.txt")
            if (!logFile.exists()) {
                Toast.makeText(this, "공유할 로그 파일이 없습니다.", Toast.LENGTH_SHORT).show()
                return
            }
            val uri = FileProvider.getUriForFile(
                this, "com.sleepysoong.autobandselector.fileprovider", logFile
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "로그 파일 공유"))
        } catch (error: Exception) {
            logProgress("로그 파일 공유 실패: " + error.message)
            Toast.makeText(this, "공유 오류: " + error.message, Toast.LENGTH_SHORT).show()
        }
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

    private fun enterPipMode() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            enterPictureInPictureMode(PictureInPictureParams.Builder().build())
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            tvAppTitle.visibility = View.GONE
            cardScanProgress.setPadding(8, 8, 8, 8)
            tvScanStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            tvScanCountdown.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            tvScanResults.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            btnStopScan.visibility = View.GONE
        } else {
            tvAppTitle.visibility = View.VISIBLE
            cardScanProgress.setPadding(20, 20, 20, 20)
            tvScanStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            tvScanCountdown.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            tvScanResults.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            btnStopScan.visibility = View.VISIBLE
        }
    }
}
