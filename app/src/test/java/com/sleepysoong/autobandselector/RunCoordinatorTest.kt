package com.sleepysoong.autobandselector

import android.content.Context
import com.sleepysoong.autobandselector.automation.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class RunCoordinatorTest {
    @Test
    fun duplicateStartsAuthorizeOnlyOneEffect() = runTest {
        val actions = mutableListOf<MacroAction>()
        val coordinator = RunCoordinator(backgroundScope) { action ->
            actions += action
            awaitCancellation()
        }
        val first = coordinator.start(RunMode.Scan) as StartResult.Started
        val second = coordinator.start(RunMode.Restore)

        assertEquals(StartResult.AlreadyRunning(first.action), second)
        assertEquals(MacroState.Running(first.action), coordinator.state.value)
        runCurrent()
        assertEquals(listOf(first.action), actions)
    }

    @Test
    fun startPublishesPreflightBeforeDispatchingEffect() = runTest {
        val actions = mutableListOf<MacroAction>()
        val coordinator = RunCoordinator(backgroundScope) { action ->
            actions += action
            awaitCancellation()
        }
        assertEquals(MacroState.Idle, coordinator.state.value)
        val started = coordinator.start(RunMode.Scan) as StartResult.Started
        assertEquals(MacroState.Running(started.action), coordinator.state.value)
        assertTrue(actions.isEmpty())
        runCurrent()
        assertEquals(listOf(started.action), actions)
    }

    @Test
    fun simultaneousStartsHaveExactlyOneWinner() = runTest {
        val actions = mutableListOf<MacroAction>()
        val coordinator = RunCoordinator(backgroundScope) { action ->
            actions += action
            awaitCancellation()
        }
        val ready = CountDownLatch(2)
        val release = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val requests = RunMode.entries.map { mode ->
                workers.submit<StartResult> {
                    ready.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    coordinator.start(mode)
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            release.countDown()
            val results = requests.map { it.get(5, TimeUnit.SECONDS) }
            val started = results.filterIsInstance<StartResult.Started>().single()
            assertEquals(started.action,
                results.filterIsInstance<StartResult.AlreadyRunning>().single().action)
            runCurrent()
            assertEquals(listOf(started.action), actions)
        } finally {
            release.countDown()
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun duplicateObservationsAdvanceOnlyOnceAndConsumeOldAttempt() = runTest {
        val actions = mutableListOf<MacroAction>()
        val coordinator = RunCoordinator(backgroundScope) { action ->
            actions += action
            awaitCancellation()
        }
        val first = (coordinator.start(RunMode.Scan) as StartResult.Started).action
        runCurrent()
        val result = MacroResult.Advance(first, MacroStage.EnterMenu)
        assertTrue(coordinator.submit(result))
        assertFalse(coordinator.submit(result))
        val next = (coordinator.state.value as MacroState.Running).action
        assertEquals(first.runId, next.runId)
        assertEquals(AttemptId(first.attemptId.value + 1), next.attemptId)
        assertEquals(MacroStage.EnterMenu, next.stage)
        assertFalse(coordinator.isAuthorized(first))
        assertTrue(coordinator.isAuthorized(next))
        runCurrent()
        assertEquals(listOf(first, next), actions)
        coordinator.stop(next)
    }

    @Test
    fun wrongRunAttemptStageAndModeAreNoOpsIncludingStaleStop() = runTest {
        val coordinator = RunCoordinator(backgroundScope) { awaitCancellation() }
        val action = (coordinator.start(RunMode.Scan) as StartResult.Started).action
        val wrongActions = listOf(
            action.copy(runId = RunId(UUID.randomUUID())),
            action.copy(attemptId = AttemptId(42)),
            action.copy(stage = MacroStage.EnterMenu),
            action.copy(mode = RunMode.Restore)
        )
        wrongActions.forEach { wrong ->
            assertFalse(coordinator.submit(MacroResult.Advance(wrong, MacroStage.EnterMenu)))
            assertFalse(coordinator.stop(wrong))
            assertFalse(coordinator.isAuthorized(wrong))
            assertEquals(MacroState.Running(action), coordinator.state.value)
        }
        coordinator.stop(action)
    }

    @Test
    fun stopBeforeDispatchPreventsAnyEffect() = runTest {
        val actions = mutableListOf<MacroAction>()
        val coordinator = RunCoordinator(backgroundScope) { action ->
            actions += action
            awaitCancellation()
        }
        val action = (coordinator.start(RunMode.Scan) as StartResult.Started).action
        assertTrue(coordinator.stop(action))
        assertFalse(coordinator.stop(action))
        runCurrent()
        assertTrue(actions.isEmpty())
        assertEquals(MacroState.Cancelled(action, CancelReason.UserStop), coordinator.state.value)
    }

    @Test
    fun stopRevokesIdentityBeforeCancellationCleanupCanSubmit() = runTest {
        val entered = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Pair<Boolean, Boolean>>()
        lateinit var coordinator: RunCoordinator
        coordinator = RunCoordinator(backgroundScope) { action ->
            try {
                entered.complete(Unit)
                awaitCancellation()
            } finally {
                cleanup.complete(coordinator.isAuthorized(action) to
                    coordinator.submit(MacroResult.Advance(action, MacroStage.EnterMenu)))
            }
        }
        val action = (coordinator.start(RunMode.Scan) as StartResult.Started).action
        withTimeout(1_000) { entered.await() }
        assertTrue(coordinator.stop(action, CancelReason.AccessibilityLost))
        assertEquals(false to false, withTimeout(1_000) { cleanup.await() })
        assertEquals(MacroState.Cancelled(action, CancelReason.AccessibilityLost),
            coordinator.state.value)
    }

    @Test
    fun lateNonCancellableEffectCannotRearmStoppedRunOrAffectNewRestore() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val returned = CompletableDeferred<Unit>()
        val actions = mutableListOf<MacroAction>()
        val coordinator = RunCoordinator(backgroundScope) { action ->
            actions += action
            if (action.mode == RunMode.Scan) {
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                    returned.complete(Unit)
                    MacroResult.Advance(action, MacroStage.EnterMenu)
                }
            } else awaitCancellation()
        }
        val old = (coordinator.start(RunMode.Scan) as StartResult.Started).action
        try {
            withTimeout(1_000) { entered.await() }
            assertTrue(coordinator.stop(old))
            val fresh = (coordinator.start(RunMode.Restore) as StartResult.Started).action
            assertNotEquals(old.runId, fresh.runId)
            assertEquals(AttemptId(1), fresh.attemptId)
            release.complete(Unit)
            withTimeout(1_000) { returned.await() }
            runCurrent()
            assertEquals(MacroState.Running(fresh), coordinator.state.value)
            assertEquals(listOf(old, fresh), actions)
            assertFalse(coordinator.submit(MacroResult.Advance(old, MacroStage.EnterMenu)))
            coordinator.stop(fresh)
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun timeoutOccursAtDeadlineAndRejectsLateSuccess() = runTest {
        val coordinator = RunCoordinator(backgroundScope, stepTimeoutMillis = 100) {
            awaitCancellation()
        }
        val action = (coordinator.start(RunMode.Scan) as StartResult.Started).action
        val failed = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(1_000) { coordinator.state.first { it is MacroState.Failed } }
        }
        runCurrent()
        advanceTimeBy(99)
        runCurrent()
        assertTrue(coordinator.isAuthorized(action))
        advanceTimeBy(1)
        runCurrent()
        assertEquals(MacroState.Failed(MacroResult.Failure(action, FailureReason.Timeout)),
            failed.await())
        assertFalse(coordinator.isAuthorized(action))
        assertFalse(coordinator.submit(MacroResult.Advance(action, MacroStage.EnterMenu)))
    }

    @Test
    fun previousAttemptDeadlineCannotTimeOutSuccessor() = runTest {
        val coordinator = RunCoordinator(backgroundScope, stepTimeoutMillis = 100) {
            awaitCancellation()
        }
        val first = (coordinator.start(RunMode.Scan) as StartResult.Started).action
        runCurrent()
        advanceTimeBy(90)
        assertTrue(coordinator.submit(MacroResult.Advance(first, MacroStage.EnterMenu)))
        val next = (coordinator.state.value as MacroState.Running).action
        runCurrent()
        advanceTimeBy(10)
        runCurrent()
        assertTrue(coordinator.isAuthorized(next))
        advanceTimeBy(90)
        runCurrent()
        assertEquals(MacroState.Failed(MacroResult.Failure(next, FailureReason.Timeout)),
            coordinator.state.value)
    }

    @Test
    fun timeoutRevokesEvenWhileEffectIgnoresCancellation() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val coordinator = RunCoordinator(backgroundScope, stepTimeoutMillis = 100) { action ->
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
                MacroResult.Advance(action, MacroStage.EnterMenu)
            }
        }
        val action = (coordinator.start(RunMode.Scan) as StartResult.Started).action
        try {
            withTimeout(1_000) { entered.await() }
            advanceTimeBy(100)
            runCurrent()
            assertEquals(MacroState.Failed(MacroResult.Failure(action, FailureReason.Timeout)),
                coordinator.state.value)
            release.complete(Unit)
            runCurrent()
            assertFalse(coordinator.isAuthorized(action))
            assertTrue(coordinator.state.value is MacroState.Failed)
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun returnedResultsDriveStepsAndTerminalResultsCannotRunAgain() = runTest {
        val actions = mutableListOf<MacroAction>()
        val coordinator = RunCoordinator(backgroundScope) { action ->
            actions += action
            when (action.stage) {
                MacroStage.Preflight -> MacroResult.Advance(action, MacroStage.DisableSelection)
                MacroStage.DisableSelection -> MacroResult.Advance(action, MacroStage.NetworkModeAutomatic)
                MacroStage.NetworkModeAutomatic -> MacroResult.Advance(action, MacroStage.VerifyAutomatic)
                MacroStage.VerifyAutomatic -> MacroResult.Complete(action, RecoveryStatus.AutomaticVerified)
                else -> error("Unexpected restore step")
            }
        }
        val completed = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(1_000) { coordinator.state.first { it is MacroState.Completed } }
        }
        coordinator.start(RunMode.Restore)
        val terminal = completed.await() as MacroState.Completed
        assertEquals(listOf(MacroStage.Preflight, MacroStage.DisableSelection,
            MacroStage.NetworkModeAutomatic, MacroStage.VerifyAutomatic), actions.map { it.stage })
        assertEquals(listOf(1L, 2L, 3L, 4L), actions.map { it.attemptId.value })
        assertEquals(1, actions.map { it.runId }.distinct().size)
        assertFalse(coordinator.isAuthorized(terminal.result.action))
        assertFalse(coordinator.submit(terminal.result))
        assertFalse(coordinator.stop(terminal.result.action))
        assertEquals(terminal, coordinator.state.value)
        assertTrue(coordinator.start(RunMode.Scan) is StartResult.Started)
        coordinator.stop((coordinator.state.value as MacroState.Running).action)
    }

    @Test
    fun thrownEffectBecomesTypedFailureAndDoesNotAutoRestore() = runTest {
        val actions = mutableListOf<MacroAction>()
        val coordinator = RunCoordinator(backgroundScope) { action ->
            actions += action
            throw IllegalStateException("fixture")
        }
        val action = (coordinator.start(RunMode.Scan) as StartResult.Started).action
        runCurrent()
        val expected = MacroState.Failed(MacroResult.Failure(action,
            FailureReason.EffectError(IllegalStateException::class.java.name, "fixture")))
        assertEquals(expected, coordinator.state.value)
        assertEquals(listOf(action), actions)
        assertFalse(coordinator.submit(MacroResult.Advance(action, MacroStage.DisableSelection)))
    }

    @Test
    fun ownerCancellationRevokesEvenBeforeEffectDispatch() = runTest {
        val owner = CoroutineScope(coroutineContext + Job())
        val coordinator = RunCoordinator(owner) { awaitCancellation() }
        val action = (coordinator.start(RunMode.Scan) as StartResult.Started).action
        owner.cancel()
        runCurrent()
        assertFalse(coordinator.isAuthorized(action))
        assertEquals(MacroState.Cancelled(action, CancelReason.OwnerCancelled),
            coordinator.state.value)
    }

    @Test
    fun stalePrefsCannotAuthorizeAndNewCollectorsDoNotResume() = runTest {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences(
            "BandSelectorPrefs", Context.MODE_PRIVATE)
        prefs.edit().putString("macro_mode", "SCANNING")
            .putString("target_band_to_set", "LTE B8")
            .putBoolean("band_setting_applied", true).commit()
        val actions = mutableListOf<MacroAction>()
        val old = RunCoordinator(backgroundScope) { awaitCancellation() }
        val stale = (old.start(RunMode.Scan) as StartResult.Started).action
        old.stop(stale, CancelReason.OwnerCancelled)
        try {
            val fresh = RunCoordinator(backgroundScope) { action ->
                actions += action
                awaitCancellation()
            }
            repeat(2) {
                assertEquals(MacroState.Idle, withTimeout(1_000) { fresh.state.first() })
            }
            assertFalse(fresh.submit(MacroResult.Advance(stale, MacroStage.EnterMenu)))
            runCurrent()
            assertEquals(MacroState.Idle, fresh.state.value)
            assertTrue(actions.isEmpty())
            assertEquals("SCANNING", prefs.getString("macro_mode", null))
            val restore = (fresh.start(RunMode.Restore) as StartResult.Started).action
            assertNotEquals(stale.runId, restore.runId)
            assertEquals(RunMode.Restore, restore.mode)
            runCurrent()
            assertEquals(listOf(restore), actions)
            fresh.stop(restore)
        } finally {
            prefs.edit().clear().commit()
        }
    }
}
