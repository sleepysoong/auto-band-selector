package com.sleepysoong.autobandselector

import com.sleepysoong.autobandselector.automation.*
import com.sleepysoong.autobandselector.data.SettingsConfiguration
import com.sleepysoong.autobandselector.ui.*
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

/** Pure mapping from orchestration state to what the Fold6 glass screen renders. */
class UiStateMappingTest {
    private val runId = RunId(UUID.randomUUID())
    private val subscription = SelectedKtSubscription(7, 0)
    private val ready = PreflightUi(
        accessibilityEnabled = true,
        subscriptionText = "KT eSIM (SIM 1)",
        blocked = null
    )

    @Test
    fun idleOffersStartAndRestoreWithFreshPayloadNotice() {
        val ui = UiStateMapper.map(BandScanState.Idle, ready, SettingsConfiguration())
        assertTrue(ui.startEnabled)
        assertFalse(ui.stopVisible)
        assertTrue(ui.restoreEnabled)
        assertEquals(27_000_000L, ui.payloadNoticeBytes)
    }

    @Test
    fun runningDisablesStartShowsStopAndCandidate() {
        val running = BandScanState.Running(runId, ScanPhase.MeasuringCandidate, KtBand.B3, emptyList())
        val ui = UiStateMapper.map(running, ready, SettingsConfiguration())
        assertFalse(ui.startEnabled)
        assertTrue(ui.stopVisible)
        assertFalse(ui.restoreEnabled)
        assertEquals(3, ui.candidateBand)
        assertEquals(ScanPhase.MeasuringCandidate, ui.phase)
    }

    @Test
    fun completedShowsWinnerAndRestoresControls() {
        val done = BandScanState.Completed(runId, BandWinner(KtBand.B1, 22.5), emptyList())
        val ui = UiStateMapper.map(done, ready, SettingsConfiguration())
        assertTrue(ui.startEnabled)
        assertFalse(ui.stopVisible)
        assertTrue(ui.restoreEnabled)
        assertEquals(1, ui.winnerBand)
    }

    @Test
    fun failedSurfacesReasonAndRecoveryWithoutHidingRestore() {
        val failed = BandScanState.Failed(
            runId, "restore failed", emptyList(),
            recovery = RestoreResult.Failed("verify window unavailable")
        )
        val ui = UiStateMapper.map(failed, ready, SettingsConfiguration())
        assertEquals("restore failed", ui.failureReason)
        assertEquals("verify window unavailable", ui.recoveryText)
        assertTrue(ui.restoreEnabled)
        assertTrue(ui.startEnabled)
    }

    @Test
    fun cancelledKeepsNextActionStartOnly() {
        val cancelled = BandScanState.Cancelled(runId, emptyList())
        val ui = UiStateMapper.map(cancelled, ready, SettingsConfiguration())
        assertTrue(ui.startEnabled)
        assertTrue(ui.restoreEnabled)
        assertTrue(ui.cancelled)
    }

    @Test
    fun blockedAccessibilityForcesSettingsActionAndDisablesStart() {
        val blocked = ready.copy(accessibilityEnabled = false, blocked = null)
        val ui = UiStateMapper.map(BandScanState.Idle, blocked, SettingsConfiguration())
        assertFalse(ui.startEnabled)
        assertTrue(ui.accessibilityActionVisible)
        assertFalse(ui.simSettingsVisible)
    }

    @Test
    fun blockedSubscriptionShowsSimSettingsAction() {
        val blocked = ready.copy(blocked = "wrong default data subscription")
        val ui = UiStateMapper.map(BandScanState.Idle, blocked, SettingsConfiguration())
        assertFalse(ui.startEnabled)
        assertTrue(ui.simSettingsVisible)
        assertEquals("wrong default data subscription", ui.blockedReason)
    }

    @Test
    fun resultRowsExposeExactBandsOnlyOnce() {
        val results = listOf(
            CandidateResult(KtBand.B1, UUID.randomUUID(), CandidateOutcome.Valid(8.0, listOf(7.0, 8.0, 9.0))),
            CandidateResult(KtBand.B3, UUID.randomUUID(), CandidateOutcome.Valid(20.0, listOf(19.0, 20.0, 21.0))),
            CandidateResult(KtBand.B8, UUID.randomUUID(), CandidateOutcome.Failed("ProbeFailure.Timeout"))
        )
        val ui = UiStateMapper.map(
            BandScanState.Running(runId, ScanPhase.VerifyingWinner, KtBand.B1, results), ready, SettingsConfiguration()
        )
        assertEquals(listOf(1, 3, 8), ui.rows.map { it.band })
        assertEquals("20.0", ui.rows[1].medianText)
        assertEquals("ProbeFailure.Timeout", ui.rows[2].failureText)
    }
}
