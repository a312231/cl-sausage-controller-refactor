package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.bean.KaoPan

object ManualPanPositionDataClearPolicy {

    data class State(
        val moveStatus: Int,
        val onlineStatus: Int,
        val hasProcessingOrder: Boolean
    )

    data class ReadinessDecision(
        val ok: Boolean,
        val reason: String
    )

    data class PanSnapshot(
        val positionSn: Int,
        val startTime: Long,
        val bakingTime: Long,
        val holdingTime: Long,
        val closeTime: Long,
        val tasteCode: String?,
        val tasteName: String?,
        val productId: Int?,
        val productName: String?,
        val temperature: Int,
        val status: Int,
        val isHasSausage: Boolean,
        val isHasGrilling: Boolean,
        val cmdStatusMove: Int,
        val cmdStatusTake: Int,
        val cmdStatusDiscard: Int
    ) {
        fun toLogText(): String {
            return "positionSn=$positionSn，isHasSausage=$isHasSausage，status=$status，" +
                "holdingTime=$holdingTime，tasteCode=${tasteCode ?: "无"}，" +
                "tasteName=${tasteName ?: "无"}，temperature=$temperature，" +
                "isHasGrilling=$isHasGrilling，cmdStatusMove=$cmdStatusMove，" +
                "cmdStatusTake=$cmdStatusTake，cmdStatusDiscard=$cmdStatusDiscard"
        }
    }

    data class ClearResult(
        val pans: List<KaoPan>,
        val cleared: Boolean,
        val before: PanSnapshot?,
        val after: PanSnapshot?
    )

    fun checkReadiness(state: State): ReadinessDecision {
        return when {
            state.moveStatus != 0 -> ReadinessDecision(
                ok = false,
                reason = "机械动作状态不是空闲：moveStatus=${state.moveStatus}"
            )
            state.onlineStatus == 4 -> ReadinessDecision(
                ok = false,
                reason = "设备正在出餐：onlineStatus=4"
            )
            state.hasProcessingOrder -> ReadinessDecision(
                ok = false,
                reason = "存在处理中的订单"
            )
            else -> ReadinessDecision(ok = true, reason = "允许清除")
        }
    }

    fun clearPositionData(
        pans: List<KaoPan>,
        positionSn: Int,
        bakingTimeMinutes: Int,
        discardTimeHours: Int
    ): ClearResult {
        var before: PanSnapshot? = null
        var after: PanSnapshot? = null
        var cleared = false
        val updated = pans.map { pan ->
            if (pan.positionSn != positionSn) {
                return@map pan
            }
            before = pan.snapshot()
            pan.apply {
                startTime = 0L
                bakingTime = bakingTimeMinutes * 60L * 1000L
                holdingTime = 0L
                closeTime = discardTimeHours * 60L * 60L * 1000L
                taste = null
                temperature = 0
                status = 0
                isHasSausage = false
                isHasGrilling = false
                cmdStatusMove = 0
                cmdStatusTake = 0
                cmdStatusDiscard = 0
            }
            after = pan.snapshot()
            cleared = true
            pan
        }
        return ClearResult(
            pans = updated,
            cleared = cleared,
            before = before,
            after = after
        )
    }

    fun snapshotOf(pan: KaoPan): PanSnapshot = pan.snapshot()

    private fun KaoPan.snapshot(): PanSnapshot {
        return PanSnapshot(
            positionSn = positionSn,
            startTime = startTime,
            bakingTime = bakingTime,
            holdingTime = holdingTime,
            closeTime = closeTime,
            tasteCode = taste?.tasteCode,
            tasteName = taste?.tasteName,
            productId = taste?.productId,
            productName = taste?.productName,
            temperature = temperature,
            status = status,
            isHasSausage = isHasSausage,
            isHasGrilling = isHasGrilling,
            cmdStatusMove = cmdStatusMove,
            cmdStatusTake = cmdStatusTake,
            cmdStatusDiscard = cmdStatusDiscard
        )
    }
}
