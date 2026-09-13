package com.sleepysoong.autobandselector.automation

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Process-local authorization, not persistence or scan policy. Own one instance in the application.
 * The effect executes one step and reports verified progress; it must cooperate with cancellation
 * and retain the original action on callbacks. Observing [state] never starts or resumes work.
 */
class RunCoordinator(
    private val scope: CoroutineScope,
    private val stepTimeoutMillis: Long = 30_000,
    private val effect: suspend (MacroAction) -> MacroResult
) {
    init {
        require(stepTimeoutMillis > 0) { "Step timeout must be positive" }
    }

    private val lock = Any()
    private val mutableState = MutableStateFlow<MacroState>(MacroState.Idle)
    val state: StateFlow<MacroState> = mutableState.asStateFlow()
    private var active: Pending? = null

    /** Only an explicit user Start or Restore may call this; history is not runnable input. */
    fun start(mode: RunMode): StartResult {
        val pending = synchronized(lock) {
            active?.let { return StartResult.AlreadyRunning(it.action) }
            check(scope.isActive) { "Coordinator owner is no longer active" }
            install(MacroAction(RunId(UUID.randomUUID()), AttemptId(1), mode, MacroStage.Preflight))
        }
        pending.start()
        return StartResult.Started(pending.action)
    }

    /** Check immediately before an adapter side effect, as well as retaining cancellable handles. */
    fun isAuthorized(action: MacroAction): Boolean = synchronized(lock) {
        matches(action)
    }

    /** Duplicate, wrong-state, wrong-run and superseded-attempt observations are silent no-ops. */
    fun submit(result: MacroResult): Boolean {
        val previous: Pending
        val next: Pending?
        synchronized(lock) {
            if (!matches(result.action)) return false
            previous = checkNotNull(active)
            // Revoke before publishing terminal state, cancelling resources or starting a successor.
            active = null
            next = when (result) {
                is MacroResult.Advance -> install(result.action.copy(
                    attemptId = AttemptId(result.action.attemptId.value + 1),
                    stage = result.nextStage
                ))
                is MacroResult.Complete -> {
                    mutableState.value = MacroState.Completed(result)
                    null
                }
                is MacroResult.Failure -> {
                    mutableState.value = MacroState.Failed(result)
                    null
                }
            }
        }
        previous.cancel()
        next?.start()
        return true
    }

    /** Stop/interrupt revokes first. It never performs hidden rollback or creates a Restore run. */
    fun stop(action: MacroAction, reason: CancelReason = CancelReason.UserStop): Boolean {
        val previous = synchronized(lock) {
            val pending = active ?: return false
            if (pending.action != action) return false
            active = null
            mutableState.value = MacroState.Cancelled(action, reason)
            pending
        }
        previous.cancel()
        return true
    }

    private fun matches(action: MacroAction): Boolean =
        active?.let { it.action == action && !it.effectJob.isCancelled && scope.isActive } == true

    /** Called under lock. Lazy jobs cannot dispatch an effect before its identity is installed. */
    private fun install(action: MacroAction): Pending {
        val effectJob = scope.launch(start = CoroutineStart.LAZY) {
            if (!isAuthorized(action)) return@launch
            try {
                submit(effect(action))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                submit(MacroResult.Failure(action,
                    FailureReason.EffectError(failure.javaClass.name, failure.message)))
            }
        }
        // A separate scheduler deadline revokes even if an adapter temporarily ignores cancellation.
        // Coroutine delay is monotonic and uses the injected scope's virtual scheduler in tests.
        val timeoutJob = scope.launch(start = CoroutineStart.LAZY) {
            delay(stepTimeoutMillis)
            submit(MacroResult.Failure(action, FailureReason.Timeout))
        }
        return Pending(action, effectJob, timeoutJob).also {
            active = it
            mutableState.value = MacroState.Running(action)
        }
    }

    private inner class Pending(
        val action: MacroAction,
        val effectJob: Job,
        val timeoutJob: Job
    ) {
        fun start() {
            effectJob.invokeOnCompletion { cause ->
                if (cause is CancellationException) stop(action, CancelReason.OwnerCancelled)
            }
            timeoutJob.start()
            effectJob.start()
        }

        fun cancel() {
            timeoutJob.cancel()
            effectJob.cancel()
        }
    }
}
