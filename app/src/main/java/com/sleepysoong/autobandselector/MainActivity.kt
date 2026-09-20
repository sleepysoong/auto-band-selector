package com.sleepysoong.autobandselector

import android.Manifest
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.appcompat.app.AlertDialog
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.sleepysoong.autobandselector.automation.BandScanState
import com.sleepysoong.autobandselector.automation.RuntimeStart
import com.sleepysoong.autobandselector.network.KtSubscriptionResolution
import com.sleepysoong.autobandselector.ui.BandSelectorScreen
import com.sleepysoong.autobandselector.ui.PreflightUi
import com.sleepysoong.autobandselector.ui.UiStateMapper
import com.sleepysoong.autobandselector.data.Carrier
import com.sleepysoong.autobandselector.data.SettingsConfiguration
import com.sleepysoong.autobandselector.data.SettingsRepository
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val activityLauncher: (Intent) -> Unit = { intent -> startActivity(intent) }
    private val phonePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { render() }
    private lateinit var app: BandSelectorApp
    private lateinit var settingsRepository: SettingsRepository
    private var configuration = SettingsConfiguration()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as BandSelectorApp
        settingsRepository = SettingsRepository(this)
        app.activityLauncher = activityLauncher
        configuration = settingsRepository.loadConfiguration()
        render()
        lifecycleScope.launch {
            app.runtime.state.collectLatest { state -> updatePip(state) }
        }
    }

    override fun onDestroy() {
        if (app.activityLauncher === activityLauncher) app.activityLauncher = null
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // Observation only: onResume never starts work.
        render()
    }

    private fun render() {
        setContent {
            val state by app.runtime.state.collectAsState(initial = BandScanState.Idle)
            val preflight = currentPreflight()
            BandSelectorScreen(
                ui = UiStateMapper.map(state, preflight, configuration),
                configuration = configuration,
                subscriptionText = preflight.subscriptionText,
                onStart = { handleRuntime(app.runtime.startScan()) },
                onStop = {
                    if (app.runtime.stop()) writeLog("사용자가 실행을 중지했습니다.")
                    render()
                },
                onRestore = { handleRuntime(app.runtime.restore()) },
                onOpenAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                onRequestPhonePermission = { phonePermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE) },
                onConfirmSlot = { slot -> settingsRepository.confirmLogicalSlot(slot); render() },
                onOpenSimSettings = { startActivity(Intent(Settings.ACTION_SETTINGS)) },
                onShowLogs = { showLogsDialog() },
                onDeviceCarrier = { updateCarriers(it, configuration.simCarrier) },
                onSimCarrier = { updateCarriers(configuration.deviceCarrier, it) }
            )
        }
    }

    private fun handleRuntime(start: RuntimeStart) {
        when (start) {
            is RuntimeStart.Started -> {
                writeLog("새 실행이 시작되었습니다. runId=" + start.runId.value)
                render()
            }
            RuntimeStart.AlreadyRunning -> writeLog("이미 실행 중입니다.")
            is RuntimeStart.Blocked -> {
                writeLog("실행이 차단되었습니다: " + start.reason)
                start.settingsIntent?.let(::startActivity)
            }
        }
    }

    private fun currentPreflight(): PreflightUi {
        val expectedService = ComponentName(this, BandSelectorService::class.java)
        val accessEnabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty().split(':').mapNotNull(ComponentName::unflattenFromString)
            .any { it == expectedService }
        val resolution = com.sleepysoong.autobandselector.network.KtSubscriptionResolver(this)
            .resolve(settingsRepository.confirmedLogicalSlotIndex)
        val blocked: String?
        val subscriptionText: String?
        var phonePermissionRequired = false
        var slotConfirmationRequired = false
        when (resolution) {
            is KtSubscriptionResolution.Ready -> {
                blocked = null
                subscriptionText = "KT eSIM 사용 중 (SIM " + (resolution.candidate.logicalSlotIndex + 1) + ")"
            }
            is KtSubscriptionResolution.WrongDefaultData -> {
                blocked = "KT eSIM이 기본 데이터 SIM이 아닙니다."
                subscriptionText = null
            }
            is KtSubscriptionResolution.SlotConfirmationRequired -> {
                blocked = "삼성 메뉴에서 표시되는 SIM 위치를 확인해야 합니다."
                subscriptionText = null
                slotConfirmationRequired = true
            }
            KtSubscriptionResolution.PermissionRequired -> {
                blocked = "전화 상태 읽기 권한이 필요합니다."
                subscriptionText = null
                phonePermissionRequired = true
            }
            else -> {
                blocked = "KT eSIM을 찾을 수 없습니다."
                subscriptionText = null
            }
        }
        return PreflightUi(accessEnabled, subscriptionText, blocked, phonePermissionRequired, slotConfirmationRequired)
    }

    private fun updatePip(state: BandScanState) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE).not()
        ) return
        if (state !is BandScanState.Running) return
        val stopIntent = PendingIntent.getBroadcast(
            this, REQUEST_STOP, Intent(this, StopScanReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopAction = RemoteAction(
            Icon.createWithResource(this, R.drawable.ic_cat_launcher),
            "중지", "실행 중인 스캔을 즉시 중지", stopIntent
        )
        val builder = PictureInPictureParams.Builder().setActions(listOf(stopAction))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setAutoEnterEnabled(true)
        val params = builder.build()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setPictureInPictureParams(params)
            else if (!isInPictureInPictureMode) enterPictureInPictureMode(params)
        }
    }

    private fun updateCarriers(device: Carrier, sim: Carrier) {
        configuration = SettingsConfiguration(device, sim)
        settingsRepository.saveConfiguration(configuration)
        render()
    }

    private fun logText(): String = try {
        openFileInput("logs.txt").use { it.bufferedReader().readText() }
    } catch (error: Exception) { "저장된 로그 기록이 없습니다." }

    private fun writeLog(message: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        try {
            val file = File(filesDir, "logs.txt")
            if (file.length() > 1024 * 1024) file.writeText(file.readText().takeLast(768 * 1024))
            openFileOutput("logs.txt", Context.MODE_APPEND).use {
                it.write(("[" + timestamp + "] " + message + "\n").toByteArray())
            }
        } catch (error: Exception) { Log.e("BandSelectorLog", "log write failed", error) }
    }

    private fun showLogsDialog() {
        AlertDialog.Builder(this)
            .setTitle("누적 시스템 로그 기록")
            .setMessage(logText())
            .setPositiveButton("복사") { _, _ ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("logs", logText()))
            }
            .setNeutralButton("공유") { _, _ -> shareLogFile() }
            .setNegativeButton("삭제") { _, _ -> deleteFile("logs.txt") }
            .show()
    }

    private fun shareLogFile() {
        val file = File(filesDir, "logs.txt")
        if (!file.exists()) {
            Toast.makeText(this, "공유할 로그 파일이 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(
            this, "com.sleepysoong.autobandselector.fileprovider", file
        )
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, "로그 파일 공유"))
    }

    companion object { private const val REQUEST_STOP = 77 }
}

class StopScanReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        (context.applicationContext as? BandSelectorApp)?.runtime?.stop()
    }
}
