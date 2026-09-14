package com.sleepysoong.autobandselector.data

import android.app.Application
import android.content.Context
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsRepositoryTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences("BandSelectorPrefs", Context.MODE_PRIVATE)
    private val legacyKeys = setOf("macro_mode", "target_band_to_set", "band_setting_applied",
        "scan_step", "scan_bands", "scan_speeds")

    @Before
    fun clearPreferences() {
        assertTrue(prefs.edit().clear().commit())
    }

    @Test
    fun defaultsNeverConfirmASlotOrRestoreARun() {
        val repository = SettingsRepository(app)
        assertEquals(SettingsConfiguration(), repository.loadConfiguration())
        assertNull(repository.confirmedLogicalSlotIndex)
        assertTrue(repository.history().isEmpty())
        assertTrue(prefs.all.keys.intersect(legacyKeys).isEmpty())
    }

    @Test
    fun carrierConfigurationRoundTripsUsingExistingPreferenceKeys() {
        val config = SettingsConfiguration(Carrier.LGU_PLUS, Carrier.KT)
        SettingsRepository(app).saveConfiguration(config)
        assertEquals(config, SettingsRepository(app).loadConfiguration())
        assertEquals("LGU+", prefs.getString("device_carrier", null))
        assertEquals("KT", prefs.getString("sim_carrier", null))
    }

    @Test
    fun logicalSlotZeroPersistsOnlyAfterExplicitConfirmationAndCanBeCleared() {
        val repository = SettingsRepository(app)
        repository.saveConfiguration(SettingsConfiguration(Carrier.SKT, Carrier.KT))
        assertNull(SettingsRepository(app).confirmedLogicalSlotIndex)
        repository.confirmLogicalSlot(0)
        assertEquals(0, SettingsRepository(app).confirmedLogicalSlotIndex)
        repository.clearLogicalSlotConfirmation()
        assertNull(SettingsRepository(app).confirmedLogicalSlotIndex)
    }

    @Test
    fun confirmationUsesLogicalIndexRatherThanHardcodedSecondSim() {
        for (slot in listOf(0, 1, 2)) {
            SettingsRepository(app).confirmLogicalSlot(slot)
            assertEquals(slot, SettingsRepository(app).confirmedLogicalSlotIndex)
        }
    }

    @Test
    fun negativeLogicalSlotCannotBePersisted() {
        assertThrows(IllegalArgumentException::class.java) { SettingsRepository(app).confirmLogicalSlot(-1) }
        assertNull(SettingsRepository(app).confirmedLogicalSlotIndex)
    }

    @Test
    fun migrationRemovesEveryLegacyRunnableFlagWithoutLosingConfigurationOrHistory() {
        val repository = SettingsRepository(app)
        repository.saveConfiguration(SettingsConfiguration(Carrier.LGU_PLUS, Carrier.KT))
        repository.confirmLogicalSlot(0)
        repository.appendHistory(entry(10))
        val retained = prefs.all.toMap()
        seedLegacyRun()
        val reopened = SettingsRepository(app)
        assertTrue(prefs.all.keys.intersect(legacyKeys).isEmpty())
        assertEquals(retained, prefs.all)
        assertEquals(SettingsConfiguration(Carrier.LGU_PLUS, Carrier.KT), reopened.loadConfiguration())
        assertEquals(0, reopened.confirmedLogicalSlotIndex)
        assertEquals(listOf(entry(10)), reopened.history())
        SettingsRepository(app)
        assertEquals(retained, prefs.all)
    }

    @Test
    fun migrationAlsoPreservesExistingUnknownConfigurationAndHistoryBytes() {
        prefs.edit().putString("device_carrier", "KT").putString("sim_carrier", "LGU+")
            .putString("future_config", "fixture").putString("completed_history", "opaque-history-fixture").commit()
        val retained = prefs.all.toMap()
        seedLegacyRun()
        SettingsRepository(app)
        assertEquals(retained, prefs.all)
    }

    @Test
    fun historyRoundTripsNewestFirstAndIsCappedAtTwentyOnDisk() {
        val repository = SettingsRepository(app)
        (1L..25L).forEach { repository.appendHistory(entry(it)) }
        val expected = (25L downTo 6L).map { entry(it) }
        assertEquals(expected, repository.history())
        assertEquals(expected, SettingsRepository(app).history())
        assertEquals(20, JSONArray(prefs.getString("run_history_v1", null)).length())
    }

    @Test
    fun configurationAndHistoryWritesNeverPersistIdentifiersOrExecutableState() {
        val repository = SettingsRepository(app)
        repository.saveConfiguration(SettingsConfiguration(Carrier.SKT, Carrier.KT))
        repository.confirmLogicalSlot(0)
        repository.appendHistory(entry(123))
        assertEquals(setOf("device_carrier", "sim_carrier", "confirmed_logical_slot_index", "run_history_v1"),
            prefs.all.keys)
        val history = JSONArray(prefs.getString("run_history_v1", null))
        val storedEntry = history.getJSONObject(0)
        assertEquals(setOf("completedAtEpochMillis", "outcome", "measurements"), storedEntry.keys().asSequence().toSet())
        val measurement = storedEntry.getJSONArray("measurements").getJSONObject(0)
        assertEquals(setOf("band", "downloadMbps"), measurement.keys().asSequence().toSet())
        assertEquals("LTE_B3", measurement.getString("band"))
        assertEquals(12.5, measurement.getDouble("downloadMbps"), 0.0)
        assertEquals("COMPLETED", storedEntry.getString("outcome"))
        assertTrue(prefs.all.keys.intersect(legacyKeys).isEmpty())
    }

    @Test
    fun corruptHistoryIsReportedRatherThanSilentlyDiscardedOrOverwritten() {
        prefs.edit().putString("run_history_v1", "not-json").commit()
        val repository = SettingsRepository(app)
        assertThrows(org.json.JSONException::class.java) { repository.history() }
        assertThrows(org.json.JSONException::class.java) { repository.appendHistory(entry(1)) }
        assertEquals("not-json", prefs.getString("run_history_v1", null))
    }

    @Test
    fun historyRejectsNonFiniteOrNegativeMeasurementsAtThePersistenceBoundary() {
        for (speed in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0)) {
            assertThrows(IllegalArgumentException::class.java) {
                SettingsRepository(app).appendHistory(entry(1).copy(measurements = listOf(BandMeasurement(LteBand.LTE_B3, speed))))
            }
        }
        assertTrue(SettingsRepository(app).history().isEmpty())
    }

    private fun seedLegacyRun() {
        assertTrue(prefs.edit().putString("macro_mode", "APPLY_BEST")
            .putString("target_band_to_set", "LTE B8").putBoolean("band_setting_applied", true)
            .putInt("scan_step", 2).putString("scan_bands", "LTE B1,LTE B3,LTE B8")
            .putString("scan_speeds", "LTE B8:99").commit())
    }

    private fun entry(time: Long) = RunHistoryEntry(time, HistoryOutcome.COMPLETED,
        listOf(BandMeasurement(LteBand.LTE_B3, 12.5)))
}
