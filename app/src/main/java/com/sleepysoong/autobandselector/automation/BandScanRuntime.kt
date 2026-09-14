package com.sleepysoong.autobandselector.automation

import android.content.Intent
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
    private val launch: (Intent) -> Unit
) : BandAutomation {

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

    override fun cancel(runId: RunId) = RuntimeBridge.revoke()

    private suspend fun operation(
        mode: RunMode,
        initialStage: MacroStage,
        subscription: SelectedKtSubscription,
        band: KtBand?
    ): AutomationResult {
        val done = CompletableDeferred<MacroState>()
        val coordinator = RunCoordinator(scope, STEP_TIMEOUT_MS) { awaitCancellation() }
        val start = coordinator.start(mode, initialStage) as? StartResult.Started
            ?: return AutomationResult.Failed("coordinator unavailable")
        scope.launch {
            done.complete(coordinator.state.first { terminal ->
                terminal is MacroState.Completed || terminal is MacroState.Failed ||
                    terminal is MacroState.Cancelled
            })
        }
        val binding = RuntimeBridge.RunBinding.fromCoordinator(
            coordinator, parser, subscription.logicalSlotIndex, band?.number
        )
        val request = RuntimeBridge.installRun(binding, resolver)
        if (request == null) {
            RuntimeBridge.detach()
            return AutomationResult.Unsupported("verified Samsung Phone entry is unavailable")
        }
        launch(request)
        val terminal = try {
            withTimeout(OPERATION_TIMEOUT_MS) { done.await() }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            RuntimeBridge.revoke()
            (coordinator.state.value as? MacroState.Running)?.action?.let {
                coordinator.stop(it, CancelReason.OwnerCancelled)
            }
            return AutomationResult.Failed("operation timed out")
        }
        RuntimeBridge.detach()
        return when (terminal) {
            is MacroState.Completed -> AutomationResult.Verified
            is MacroState.Failed -> AutomationResult.Failed(
                when (val reason = terminal.result.reason) {
                    FailureReason.Timeout -> "timeout"
                    is FailureReason.EffectError -> reason.message ?: "effect failed"
                    is FailureReason.Rejected -> reason.reason
                })
            is MacroState.Cancelled -> AutomationResult.Failed("cancelled")
            else -> AutomationResult.Failed("runtime became idle before completion")
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
    private val resolver: KtSubscriptionResolver,
    private val settings: SettingsRepository,
    private val automationFactory: (SelectedKtSubscription) -> BandAutomation,
    private val probeFactory: (SelectedKtSubscription) -> BandProbe
) {
    private val orchestratorScope = CoroutineScope(scope.coroutineContext + SupervisorJob())
    private var current: BandScanOrchestrator? = null

    private val published = kotlinx.coroutines.flow.MutableStateFlow<BandScanOrchestrator?>(null)

    /** Combined visible state: Idle until the first Start in this process. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val state: Flow<BandScanState> =
        published.flatMapLatest { it?.state ?: flowOf(BandScanState.Idle) }

    @Synchronized fun orchestrator(): BandScanOrchestrator =
        current ?: throw IllegalStateException("Runtime start was never requested")

    @Synchronized fun startScan(): RuntimeStart =
        when (val resolution = resolver.resolve(settings.confirmedLogicalSlotIndex)) {
            KtSubscriptionResolution.PermissionRequired ->
                RuntimeStart.Blocked("READ_PHONE_STATE")
            is KtSubscriptionResolution.WrongDefaultData ->
                RuntimeStart.Blocked("wrong default data subscription")
            is KtSubscriptionResolution.SlotConfirmationRequired ->
                RuntimeStart.Blocked("slot confirmation required")
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
                        RuntimeStart.Started(started.runId)
                    }
                    is BandScanStart.AlreadyRunning -> RuntimeStart.AlreadyRunning
                }
            }
            else -> RuntimeStart.Blocked(resolution.javaClass.simpleName)
        }

    @Synchronized fun restore(): RuntimeStart {
        val current = current ?: return RuntimeStart.Blocked("no previous run boundary")
        val resolution = resolver.resolve(settings.confirmedLogicalSlotIndex)
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
}
