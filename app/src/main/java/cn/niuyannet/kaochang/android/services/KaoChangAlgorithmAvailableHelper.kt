package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.model.bean.KaoPan

internal data class AvailableHeatBatch(
    val name: String,
    val remainHeatTimeMs: Long
)

internal fun findNextOpeningSellBatch(
    isLowOrTransition: Boolean,
    sellArea: List<KaoPan>,
    now: Long,
    config: AppConfigBean = AppConfig.getAppConfig()
): AvailableHeatBatch? {
    val openingBatches = if (isLowOrTransition) {
        listOf("1~9开盘批次" to sellArea)
    } else {
        listOf(
            "1~9开盘批次" to sellArea.filter { it.positionSn in 1..9 },
            "10~21开盘批次" to sellArea.filter { it.positionSn in 10..21 }
        )
    }
    return openingBatches
        .mapNotNull { (name, pans) ->
            calcAvailableBatchRemainHeatTime(pans, now, config)?.let { remain ->
                AvailableHeatBatch(name = name, remainHeatTimeMs = remain)
            }
        }
        .minByOrNull { it.remainHeatTimeMs }
}

internal fun findNextSupplementHeatBatch(
    isLowOrTransition: Boolean,
    heatArea: List<KaoPan>,
    now: Long,
    config: AppConfigBean = AppConfig.getAppConfig()
): AvailableHeatBatch? {
    val supplementBatches = if (isLowOrTransition) {
        listOf(
            "13~15补肠批次" to heatArea.filter { it.positionSn in 13..15 },
            "16~18补肠批次" to heatArea.filter { it.positionSn in 16..18 }
        )
    } else {
        listOf("25~33补肠批次" to heatArea.filter { it.positionSn in 25..33 })
    }
    return supplementBatches
        .mapNotNull { (name, pans) ->
            calcAvailableBatchRemainHeatTime(pans, now, config)?.let { remain ->
                AvailableHeatBatch(name = name, remainHeatTimeMs = remain)
            }
        }
        .minByOrNull { it.remainHeatTimeMs }
}

private fun calcAvailableBatchRemainHeatTime(
    pans: List<KaoPan>,
    now: Long,
    config: AppConfigBean
): Long? {
    return pans
        .asSequence()
        .filter { it.isHasSausage && it.startTime > 0L && it.holdingTime <= 0L }
        .mapNotNull { pan ->
            val remain = pan.bakingTime - FaultHeatPauseManager.effectiveBakingElapsedMs(pan, config, now)
            remain.takeIf { it > 0L }
        }
        .maxOrNull()
}
