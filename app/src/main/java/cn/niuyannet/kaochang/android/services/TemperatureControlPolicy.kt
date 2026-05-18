package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.bean.KaoPan

object TemperatureControlPolicy {
    enum class ZoneMode {
        HEATING,
        KEEP_WARM,
        STOP
    }

    data class ZoneTemperatureDecision(
        val area: Int,
        val positions: IntRange,
        val mode: ZoneMode,
        val targetTemperature: Int
    )

    data class EmptyPanStopDecision(
        val shouldStopAllHeating: Boolean,
        val nextHasAnySausage: Boolean
    )

    data class ManualRecoveryHeatingDecision(
        val canRecoverDeviceStatus: Boolean,
        val shouldRestoreTemperature: Boolean,
        val reason: String
    )

    private val zoneDefinitions = listOf(
        1 to (1..9),
        2 to (13..21),
        3 to (25..33)
    )

    fun decideZoneTemperatures(
        pans: List<KaoPan>,
        keepWarmTemperature: Int,
        heatingTemperature: Int,
        heatingOffsetsByArea: Map<Int, Int> = emptyMap(),
        pendingDiscardPositions: Collection<Int> = emptyList()
    ): List<ZoneTemperatureDecision> {
        return zoneDefinitions.map { (area, positions) ->
            val zonePans = pans.filter {
                it.positionSn in positions && it.positionSn !in pendingDiscardPositions
            }
            val mode = when {
                zonePans.any { it.isHasSausage && it.status == 1 } -> ZoneMode.HEATING
                zonePans.any { it.isHasSausage && (it.status == 2 || it.holdingTime > 0L) } -> ZoneMode.KEEP_WARM
                else -> ZoneMode.STOP
            }
            ZoneTemperatureDecision(
                area = area,
                positions = positions,
                mode = mode,
                targetTemperature = when (mode) {
                    ZoneMode.HEATING -> FourFTemperaturePolicy.clampTargetTemperature(
                        heatingTemperature + (heatingOffsetsByArea[area] ?: 0)
                    )
                    ZoneMode.KEEP_WARM -> FourFTemperaturePolicy.clampTargetTemperature(keepWarmTemperature)
                    ZoneMode.STOP -> 0
                }
            )
        }
    }

    fun hasAnySausageInPhysicalPans(pans: List<KaoPan>): Boolean {
        return pans.any { it.positionSn in 1..33 && it.isHasSausage }
    }

    fun decideEmptyPanStop(
        hasAnySausage: Boolean,
        previousHasAnySausage: Boolean?,
        force: Boolean
    ): EmptyPanStopDecision {
        if (hasAnySausage) {
            return EmptyPanStopDecision(
                shouldStopAllHeating = false,
                nextHasAnySausage = true
            )
        }

        return EmptyPanStopDecision(
            shouldStopAllHeating = force || previousHasAnySausage != false,
            nextHasAnySausage = false
        )
    }

    fun shouldRestoreHeatingAfterStartup(
        hasRestored: Boolean,
        modbusConnected: Boolean
    ): Boolean {
        return !hasRestored && modbusConnected
    }

    fun decideManualRecoveryHeating(
        modbusConnected: Boolean,
        actionGroupReady: Boolean,
        legacyActionStatus201: Int,
        moveStatus: Int,
        panMoveResult203: Int?,
        targetOnlineStatus: Int
    ): ManualRecoveryHeatingDecision {
        return when {
            !modbusConnected -> ManualRecoveryHeatingDecision(
                canRecoverDeviceStatus = false,
                shouldRestoreTemperature = false,
                reason = "下位机串口未连接"
            )
            !actionGroupReady -> ManualRecoveryHeatingDecision(
                canRecoverDeviceStatus = false,
                shouldRestoreTemperature = false,
                reason = "300/400动作组恢复检查未通过"
            )
            legacyActionStatus201 != 0 -> ManualRecoveryHeatingDecision(
                canRecoverDeviceStatus = false,
                shouldRestoreTemperature = false,
                reason = "201（旧动作状态寄存器）=$legacyActionStatus201（非0=旧协议动作未空闲）"
            )
            moveStatus == 3 && panMoveResult203 != 0 -> ManualRecoveryHeatingDecision(
                canRecoverDeviceStatus = false,
                shouldRestoreTemperature = false,
                reason = "moveStatus（机械动作状态）=3（烤盘搬移中），203（烤盘搬移结果寄存器）=${panMoveResult203 ?: "读取失败"}（非0=未确认完成）"
            )
            targetOnlineStatus == 1 -> ManualRecoveryHeatingDecision(
                canRecoverDeviceStatus = true,
                shouldRestoreTemperature = true,
                reason = "恢复到 onlineStatus（设备营业状态）=1（运营中），允许按烤盘状态重刷温度"
            )
            else -> ManualRecoveryHeatingDecision(
                canRecoverDeviceStatus = true,
                shouldRestoreTemperature = false,
                reason = "恢复到 onlineStatus（设备营业状态）=$targetOnlineStatus（非运营中），不自动重刷温度"
            )
        }
    }
}
