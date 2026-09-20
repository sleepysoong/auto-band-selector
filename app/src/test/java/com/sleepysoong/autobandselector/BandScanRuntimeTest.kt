package com.sleepysoong.autobandselector

import com.sleepysoong.autobandselector.automation.AutomationResult
import com.sleepysoong.autobandselector.automation.BandAutomation
import com.sleepysoong.autobandselector.automation.BandProbe
import com.sleepysoong.autobandselector.automation.BandScanRuntime
import com.sleepysoong.autobandselector.automation.BandScanState
import com.sleepysoong.autobandselector.automation.KtBand
import com.sleepysoong.autobandselector.automation.RestoreResult
import com.sleepysoong.autobandselector.automation.RunId
import com.sleepysoong.autobandselector.automation.RuntimeStart
import com.sleepysoong.autobandselector.automation.SelectedKtSubscription
import com.sleepysoong.autobandselector.network.KtSubscriptionResolution
import com.sleepysoong.autobandselector.network.ProbeOutcome
import com.sleepysoong.autobandselector.network.SubscriptionCandidate
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BandScanRuntimeTest {
    private val ready = KtSubscriptionResolution.Ready(
        SubscriptionCandidate(41, 0, true, "450", "08")
    )

    @Test fun secondStartCannotReplaceActiveOrchestrator() = runTest {
        val gate = CompletableDeferred<AutomationResult>()
        val automation = FakeAutomation { gate.await() }
        val runtime = BandScanRuntime(
            scope = this,
            resolutionSource = { ready },
            historySink = {},
            automationFactory = { automation },
            probeFactory = { BandProbe { _, _, _ -> success(10.0) } }
        )

        assertTrue(runtime.startScan() is RuntimeStart.Started)
        runCurrent()
        assertEquals(RuntimeStart.AlreadyRunning, runtime.startScan())
        assertTrue(runtime.stop())
        assertEquals(1, automation.cancelCount)
    }

    @Test fun oneTerminalRunPublishesOneHistorySummary() = runTest {
        val history = mutableListOf<BandScanState>()
        val runtime = BandScanRuntime(
            scope = this,
            resolutionSource = { ready },
            historySink = { history += it },
            automationFactory = { FakeAutomation { AutomationResult.Verified } },
            probeFactory = { BandProbe { _, band, _ ->
                success(when (band) { KtBand.B1 -> 8.0; KtBand.B3 -> 20.0; KtBand.B8 -> 11.0 })
            } }
        )

        runtime.startScan()
        advanceUntilIdle()

        assertEquals(1, history.size)
        assertTrue(history.single() is BandScanState.Completed)
    }

    private fun success(median: Double) = ProbeOutcome.Success(
        median, listOf(median - 1.0, median, median + 1.0)
    )

    private class FakeAutomation(
        private val apply: suspend (KtBand) -> AutomationResult
    ) : BandAutomation {
        var cancelCount = 0
        override suspend fun applyAndVerify(runId: RunId, subscription: SelectedKtSubscription, band: KtBand) = apply(band)
        override suspend fun verifyRegistered(runId: RunId, subscription: SelectedKtSubscription, band: KtBand) = AutomationResult.Verified
        override suspend fun restoreAutomatic(runId: RunId, subscription: SelectedKtSubscription) = RestoreResult.Verified
        override fun cancel(runId: RunId) { cancelCount++ }
    }
}
