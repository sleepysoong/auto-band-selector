package com.sleepysoong.autobandselector.qa

import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sleepysoong.autobandselector.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run on a hardware-accelerated device: Activity creation alone misses RenderThread crashes. */
@RunWith(AndroidJUnit4::class)
class StartupRenderingTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun glassScreenRendersOnLaunchAndAfterRecreation() {
        assertScreenRenders()
        compose.activityRule.scenario.recreate()
        assertScreenRenders()
    }

    private fun assertScreenRenders() {
        compose.waitForIdle()
        val image = compose.onRoot().captureToImage()
        assertTrue("Screen must have a rendered width", image.width > 0)
        assertTrue("Screen must have a rendered height", image.height > 0)
    }
}
