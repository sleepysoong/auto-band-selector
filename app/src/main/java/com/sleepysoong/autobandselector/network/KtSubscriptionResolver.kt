package com.sleepysoong.autobandselector.network

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager

data class SubscriptionCandidate(
    val subscriptionId: Int,
    val logicalSlotIndex: Int,
    val isEmbedded: Boolean,
    val mcc: String?,
    val mnc: String?
)

sealed interface KtSubscriptionResolution {
    data object PermissionRequired : KtSubscriptionResolution
    data object EmbeddedSubscriptionsUnsupported : KtSubscriptionResolution
    data object TelephonyUnavailable : KtSubscriptionResolution
    data object NoEmbeddedKt : KtSubscriptionResolution
    data object InvalidSubscriptionMetadata : KtSubscriptionResolution
    data class MultipleEmbeddedKt(val count: Int) : KtSubscriptionResolution
    data class WrongDefaultData(val candidate: SubscriptionCandidate, val defaultDataSubscriptionId: Int) : KtSubscriptionResolution
    data class SlotConfirmationRequired(val candidate: SubscriptionCandidate) : KtSubscriptionResolution
    data class Ready(val candidate: SubscriptionCandidate) : KtSubscriptionResolution
}

/** Pure policy over active subscriptions. Names and physical tray positions are not evidence. */
object KtCandidateSelector {
    fun select(
        candidates: List<SubscriptionCandidate>,
        defaultDataSubscriptionId: Int,
        confirmedLogicalSlotIndex: Int? = null
    ): KtSubscriptionResolution {
        val matches = candidates.filter { it.isEmbedded && it.mcc == "450" && it.mnc == "08" }
        if (matches.isEmpty()) return KtSubscriptionResolution.NoEmbeddedKt
        if (matches.size > 1) return KtSubscriptionResolution.MultipleEmbeddedKt(matches.size)
        val candidate = matches.single()
        if (candidate.subscriptionId < 0 || candidate.logicalSlotIndex < 0) {
            return KtSubscriptionResolution.InvalidSubscriptionMetadata
        }
        if (candidate.subscriptionId != defaultDataSubscriptionId) {
            return KtSubscriptionResolution.WrongDefaultData(candidate, defaultDataSubscriptionId)
        }
        if (candidate.logicalSlotIndex != confirmedLogicalSlotIndex) {
            return KtSubscriptionResolution.SlotConfirmationRequired(candidate)
        }
        return KtSubscriptionResolution.Ready(candidate)
    }
}

/**
 * Public-API adapter. Ready is a fresh eligibility snapshot, never an instruction to run.
 * Call again before starting and after subscription/default-data changes. No identifiers are saved.
 */
class KtSubscriptionResolver(context: Context) {
    private val appContext = context.applicationContext

    fun resolve(confirmedLogicalSlotIndex: Int? = null): KtSubscriptionResolution {
        if (appContext.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return KtSubscriptionResolution.PermissionRequired
        }
        // SubscriptionInfo.isEmbedded was added in API 28; never infer eSIM from a slot on 26/27.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return KtSubscriptionResolution.EmbeddedSubscriptionsUnsupported
        }
        val subscriptions = appContext.getSystemService(SubscriptionManager::class.java)
            ?: return KtSubscriptionResolution.TelephonyUnavailable
        return try {
            val candidates = subscriptions.activeSubscriptionInfoList.orEmpty().map { info ->
                val (mcc, mnc) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    info.mccString to info.mncString
                } else {
                    // API 28 has only lossy/deprecated integer MCC/MNC on SubscriptionInfo.
                    val telephony = appContext.getSystemService(TelephonyManager::class.java)
                        ?: return KtSubscriptionResolution.TelephonyUnavailable
                    val operator = telephony.createForSubscriptionId(info.subscriptionId).simOperator
                    if (operator != null && operator.length in 5..6 && operator.all { it in '0'..'9' }) {
                        operator.take(3) to operator.substring(3)
                    } else {
                        null to null
                    }
                }
                SubscriptionCandidate(info.subscriptionId, info.simSlotIndex, info.isEmbedded, mcc, mnc)
            }
            KtCandidateSelector.select(candidates, SubscriptionManager.getDefaultDataSubscriptionId(), confirmedLogicalSlotIndex)
        } catch (error: SecurityException) {
            // Permission can be revoked between the check and the binder call.
            KtSubscriptionResolution.PermissionRequired
        } catch (error: UnsupportedOperationException) {
            KtSubscriptionResolution.TelephonyUnavailable
        }
    }
}
