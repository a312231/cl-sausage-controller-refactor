package cn.niuyannet.kaochang.android.services.actiongroup

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AtomicActionExecutorTest {

    @Test
    fun execute_shouldWriteInfoBeforeCommandAndReturnSuccess() = runBlocking {
        val gateway = FakeGateway(
            reads = mapOf(311 to queueOf(AtomicActionCodes.IDLE, AtomicActionCodes.RUNNING, AtomicActionCodes.IDLE))
        )
        val executor = AtomicActionExecutor(gateway, logger = {})

        val result = executor.execute(AtomicActions.in1, infoValue = 17)

        assertEquals(AtomicActionResult.Success, result)
        assertEquals(listOf(Write(312, 17), Write(310, 1)), gateway.writes)
    }

    @Test
    fun execute_shouldRejectStaleIdleAfterCommandUntilRunningIsObserved() = runBlocking {
        val spec = AtomicActionSpec("out1-test", 410, 411, 412, 80L, 10L, "out1：从烤盘取熟肠到穿签位")
        val gateway = FakeGateway(
            reads = mapOf(411 to queueOf(AtomicActionCodes.IDLE, AtomicActionCodes.IDLE, AtomicActionCodes.IDLE))
        )
        val logs = mutableListOf<String>()
        val executor = AtomicActionExecutor(gateway, logger = { logs += it })

        val result = executor.execute(spec, infoValue = 2)

        assertTrue("未观察到 0x0001（执行中）时，旧 0x0000 不能被当作成功", result is AtomicActionResult.Timeout)
        assertTrue(logs.any { it.contains("动作未确认启动") })
        assertTrue(
            "技术日志要说明动作编号、中文含义和结果寄存器，文件运维日志由动作组 Runner 统一补上下文",
            logs.any {
                it.contains("动作启动失败：out1-test（out1：从烤盘取熟肠到穿签位）下位机未确认执行") &&
                    it.contains("结果寄存器411一直为0x0000（旧成功/空闲）")
            }
        )
        assertEquals(listOf(Write(412, 2), Write(410, 1)), gateway.writes)
    }

    @Test
    fun execute_shouldDiagnosePossibleControllerResetWhenCommandRegisterClearsBeforeStartupConfirmed() = runBlocking {
        val spec = AtomicActionSpec("in4-test", 340, 341, 342, 80L, 10L, "in4：升降台下降")
        val gateway = FakeGateway(
            reads = mapOf(
                341 to queueOf(AtomicActionCodes.IDLE, AtomicActionCodes.IDLE, AtomicActionCodes.IDLE),
                340 to queueOf(AtomicActionCodes.IDLE)
            )
        )
        val logs = mutableListOf<String>()
        val executor = AtomicActionExecutor(gateway, logger = { logs += it })

        val result = executor.execute(spec, infoValue = 0)

        assertTrue(result is AtomicActionResult.Timeout)
        val timeout = result as AtomicActionResult.Timeout
        assertTrue(timeout.message.contains("疑似下位机复位/命令丢失"))
        assertTrue(timeout.message.contains("commandRegister=340"))
        assertTrue(timeout.message.contains("currentCommand=0x0000"))
        assertTrue(
            logs.any {
                it.contains("启动确认失败诊断") &&
                    it.contains("commandRegister（命令寄存器）=340") &&
                    it.contains("当前命令=0x0000（空闲/已清零）")
            }
        )
        assertEquals(listOf(Write(342, 0), Write(340, 1)), gateway.writes)
    }

    @Test
    fun execute_shouldSucceedOnlyAfterRunningThenIdle() = runBlocking {
        val spec = AtomicActionSpec("out1-test", 410, 411, 412, 1_000L, 10L, "out1：从烤盘取熟肠到穿签位")
        val gateway = FakeGateway(
            reads = mapOf(411 to queueOf(AtomicActionCodes.IDLE, AtomicActionCodes.RUNNING, AtomicActionCodes.IDLE))
        )
        val logs = mutableListOf<String>()
        val executor = AtomicActionExecutor(gateway, logger = { logs += it })

        val result = executor.execute(spec, infoValue = 2)

        assertEquals(AtomicActionResult.Success, result)
        assertTrue(logs.any { it.contains("动作启动确认成功") })
    }

    @Test
    fun execute_shouldTakeOverPollingWhenActionIsAlreadyRunning() = runBlocking {
        val gateway = FakeGateway(
            reads = mapOf(321 to queueOf(AtomicActionCodes.RUNNING, AtomicActionCodes.IDLE))
        )
        val executor = AtomicActionExecutor(gateway, logger = {})

        val result = executor.execute(AtomicActions.in2, infoValue = 17)

        assertEquals(AtomicActionResult.Success, result)
        assertTrue("接管轮询时不能重复写信息或命令寄存器", gateway.writes.isEmpty())
    }

    @Test
    fun execute_shouldMapKnownErrorCodeToChineseMessage() = runBlocking {
        val gateway = FakeGateway(
            reads = mapOf(351 to queueOf(AtomicActionCodes.IDLE, AtomicActionCodes.IR_THROW_FAILED))
        )
        val executor = AtomicActionExecutor(gateway, logger = {})

        val result = executor.execute(AtomicActions.in5, infoValue = 0)

        assertTrue(result is AtomicActionResult.Failed)
        val failed = result as AtomicActionResult.Failed
        assertEquals(0x00E6, failed.errorCode)
        assertEquals("抛肠红外未确认抛出成功", failed.message)
    }

    @Test
    fun execute_shouldEmitTelemetryWhenActionFinishes() = runBlocking {
        val gateway = FakeGateway(
            reads = mapOf(371 to queueOf(AtomicActionCodes.IDLE, AtomicActionCodes.RUNNING, AtomicActionCodes.IDLE))
        )
        val telemetry = mutableListOf<ActionGroupTelemetry>()
        val executor = AtomicActionExecutor(
            gateway = gateway,
            logger = {},
            telemetryContext = ActionGroupTelemetryContext(
                sessionId = "session-1",
                boxNo = 17,
                panNo = 2,
                orderId = "order-1",
                resourceLocks = "BOTTOM|LIFT|ABOVE"
            ),
            telemetrySink = { telemetry += it }
        )

        val result = executor.execute(AtomicActions.in7, infoValue = 2)

        assertEquals(AtomicActionResult.Success, result)
        assertEquals(1, telemetry.size)
        assertEquals("300_400", telemetry.first().actionProtocolVersion)
        assertEquals("session-1", telemetry.first().sessionId)
        assertEquals("in7", telemetry.first().actionName)
        assertEquals(370, telemetry.first().registerAddress)
        assertEquals(371, telemetry.first().resultRegister)
        assertEquals(17, telemetry.first().boxNo)
        assertEquals(2, telemetry.first().panNo)
        assertEquals("order-1", telemetry.first().orderId)
        assertEquals("BOTTOM|LIFT|ABOVE", telemetry.first().resourceLocks)
        assertEquals(null, telemetry.first().errorCode)
    }

    @Test
    fun definitions_shouldUse300And400Registers() {
        assertEquals(AtomicActionSpec("in7", 370, 371, 372, 120_000L, 500L, "in7：上平台从升降台夹肠并放入烤盘"), AtomicActions.in7)
        assertEquals(AtomicActionSpec("out5", 450, 451, 452, 90_000L, 500L, "out5：检查顾客是否取走，超时未取由下位机自动丢弃"), AtomicActions.out5)
    }

    data class Write(val address: Int, val value: Int)

    class FakeGateway(
        private val reads: Map<Int, ArrayDeque<Int>> = emptyMap(),
        private val writeResult: AtomicIoResult = AtomicIoResult.Success
    ) : AtomicModbusGateway {
        val writes = mutableListOf<Write>()

        override suspend fun writeRegister(address: Int, value: Int): AtomicIoResult {
            writes += Write(address, value)
            return writeResult
        }

        override suspend fun readRegister(address: Int): AtomicReadResult {
            val value = reads[address]?.removeFirstOrNull() ?: AtomicActionCodes.IDLE
            return AtomicReadResult.Success(value)
        }
    }

    companion object {
        fun queueOf(vararg values: Int): ArrayDeque<Int> = ArrayDeque<Int>().apply { addAll(values.toList()) }
    }
}
