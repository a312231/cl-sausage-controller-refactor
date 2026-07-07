package cn.niuyannet.kaochang.android.services

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AlgorithmFaultDiagnosticsSourceTest {

    @Test
    fun pollKaoPanStatus_shouldLogFaultDiagnosticsBeforeEarlyReturn() {
        val source = algorithmSourceText()
        val pollStart = source.indexOf("private suspend fun pollKaoPanStatus()")
        assertTrue("必须存在 pollKaoPanStatus", pollStart >= 0)
        val pollEnd = source.indexOf("private fun settleMaturedZonesBeforeDispatch", pollStart)
            .let { if (it >= 0) it else source.length }
        val pollBlock = source.substring(pollStart, pollEnd)

        assertTrue(
            "营业时间门控提前 return 前必须输出故障停热诊断日志，便于判断故障停热是否被营业时间吞掉",
            pollBlock.contains("logFaultHeatDiagnosticOnce(\"not_within_business_hours_before_return\"") &&
                pollBlock.contains("logFaultHeatDiagnosticOnce(\"error_status_before_fault_stop\"")
        )
        assertTrue(
            "故障停热诊断 helper 必须包含定位真因所需字段",
            source.contains("【故障停热诊断】") &&
                source.contains("isWithinBusinessHours") &&
                source.contains("businessTime") &&
                source.contains("faultHeatingStopped") &&
                source.contains("isRunning") &&
                source.contains("errorCode")
        )
    }

    @Test
    fun algorithmFaultStop_shouldLogFaultHeatingGuardState() {
        val source = algorithmSourceText()
        val faultStart = source.indexOf("private fun stopHeatingAndDispatchForFault(")
        assertTrue("必须存在故障停热收口入口", faultStart >= 0)
        val faultEnd = source.indexOf("/**\n     * 轮询烤盘和设备状态", faultStart)
            .let { if (it >= 0) it else source.length }
        val faultBlock = source.substring(faultStart, faultEnd)

        assertTrue(
            "故障停热收口日志必须输出 faultHeatingStopped，便于判断是否被幂等守卫跳过",
            faultBlock.contains("faultHeatDiagnosticText(") &&
                source.contains("faultHeatingStopped") &&
                source.contains("isRunning") &&
                source.contains("errorCode")
        )
    }

    @Test
    fun pollKaoPanStatus_shouldRefreshActiveBakingTimeBeforeMaturitySettlement() {
        val source = algorithmSourceText()
        val pollStart = source.indexOf("private suspend fun pollKaoPanStatus()")
        assertTrue("必须存在 pollKaoPanStatus", pollStart >= 0)
        val pollEnd = source.indexOf("private fun settleMaturedZonesBeforeDispatch", pollStart)
            .let { if (it >= 0) it else source.length }
        val pollBlock = source.substring(pollStart, pollEnd)

        val refreshCall = pollBlock.indexOf("refreshActiveBakingTimeBeforeMaturityCheck(")
        val settleCall = pollBlock.indexOf("settleMaturedZonesBeforeDispatch(")
        assertTrue("调度轮询必须在成熟判断前回刷烤制中烤盘的 bakingTime", refreshCall >= 0)
        assertTrue("必须存在成熟转保温/回搬判断入口", settleCall >= 0)
        assertTrue("bakingTime 回刷必须早于成熟判断，避免后台改 15 分钟后烤盘仍按旧时长烤制", refreshCall < settleCall)
    }

    @Test
    fun pollKaoPanStatus_shouldNormalizeSupplementSourcesBeforeEarlyReturns() {
        val source = algorithmSourceText()
        val pollStart = source.indexOf("private suspend fun pollKaoPanStatus()")
        assertTrue("必须存在 pollKaoPanStatus", pollStart >= 0)
        val pollEnd = source.indexOf("private fun settleMaturedZonesBeforeDispatch", pollStart)
            .let { if (it >= 0) it else source.length }
        val pollBlock = source.substring(pollStart, pollEnd)

        val normalizeCall = pollBlock.indexOf("normalizeSupplementSourceKeepWarmBeforeEarlyReturns(")
        val businessReturn = pollBlock.indexOf("return", pollBlock.indexOf("if (!isWithinBusinessHours)"))
        val faultReturn = pollBlock.indexOf("return", pollBlock.indexOf("if (config.errorStatus != AppConfigBean.ERROR_STATUS_NORMAL)"))
        val dispatchGateReturn = pollBlock.indexOf("return", pollBlock.indexOf("if (isEnabled && config.isEnable == 1 && !autoDispatchDecision.canStart)"))
        assertTrue("必须存在调度早退前补热源区历史可售纠偏入口", normalizeCall >= 0)
        assertTrue("补热源区历史可售纠偏必须早于非营业时间早退", normalizeCall < businessReturn)
        assertTrue("补热源区历史可售纠偏必须早于故障早退", normalizeCall < faultReturn)
        assertTrue("补热源区历史可售纠偏必须早于机械/订单门禁早退", normalizeCall < dispatchGateReturn)
    }

    @Test
    fun pollKaoPanStatus_shouldNormalizeOldLowHeatSourcesBeforeAvailableProjection() {
        val source = algorithmSourceText()
        val pollStart = source.indexOf("private suspend fun pollKaoPanStatus()")
        assertTrue("必须存在 pollKaoPanStatus", pollStart >= 0)
        val pollEnd = source.indexOf("private fun settleMaturedZonesBeforeDispatch", pollStart)
            .let { if (it >= 0) it else source.length }
        val pollBlock = source.substring(pollStart, pollEnd)

        val normalizeCall = pollBlock.indexOf("normalizeOldLowHeatSourcesBeforeAvailabilityProjection(")
        val checkAvailableCall = pollBlock.indexOf("checkAvailable(config, kaoPanList)")
        assertTrue("必须存在低转高旧低补热源可售投影前纠偏入口", normalizeCall >= 0)
        assertTrue("必须存在二维码/可售投影入口", checkAvailableCall >= 0)
        assertTrue("旧低补热源纠偏必须早于 checkAvailable，避免 13~18 历史 status=2 先进入 1~21 可售投影", normalizeCall < checkAvailableCall)
    }

    @Test
    fun pollKaoPanStatus_shouldSettleOldLowHeatBeforePendingDiscardEarlyReturn() {
        val source = algorithmSourceText()
        val pollStart = source.indexOf("private suspend fun pollKaoPanStatus()")
        assertTrue("必须存在 pollKaoPanStatus", pollStart >= 0)
        val pollEnd = source.indexOf("private fun settleMaturedZonesBeforeDispatch", pollStart)
            .let { if (it >= 0) it else source.length }
        val pollBlock = source.substring(pollStart, pollEnd)

        val oldLowHeatSettle = pollBlock.indexOf("settleOldLowHeatSourcesBeforePendingDiscard(")
        val pendingDiscardReturn = pollBlock.indexOf("OverKeepWarmDiscardScanner.scanAndDiscard(1..33, \"pending_discard_priority\")")
        assertTrue("必须存在待丢弃优先前旧低补热源收口入口", oldLowHeatSettle >= 0)
        assertTrue("必须存在 pendingDiscard 优先丢弃返回入口", pendingDiscardReturn >= 0)
        assertTrue("低转高旧低补热源必须先转等待回搬不可售，再允许 pendingDiscard 提前 return", oldLowHeatSettle < pendingDiscardReturn)
    }

    private fun algorithmSourceText(): String {
        val sourceFile = listOf(
            File("src/main/java/cn/niuyannet/kaochang/android/services/KaoChangAlgorithm.kt"),
            File("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangAlgorithm.kt")
        ).first { it.exists() }
        return sourceFile.readText().replace("\r\n", "\n")
    }
}
