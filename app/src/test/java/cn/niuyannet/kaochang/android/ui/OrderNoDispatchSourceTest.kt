package cn.niuyannet.kaochang.android.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OrderNoDispatchSourceTest {

    @Test
    fun noDispatch_shouldNotApplyMechanicalFaultPolicy() {
        val source = sourceText()
        val operate = operateSourceText()
        val start = source.indexOf("private suspend fun handleOrderItemDelivery(")
        assertTrue("必须存在单根订单处理函数", start >= 0)
        val end = source.indexOf("private fun evaluateSingleDeliveryReadiness", start)
            .let { if (it >= 0) it else source.length }
        val block = source.substring(start, end)

        assertTrue("单根 no-dispatch 必须有显式分支", block.contains("NO_SELLABLE_STOCK"))
        assertTrue("证据未知必须有显式分支", block.contains("EVIDENCE_UNKNOWN"))
        assertTrue("开始事件必须在命令下发回调后", operate.contains("onActionCommandWritten"))
        val takeStart = operate.indexOf("suspend fun takeSausageResult(")
        val takeEnd = operate.indexOf("internal fun applySuccessfulTakePanState", takeStart)
            .let { if (it >= 0) it else operate.length }
        val takeBlock = operate.substring(takeStart, takeEnd)
        assertTrue(
            "开始事件必须在 durable DISPATCHED 之后",
            takeBlock.indexOf("out1DispatchEvidence = Out1DispatchEvidence.DISPATCHED") <
                takeBlock.indexOf("reportActionGroupOrderStartedEvent")
        )
        val noStockBranch = block.indexOf("TakeSausageResult.NO_SELLABLE_STOCK")
        val faultPolicy = block.indexOf("applySingleDeliveryExceptionFault(config, e)")
        assertTrue("no-dispatch 分支必须在机械故障策略前直接返回", noStockBranch >= 0 && faultPolicy > noStockBranch)
    }

    @Test
    fun preOutFailures_shouldNotClaimMechanicalDispatchEvidence() {
        val source = sourceText()
        val operate = operateSourceText()
        val handleStart = source.indexOf("private suspend fun handleOrderItemDelivery(")
        val handleEnd = source.indexOf("private fun evaluateSingleDeliveryReadiness", handleStart)
            .let { if (it >= 0) it else source.length }
        val handleBlock = source.substring(handleStart, handleEnd)

        val modbusStart = handleBlock.indexOf("val modbusReadyResult")
        val modbusEnd = handleBlock.indexOf("val readinessAfterModbus", modbusStart)
        val modbusBlock = handleBlock.substring(modbusStart, modbusEnd)
        assertTrue("Modbus 准备失败必须明确按未下发收口", modbusBlock.contains("OrderFailureMetadata.DEVICE_BUSY_NO_DISPATCH"))
        assertTrue("Modbus 准备失败必须直接返回 NO_DISPATCH", modbusBlock.contains("OrderExecutionOutcome.NO_DISPATCH"))
        assertFalse("Modbus 准备失败不得抛给机械故障兜底", modbusBlock.contains("throw IllegalStateException"))

        val catchStart = handleBlock.indexOf("} catch (e: Exception)")
        val catchEnd = handleBlock.indexOf("return DeliveryResult(false, deliveredCount", catchStart)
        val catchBlock = handleBlock.substring(catchStart, catchEnd)
        assertTrue("无法确认 out1 证据的异常必须按 UNKNOWN 上报", catchBlock.contains("OrderFailureMetadata.EVIDENCE_UNKNOWN"))
        assertFalse("异常兜底不得静态伪造机械失败", catchBlock.contains("OrderFailureMetadata.MECHANICAL_FAILED"))

        val takeStart = operate.indexOf("suspend fun takeSausageResult(")
        val runnerStart = operate.indexOf(".run(", takeStart)
        val beforeRunner = operate.substring(takeStart, runnerStart)
        assertFalse("动作组 runner 执行前不得返回机械失败", beforeRunner.contains("return TakeSausageResult.ERROR"))
        assertTrue("动作组 runner 执行前失败必须返回未下发", beforeRunner.contains("return TakeSausageResult.NO_DISPATCH"))

        val resultMappingStart = operate.indexOf("val takeResult = when (actionGroupResult)", runnerStart)
        val resultMappingEnd = operate.indexOf("if (takeResult == TakeSausageResult.NO_DISPATCH)", resultMappingStart)
        val resultMapping = operate.substring(resultMappingStart, resultMappingEnd)
        assertTrue(
            "明确机械失败必须同时具备 durable DISPATCHED 证据",
            resultMapping.contains("evidence == Out1DispatchEvidence.DISPATCHED")
        )
    }

    private fun sourceText(): String {
        val sourceFile = listOf(
            File("src/main/java/cn/niuyannet/kaochang/android/ui/OrderActivity.kt"),
            File("app/src/main/java/cn/niuyannet/kaochang/android/ui/OrderActivity.kt")
        ).first { it.exists() }
        return sourceFile.readText().replace("\r\n", "\n")
    }

    private fun operateSourceText(): String {
        val sourceFile = listOf(
            File("src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt"),
            File("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        ).first { it.exists() }
        return sourceFile.readText().replace("\r\n", "\n")
    }
}
