package com.sleepysoong.autobandselector

import android.app.Application
import android.content.Intent
import com.sleepysoong.autobandselector.automation.BandProbe
import com.sleepysoong.autobandselector.automation.BandScanRuntime
import com.sleepysoong.autobandselector.automation.PackageManagerSamsungPhoneActivityResolver
import com.sleepysoong.autobandselector.automation.RuntimeBandAutomation
import com.sleepysoong.autobandselector.automation.SamsungProfiles
import com.sleepysoong.autobandselector.data.SettingsRepository
import com.sleepysoong.autobandselector.network.CellularSpeedProbe
import com.sleepysoong.autobandselector.network.KtSubscriptionResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Owns process-lifetime scan state; activities attach only a launcher seam. */
class BandSelectorApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Current activity renderer may launch the verified Samsung dialer request. */
    var activityLauncher: ((Intent) -> Unit)? = null

    val runtime: BandScanRuntime by lazy {
        val resolver = PackageManagerSamsungPhoneActivityResolver(packageManager)
        val parser = SamsungProfiles.production()
        BandScanRuntime(
            scope = appScope,
            resolver = KtSubscriptionResolver(this),
            settings = SettingsRepository(this),
            automationFactory = {
                RuntimeBandAutomation(appScope, parser, resolver) { intent ->
                    activityLauncher?.invoke(intent)
                }
            },
            probeFactory = {
                BandProbe { subId, _, _ -> CellularSpeedProbe(this).measureSelected(subId) }
            }
        )
    }
}
