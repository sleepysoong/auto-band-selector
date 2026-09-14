package com.sleepysoong.autobandselector

import android.content.Context
import android.content.Intent
import android.net.Uri
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

// Characterizes the Compose-era entry: open means idle observation only,
// legacy runnable prefs are purged, and FileProvider log sharing still works.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class LegacyEntryContractTest {
    private lateinit var controller: ActivityController<MainActivity>
    private val activity get() = controller.get()
    private val prefs get() = activity.getSharedPreferences("BandSelectorPrefs", Context.MODE_PRIVATE)

    @Before
    fun createActivity() {
        controller = Robolectric.buildActivity(MainActivity::class.java)
        prefs.edit().clear().commit()
        controller.setup()
    }

    @After
    fun closeActivity() {
        controller.pause().stop().destroy()
        prefs.edit().clear().commit()
        File(activity.filesDir, "logs.txt").delete()
    }

    @Test
    fun openIsIdleAndLegacyRunnablePreferencesArePurged() {
        prefs.edit().putString("macro_mode", "APPLY_BEST")
            .putInt("scan_step", 3).putString("scan_bands", "LTE B8")
            .putString("scan_speeds", "LTE B8:99.0")
            .putString("target_band_to_set", "LTE B8")
            .putBoolean("band_setting_applied", true).commit()
        controller.pause().stop().destroy()
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertFalse(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse(prefs.contains("macro_mode"))
        assertFalse(prefs.contains("scan_bands"))
        assertFalse(prefs.contains("band_setting_applied"))
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
