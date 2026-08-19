package cn.niuyannet.kaochang.android.services.reset

import cn.niuyannet.kaochang.android.init.AppConfigBean

data class LowerBoardResetResult(
    val success: Boolean,
    val resetWritten: Boolean,
    val reason: String
)

enum class ResetWriteResult {
    SUCCESS,
    WRITE_FAILED,
    PERSIST_FAILED
}

class LowerBoardResetCoordinator(
    private val store: ResetCloseoutStore,
    private val newResetId: () -> String,
    private val nowMs: () -> Long,
    private val currentProcessId: () -> String,
    private val prepareAndApplyTemporaryBlock: (ResetCloseoutRecord, String) -> Boolean,
    private val confirmServerBlock: () -> Boolean,
    private val writeResetAndPersistResult: (ResetCloseoutRecord, Long) -> ResetWriteResult,
    private val scheduleColdRestart: () -> Boolean
) {
    fun execute(
        kind: ResetKind,
        currentOnlineStatus: Int,
        currentOrderBlockReason: String,
        currentHomeQrHidden: Int,
        currentScanBlockStatus: Int
    ): LowerBoardResetResult {
        val original = store.load()
        if (original?.phase == ResetCloseoutPhase.RESET_WRITTEN) {
            if (original.originProcessId == currentProcessId() && scheduleColdRestart()) {
                return LowerBoardResetResult(true, true, "999 已写入，App 正在重试冷重启")
            }
            return LowerBoardResetResult(false, true, "999 已写入，等待 App 冷重启后收口")
        }
        if (original?.phase == ResetCloseoutPhase.SERVER_CLOSED) {
            return LowerBoardResetResult(false, true, "复位订单已收口，等待恢复稳定状态")
        }
        val previous = if (original?.phase == ResetCloseoutPhase.PREPARED) {
            original
        } else {
            (original ?: ResetCloseoutRecord()).copy(
                previousOnlineStatus = currentOnlineStatus,
                previousOrderBlockReason = currentOrderBlockReason,
                previousHomeQrHidden = currentHomeQrHidden,
                previousScanBlockStatus = currentScanBlockStatus
            )
        }
        val effectiveKind = if (original?.kind == ResetKind.EMERGENCY || kind == ResetKind.EMERGENCY) {
            ResetKind.EMERGENCY
        } else {
            ResetKind.NORMAL
        }
        val prepared = previous.copy(
            resetId = original?.resetId ?: newResetId(),
            originProcessId = currentProcessId(),
            kind = effectiveKind,
            phase = ResetCloseoutPhase.PREPARED,
            resetWrittenAtMs = null
        ).requireValid()
        if (!prepareAndApplyTemporaryBlock(prepared, ORDER_BLOCK_REASON)) {
            return LowerBoardResetResult(false, false, "复位临时禁售落盘失败，保留准备证据等待重试")
        }
        if (!confirmServerBlock()) {
            return LowerBoardResetResult(false, false, "后台未确认临时禁售，未写入 999，保留准备状态等待重试")
        }
        when (writeResetAndPersistResult(prepared, nowMs().coerceAtLeast(1L))) {
            ResetWriteResult.PERSIST_FAILED -> return LowerBoardResetResult(
                false,
                true,
                "999 已写入但结果证据落盘失败，保持机械写禁用和临时禁售"
            )
            ResetWriteResult.WRITE_FAILED -> {
                return LowerBoardResetResult(
                    false,
                    false,
                    "999 写入失败，保持临时禁售和机械写禁用，等待重试"
                )
            }
            ResetWriteResult.SUCCESS -> Unit
        }
        if (!scheduleColdRestart()) {
            return LowerBoardResetResult(false, true, "999 已写入，但 App 冷重启安排失败")
        }
        return LowerBoardResetResult(true, true, "999 已写入，App 正在冷重启")
    }

    companion object {
        const val ORDER_BLOCK_REASON = AppConfigBean.ORDER_BLOCK_REASON_SYSTEM_RESET_IN_PROGRESS
    }
}
