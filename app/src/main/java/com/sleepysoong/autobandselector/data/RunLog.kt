package com.sleepysoong.autobandselector.data

import android.content.Context
import android.util.Log
import com.sleepysoong.autobandselector.automation.BandScanState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One process-wide log, shared by the UI, runtime and accessibility service. */
class RunLog(context: Context) {
    private val file = File(context.filesDir, "logs.txt")
    private val content = MutableStateFlow(runCatching {
        if (file.exists()) file.readText() else ""
    }.getOrDefault(""))
    val text = content.asStateFlow()

    @Synchronized fun append(message: String) {
        val safe = message.replace("319712358", "[진입 코드]")
            .replace("774632", "[비밀번호]").replace("*123456#", "[서비스 코드]")
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).format(Date())
        val line = "[$timestamp] $safe\n"
        try {
            if (file.length() + line.toByteArray().size > 1024 * 1024) {
                // Korean text may use three bytes per character; keep rotation bounded in bytes.
                file.writeText(content.value.takeLast(128 * 1024))
            }
            file.appendText(line)
            content.value = file.readText()
        } catch (error: Exception) {
            Log.e("BandSelectorLog", "log write failed", error)
            content.value = content.value.takeLast(128 * 1024) + line + "로그 파일 저장 실패\n"
        }
    }

    @Synchronized fun clear(): Boolean = try {
        file.writeText("")
        content.value = ""
        true
    } catch (error: Exception) {
        Log.e("BandSelectorLog", "log clear failed", error)
        false
    }

    fun record(state: BandScanState) {
        val message = when (state) {
            BandScanState.Idle -> return
            is BandScanState.Running -> "진행: ${state.phase}, 대역=${state.candidate ?: "자동"}"
            is BandScanState.Completed -> "완료: ${state.winner.band}, ${state.winner.medianMbps} Mbps"
            is BandScanState.Failed -> "실패: ${state.reason} / 복구=${state.recovery}"
            is BandScanState.Cancelled -> "실행이 중지되었습니다."
            is BandScanState.Restored -> "자동 모드 복구 완료"
        }
        append(message)
    }
}
