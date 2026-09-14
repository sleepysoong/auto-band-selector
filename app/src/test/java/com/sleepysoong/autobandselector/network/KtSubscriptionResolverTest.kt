package com.sleepysoong.autobandselector.network

import android.Manifest
import android.app.Application
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSubscriptionManager
import org.robolectric.shadows.ShadowSubscriptionManager.SubscriptionInfoBuilder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KtSubscriptionResolverTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val kt = SubscriptionCandidate(71, 0, true, "450", "08")

    @Before
    fun resetPermission() {
        shadowOf(app).denyPermissions(Manifest.permission.READ_PHONE_STATE)
    }

    @Test
    fun permissionDenialHasTypedOutcomeInsteadOfReadingSubscriptions() {
        assertEquals(KtSubscriptionResolution.PermissionRequired, KtSubscriptionResolver(app).resolve())
    }

    @Test
    fun permissionRevokedDuringPlatformReadHasTypedOutcome() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        shadowOf(app.getSystemService(SubscriptionManager::class.java)).setReadPhoneStatePermission(false)
        assertEquals(KtSubscriptionResolution.PermissionRequired, KtSubscriptionResolver(app).resolve())
    }

    @Test
    fun singleEmbeddedKtUsesExactMccMncAndRequiresExplicitSlotConfirmation() {
        assertEquals(KtSubscriptionResolution.SlotConfirmationRequired(kt), select(listOf(kt)))
        assertEquals(KtSubscriptionResolution.Ready(kt), select(listOf(kt), confirmed = 0))
    }

    @Test
    fun noEmbeddedKtIncludesPhysicalKtAndOtherOrMissingOperators() {
        val rejected = listOf(
            kt.copy(isEmbedded = false), kt.copy(mcc = "310"), kt.copy(mnc = "05"),
            kt.copy(mcc = null), kt.copy(mnc = null), kt.copy(mnc = "8"), kt.copy(mnc = "008")
        )
        assertEquals(KtSubscriptionResolution.NoEmbeddedKt, select(rejected))
        assertEquals(KtSubscriptionResolution.NoEmbeddedKt, select(emptyList()))
    }

    @Test
    fun defaultDataDoesNotDisambiguateMultipleEmbeddedKtSubscriptions() {
        assertEquals(KtSubscriptionResolution.MultipleEmbeddedKt(2),
            select(listOf(kt, kt.copy(subscriptionId = 72, logicalSlotIndex = 1)), confirmed = 0))
    }

    @Test
    fun wrongOrAbsentDefaultDataBlocksEvenAConfirmedCandidate() {
        for (defaultId in listOf(72, SubscriptionManager.INVALID_SUBSCRIPTION_ID)) {
            assertEquals(KtSubscriptionResolution.WrongDefaultData(kt, defaultId),
                select(listOf(kt), defaultId, 0))
        }
    }

    @Test
    fun embeddedDoesNotMeanSlotTwoAndStaleConfirmationDoesNotMatch() {
        for (slot in listOf(0, 1, 2)) {
            val candidate = kt.copy(logicalSlotIndex = slot)
            assertEquals(KtSubscriptionResolution.Ready(candidate), select(listOf(candidate), confirmed = slot))
            assertEquals(KtSubscriptionResolution.SlotConfirmationRequired(candidate),
                select(listOf(candidate), confirmed = slot + 1))
        }
    }

    @Test
    fun invalidActiveSubscriptionMetadataCannotBecomeReady() {
        for (candidate in listOf(kt.copy(logicalSlotIndex = -1), kt.copy(subscriptionId = -1))) {
            assertEquals(KtSubscriptionResolution.InvalidSubscriptionMetadata,
                select(listOf(candidate), candidate.subscriptionId, candidate.logicalSlotIndex))
        }
    }

    @Test
    fun publicPlatformAdapterReadsEmbeddedMetadataNotMisleadingCarrierName() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        val subscriptions = shadowOf(app.getSystemService(SubscriptionManager::class.java))
        subscriptions.setActiveSubscriptionInfos(
            SubscriptionInfoBuilder.newBuilder().setId(71).setSimSlotIndex(0)
                .setIsEmbedded(true).setMcc("450").setMnc("08")
                .setCarrierName("unrelated label").setDisplayName("private nickname")
                .setIccId("sensitive-iccid-fixture").setNumber("sensitive-number-fixture")
                .buildSubscriptionInfo(),
            SubscriptionInfoBuilder.newBuilder().setId(72).setSimSlotIndex(1)
                .setIsEmbedded(true).setMcc("450").setMnc("05").setCarrierName("KT")
                .buildSubscriptionInfo()
        )
        ShadowSubscriptionManager.setDefaultDataSubscriptionId(71)
        assertEquals(KtSubscriptionResolution.Ready(kt), KtSubscriptionResolver(app).resolve(0))
        ShadowSubscriptionManager.setDefaultDataSubscriptionId(72)
        assertEquals(KtSubscriptionResolution.WrongDefaultData(kt, 72), KtSubscriptionResolver(app).resolve(0))
    }

    @Test
    @Config(sdk = [28])
    fun api28ReadsSubscriptionScopedSimOperatorWithoutDeprecatedMccMncIntegers() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        shadowOf(app.getSystemService(SubscriptionManager::class.java)).setActiveSubscriptionInfos(
            SubscriptionInfoBuilder.newBuilder().setId(71).setSimSlotIndex(0)
                .setIsEmbedded(true).buildSubscriptionInfo()
        )
        val telephony = app.getSystemService(TelephonyManager::class.java)
        shadowOf(telephony).setTelephonyManagerForSubscriptionId(71, telephony)
        shadowOf(telephony).setSimOperator("45008")
        ShadowSubscriptionManager.setDefaultDataSubscriptionId(71)
        assertEquals(KtSubscriptionResolution.Ready(kt), KtSubscriptionResolver(app).resolve(0))
    }

    @Test
    @Config(sdk = [26, 27])
    fun pre28DoesNotGuessEmbeddedStatus() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        assertEquals(KtSubscriptionResolution.EmbeddedSubscriptionsUnsupported,
            KtSubscriptionResolver(app).resolve(0))
    }

    @Test
    fun manifestDeclaresOnlyRequestedNewPermissionSet() {
        val declared = requireNotNull(app.packageManager.getPackageInfo(app.packageName,
            android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions).toSet()
        assertTrue(declared.containsAll(setOf(Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_NETWORK_STATE)))
        assertFalse(declared.contains(Manifest.permission.CHANGE_NETWORK_STATE))
        assertFalse(declared.contains(Manifest.permission.READ_PHONE_NUMBERS))
        assertFalse(declared.contains("android.permission.READ_PRIVILEGED_PHONE_STATE"))
    }

    private fun select(candidates: List<SubscriptionCandidate>, defaultId: Int = 71, confirmed: Int? = null) =
        KtCandidateSelector.select(candidates, defaultId, confirmed)
}
