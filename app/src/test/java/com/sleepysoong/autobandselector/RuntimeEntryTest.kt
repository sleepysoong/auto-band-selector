package com.sleepysoong.autobandselector

import android.content.ComponentName
import com.sleepysoong.autobandselector.automation.*
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RuntimeEntryTest {
    private val resolver = SamsungPhoneActivityResolver {
        listOf(ResolvedPhoneActivity(ComponentName(SamsungPhoneEntry.SAMSUNG_PHONE_PACKAGE,
            "com.samsung.android.dialer.DialtactsActivity"), true, true))
    }

    @Test fun bothLogicalSlotsReachTheCorrectSamsungSlot() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            for (logicalSlot in 0..1) {
                var observedSlot: Int? = null
                val automation = RuntimeBandAutomation(this, SamsungProfiles.production(), resolver) {
                    val binding = requireNotNull(RuntimeBridge.currentRun())
                    observedSlot = binding.expectedSimSlot
                    binding.resultSink(MacroResult.Complete(requireNotNull(binding.currentAction())))
                }
                assertEquals(AutomationResult.Verified, automation.applyAndVerify(
                    RunId(UUID.randomUUID()), SelectedKtSubscription(41, logicalSlot), KtBand.B1))
                assertEquals(logicalSlot + 1, observedSlot)
                assertNull(RuntimeBridge.currentRun())
            }
        } finally { Dispatchers.resetMain(); RuntimeBridge.detach() }
    }

    @Test fun missingPhoneIsLoggedAndAuthorizationIsReleased() = runTest {
        val messages = mutableListOf<String>()
        val automation = RuntimeBandAutomation(this, SamsungProfiles.production(),
            SamsungPhoneActivityResolver { emptyList() }, messages::add) { fail("must not launch") }
        assertTrue(automation.applyAndVerify(RunId(UUID.randomUUID()),
            SelectedKtSubscription(41, 0), KtBand.B1) is AutomationResult.Unsupported)
        assertTrue(messages.any { it.contains("진입 실패") })
        assertNull(RuntimeBridge.currentRun())
    }

    @Test fun noAccessibilityResponseTimesOutAndReleasesAuthorization() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val automation = RuntimeBandAutomation(this, SamsungProfiles.production(), resolver) { }
            val result = automation.applyAndVerify(RunId(UUID.randomUUID()),
                SelectedKtSubscription(41, 1), KtBand.B1) as AutomationResult.Failed
            assertTrue(result.reason.contains("접근성 화면 응답"))
            assertNull(RuntimeBridge.currentRun())
        } finally { Dispatchers.resetMain(); RuntimeBridge.detach() }
    }
}
