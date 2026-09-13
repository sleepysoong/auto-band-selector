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
    fun ktSelectionFeedsOrderedCandidatesIntoTheRealStartEntry() {
        activity.findViewById<RadioGroup>(R.id.rgSimCarrier).check(R.id.rbSimKt)
        assertEquals("KT", prefs.getString("sim_carrier", null))
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(activity.findViewById<Button>(R.id.btnRunMacro).performClick())

        // Drain the real lifecycleScope countdown with virtual time, never wall-clock waits.
        scheduler.advanceUntilIdle()

        assertEquals(listOf("LTE B1", "LTE B3", "LTE B8"),
            prefs.getString("scan_bands", null)?.split(","))
        assertEquals("LTE B1", prefs.getString("target_band_to_set", null))
    }

    @Test
    fun startArmsFreshScanBeforeLaunchingTheDialerRatherThanApplyingHistory() {
        activity.findViewById<RadioGroup>(R.id.rgSimCarrier).check(R.id.rbSimKt)
        // Seed completed-run state after onResume, so only the Start listener can consume it.
        prefs.edit().putString("macro_mode", "APPLY_BEST")
            .putInt("scan_step", 3).putString("scan_bands", "LTE B8")
            .putString("scan_speeds", "LTE B8:99.0")
            .putString("target_band_to_set", "LTE B8")
            .putBoolean("band_setting_applied", true).commit()
        assertTrue(activity.findViewById<Button>(R.id.btnRunMacro).performClick())
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.cardScanProgress).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.cardExecute).visibility)
        assertNull(shadowOf(activity).nextStartedActivity)

        scheduler.advanceUntilIdle()

        assertEquals("SCANNING", prefs.getString("macro_mode", null))
        assertEquals(0, prefs.getInt("scan_step", -1))
        assertEquals("", prefs.getString("scan_speeds", null))
        assertFalse(prefs.getBoolean("band_setting_applied", true))
        assertEquals("LTE B1", prefs.getString("target_band_to_set", null))
        val dialer = requireNotNull(shadowOf(activity).nextStartedActivity)
        assertEquals(Intent.ACTION_DIAL, dialer.action)
        assertEquals(Uri.parse("tel:319712358"), dialer.data)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun logShareProducesReadableFileProviderStreamWithReadGrant() {
        val payload = "baseline-log-fixture\n"
        File(activity.filesDir, "logs.txt").writeText(payload)

        // This private Android adapter has no stable view ID in its dynamically built dialog.
        // Invoke it intact: real FileProvider, manifest paths, chooser and file bytes remain in play.
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
