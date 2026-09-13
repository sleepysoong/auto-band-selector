package com.sleepysoong.autobandselector.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

enum class Carrier(val storedValue: String) { SKT("SKT"), KT("KT"), LGU_PLUS("LGU+") }
data class SettingsConfiguration(val deviceCarrier: Carrier = Carrier.SKT, val simCarrier: Carrier = Carrier.SKT)
enum class LteBand { LTE_B1, LTE_B3, LTE_B5, LTE_B7, LTE_B8 }
enum class HistoryOutcome { COMPLETED, CANCELLED, FAILED }
data class BandMeasurement(val band: LteBand, val downloadMbps: Double)
data class RunHistoryEntry(val completedAtEpochMillis: Long, val outcome: HistoryOutcome, val measurements: List<BandMeasurement>)

/**
 * Configuration and completed-run history only. No mode, target, authorization or progress API.
 * Construct before exposing new run entry points; migration removes the six legacy execution keys.
 * Writes are synchronous and report disk failure. Call from a worker thread in the UI integration.
 */
class SettingsRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("BandSelectorPrefs", Context.MODE_PRIVATE)

    init {
        val legacyKeys = listOf("macro_mode", "target_band_to_set", "band_setting_applied",
            "scan_step", "scan_bands", "scan_speeds")
        if (legacyKeys.any(preferences::contains)) {
            val editor = preferences.edit()
            legacyKeys.forEach(editor::remove)
            persist(editor)
        }
    }

    val confirmedLogicalSlotIndex: Int?
        get() = if (preferences.contains(CONFIRMED_SLOT)) preferences.getInt(CONFIRMED_SLOT, -1).also {
            require(it >= 0) { "Invalid stored logical slot" }
        } else null

    fun loadConfiguration() = SettingsConfiguration(
        readCarrier("device_carrier"), readCarrier("sim_carrier")
    )

    fun saveConfiguration(configuration: SettingsConfiguration) {
        persist(preferences.edit()
            .putString("device_carrier", configuration.deviceCarrier.storedValue)
            .putString("sim_carrier", configuration.simCarrier.storedValue))
    }

    /** Only call following an explicit user confirmation of the zero-based logical slot. */
    fun confirmLogicalSlot(logicalSlotIndex: Int) {
        require(logicalSlotIndex >= 0) { "Logical slot must be nonnegative" }
        persist(preferences.edit().putInt(CONFIRMED_SLOT, logicalSlotIndex))
    }

    fun clearLogicalSlotConfirmation() {
        persist(preferences.edit().remove(CONFIRMED_SLOT))
    }

    /** Newest appended first. Malformed persisted data is reported, never replaced with empty history. */
    fun history(): List<RunHistoryEntry> {
        val json = JSONArray(preferences.getString(HISTORY, "[]"))
        return (0 until minOf(json.length(), HISTORY_LIMIT)).map { index ->
            val entry = json.getJSONObject(index)
            val measurements = entry.getJSONArray("measurements")
            RunHistoryEntry(entry.getLong("completedAtEpochMillis"), HistoryOutcome.valueOf(entry.getString("outcome")),
                (0 until measurements.length()).map { measurementIndex ->
                    val measurement = measurements.getJSONObject(measurementIndex)
                    BandMeasurement(LteBand.valueOf(measurement.getString("band")), measurement.getDouble("downloadMbps"))
                })
        }
    }

    fun appendHistory(entry: RunHistoryEntry) {
        require(entry.completedAtEpochMillis >= 0) { "Completion time must be nonnegative" }
        require(entry.measurements.all { it.downloadMbps.isFinite() && it.downloadMbps >= 0 }) {
            "Download measurements must be finite and nonnegative"
        }
        // SharedPreferences is shared across repository instances in this process.
        synchronized(preferences) {
            val entries = (listOf(entry) + history()).take(HISTORY_LIMIT)
            val json = JSONArray()
            entries.forEach { item ->
                val measurements = JSONArray()
                item.measurements.forEach { measurement ->
                    measurements.put(JSONObject().put("band", measurement.band.name)
                        .put("downloadMbps", measurement.downloadMbps))
                }
                json.put(JSONObject().put("completedAtEpochMillis", item.completedAtEpochMillis)
                    .put("outcome", item.outcome.name).put("measurements", measurements))
            }
            persist(preferences.edit().putString(HISTORY, json.toString()))
        }
    }

    private fun readCarrier(key: String): Carrier {
        val value = preferences.getString(key, Carrier.SKT.storedValue)
        return Carrier.entries.first { it.storedValue == value }
    }

    private fun persist(editor: SharedPreferences.Editor) {
        check(editor.commit()) { "Settings persistence failed" }
    }

    private companion object {
        const val CONFIRMED_SLOT = "confirmed_logical_slot_index"
        const val HISTORY = "run_history_v1"
        const val HISTORY_LIMIT = 20
    }
}
