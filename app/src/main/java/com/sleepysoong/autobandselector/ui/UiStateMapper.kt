package com.sleepysoong.autobandselector.ui

import com.sleepysoong.autobandselector.automation.BandScanState
import com.sleepysoong.autobandselector.automation.CandidateOutcome
import com.sleepysoong.autobandselector.automation.RestoreResult
import com.sleepysoong.autobandselector.automation.ScanPhase
import com.sleepysoong.autobandselector.data.SettingsConfiguration

/** Accessibility + KT eSIM preflight as observed facts; never an authorization channel. */
data class PreflightUi(
    val accessibilityEnabled: Boolean,
    val subscriptionText: String?,
    val blocked: String?
)

data class BandRowUi(val band: Int, val status: String, val medianText: String?, val failureText: String?)

data class GlassUiState(
    val startEnabled: Boolean,
    val stopVisible: Boolean,
    val restoreEnabled: Boolean,
    val phase: ScanPhase?,
    val candidateBand: Int?,
    val winnerBand: Int?,
    val failureReason: String?,
    val recoveryText: String?,
    val cancelled: Boolean,
    val accessibilityActionVisible: Boolean,
    val simSettingsVisible: Boolean,
    val blockedReason: String?,
    val payloadNoticeBytes: Long,
    val rows: List<BandRowUi>
)

/** The comparison downloads at most three 3 MB samples per one of B1/B3/B8 on every Start. */
const val MAX_PAYLOAD_BYTES: Long = 27_000_000L

object UiStateMapper {
    fun map(
        state: BandScanState,
        preflight: PreflightUi,
        configuration: SettingsConfiguration
    ): GlassUiState {
        val preflightBlocked = !preflight.accessibilityEnabled || preflight.blocked != null
        val running = state as? BandScanState.Running
        val completed = state as? BandScanState.Completed
        val failed = state as? BandScanState.Failed
        val cancelled = state is BandScanState.Cancelled
        return GlassUiState(
            startEnabled = running == null && !preflightBlocked,
            stopVisible = running != null,
            restoreEnabled = running == null,
            phase = running?.phase,
            candidateBand = running?.candidate?.number,
            winnerBand = completed?.winner?.band?.number,
            failureReason = failed?.reason,
            recoveryText = when (val recovery = failed?.recovery) {
                null, RestoreResult.NotAttempted -> null
                is RestoreResult.Failed -> recovery.reason
                RestoreResult.Verified -> "Automatic"
            },
            cancelled = cancelled,
            accessibilityActionVisible = !preflight.accessibilityEnabled,
            simSettingsVisible = preflight.blocked != null,
            blockedReason = preflight.blocked,
            payloadNoticeBytes = MAX_PAYLOAD_BYTES,
            rows = currentResults(state).map { result ->
                when (val outcome = result.outcome) {
                    is CandidateOutcome.Valid -> BandRowUi(
                        result.band.number, "valid",
                        medianText = trim(outcome.medianMbps), failureText = null
                    )
                    is CandidateOutcome.Unsupported -> BandRowUi(
                        result.band.number, "unsupported", null, outcome.reason
                    )
                    is CandidateOutcome.Failed -> BandRowUi(
                        result.band.number, "failed", null, outcome.reason
                    )
                }
            }
        )
    }

    private fun currentResults(state: BandScanState) = when (state) {
        is BandScanState.Running -> state.results
        is BandScanState.Completed -> state.results
        is BandScanState.Failed -> state.results
        is BandScanState.Cancelled -> state.results
        else -> emptyList()
    }

    private fun trim(value: Double): String =
        java.util.Locale.US.let { String.format(it, "%.1f", value) }
}
