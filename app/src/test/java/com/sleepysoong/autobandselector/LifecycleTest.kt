package com.sleepysoong.autobandselector

import android.Manifest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import com.sleepysoong.autobandselector.automation.RuntimeStart

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LifecycleTest {

    @Test
    fun processDeathLeavesNoResumeableStateAndReopenIsIdle() {
        val first = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val prefs = first.getSharedPreferences("BandSelectorPrefs", android.content.Context.MODE_PRIVATE)
        assertFalse("runtime writes no runnable keys", prefs.contains("macro_mode"))
        first.finish()
        Robolectric.flushForegroundThreadScheduler()
        val second = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertNull(shadowOf(second).nextStartedActivity)
        val app = second.application as BandSelectorApp
        assertTrue(app.runtime.startScan() is RuntimeStart.Blocked) // no telephony/permission: blocked, never silently started
    }

    @Test
    fun stopWithoutRunIsInertAndRestoreRequiresPreviousBoundary() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val app = activity.application as BandSelectorApp
        assertFalse(app.runtime.stop())
        assertTrue(app.runtime.restore() is RuntimeStart.Blocked)
    }
}
