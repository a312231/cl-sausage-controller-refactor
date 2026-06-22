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

    private fun algorithmSourceText(): String {
        val sourceFile = listOf(
            File("src/main/java/cn/niuyannet/kaochang/android/services/KaoChangAlgorithm.kt"),
            File("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangAlgorithm.kt")
        ).first { it.exists() }
        return sourceFile.readText().replace("\r\n", "\n")
    }
}
