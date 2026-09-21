package com.sleepysoong.autobandselector.data

import com.sleepysoong.autobandselector.automation.*
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RunLogTest {
    @Test fun failureIsPersistedAndClearUpdatesOpenLog() {
        val context = RuntimeEnvironment.getApplication()
        val log = RunLog(context)
        assertTrue(log.clear())
        log.record(BandScanState.Failed(RunId(UUID.randomUUID()), "접근성 응답 시간 초과", emptyList()))
        assertTrue(log.text.value.contains("실패: 접근성 응답 시간 초과"))
        assertEquals(log.text.value, RunLog(context).text.value)
        assertTrue(log.clear())
        assertEquals("", log.text.value)
        assertEquals("", RunLog(context).text.value)
    }

    @Test fun secretCodesAreRedacted() {
        val log = RunLog(RuntimeEnvironment.getApplication())
        log.clear()
        log.append("319712358 774632 *123456#")
        assertFalse(log.text.value.contains("774632"))
        assertFalse(log.text.value.contains("319712358"))
        assertFalse(log.text.value.contains("*123456#"))
    }
}
