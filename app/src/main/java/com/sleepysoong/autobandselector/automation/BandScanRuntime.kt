package com.sleepysoong.autobandselector.automation

import android.content.Intent
import com.sleepysoong.autobandselector.data.BandMeasurement
import com.sleepysoong.autobandselector.data.HistoryOutcome
import com.sleepysoong.autobandselector.data.LteBand
import com.sleepysoong.autobandselector.data.RunHistoryEntry
import com.sleepysoong.autobandselector.data.SettingsRepository
import com.sleepysoong.autobandselector.network.KtSubscriptionResolution
import com.sleepysoong.autobandselector.network.KtSubscriptionResolver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext

sealed interface RuntimeStart {
    data class Started(val runId: RunId) : RuntimeStart
    data object AlreadyRunning : RuntimeStart
    data class Blocked(val reason: String, val settingsIntent: Intent? = null) : RuntimeStart
}

/**
 * Production BandAutomation: every unit of radio interaction is one process-local
 * RunCoordinator run, authorized through RuntimeBridge and observed by the accessibility
 * service. The Activity launches only the verified Samsung ACTION_DIAL request.
 */
class RuntimeBandAutomation(
    private val scope: CoroutineScope,
    private val parser: SamsungScreenParser,
    private val resolver: SamsungPhoneActivityResolver,
    private val log: (String) -> Unit = {},
    private val launch: (Intent) -> Unit
) : BandAutomation {
    @Volatile private var activeBinding: RuntimeBridge.RunBinding? = null

    override suspend fun applyAndVerify(
        runId: RunId,
        subscription: SelectedKtSubscription,
        band: KtBand
    ) = operation(RunMode.Scan, MacroStage.EnterMenu, subscription, band)

    override suspend fun verifyRegistered(
        runId: RunId,
        subscription: SelectedKtSubscription,
        band: KtBand
    ) = operation(RunMode.Scan, MacroStage.VerifyRegisteredBand, subscription, band)

    override suspend fun restoreAutomatic(
        runId: RunId,
        subscription: SelectedKtSubscription
    ): RestoreResult = when (
        val result = operation(RunMode.Restore, MacroStage.EnterMenu, subscription, null)
    ) {
        AutomationResult.Verified -> RestoreResult.Verified
        is AutomationResult.Unsupported -> RestoreResult.Failed(result.reason)
        is AutomationResult.Failed -> RestoreResult.Failed(result.reason)
    }

    override fun cancel(runId: RunId) {
        activeBinding?.let(RuntimeBridge::revoke)
    }

    private suspend fun operation(
        mode: RunMode,
        initialStage: MacroStage,
        subscription: SelectedKtSubscription,
        band: KtBand?
    ): AutomationResult {
        if (subscription.logicalSlotIndex !in 0..1) {
            return AutomationResult.Unsupported("지원하지 않는 SIM 슬롯입니다.")
        }
        val done = CompletableDeferred<MacroState>()
        val coordinator = RunCoordinator(scope, STEP_TIMEOUT_MS) { awaitCancellation() }
        coordinator.start(mode, initialStage) as? StartResult.Started
            ?: return AutomationResult.Failed("coordinator unavailable")
        scope.launch {
            done.complete(coordinator.state.first { terminal ->
                terminal is MacroState.Completed || terminal is MacroState.Failed ||
                    terminal is MacroState.Cancelled
            })
        }
        val binding = RuntimeBridge.RunBinding.fromCoordinator(
            coordinator, parser, subscription.logicalSlotIndex + 1, band?.number
        )
        activeBinding = binding
        return try {
            log("삼성 전화 앱 확인: SIM ${subscription.logicalSlotIndex + 1}, 단계=$initialStage")
            val request = RuntimeBridge.installRun(binding, resolver)
            if (request == null) {
                log("삼성 전화 앱 진입 실패: 지원되는 전화 화면을 찾을 수 없습니다.")
                return AutomationResult.Unsupported("삼성 전화 앱을 찾을 수 없거나 지원되지 않는 진입 화면입니다.")
            }
            withContext(Dispatchers.Main.immediate) { launch(request) }
            log("삼성 전화 앱 실행 요청 완료. 접근성 화면 응답을 기다립니다.")
            val terminal = withTimeout(OPERATION_TIMEOUT_MS) { done.await() }
            when (terminal) {
                is MacroState.Completed -> AutomationResult.Verified
                is MacroState.Failed -> AutomationResult.Failed(
                    when (val reason = terminal.result.reason) {
                        FailureReason.Timeout -> "${terminal.result.action.stage}: 15초 안에 접근성 화면 응답이 없습니다."
                        is FailureReason.EffectError -> reason.message ?: "effect failed"
                        is FailureReason.Rejected -> reason.reason
                    })
                is MacroState.Cancelled -> AutomationResult.Failed("접근성 실행 중단: ${terminal.reason}")
                else -> AutomationResult.Failed("runtime became idle before completion")
            }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            AutomationResult.Failed("operation timed out")
        } finally {
            RuntimeBridge.detach(binding)
            if (activeBinding === binding) activeBinding = null
            (coordinator.state.value as? MacroState.Running)?.action?.let {
                coordinator.stop(it, CancelReason.OwnerCancelled)
            }
        }
    }

    private companion object {
        const val STEP_TIMEOUT_MS = 15_000L
        const val OPERATION_TIMEOUT_MS = 180_000L
    }
}

