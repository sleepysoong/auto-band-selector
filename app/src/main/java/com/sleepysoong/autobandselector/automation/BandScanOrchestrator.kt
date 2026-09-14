package com.sleepysoong.autobandselector.automation

import com.sleepysoong.autobandselector.network.ProbeOutcome
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class KtBand(val number: Int) {
    B1(1),
    B3(3),
    B8(8)
}

data class SelectedKtSubscription(
    val subscriptionId: Int,
    val logicalSlotIndex: Int
) {
    init {
        require(subscriptionId >= 0)
        require(logicalSlotIndex >= 0)
    }
}

sealed interface AutomationResult {
    data object Verified : AutomationResult
    data class Unsupported(val reason: String) : AutomationResult
    data class Failed(val reason: String) : AutomationResult
}

sealed interface RestoreResult {
    data object Verified : RestoreResult
    data class Failed(val reason: String) : RestoreResult
    data object NotAttempted : RestoreResult
}

interface BandAutomation {
    suspend fun applyAndVerify(
        runId: RunId,
        subscription: SelectedKtSubscription,
        band: KtBand
    ): AutomationResult

    suspend fun verifyRegistered(
        runId: RunId,
        subscription: SelectedKtSubscription,
        band: KtBand
    ): AutomationResult

    suspend fun restoreAutomatic(
        runId: RunId,
        subscription: SelectedKtSubscription
    ): RestoreResult

    fun cancel(runId: RunId)
}

fun interface BandProbe {
    suspend fun measure(
        subscriptionId: Int,
        band: KtBand,
        sampleBatchId: UUID
    ): ProbeOutcome
}

sealed interface CandidateOutcome {
    data class Valid(val medianMbps: Double, val samplesMbps: List<Double>) : CandidateOutcome
    data class Unsupported(val reason: String) : CandidateOutcome
    data class Failed(val reason: String) : CandidateOutcome
}

data class CandidateResult(
    val band: KtBand,
    val sampleBatchId: UUID,
    val outcome: CandidateOutcome
)

data class BandWinner(val band: KtBand, val medianMbps: Double)

enum class ScanPhase {
    ApplyingCandidate,
    MeasuringCandidate,
    VerifyingCandidate,
    RestoringAfterFailure,
    ApplyingWinner,
    VerifyingWinner,
    RestoringAutomatic
}

sealed interface BandScanState {
    data object Idle : BandScanState
    data class Running(
        val runId: RunId,
        val phase: ScanPhase,
        val candidate: KtBand?,
        val results: List<CandidateResult>
    ) : BandScanState

    data class Completed(
        val runId: RunId,
        val winner: BandWinner,
        val results: List<CandidateResult>
    ) : BandScanState

    data class Restored(val runId: RunId) : BandScanState

    data class Failed(
        val runId: RunId,
        val reason: String,
        val results: List<CandidateResult>,
        val winner: BandWinner? = null,
        val recovery: RestoreResult = RestoreResult.NotAttempted
    ) : BandScanState

    data class Cancelled(val runId: RunId, val results: List<CandidateResult>) : BandScanState
}

sealed interface BandScanStart {
    val runId: RunId

    data class Started(override val runId: RunId) : BandScanStart
    data class AlreadyRunning(override val runId: RunId) : BandScanStart
}

/**
 * Owns fresh per-run ranking policy. It never consumes persisted measurements as runnable input.
 * Android UI and accessibility details stay behind [BandAutomation].
 */
