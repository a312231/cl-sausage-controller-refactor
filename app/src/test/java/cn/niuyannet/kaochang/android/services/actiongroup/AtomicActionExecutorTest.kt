package cn.niuyannet.kaochang.android.services.actiongroup

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AtomicActionExecutorTest {

    @Test
    fun execute_shouldWriteInfoBeforeCommandAndReturnSuccess() = runBlocking {
        val gateway = FakeGateway(
            reads = mapOf(311 to queueOf(AtomicActionCodes.IDLE, AtomicActionCodes.RUNNING, COMPLETED))
        )
        val executor = AtomicActionExecutor(gateway, logger = {})

        val result = executor.execute(AtomicActions.in1, infoValue = 17)

        assertEquals(AtomicActionResult.Success, result)
        assertEquals(listOf(Write(312, 17), Write(310, 1)), gateway.writes)
    }

    @Test
    fun execute_shouldTreatStaleCompletedAsPendingUntilRunningThenCompleted() = runBlocking {
        val spec = AtomicActionSpec("in1-test", 310, 311, 312, 1_000L, 10L, "in1：移动到肠箱预推送位置")
        val gateway = FakeGateway(
            reads = mapOf(
                311 to queueOf(
                    COMPLETED,
                    COMPLETED,
                    AtomicActionCodes.IDLE,
                    AtomicActionCodes.RUNNING,
                    COMPLETED
                ),
                310 to queueOf(
                    AtomicActionCodes.IDLE,
                    AtomicActionCodes.RUNNING,
                    AtomicActionCodes.RUNNING
                )
            )
        )
        val logs = mutableListOf<String>()
        val executor = AtomicActionExecutor(gateway, logger = { logs += it })

        val result = executor.execute(spec, infoValue = 17)

        assertEquals(AtomicActionResult.Success, result)
        assertEquals(listOf(Write(312, 17), Write(310, 1)), gateway.writes)
        assertTrue(logs.any { it.contains("命令仍在等待下位机接收") && it.contains("当前结果=0x0002") })
        assertTrue(logs.any { it.contains("动作写入确认成功") && it.contains("resultCode=0x0000") })
    }

    @Test
    fun execute_shouldSucceedWhenResultJumpsFromIdleTo2WithCommandCleared() = runBlocking {
        val spec = AtomicActionSpec("in3-test", 330, 331, 332, 1_000L, 10L, "in3：回到抛肠点")
        val gateway = FakeGateway(
            reads = mapOf(
                331 to queueOf(AtomicActionCodes.IDLE, COMPLETED),
                330 to queueOf(AtomicActionCodes.IDLE)
            )
        )
        val logs = mutableListOf<String>()
        val executor = AtomicActionExecutor(gateway, logger = { logs += it })

        val result = executor.execute(spec, infoValue = 0)

        assertEquals(AtomicActionResult.Success, result)
        assertEquals(listOf(Write(332, 0), Write(330, 1)), gateway.writes)
        assertTrue(logs.any { it.contains("快速完成确认") && it.contains("0x0002") })
    }

    @Test
    fun execute_shouldClearStaleCommandOneWhenResultIsIdleBeforeFreshStart() = runBlocking {
        val spec = AtomicActionSpec("in3-test", 330, 331, 332, 1_000L, 10L, "in3：回到抛肠点")
        val gateway = FakeGateway(
            reads = mapOf(
                331 to queueOf(AtomicActionCodes.IDLE, AtomicActionCodes.IDLE, AtomicActionCodes.RUNNING, COMPLETED),
                330 to queueOf(AtomicActionCodes.RUNNING)
            )
        )
        val logs = mutableListOf<String>()
        val executor = AtomicActionExecutor(gateway, logger = { logs += it })

        val result = executor.execute(spec, infoValue = 0)

        assertEquals(AtomicActionResult.Success, result)
        assertEquals(listOf(Write(330, 0), Write(332, 0), Write(330, 1)), gateway.writes)
        assertTrue(logs.any { it.contains("清理残留命令镜像") && it.contains("当前结果=0x0000") })
    }

    @Test
    fun execute_shouldClearStaleCommandOneWhenResultIsCompletedBeforeFreshStart() = runBlocking {
        val spec = AtomicActionSpec("out1-test", 410, 411, 412, 1_000L, 10L, "out1：从烤盘取熟肠到穿签位")
        val gateway = FakeGateway(
            reads = mapOf(
                411 to queueOf(COMPLETED, AtomicActionCodes.IDLE, AtomicActionCodes.RUNNING, COMPLETED),
                410 to queueOf(AtomicActionCodes.RUNNING)
            )
        )
        val executor = AtomicActionExecutor(gateway, logger = {})

        val result = executor.execute(spec, infoValue = 8)

        assertEquals(AtomicActionResult.Success, result)
        assertEquals(listOf(Write(410, 0), Write(412, 8), Write(410, 1)), gateway.writes)
    }

    @Test
    fun execute_shouldRejectStaleCompletedWhenFreshZeroOrRunningIsNeverObserved() = runBlocking {
        val spec = AtomicActionSpec("out1-test", 410, 411, 412, 80L, 10L, "out1：从烤盘取熟肠到穿签位")
        val gateway = FakeGateway(
            reads = mapOf(
                411 to repeatedQueue(COMPLETED, 20),
                410 to repeatedQueue(AtomicActionCodes.RUNNING, 20)
            )
        )
        val logs = mutableListOf<String>()
        val executor = AtomicActionExecutor(
            gateway = gateway,
            logger = { logs += it },
            startupConfirmTimeoutMs = 30L,
            commandPendingConfirmTimeoutMs = 60L
        )

        val result = executor.execute(spec, infoValue = 2)

        assertTrue("启动前残留 0x0002 且写命令后未看到本轮 0/1 时，不能被当作成功", result is AtomicActionResult.Timeout)
        assertTrue(logs.any { it.contains("命令仍在等待下位机接收") && it.contains("当前结果=0x0002") })
        assertEquals(listOf(Write(410, 0), Write(412, 2), Write(410, 1)), gateway.writes)
    }

    @Test
    fun execute_shouldNotFastSucceedOnInitialReadWhenStaleResultIs2() = runBlocking {
        val spec = AtomicActionSpec("out1-test", 410, 411, 412, 1_000L, 10L, "out1：从烤盘取熟肠到穿签位")
        val gateway = FakeGateway(
            reads = mapOf(
                411 to queueOf(COMPLETED, AtomicActionCodes.IDLE, AtomicActionCodes.RUNNING, COMPLETED),
                410 to queueOf(AtomicActionCodes.IDLE)
            )
        )
        val executor = AtomicActionExecutor(gateway, logger = {})

        val result = executor.execute(spec, infoValue = 8)

        assertEquals(AtomicActionResult.Success, result)
        assertEquals("首读 result=2 不能直接成功，必须写入本轮新命令", listOf(Write(412, 8), Write(410, 1)), gateway.writes)
    }

    @Test
    fun execute_shouldSucceedWhenStaleCompletedIsFollowedByCurrentCommandClearedCompleted() = runBlocking {
        val spec = AtomicActionSpec("out1-test", 410, 411, 412, 1_000L, 10L, "out1：从烤盘取熟肠到穿签位")
        val gateway = FakeGateway(
            reads = mapOf(
                411 to queueOf(COMPLETED, COMPLETED),
                410 to queueOf(AtomicActionCodes.IDLE)
            )
        )
        val executor = AtomicActionExecutor(gateway, logger = {})

        val result = executor.execute(spec, infoValue = 8)

        assertEquals(AtomicActionResult.Success, result)
        assertEquals(listOf(Write(412, 8), Write(410, 1)), gateway.writes)
    }

    @Test
    fun execute_shouldKeepWaitingWhenResultIs2ButCommandStillOne() = runBlocking {
        val spec = AtomicActionSpec("in4-test", 340, 341, 342, 120L, 10L, "in4：升降台下降")
        val gateway = FakeGateway(
            reads = mapOf(
                341 to repeatedQueue(COMPLETED, 30),
                340 to repeatedQueue(AtomicActionCodes.RUNNING, 30)
            )
        )
        val logs = mutableListOf<String>()
        val executor = AtomicActionExecutor(
            gateway = gateway,
            logger = { logs += it },
            startupConfirmTimeoutMs = 30L,
            commandPendingConfirmTimeoutMs = 80L
        )

        val result = executor.execute(spec, infoValue = 0)

        assertTrue("result=2 但 cmd 仍为 1 时不能快速成功", result is AtomicActionResult.Timeout)
        assertTrue(logs.any { it.contains("命令仍在等待下位机接收") && it.contains("当前结果=0x0002") })
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
        val executor = AtomicActionExecutor(
            gateway = gateway,
            logger = { logs += it },
            startupConfirmTimeoutMs = 30L
        )

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
    fun execute_shouldKeepWaitingWhenCommandStillPendingAfterStartupConfirmWindow() = runBlocking {
        val spec = AtomicActionSpec("in4-test", 340, 341, 342, 5_000L, 100L, "in4：升降台下降")
        val gateway = FakeGateway(
            reads = mapOf(
                341 to queueOf(
                    AtomicActionCodes.IDLE,
                    AtomicActionCodes.IDLE,
                    AtomicActionCodes.IDLE,
                    AtomicActionCodes.IDLE,
                    AtomicActionCodes.IDLE,
                    AtomicActionCodes.IDLE,
                    AtomicActionCodes.IDLE,
                    AtomicActionCodes.IDLE,
                    AtomicActionCodes.IDLE,
                    AtomicActionCodes.IDLE,
                    AtomicActionCodes.IDLE,
                    AtomicActionCodes.IDLE,
                    AtomicActionCodes.IDLE,
                    AtomicActionCodes.IDLE,
                    AtomicActionCodes.IDLE,
                    AtomicActionCodes.IDLE,
                    AtomicActionCodes.IDLE,
                    AtomicActionCodes.IDLE,
                    AtomicActionCodes.IDLE,
                    AtomicActionCodes.IDLE,
                    AtomicActionCodes.IDLE,
                    AtomicActionCodes.IDLE,
                    AtomicActionCodes.IDLE,
                    AtomicActionCodes.IDLE,
                    AtomicActionCodes.IDLE,
                    AtomicActionCodes.IDLE,
                    AtomicActionCodes.IDLE,
                    AtomicActionCodes.IDLE,
                    AtomicActionCodes.IDLE,
                    AtomicActionCodes.IDLE,
                    AtomicActionCodes.IDLE,
                    AtomicActionCodes.IDLE,
                    AtomicActionCodes.IDLE,
                    AtomicActionCodes.IDLE,
                    AtomicActionCodes.IDLE,
                    AtomicActionCodes.IDLE,
                    AtomicActionCodes.RUNNING,
                    COMPLETED
                ),
                340 to repeatedQueue(AtomicActionCodes.RUNNING, 40)
            )
        )
        val logs = mutableListOf<String>()
        val executor = AtomicActionExecutor(gateway, logger = { logs += it })

        val result = executor.execute(spec, infoValue = 0)

        assertEquals(AtomicActionResult.Success, result)
        assertTrue(
            logs.any {
                it.contains("命令仍在等待下位机接收") &&
                    it.contains("commandRegister（命令寄存器）=340") &&
                    it.contains("当前命令=0x0001（请求执行）")
            }
        )
    }

    @Test
    fun execute_shouldTimeoutWhenStartupConfirmMissingAndStrictPolicyEnabled() = runBlocking {
        val spec = AtomicActionSpec("in3-test", 330, 331, 332, 120L, 10L, "in3：回到抛肠点")
        val gateway = FakeGateway(
            reads = mapOf(
                331 to repeatedQueue(AtomicActionCodes.IDLE, 30),
                330 to repeatedQueue(AtomicActionCodes.RUNNING, 30)
            )
        )
        val executor = AtomicActionExecutor(
            gateway = gateway,
            logger = {},
            startupConfirmTimeoutMs = 30L,
            commandPendingConfirmTimeoutMs = 80L
        )

        val result = executor.execute(spec, infoValue = 0)

        assertTrue(result is AtomicActionResult.Timeout)
    }

    @Test
    fun execute_shouldSucceedOnlyAfterRunningThenCompleted() = runBlocking {
        val spec = AtomicActionSpec("out1-test", 410, 411, 412, 1_000L, 10L, "out1：从烤盘取熟肠到穿签位")
        val gateway = FakeGateway(
            reads = mapOf(411 to queueOf(AtomicActionCodes.IDLE, AtomicActionCodes.RUNNING, COMPLETED))
        )
        val logs = mutableListOf<String>()
        val executor = AtomicActionExecutor(gateway, logger = { logs += it })

        val result = executor.execute(spec, infoValue = 2)

        assertEquals(AtomicActionResult.Success, result)
        assertTrue(logs.any { it.contains("动作启动确认成功") })
    }

    @Test
    fun execute_shouldTimeoutWhenIdleAppearsAfterRunningInNewProtocol() = runBlocking {
        val spec = AtomicActionSpec("out1-test", 410, 411, 412, 80L, 10L, "out1：从烤盘取熟肠到穿签位")
        val gateway = FakeGateway(
            reads = mapOf(411 to queueOf(AtomicActionCodes.IDLE, AtomicActionCodes.RUNNING, AtomicActionCodes.IDLE))
        )
        val logs = mutableListOf<String>()
        val executor = AtomicActionExecutor(gateway, logger = { logs += it })

        val result = executor.execute(spec, infoValue = 2)

        assertTrue(result is AtomicActionResult.Timeout)
        assertTrue(logs.any { it.contains("执行中后返回0x0000") })
    }

    @Test
    fun execute_shouldTakeOverPollingWhenActionIsAlreadyRunning() = runBlocking {
        val gateway = FakeGateway(
            reads = mapOf(321 to queueOf(AtomicActionCodes.RUNNING, COMPLETED))
        )
        val executor = AtomicActionExecutor(gateway, logger = {})

        val result = executor.execute(AtomicActions.in2, infoValue = 17)

        assertEquals(AtomicActionResult.Success, result)
        assertTrue("接管轮询时不能重复写信息或命令寄存器", gateway.writes.isEmpty())
    }

    @Test
    fun execute_shouldNotClearCommandWhenResultIsRunningBeforeFreshStart() = runBlocking {
        val gateway = FakeGateway(
            reads = mapOf(
                321 to queueOf(AtomicActionCodes.RUNNING, COMPLETED),
                320 to queueOf(AtomicActionCodes.RUNNING)
            )
        )
        val executor = AtomicActionExecutor(gateway, logger = {})

        val result = executor.execute(AtomicActions.in2, infoValue = 17)

        assertEquals(AtomicActionResult.Success, result)
        assertTrue("结果寄存器 0x0001（执行中）表示需要接管轮询，不能写命令 0 清镜像", gateway.writes.isEmpty())
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
        assertEquals("下平台抛肠后残肠红外失败", failed.message)
    }

    @Test
    fun execute_shouldMapIn3XResetErrorCodeToChineseMessage() = runBlocking {
        val gateway = FakeGateway(
            reads = mapOf(331 to queueOf(AtomicActionCodes.IDLE, AtomicActionCodes.IN3_X_RESET_FAILED))
        )
        val executor = AtomicActionExecutor(gateway, logger = {})

        val result = executor.execute(AtomicActions.in3, infoValue = 0)

        assertTrue(result is AtomicActionResult.Failed)
        val failed = result as AtomicActionResult.Failed
        assertEquals(0x00F0, failed.errorCode)
        assertEquals("in3 的 X 复位失败", failed.message)
    }

    @Test
    fun messageOf_shouldMapStickEmptyAndBusySeparately() {
        assertEquals(
            "穿签插空，签杆推出但疑似未穿到肠",
            AtomicActionExecutor.messageOf(AtomicActionCodes.STICK_EMPTY)
        )
        assertEquals("忙碌错误", AtomicActionExecutor.messageOf(AtomicActionCodes.BUSY))
    }

    @Test
    fun execute_shouldEmitTelemetryWhenActionFinishes() = runBlocking {
        val gateway = FakeGateway(
            reads = mapOf(371 to queueOf(AtomicActionCodes.IDLE, AtomicActionCodes.RUNNING, COMPLETED))
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
        assertEquals(AtomicActionSpec("in1", 310, 311, 312, 80_000L, 200L, "in1：移动到肠箱预推送位置"), AtomicActions.in1)
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
        private const val COMPLETED = 0x0002

        fun queueOf(vararg values: Int): ArrayDeque<Int> = ArrayDeque<Int>().apply { addAll(values.toList()) }
        fun repeatedQueue(value: Int, count: Int): ArrayDeque<Int> =
            ArrayDeque<Int>().apply { repeat(count) { add(value) } }
    }
}
