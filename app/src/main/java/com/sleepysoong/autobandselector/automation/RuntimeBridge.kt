package com.sleepysoong.autobandselector.automation

import android.content.Intent

/** Process-local production seam shared by caller integration and the accessibility service. */
object RuntimeBridge {
    data class RunBinding(
        val parser: SamsungScreenParser,
        val currentAction: () -> MacroAction?,
        val resultSink: (MacroResult) -> Unit,
        val isAuthorized: (MacroAction) -> Boolean,
        val expectedPackage: String,
        val expectedSimSlot: Int,
        val targetBand: Int?,
        val onRevoked: () -> Unit
    ) {
        init {
            require(expectedPackage == SamsungPhoneEntry.SAMSUNG_PHONE_PACKAGE)
            require(expectedSimSlot in 1..2)
        }

        companion object {
            fun fromCoordinator(
                coordinator: RunCoordinator,
                parser: SamsungScreenParser,
                expectedSimSlot: Int,
                targetBand: Int?
            ): RunBinding = RunBinding(
                parser = parser,
                currentAction = { (coordinator.state.value as? MacroState.Running)?.action },
                resultSink = { coordinator.submit(it) },
                isAuthorized = coordinator::isAuthorized,
                expectedPackage = SamsungPhoneEntry.SAMSUNG_PHONE_PACKAGE,
                expectedSimSlot = expectedSimSlot,
                targetBand = targetBand,
                onRevoked = {
                    (coordinator.state.value as? MacroState.Running)?.action?.let {
                        coordinator.stop(it, CancelReason.AccessibilityLost)
                    }
                }
            )
        }
    }

    @Volatile private var run: RunBinding? = null

    /**
     * Installs process-local run authorization and returns the only supported Activity request.
     * Caller integration starts this request; AccessibilityService never starts an Activity.
     */
    @Synchronized fun installRun(value: RunBinding, resolver: SamsungPhoneActivityResolver): Intent? {
        val request = SamsungPhoneEntry.dialIntent(resolver) ?: return null
        run = value
        return request
    }

    fun currentRun(): RunBinding? = run

    @Synchronized fun revoke() {
        val old = run
        run = null
        old?.onRevoked?.invoke()
    }

    @Synchronized fun revoke(expected: RunBinding): Boolean {
        if (run !== expected) return false
        run = null
        expected.onRevoked()
        return true
    }

    @Synchronized fun detach(expected: RunBinding): Boolean {
        if (run !== expected) return false
        run = null
        return true
    }

    @Synchronized fun detach() { run = null }
}
