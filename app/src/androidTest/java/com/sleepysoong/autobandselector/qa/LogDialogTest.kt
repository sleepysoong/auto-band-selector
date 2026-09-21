package com.sleepysoong.autobandselector.qa

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sleepysoong.autobandselector.ui.GlassLogDialog
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LogDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun longLogRendersAndCopyAndClearKeepDialogOpen() {
        val original = (1..200).joinToString("\n") { "단계 $it: 접근성 응답 확인" }
        val logs = mutableStateOf(original)
        var copied: String? = null
        compose.setContent {
            GlassLogDialog(logs.value, onDismiss = {}, onCopy = { copied = logs.value },
                onClear = { logs.value = "" }, onShare = {})
        }
        compose.waitForIdle()
        val image = compose.onAllNodes(isRoot()).onLast().captureToImage()
        assertTrue(image.width > 0 && image.height > 0)
        compose.onNodeWithContentDescription("전체 로그 복사").performClick()
        compose.runOnIdle { assertEquals(original, copied) }
        compose.onNodeWithContentDescription("로그 초기화").performClick()
        compose.onNodeWithText("저장된 로그 기록이 없습니다.").assertExists()
        compose.onNodeWithContentDescription("전체 로그 복사").assertIsNotEnabled()
        compose.onNodeWithContentDescription("로그 닫기").assertExists()
    }
}
