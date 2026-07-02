package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.KaoPanHelper
import cn.niuyannet.kaochang.android.model.bean.KaoPan

object ManualPanPositionDataClearExecutor {

    data class Dependencies(
        val getPans: () -> List<KaoPan> = { KaoPanHelper.getKaoPanList() },
        val savePansSync: (List<KaoPan>) -> Unit = { KaoPanHelper.saveKaoPanListSync(it) },
        val reportPhysicalDelta: (KaoPan, String, String) -> Unit = { pan, reason, source ->
            PanPhysicalStateDeltaReporter.reportPanChanged(
                changedPan = pan,
                reason = reason,
                source = source
            )
        },
        val clearDiscardPosition: (Int) -> Unit = { FaultHeatPauseManager.clearDiscardPositionAndAlertIfFinished(it) },
        val stopAllHeatingIfNoSausage: (String) -> Unit = { KaoChangOperate.stopAllHeatingIfNoSausage(it, force = true) },
        val uploadSnapshot: (String) -> Unit = { SnapshotSyncPolicy.maybeUploadSnapshot(it) }
    )

    fun execute(
        positionSn: Int,
        bakingTimeMinutes: Int,
        discardTimeHours: Int,
        dependencies: Dependencies = Dependencies()
    ): ManualPanPositionDataClearPolicy.ClearResult {
        val result = ManualPanPositionDataClearPolicy.clearPositionData(
            pans = dependencies.getPans(),
            positionSn = positionSn,
            bakingTimeMinutes = bakingTimeMinutes,
            discardTimeHours = discardTimeHours
        )
        if (!result.cleared) {
            return result
        }
        dependencies.savePansSync(result.pans)
        result.pans.firstOrNull { it.positionSn == positionSn }?.let { clearedPan ->
            dependencies.reportPhysicalDelta(
                clearedPan,
                "maintenance_clear_pan_position:$positionSn",
                "maintenance_clear_pan_position"
            )
        }
        dependencies.clearDiscardPosition(positionSn)
        dependencies.stopAllHeatingIfNoSausage("维护页仅清除烤盘位数据:$positionSn")
        dependencies.uploadSnapshot("maintenance_clear_pan_position:$positionSn")
        return result
    }
}