class BandScanOrchestrator(
    private val scope: CoroutineScope,
    private val automation: BandAutomation,
    private val probe: BandProbe,
    private val runIdFactory: () -> RunId = { RunId(UUID.randomUUID()) },
    private val sampleIdFactory: () -> UUID = UUID::randomUUID
) {
    private data class ActiveRun(val runId: RunId, val job: Job)

    private val lock = Any()
    private val mutableState = MutableStateFlow<BandScanState>(BandScanState.Idle)
    val state: StateFlow<BandScanState> = mutableState.asStateFlow()
    private var active: ActiveRun? = null

    fun start(subscription: SelectedKtSubscription): BandScanStart =
        launchRun { runId -> scan(runId, subscription) }

    fun restore(subscription: SelectedKtSubscription): BandScanStart =
        launchRun { runId -> restoreOnly(runId, subscription) }

    fun stop(runId: RunId): Boolean {
        val stopped = synchronized(lock) {
            val current = active ?: return false
            if (current.runId != runId) return false
            active = null
            val results = when (val currentState = mutableState.value) {
                is BandScanState.Running -> currentState.results
                else -> emptyList()
            }
            mutableState.value = BandScanState.Cancelled(runId, results)
            current
        }
        stopped.job.cancel()
        automation.cancel(runId)
        return true
    }

    private fun launchRun(block: suspend (RunId) -> Unit): BandScanStart {
        val installed = synchronized(lock) {
            active?.let { return BandScanStart.AlreadyRunning(it.runId) }
            val runId = runIdFactory()
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    block(runId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    failIfCurrent(runId, failure.message ?: failure.javaClass.simpleName, emptyList())
                }
            }
            ActiveRun(runId, job).also { active = it }
        }
        installed.job.start()
        return BandScanStart.Started(installed.runId)
    }

    private suspend fun scan(runId: RunId, subscription: SelectedKtSubscription) {
        val results = mutableListOf<CandidateResult>()
        for (band in CANDIDATES) {
            if (!publishRunning(runId, ScanPhase.ApplyingCandidate, band, results)) return
            when (val applied = automation.applyAndVerify(runId, subscription, band)) {
                is AutomationResult.Unsupported -> {
                    results += CandidateResult(band, sampleIdFactory(), CandidateOutcome.Unsupported(applied.reason))
                    continue
                }
                is AutomationResult.Failed -> {
                    failIfCurrent(runId, applied.reason, results)
                    return
                }
                AutomationResult.Verified -> Unit
            }

            val sampleBatchId = sampleIdFactory()
            if (!publishRunning(runId, ScanPhase.MeasuringCandidate, band, results)) return
            when (val measured = probe.measure(subscription.subscriptionId, band, sampleBatchId)) {
                is ProbeOutcome.Failure -> {
                    results += CandidateResult(
                        band,
                        sampleBatchId,
                        CandidateOutcome.Failed(measured.kind.javaClass.simpleName)
                    )
                    if (!restoreBeforeContinuing(runId, subscription, band, results)) return
                    continue
                }
                is ProbeOutcome.Success -> {
                    if (!publishRunning(runId, ScanPhase.VerifyingCandidate, band, results)) return
                    when (val verified = automation.verifyRegistered(runId, subscription, band)) {
                        is AutomationResult.Failed -> {
                            val recovery = restoreNow(runId, subscription, band, results)
                            failIfCurrent(runId, verified.reason, results, recovery = recovery)
                            return
                        }
                        is AutomationResult.Unsupported -> {
                            val recovery = restoreNow(runId, subscription, band, results)
                            failIfCurrent(runId, verified.reason, results, recovery = recovery)
                            return
                        }
                        AutomationResult.Verified -> results += CandidateResult(
                            band,
                            sampleBatchId,
                            CandidateOutcome.Valid(measured.medianMbps, measured.samplesMbps)
                        )
                    }
                }
            }
        }

        val winner = chooseWinner(results)
        if (winner == null) {
            val recovery = restoreNow(runId, subscription, null, results)
            failIfCurrent(runId, "no valid candidate", results, recovery = recovery)
            return
        }

        if (!publishRunning(runId, ScanPhase.ApplyingWinner, winner.band, results)) return
        when (val applied = automation.applyAndVerify(runId, subscription, winner.band)) {
            is AutomationResult.Failed -> {
                val recovery = restoreNow(runId, subscription, winner.band, results)
                failIfCurrent(runId, applied.reason, results, winner, recovery)
                return
            }
            is AutomationResult.Unsupported -> {
                val recovery = restoreNow(runId, subscription, winner.band, results)
                failIfCurrent(runId, applied.reason, results, winner, recovery)
                return
            }
            AutomationResult.Verified -> Unit
        }

        if (!publishRunning(runId, ScanPhase.VerifyingWinner, winner.band, results)) return
        when (val verified = automation.verifyRegistered(runId, subscription, winner.band)) {
            is AutomationResult.Failed -> {
                val recovery = restoreNow(runId, subscription, winner.band, results)
                failIfCurrent(runId, verified.reason, results, winner, recovery)
            }
            is AutomationResult.Unsupported -> {
                val recovery = restoreNow(runId, subscription, winner.band, results)
                failIfCurrent(runId, verified.reason, results, winner, recovery)
            }
            AutomationResult.Verified -> completeIfCurrent(runId, winner, results)
        }
    }

    private suspend fun restoreOnly(runId: RunId, subscription: SelectedKtSubscription) {
        if (!publishRunning(runId, ScanPhase.RestoringAutomatic, null, emptyList())) return
        when (val restored = automation.restoreAutomatic(runId, subscription)) {
            RestoreResult.Verified -> synchronized(lock) {
                if (active?.runId == runId) {
                    active = null
                    mutableState.value = BandScanState.Restored(runId)
                }
            }
            is RestoreResult.Failed -> failIfCurrent(
                runId,
                restored.reason,
                emptyList(),
                recovery = restored
            )
            RestoreResult.NotAttempted -> failIfCurrent(
                runId,
                "restore was not attempted",
                emptyList()
            )
        }
    }

    private suspend fun restoreBeforeContinuing(
        runId: RunId,
        subscription: SelectedKtSubscription,
        band: KtBand,
        results: List<CandidateResult>
    ): Boolean {
        val recovery = restoreNow(runId, subscription, band, results)
        if (recovery == RestoreResult.Verified) return isCurrent(runId)
        val reason = (recovery as? RestoreResult.Failed)?.reason ?: "restore was not attempted"
        failIfCurrent(runId, reason, results, recovery = recovery)
        return false
    }

    private suspend fun restoreNow(
        runId: RunId,
        subscription: SelectedKtSubscription,
        band: KtBand?,
        results: List<CandidateResult>
    ): RestoreResult {
        if (!publishRunning(runId, ScanPhase.RestoringAfterFailure, band, results)) {
            return RestoreResult.NotAttempted
        }
        return automation.restoreAutomatic(runId, subscription)
    }

    private fun chooseWinner(results: List<CandidateResult>): BandWinner? {
        var winner: BandWinner? = null
        for (candidate in CANDIDATES) {
            val valid = results.firstOrNull { it.band == candidate }?.outcome as? CandidateOutcome.Valid
                ?: continue
            if (winner == null || valid.medianMbps > winner.medianMbps) {
                winner = BandWinner(candidate, valid.medianMbps)
            }
        }
        return winner
    }

    private fun publishRunning(
        runId: RunId,
        phase: ScanPhase,
        candidate: KtBand?,
        results: List<CandidateResult>
    ): Boolean = synchronized(lock) {
        if (active?.runId != runId) return false
        mutableState.value = BandScanState.Running(runId, phase, candidate, results.toList())
        true
    }

    private fun completeIfCurrent(
        runId: RunId,
        winner: BandWinner,
        results: List<CandidateResult>
    ) {
        synchronized(lock) {
            if (active?.runId != runId) return
            active = null
            mutableState.value = BandScanState.Completed(runId, winner, results.toList())
        }
    }

    private fun failIfCurrent(
        runId: RunId,
        reason: String,
        results: List<CandidateResult>,
        winner: BandWinner? = null,
        recovery: RestoreResult = RestoreResult.NotAttempted
    ) {
        synchronized(lock) {
            if (active?.runId != runId) return
            active = null
            mutableState.value = BandScanState.Failed(
                runId,
                reason,
                results.toList(),
                winner,
                recovery
            )
        }
    }

    private fun isCurrent(runId: RunId): Boolean = synchronized(lock) {
        active?.runId == runId
    }

    private companion object {
        val CANDIDATES = listOf(KtBand.B1, KtBand.B3, KtBand.B8)
    }
}
