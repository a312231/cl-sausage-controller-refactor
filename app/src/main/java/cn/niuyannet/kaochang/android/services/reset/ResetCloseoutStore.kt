package cn.niuyannet.kaochang.android.services.reset

import com.alibaba.fastjson.JSON
import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.utils.PreferenceUtils

enum class PrepareOrderEvidenceResult {
    NEW,
    EXISTING,
    REJECTED
}

enum class Out4ConfirmResult {
    ADVANCED_THIS_CALL,
    ALREADY_AT_QUANTITY,
    NOT_PREPARED,
    WRITE_FAILED,
    CORRUPTED
}

class ResetCloseoutStore(
    private val readRaw: () -> String?,
    private val writeRawDurably: (String) -> Boolean
) {
    @Synchronized
    fun load(): ResetCloseoutRecord? {
        val raw = readRaw()?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            JSON.parseObject(raw, ResetCloseoutRecord::class.java).requireValid()
        }.getOrElse { error ->
            throw IllegalStateException("复位收口证据损坏", error)
        }
    }

    @Synchronized
    fun saveDurable(record: ResetCloseoutRecord): Boolean =
        writeRawDurably(JSON.toJSONString(record.requireValid()))

    @Synchronized
    fun clearDurable(): Boolean = writeRawDurably("")

    @Synchronized
    fun prepareOrderDurable(
        orderNo: String,
        items: List<ResetOrderItem>,
        previousOnlineStatus: Int,
        previousOrderBlockReason: String
    ): PrepareOrderEvidenceResult {
        val current = load()
        if (current != null) {
            if (current.phase != ResetCloseoutPhase.ORDER_ACTIVE || current.orderNo != orderNo) {
                return PrepareOrderEvidenceResult.REJECTED
            }
            val sameItems = current.items.map { it.itemId to it.quantity } ==
                items.map { it.itemId to it.quantity }
            return if (sameItems) {
                PrepareOrderEvidenceResult.EXISTING
            } else {
                PrepareOrderEvidenceResult.REJECTED
            }
        }
        return if (saveDurable(
            ResetCloseoutRecord(
                phase = ResetCloseoutPhase.ORDER_ACTIVE,
                orderNo = orderNo,
                items = items,
                previousOnlineStatus = previousOnlineStatus,
                previousOrderBlockReason = previousOrderBlockReason
            )
        )) PrepareOrderEvidenceResult.NEW else PrepareOrderEvidenceResult.REJECTED
    }

    @Synchronized
    fun confirmOut4Durable(orderNo: String, itemId: Long): Out4ConfirmResult {
        val current = runCatching { load() }.getOrElse { return Out4ConfirmResult.CORRUPTED }
            ?: return Out4ConfirmResult.NOT_PREPARED
        if (
            current.phase !in setOf(
                ResetCloseoutPhase.ORDER_ACTIVE,
                ResetCloseoutPhase.PREPARED,
                ResetCloseoutPhase.RESET_WRITTEN
            ) || current.orderNo != orderNo
        ) return Out4ConfirmResult.NOT_PREPARED
        val item = current.items.firstOrNull { it.itemId == itemId }
            ?: return Out4ConfirmResult.NOT_PREPARED
        if (item.confirmedOut4 >= item.quantity) {
            return Out4ConfirmResult.ALREADY_AT_QUANTITY
        }
        val updated = ResetCloseoutPolicy.confirmOut4(current, itemId)
        return if (saveDurable(updated)) {
            Out4ConfirmResult.ADVANCED_THIS_CALL
        } else {
            Out4ConfirmResult.WRITE_FAILED
        }
    }

    @Synchronized
    fun clearOrderDurable(orderNo: String): Boolean {
        val current = load() ?: return true
        if (current.phase != ResetCloseoutPhase.ORDER_ACTIVE || current.orderNo != orderNo) return false
        return clearDurable()
    }

    companion object {
        const val KEY_RESET_CLOSEOUT_RECORD = "sp_reset_closeout_record_json"

        fun sharedPreferences(): ResetCloseoutStore = ResetCloseoutStore(
            readRaw = {
                PreferenceUtils.getStringPreference(
                    AppConfig.Sp_File_URL,
                    KEY_RESET_CLOSEOUT_RECORD,
                    null
                )
            },
            writeRawDurably = { raw ->
                PreferenceUtils.saveStringPreferenceDurable(
                    AppConfig.Sp_File_URL,
                    KEY_RESET_CLOSEOUT_RECORD,
                    raw
                )
            }
        )
    }
}
