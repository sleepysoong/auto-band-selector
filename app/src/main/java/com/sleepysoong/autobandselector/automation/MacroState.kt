package com.sleepysoong.autobandselector.automation

import java.util.UUID

@JvmInline
value class RunId(val value: UUID)

@JvmInline
value class AttemptId(val value: Long)

enum class RunMode { Scan, Restore }

/** Vocabulary only: candidate selection and verification policy belong to orchestration. */
enum class MacroStage {
    Preflight, EnterMenu, ChooseSimIfShown, ReadBandPage,
    ConfigureCandidate, VerifyCandidate, AwaitCellular, VerifyRegisteredBand,
    MeasureCandidate, ChooseWinner, ApplyWinner, VerifyWinner,
    DisableSelection, NetworkModeAutomatic, VerifyAutomatic
}

enum class RecoveryStatus { UnknownNotRestored, AutomaticVerified }
enum class CancelReason { UserStop, AccessibilityLost, DeviceLocked, OwnerCancelled }

/** One authorized step. Keep this exact value across callbacks; never retag old observations. */
data class MacroAction(
    val runId: RunId,
    val attemptId: AttemptId,
    val mode: RunMode,
    val stage: MacroStage
)

sealed interface FailureReason {
    data object Timeout : FailureReason
    data class EffectError(val type: String, val message: String?) : FailureReason
    data class Rejected(val reason: String) : FailureReason
}

/** A trusted adapter reports verified progress, not merely a click or a network callback. */
sealed interface MacroResult {
    val action: MacroAction

    data class Advance(override val action: MacroAction, val nextStage: MacroStage) : MacroResult
    data class Complete(
        override val action: MacroAction,
        val recoveryStatus: RecoveryStatus = RecoveryStatus.UnknownNotRestored
    ) : MacroResult
    data class Failure(
        override val action: MacroAction,
        val reason: FailureReason,
        val recoveryStatus: RecoveryStatus = RecoveryStatus.UnknownNotRestored
    ) : MacroResult
}

sealed interface MacroState {
    data object Idle : MacroState
    data class Running(val action: MacroAction) : MacroState
    data class Completed(val result: MacroResult.Complete) : MacroState
    data class Failed(val result: MacroResult.Failure) : MacroState
    data class Cancelled(
        val action: MacroAction,
        val reason: CancelReason,
        val recoveryStatus: RecoveryStatus = RecoveryStatus.UnknownNotRestored
    ) : MacroState
}

sealed interface StartResult {
    data class Started(val action: MacroAction) : StartResult
    data class AlreadyRunning(val action: MacroAction) : StartResult
}