/**
 * Entry-point wiring used by MainActivity: fresh subscription resolution each run,
 * history persistence and log hooks stay outside. No state survives process death.
 */
class BandScanRuntime(
    private val scope: CoroutineScope,
    private val resolutionSource: () -> KtSubscriptionResolution,
    private val historySink: (BandScanState) -> Unit = {},
    private val automationFactory: (SelectedKtSubscription) -> BandAutomation,
    private val probeFactory: (SelectedKtSubscription) -> BandProbe
) {
    constructor(
        scope: CoroutineScope,
        resolver: KtSubscriptionResolver,
        settings: SettingsRepository,
        automationFactory: (SelectedKtSubscription) -> BandAutomation,
        probeFactory: (SelectedKtSubscription) -> BandProbe
    ) : this(
        scope = scope,
        resolutionSource = { resolver.resolve(settings.confirmedLogicalSlotIndex) },
        historySink = { terminal -> persistHistory(settings, terminal) },
        automationFactory = automationFactory,
        probeFactory = probeFactory
    )

    private val orchestratorScope = CoroutineScope(scope.coroutineContext + SupervisorJob())
    private var current: BandScanOrchestrator? = null
    private val published = kotlinx.coroutines.flow.MutableStateFlow<BandScanOrchestrator?>(null)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val state: Flow<BandScanState> =
        published.flatMapLatest { it?.state ?: flowOf(BandScanState.Idle) }

    @Synchronized fun startScan(): RuntimeStart {
        if (current?.state?.value is BandScanState.Running) return RuntimeStart.AlreadyRunning
        return when (val resolution = resolutionSource()) {
            KtSubscriptionResolution.PermissionRequired -> RuntimeStart.Blocked("READ_PHONE_STATE")
            is KtSubscriptionResolution.WrongDefaultData -> RuntimeStart.Blocked("wrong default data subscription")
            is KtSubscriptionResolution.SlotConfirmationRequired -> RuntimeStart.Blocked("slot confirmation required")
            is KtSubscriptionResolution.Ready -> {
                val selected = SelectedKtSubscription(
                    resolution.candidate.subscriptionId, resolution.candidate.logicalSlotIndex
                )
                val orchestrator = BandScanOrchestrator(
                    orchestratorScope, automationFactory(selected), probeFactory(selected)
                )
                when (val started = orchestrator.start(selected)) {
                    is BandScanStart.Started -> {
                        current = orchestrator
                        published.value = orchestrator
                        observeTerminal(orchestrator)
                        RuntimeStart.Started(started.runId)
                    }
                    is BandScanStart.AlreadyRunning -> RuntimeStart.AlreadyRunning
                }
            }
            else -> RuntimeStart.Blocked(resolution.javaClass.simpleName)
        }
    }

    @Synchronized fun restore(): RuntimeStart {
        val current = current ?: return RuntimeStart.Blocked("no previous run boundary")
        val resolution = resolutionSource()
        val ready = resolution as? KtSubscriptionResolution.Ready
            ?: return RuntimeStart.Blocked("restore preflight failed: " + resolution.javaClass.simpleName)
        val selected = SelectedKtSubscription(ready.candidate.subscriptionId, ready.candidate.logicalSlotIndex)
        published.value = current
        return when (val started = current.restore(selected)) {
            is BandScanStart.Started -> RuntimeStart.Started(started.runId)
            is BandScanStart.AlreadyRunning -> RuntimeStart.AlreadyRunning
        }
    }

    @Synchronized fun stop(): Boolean {
        val orchestrator = current ?: return false
        val running = (orchestrator.state.value as? BandScanState.Running) ?: return false
        return orchestrator.stop(running.runId)
    }

    private fun observeTerminal(orchestrator: BandScanOrchestrator) {
        scope.launch {
            val terminal = orchestrator.state.first {
                it is BandScanState.Completed || it is BandScanState.Failed ||
                    it is BandScanState.Cancelled
            }
            historySink(terminal)
        }
    }

    private companion object {
        fun persistHistory(settings: SettingsRepository, terminal: BandScanState) {
            val results = when (terminal) {
                is BandScanState.Completed -> terminal.results
                is BandScanState.Failed -> terminal.results
                is BandScanState.Cancelled -> terminal.results
                else -> return
            }
            val outcome = when (terminal) {
                is BandScanState.Completed -> HistoryOutcome.COMPLETED
                is BandScanState.Failed -> HistoryOutcome.FAILED
                is BandScanState.Cancelled -> HistoryOutcome.CANCELLED
                BandScanState.Idle, is BandScanState.Restored, is BandScanState.Running -> return
            }
            val measurements = results.mapNotNull { result ->
                val valid = result.outcome as? CandidateOutcome.Valid ?: return@mapNotNull null
                BandMeasurement(
                    when (result.band) {
                        KtBand.B1 -> LteBand.LTE_B1
                        KtBand.B3 -> LteBand.LTE_B3
                        KtBand.B8 -> LteBand.LTE_B8
                    },
                    valid.medianMbps
                )
            }
            settings.appendHistory(RunHistoryEntry(System.currentTimeMillis(), outcome, measurements))
        }
    }
}
