package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.fault.FaultCode
import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.model.bean.KaoPan

object ManualPanMovePolicy {

    data class State(
        val sourcePan: KaoPan?,
        val targetPan: KaoPan?,
        val sourcePositionSn: Int,
        val targetPositionSn: Int,
        val appConfigIsEnable: Int,
        val algorithmIsEnabled: Boolean,
        val moveStatus: Int,
        val onlineStatus: Int,
        val errorStatus: Int,
        val errorCode: String?,
        val errorReason: String?,
        val inspectionMode: Int,
        val hasProcessingOrder: Boolean
    )

    data class ReadinessDecision(
        val ok: Boolean,
        val reason: String,
        val requiresConfirmation: Boolean = false,
        val warning: String? = null
    )

    fun checkReadiness(state: State): ReadinessDecision {
        return when {
            state.sourcePan == null -> rejected("源工位不存在：source=${state.sourcePositionSn}")
            state.targetPan == null -> rejected("目标工位不存在：target=${state.targetPositionSn}")
            state.sourcePositionSn == state.targetPositionSn -> rejected("源工位和目标工位不能相同：${state.sourcePositionSn}")
            !state.sourcePan.isHasSausage -> rejected("源工位无烤肠：source=${state.sourcePositionSn}")
            state.targetPan.isHasSausage -> rejected("目标工位已有烤肠：target=${state.targetPositionSn}")
            state.appConfigIsEnable != 0 || state.algorithmIsEnabled -> rejected(
                "请先关闭烤肠算法：isEnable=${state.appConfigIsEnable}, algorithmIsEnabled=${state.algorithmIsEnabled}"
            )
            state.moveStatus != 0 -> rejected("机械动作状态不是空闲：moveStatus=${state.moveStatus}")
            state.onlineStatus == 4 -> rejected("设备正在出餐：onlineStatus=4")
            state.hasProcessingOrder -> rejected("存在处理中的订单")
            isPanMoveFault(state.errorCode) -> rejected(
                "当前故障为搬盘机构故障，禁止手动搬盘：errorCode=${state.errorCode}"
            )
            state.errorStatus != AppConfigBean.ERROR_STATUS_NORMAL ||
                state.inspectionMode != AppConfigBean.INSPECTION_MODE_NORMAL -> {
                val warning = buildWarning(state)
                ReadinessDecision(
                    ok = true,
                    reason = "允许搬移，但需要确认故障/检修状态",
                    requiresConfirmation = true,
                    warning = warning
                )
            }
            else -> ReadinessDecision(ok = true, reason = "允许搬移")
        }
    }

    fun isPanMoveFault(errorCode: String?): Boolean {
        val normalized = errorCode?.trim()
        return normalized == FaultCode.KAOPAN_MOVE_FAILED.name ||
            normalized == FaultCode.KAOPAN_MOVE_TIMEOUT.name
    }

    private fun rejected(reason: String): ReadinessDecision =
        ReadinessDecision(ok = false, reason = reason)

    private fun buildWarning(state: State): String {
        val code = state.errorCode?.takeIf { it.isNotBlank() } ?: "无"
        val reason = state.errorReason?.takeIf { it.isNotBlank() } ?: "无"
        return "当前设备处于故障或检修状态，请现场确认机械安全后再搬盘。\n\n" +
            "errorStatus=${state.errorStatus}\n" +
            "errorCode=$code\n" +
            "errorReason=$reason\n" +
            "inspectionMode=${state.inspectionMode}"
    }
}
