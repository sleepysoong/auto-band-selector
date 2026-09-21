package com.sleepysoong.autobandselector.automation

/**
 * Production screen profiles. Without a verified on-device capture, every field ID is empty and
 * matching relies on the allowlisted package plus unique known titles. Any unknown window is
 * UntrustedWindow by construction; runtime must fail closed on unsupported firmware.
 */
object SamsungProfiles {
    const val HIDDEN_MENU_PACKAGE = "com.sec.android.app.hiddenmenu"
    val supportedPackages = setOf(SamsungPhoneEntry.SAMSUNG_PHONE_PACKAGE, HIDDEN_MENU_PACKAGE)

    fun production(): SamsungScreenParser = SamsungScreenParser(
        supportedPackages.map { packageName ->
            ScreenProfile(
                WindowIdentity(packageName, ""),
                ScreenKind.entries.toSet(),
                ScreenFieldIds(title = "", digits = "", password = "", rat = "", band = "", sim = "")
            )
        }
    )
}
