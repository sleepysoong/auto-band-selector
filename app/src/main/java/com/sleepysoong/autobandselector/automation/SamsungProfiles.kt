package com.sleepysoong.autobandselector.automation

/**
 * Production screen profiles. Without a verified on-device capture, every field ID is empty and
 * matching relies on the allowlisted package plus unique known titles. Any unknown window is
 * UntrustedWindow by construction; runtime must fail closed on unsupported firmware.
 */
object SamsungProfiles {
    fun production(): SamsungScreenParser = SamsungScreenParser(
        listOf(
            ScreenProfile(
                WindowIdentity(SamsungPhoneEntry.SAMSUNG_PHONE_PACKAGE, ""),
                ScreenKind.entries.toSet(),
                ScreenFieldIds(title = "", digits = "", password = "", rat = "", band = "", sim = "")
            )
        )
    )
}
