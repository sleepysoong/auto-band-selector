package com.sleepysoong.autobandselector

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.Button
import android.widget.RadioGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(ExperimentalCoroutinesApi::class)
class LegacyEntryContractTest {
    private val scheduler = TestCoroutineScheduler()
    private lateinit var controller: ActivityController<MainActivity>
    private val activity get() = controller.get()
    private val prefs get() = activity.getSharedPreferences("BandSelectorPrefs", Context.MODE_PRIVATE)

    @Before
    fun createActivity() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.get().getSharedPreferences("BandSelectorPrefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        controller.setup()
    }

    @After
    fun closeActivity() {
        try {
            controller.pause().stop().destroy()
            scheduler.runCurrent()
            prefs.edit().clear().commit()
            File(activity.filesDir, "logs.txt").delete()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun appOpenStaysIdleAndWritesNoRunnableState() {
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.cardAccessibility).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.cardScanProgress).visibility)
        assertNull(shadowOf(activity).nextStartedActivity)
        prefs.edit().putString("macro_mode", "APPLY_BEST")
            .putInt("scan_step", 3).putString("scan_bands", "LTE B8")
            .putString("scan_speeds", "LTE B8:99.0")
            .putString("target_band_to_set", "LTE B8")
            .putBoolean("band_setting_applied", true).commit()
        controller.pause().stop().destroy()
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        scheduler.runCurrent()
        assertFalse(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse("legacy runtime keys must be discarded", prefs.contains("macro_mode"))
        assertFalse(prefs.contains("scan_bands"))
        assertFalse(prefs.contains("band_setting_applied"))
    }

    @Test
    fun ktSelectionPersistsAndStartWithoutGrantBlocksWithoutLaunchingDialer() {
        activity.findViewById<RadioGroup>(R.id.rgSimCarrier).check(R.id.rbSimKt)
        assertEquals("KT", prefs.getString("sim_carrier", null))
        assertEquals(View.GONE, activity.findViewById<View>(R.id.cardScanProgress).visibility)
        assertTrue(activity.findViewById<Button>(R.id.btnRunMacro).performClick())
        scheduler.runCurrent()
        // Robolectric has no READ_PHONE_STATE grant: Start must block, not open the hidden menu.
        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse(prefs.contains("macro_mode"))
        assertEquals(View.GONE, activity.findViewById<View>(R.id.cardScanProgress).visibility)
    }

    @Test
    fun logShareProducesReadableFileProviderStreamWithReadGrant() {
        val payload = "baseline-log-fixture\n"
        File(activity.filesDir, "logs.txt").writeText(payload)

        MainActivity::class.java.getDeclaredMethod("shareLogFile").apply {
            isAccessible = true
        }.invoke(activity)

        val chooser = requireNotNull(shadowOf(activity).nextStartedActivity)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = requireNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION,
            send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val uri = requireNotNull(send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertEquals("content", uri.scheme)
        assertEquals("com.sleepysoong.autobandselector.fileprovider", uri.authority)
        assertEquals("/logs/logs.txt", uri.path)
        val provider = requireNotNull(activity.packageManager.resolveContentProvider(uri.authority!!, 0))
        assertFalse(provider.exported)
        assertTrue(provider.grantUriPermissions)
        activity.contentResolver.openInputStream(uri)!!.bufferedReader().use {
            assertEquals(payload, it.readText())
        }
    }
}
