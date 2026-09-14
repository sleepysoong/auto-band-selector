package com.sleepysoong.autobandselector

import com.sleepysoong.autobandselector.automation.AutomationResult
import com.sleepysoong.autobandselector.automation.BandAutomation
import com.sleepysoong.autobandselector.automation.BandProbe
import com.sleepysoong.autobandselector.automation.BandScanOrchestrator
import com.sleepysoong.autobandselector.automation.BandScanStart
import com.sleepysoong.autobandselector.automation.BandScanState
import com.sleepysoong.autobandselector.automation.KtBand
import com.sleepysoong.autobandselector.automation.RestoreResult
import com.sleepysoong.autobandselector.automation.RunId
import com.sleepysoong.autobandselector.automation.SelectedKtSubscription
import com.sleepysoong.autobandselector.network.ProbeFailure
import com.sleepysoong.autobandselector.network.ProbeOutcome
import java.util.ArrayDeque
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BandScanIntegrationTest {
    private val subscription = SelectedKtSubscription(subscriptionId = 41, logicalSlotIndex = 0)

    @Test
    fun freshResultsChooseWinnerOnEveryStart() = runTest {
        val automation = FakeAutomation()
        val probe = FakeProbe(
            mutableMapOf(
                KtBand.B1 to ArrayDeque(listOf(8.0, 15.0)),
                KtBand.B3 to ArrayDeque(listOf(20.0, 5.0)),
                KtBand.B8 to ArrayDeque(listOf(11.0, 9.0))
            )
        )
        val orchestrator = BandScanOrchestrator(this, automation, probe)

        val first = orchestrator.start(subscription) as BandScanStart.Started
        advanceUntilIdle()
        val firstResult = orchestrator.state.value as BandScanState.Completed
        assertEquals(KtBand.B3, firstResult.winner.band)

        val second = orchestrator.start(subscription) as BandScanStart.Started
        advanceUntilIdle()
        val secondResult = orchestrator.state.value as BandScanState.Completed
        assertEquals(KtBand.B1, secondResult.winner.band)
        assertNotEquals(first.runId, second.runId)
        assertTrue(firstResult.results.zip(secondResult.results).all { (a, b) ->
            a.sampleBatchId != b.sampleBatchId
        })
        assertEquals(
            listOf(KtBand.B1, KtBand.B3, KtBand.B8, KtBand.B3,
                KtBand.B1, KtBand.B3, KtBand.B8, KtBand.B1),
            automation.appliedBands
        )
    }

    @Test
    fun exactTieChoosesEarlierCandidateB1() = runTest {
        val orchestrator = BandScanOrchestrator(
            this,
            FakeAutomation(),
            FakeProbe(scores(B1 = 10.0, B3 = 10.0, B8 = 10.0))
        )

        orchestrator.start(subscription)
        advanceUntilIdle()

        assertEquals(KtBand.B1, (orchestrator.state.value as BandScanState.Completed).winner.band)
    }

    @Test
    fun candidateNetworkFailureRestoresBeforeTryingNextCandidate() = runTest {
        val automation = FakeAutomation()
        val probe = FakeProbe(
            scores(B1 = null, B3 = 20.0, B8 = 11.0),
            failure = ProbeFailure.NetworkUnavailable
        )
        val orchestrator = BandScanOrchestrator(this, automation, probe)

        orchestrator.start(subscription)
        advanceUntilIdle()

        assertEquals(
            listOf("apply:B1", "restore", "apply:B3", "verify:B3",
                "apply:B8", "verify:B8", "apply:B3", "verify:B3"),
            automation.trace
        )
        assertEquals(KtBand.B3, (orchestrator.state.value as BandScanState.Completed).winner.band)
    }

    @Test
    fun allCandidateFailuresRestoreAndNeverPublishWinner() = runTest {
        val automation = FakeAutomation()
        val orchestrator = BandScanOrchestrator(
            this,
            automation,
            FakeProbe(scores(B1 = null, B3 = null, B8 = null), ProbeFailure.Timeout)
        )

        orchestrator.start(subscription)
        advanceUntilIdle()

        val failed = orchestrator.state.value as BandScanState.Failed
        assertEquals(null, failed.winner)
        assertEquals(RestoreResult.Verified, failed.recovery)
        assertEquals(4, automation.trace.count { it == "restore" })
    }

    @Test
    fun staleProbeCompletionAfterStopCannotChangeStateOrApplyWinner() = runTest {
        val gate = CompletableDeferred<Unit>()
        val automation = FakeAutomation()
        val probe = BandProbe { _, band, _ ->
            if (band == KtBand.B1) withContext(NonCancellable) { gate.await() }
            ProbeOutcome.Success(12.0, listOf(12.0, 12.0, 12.0))
        }
        val orchestrator = BandScanOrchestrator(this, automation, probe)

        val started = orchestrator.start(subscription) as BandScanStart.Started
        testScheduler.runCurrent()
        assertTrue(orchestrator.stop(started.runId))
        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue(orchestrator.state.value is BandScanState.Cancelled)
        assertEquals(listOf(KtBand.B1), automation.appliedBands)
        assertFalse(automation.trace.any { it == "verify:B1" })
    }

    @Test
    fun finalApplyFailureRestoresAndCannotComplete() = runTest {
        val automation = FakeAutomation(
            applyResults = mutableMapOf(
                KtBand.B3 to ArrayDeque(
                    listOf(AutomationResult.Verified, AutomationResult.Failed("final apply"))
                )
            )
        )
        val orchestrator = BandScanOrchestrator(
            this,
            automation,
            FakeProbe(scores(B1 = 8.0, B3 = 20.0, B8 = 11.0))
        )

        orchestrator.start(subscription)
        advanceUntilIdle()

        val failed = orchestrator.state.value as BandScanState.Failed
        assertEquals("final apply", failed.reason)
        assertEquals(RestoreResult.Verified, failed.recovery)
        assertFalse(orchestrator.state.value is BandScanState.Completed)
        assertEquals("restore", automation.trace.last())
    }

    @Test
    fun structuralDriverFailureStopsWithoutTryingAnotherCandidate() = runTest {
        val automation = FakeAutomation(
            applyResults = mutableMapOf(
                KtBand.B1 to ArrayDeque(listOf(AutomationResult.Failed("wrong SIM")))
            )
        )
        val orchestrator = BandScanOrchestrator(
            this,
            automation,
            FakeProbe(scores(B1 = 8.0, B3 = 20.0, B8 = 11.0))
        )

        orchestrator.start(subscription)
        advanceUntilIdle()

        assertEquals("wrong SIM", (orchestrator.state.value as BandScanState.Failed).reason)
        assertEquals(listOf(KtBand.B1), automation.appliedBands)
    }

    private fun scores(
        B1: Double?,
        B3: Double?,
        B8: Double?
    ): MutableMap<KtBand, ArrayDeque<Double>> = mutableMapOf<KtBand, ArrayDeque<Double>>().apply {
        B1?.let { put(KtBand.B1, ArrayDeque(listOf(it))) }
        B3?.let { put(KtBand.B3, ArrayDeque(listOf(it))) }
        B8?.let { put(KtBand.B8, ArrayDeque(listOf(it))) }
    }

    private class FakeProbe(
        private val scores: MutableMap<KtBand, ArrayDeque<Double>>,
        private val failure: ProbeFailure = ProbeFailure.NetworkUnavailable
    ) : BandProbe {
        override suspend fun measure(
            subscriptionId: Int,
            band: KtBand,
            sampleBatchId: UUID
        ): ProbeOutcome {
            assertEquals(41, subscriptionId)
            val score = scores[band]?.pollFirst()
                ?: return ProbeOutcome.Failure(failure)
            return ProbeOutcome.Success(score, listOf(score - 1, score, score + 1))
        }
    }

    private class FakeAutomation(
        private val applyResults: MutableMap<KtBand, ArrayDeque<AutomationResult>> = mutableMapOf(),
        private val verifyResults: MutableMap<KtBand, ArrayDeque<AutomationResult>> = mutableMapOf(),
        private val restoreResults: ArrayDeque<RestoreResult> = ArrayDeque()
    ) : BandAutomation {
        val trace = mutableListOf<String>()
        val appliedBands = mutableListOf<KtBand>()

        override suspend fun applyAndVerify(
            runId: RunId,
            subscription: SelectedKtSubscription,
            band: KtBand
        ): AutomationResult {
            trace += "apply:${band.name}"
            appliedBands += band
            return applyResults[band]?.pollFirst() ?: AutomationResult.Verified
        }

        override suspend fun verifyRegistered(
            runId: RunId,
            subscription: SelectedKtSubscription,
            band: KtBand
        ): AutomationResult {
            trace += "verify:${band.name}"
            return verifyResults[band]?.pollFirst() ?: AutomationResult.Verified
        }

        override suspend fun restoreAutomatic(
            runId: RunId,
            subscription: SelectedKtSubscription
        ): RestoreResult {
            trace += "restore"
            return restoreResults.pollFirst() ?: RestoreResult.Verified
        }

        override fun cancel(runId: RunId) {
            trace += "cancel"
        }
    }
}
