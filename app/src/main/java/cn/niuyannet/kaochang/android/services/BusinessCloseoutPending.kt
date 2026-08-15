package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.utils.PreferenceUtils

internal const val BUSINESS_CLOSEOUT_DISCARD_SOURCE = "营业结束丢弃"

internal fun shouldRefreshTemperatureAfterDiscard(businessCloseout: Boolean): Boolean =
    !businessCloseout

internal interface BusinessCloseoutPendingStore {
    fun loadRaw(): String?
    fun saveRawDurably(raw: String): Boolean
}

internal class SharedPreferenceBusinessCloseoutPendingStore(
    private val file: String = AppConfig.Sp_File_URL,
    private val key: String = KEY_BUSINESS_CLOSEOUT_PENDING
) : BusinessCloseoutPendingStore {
    override fun loadRaw(): String? = PreferenceUtils.getStringPreference(file, key, null)

    override fun saveRawDurably(raw: String): Boolean =
        PreferenceUtils.saveStringPreferenceDurable(file, key, raw)

    companion object {
        private const val KEY_BUSINESS_CLOSEOUT_PENDING = "sp_business_closeout_pending"
    }
}

internal class BusinessCloseoutPendingRepository(
    private val store: BusinessCloseoutPendingStore
) {
    fun isPending(): Boolean = !store.loadRaw().isNullOrBlank()

    fun requestDurably(): Boolean = saveIfChanged("1")

    fun clearDurably(): Boolean = saveIfChanged("")

    private fun saveIfChanged(next: String): Boolean {
        val currentPending = isPending()
        val nextPending = next.isNotBlank()
        if (currentPending == nextPending) {
            return true
        }
        return store.saveRawDurably(next)
    }
}

internal fun shouldCaptureBusinessCloseout(
    isWithinBusinessHours: Boolean,
    onlineStatus: Int,
    errorStatus: Int
): Boolean = !isWithinBusinessHours && (
    onlineStatus == 1 ||
        onlineStatus == 4 ||
        (onlineStatus == 3 && errorStatus != 0)
    )

internal fun canRunBusinessCloseoutDiscard(
    onlineStatus: Int,
    automaticRestSource: Boolean,
    errorStatus: Int,
    inspectionMode: Int,
    modbusConnected: Boolean,
    moveStatus: Int,
    orderPending: Boolean,
    recoveryBlocked: Boolean,
    emergencyResetPending: Boolean,
    lowerPlatformBlocked: Boolean
): Boolean = onlineStatus == 2 &&
    automaticRestSource &&
    errorStatus == 0 &&
    inspectionMode == 0 &&
    modbusConnected &&
    moveStatus == 0 &&
    !orderPending &&
    !recoveryBlocked &&
    !emergencyResetPending &&
    !lowerPlatformBlocked

internal fun canFinalizeBusinessCloseout(
    hasSausage: Boolean,
    hasPendingDiscard: Boolean,
    moveStatus: Int,
    orderPending: Boolean,
    recoveryBlocked: Boolean,
    emergencyResetPending: Boolean,
    hasSupplementEvidence: Boolean
): Boolean = !hasSausage &&
    !hasPendingDiscard &&
    moveStatus == 0 &&
    !orderPending &&
    !recoveryBlocked &&
    !emergencyResetPending &&
    !hasSupplementEvidence

internal fun canIssueBusinessCloseoutDiscardCommand(
    onlineStatus: Int,
    automaticRestSource: Boolean,
    errorStatus: Int,
    inspectionMode: Int,
    faultHeatPauseActive: Int,
    orderPending: Boolean,
    recoveryBlocked: Boolean,
    lowerPlatformBlocked: Boolean
): Boolean = onlineStatus == 2 &&
    automaticRestSource &&
    errorStatus == 0 &&
    inspectionMode == 0 &&
    faultHeatPauseActive == 0 &&
    !orderPending &&
    !recoveryBlocked &&
    !lowerPlatformBlocked
