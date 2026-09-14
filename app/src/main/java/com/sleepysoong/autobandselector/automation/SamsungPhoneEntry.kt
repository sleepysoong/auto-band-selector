package com.sleepysoong.autobandselector.automation

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

data class ResolvedPhoneActivity(
    val component: ComponentName,
    val exported: Boolean,
    val enabled: Boolean
)

fun interface SamsungPhoneActivityResolver {
    fun query(request: Intent): List<ResolvedPhoneActivity>
}

class PackageManagerSamsungPhoneActivityResolver(
    private val packageManager: PackageManager
) : SamsungPhoneActivityResolver {
    override fun query(request: Intent): List<ResolvedPhoneActivity> =
        packageManager.queryIntentActivities(request, PackageManager.MATCH_DEFAULT_ONLY).mapNotNull { match ->
            val info = match.activityInfo ?: return@mapNotNull null
            ResolvedPhoneActivity(ComponentName(info.packageName, info.name), info.exported, info.enabled)
        }
}

/** Activity entry belongs to the application/UI adapter; AccessibilityService never starts it. */
object SamsungPhoneEntry {
    const val DIAL_NUMBER = "319712358"
    const val SAMSUNG_PHONE_PACKAGE = "com.samsung.android.dialer"
    private val allowedComponents = setOf(
        ComponentName(SAMSUNG_PHONE_PACKAGE, "com.samsung.android.dialer.DialtactsActivity")
    )

    fun dialIntent(resolver: SamsungPhoneActivityResolver): Intent? {
        val implicit = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$DIAL_NUMBER")).apply {
            `package` = SAMSUNG_PHONE_PACKAGE
        }
        val resolved = resolver.query(implicit)
            .filter { it.exported && it.enabled }
            .singleOrNull() ?: return null
        if (resolved.component !in allowedComponents) return null
        return Intent(implicit).apply {
            component = resolved.component
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
