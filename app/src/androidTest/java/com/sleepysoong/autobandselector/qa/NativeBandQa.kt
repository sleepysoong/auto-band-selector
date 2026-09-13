package com.sleepysoong.autobandselector.qa

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NativeBandQa {
    @Test
    fun baseline() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val output = File(target.getExternalFilesDir(null), "qa/baseline").apply { mkdirs() }
        val device = UiDevice.getInstance(instrumentation)
        val assertions = listOf("menu", "sim_mapping", "registered_band", "automatic_restore")
        var status = "unverified"
        var cleanup = "complete"
        val cleanupDetails = JSONArray().apply {
            put("Physical device verification is required; no assertion is inferred from the app launch.")
        }
        try {
            val intent = target.packageManager.getLaunchIntentForPackage(target.packageName)
                ?: error("Launch intent unavailable")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            target.startActivity(intent)
            check(device.wait(Until.hasObject(By.pkg(target.packageName)), 10_000)) {
                "Target package did not reach a visible native state within 10000ms"
            }
            device.waitForIdle()
            check(device.takeScreenshot(File(output, "screen.png"))) { "Screenshot capture failed" }
            device.dumpWindowHierarchy(File(output, "hierarchy.xml"))
        } catch (t: Throwable) {
            status = "failed"
            cleanup = "incomplete"
            cleanupDetails.put("Baseline capture failed: ${t::class.java.simpleName}: ${t.message}")
        } finally {
            val assertionData = JSONArray()
            assertions.forEach { name ->
                assertionData.put(JSONObject().apply {
                    put("name", name)
                    put("passed", false)
                    put("details", "unverified: requires actual verified screen data")
                })
            }
            JSONObject().apply {
                put("schema_version", 1)
                put("scenario", "baseline")
                put("status", status)
                put("assertions", assertionData)
                put("cleanup", JSONObject().apply {
                    put("status", cleanup)
                    put("details", cleanupDetails)
                })
            }.toString(2).let { File(output, "result.json").writeText(it) }
        }
        assertEquals("Native baseline must not report success without verified physical assertions", "passed", status)
    }
}
