package cn.niuyannet.kaochang.android.services.actiongroup

import cn.niuyannet.kaochang.android.modbus.VMModbusHelper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        assertTrue(timeout.message.contains("初判责任=疑似下位机复位或命令丢失"))
        assertTrue(timeout.message.contains("命令寄存器=340"))
        assertTrue(timeout.message.contains("命令值=0x0000（空闲/已清零）"))
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
    fun execute_shouldTimeoutWhenIdleAppearsTwiceAfterRunningInNewProtocol() = runBlocking {
        val spec = AtomicActionSpec("out1-test", 410, 411, 412, 80L, 10L, "out1：从烤盘取熟肠到穿签位")
        val gateway = FakeGateway(
            reads = mapOf(
                411 to queueOf(
                    AtomicActionCodes.IDLE,
                    AtomicActionCodes.RUNNING,
                    AtomicActionCodes.IDLE,
                    AtomicActionCodes.IDLE
                )
            )
        )
        val logs = mutableListOf<String>()
        val executor = AtomicActionExecutor(gateway, logger = { logs += it })

        val result = executor.execute(spec, infoValue = 2)

        assertTrue(result is AtomicActionResult.Timeout)
        val message = (result as AtomicActionResult.Timeout).message
        assertTrue(message.contains("初判责任=下位机执行状态异常回退"))
        assertTrue(message.contains("连续空闲确认次数=2"))
        assertTrue(logs.any { it.contains("执行中后读到空闲，等待复核") && it.contains("idleAfterRunningCount=1") })
    }

    @Test
    fun execute_shouldSucceedWhenIdleAfterRunningIsFollowedByCompleted() = runBlocking {
        val spec = AtomicActionSpec("out1-test", 410, 411, 412, 1_000L, 10L, "out1：从烤盘取熟肠到穿签位")
        val gateway = FakeGateway(
            reads = mapOf(
                411 to queueOf(
                    AtomicActionCodes.IDLE,
                    AtomicActionCodes.RUNNING,
                    AtomicActionCodes.IDLE,
                    COMPLETED
                )
            )
        )
        val logs = mutableListOf<String>()
        val executor = AtomicActionExecutor(gateway, logger = { logs += it })

        val result = executor.execute(spec, infoValue = 2)

        assertEquals(AtomicActionResult.Success, result)
        assertTrue(logs.any { it.contains("执行中后读到空闲，等待复核") && it.contains("idleAfterRunningCount=1") })
    }

    @Test
    fun execute_shouldContinueWhenIdleAfterRunningIsFollowedByRunningThenCompleted() = runBlocking {
        val spec = AtomicActionSpec("out1-test", 410, 411, 412, 1_000L, 10L, "out1：从烤盘取熟肠到穿签位")
        val gateway = FakeGateway(
            reads = mapOf(
                411 to queueOf(
                    AtomicActionCodes.IDLE,
                    AtomicActionCodes.RUNNING,
                    AtomicActionCodes.IDLE,
                    AtomicActionCodes.RUNNING,
                    COMPLETED
                )
            )
        )
        val logs = mutableListOf<String>()
        val executor = AtomicActionExecutor(gateway, logger = { logs += it })

        val result = executor.execute(spec, infoValue = 2)

        assertEquals(AtomicActionResult.Success, result)
        assertTrue(logs.any { it.contains("执行中后空闲复核恢复执行中") && it.contains("idleAfterRunningCount=1") })
    }

    @Test
    fun execute_shouldReturnControllerErrorWhenIdleAfterRunningIsFollowedByErrorCode() = runBlocking {
        val gateway = FakeGateway(
            reads = mapOf(
                351 to queueOf(
                    AtomicActionCodes.IDLE,
                    AtomicActionCodes.RUNNING,
                    AtomicActionCodes.IDLE,
                    AtomicActionCodes.IR_THROW_FAILED
                )
            )
        )
        val logs = mutableListOf<String>()
        val executor = AtomicActionExecutor(gateway, logger = { logs += it })

        val result = executor.execute(AtomicActions.in5, infoValue = 0)

        assertTrue(result is AtomicActionResult.Failed)
        val failed = result as AtomicActionResult.Failed
        assertEquals(AtomicActionCodes.IR_THROW_FAILED, failed.errorCode)
        assertTrue(failed.message.contains("初判责任=下位机明确返回动作错误码"))
        assertTrue(logs.any { it.contains("执行中后读到空闲，等待复核") && it.contains("idleAfterRunningCount=1") })
    }

    @Test
    fun execute_shouldReturnStructuredDiagnosisWhenIdleAppearsTwiceAfterRunning() = runBlocking {
        val spec = AtomicActionSpec("out3", 430, 431, 432, 80L, 10L, "out3：从穿签位放到预推送位置")
        val gateway = FakeGateway(
            reads = mapOf(
                431 to queueOf(
                    AtomicActionCodes.IDLE,
                    AtomicActionCodes.RUNNING,
                    AtomicActionCodes.IDLE,
                    AtomicActionCodes.IDLE
                )
            )
        )
        val executor = AtomicActionExecutor(
            gateway = gateway,
            logger = {},
            diagnosisSnapshotProvider = {
                VMModbusHelper.ModbusDiagnosisSnapshot(
                    connected = true,
                    port = "/dev/ttyS2",
                    baudRate = 115200
                )
            }
        )

        val result = executor.execute(spec, infoValue = 0)

        assertTrue(result is AtomicActionResult.Timeout)
        val message = (result as AtomicActionResult.Timeout).message
        assertTrue(message.startsWith("初判责任=下位机执行状态异常回退"))
        assertTrue(message.contains("失败阶段=执行中后结果回退"))
        assertTrue(message.contains("动作=out3"))
        assertTrue(message.contains("结果寄存器=431"))
        assertTrue(message.contains("结果值=0x0000（空闲/未启动）"))
        assertTrue(message.contains("已观察到执行中=是"))
        assertTrue(message.contains("连续空闲确认次数=2"))
        assertTrue(message.contains("连接状态=已连接"))
        assertTrue(message.contains("串口地址=/dev/ttyS2"))
        assertFalse(message.contains("信息值"))
        assertTrue(message.length <= 300)
    }

    @Test
    fun execute_shouldReturnStructuredDiagnosisWhenStartupCommandStillPending() = runBlocking {
        val spec = AtomicActionSpec("in4-test", 340, 341, 342, 80L, 10L, "in4：升降台下降")
        val gateway = FakeGateway(
            reads = mapOf(
                341 to repeatedQueue(AtomicActionCodes.IDLE, 20),
                340 to repeatedQueue(AtomicActionCodes.RUNNING, 20)
            )
        )
        val executor = AtomicActionExecutor(
            gateway = gateway,
            logger = {},
            startupConfirmTimeoutMs = 30L,
            commandPendingConfirmTimeoutMs = 40L,
            diagnosisSnapshotProvider = { testSnapshot() }
        )

        val result = executor.execute(spec, infoValue = 0)

        assertTrue(result is AtomicActionResult.Timeout)
        val message = (result as AtomicActionResult.Timeout).message
        assertTrue(message.startsWith("初判责任=下位机长时间未接收命令"))
        assertTrue(message.contains("命令寄存器=340"))
        assertTrue(message.contains("命令值=0x0001（请求执行）"))
        assertFalse(message.contains("信息值"))
    }

    @Test
    fun execute_shouldReturnStructuredDiagnosisWhenStartupCommandCleared() = runBlocking {
        val spec = AtomicActionSpec("in4-test", 340, 341, 342, 80L, 10L, "in4：升降台下降")
        val gateway = FakeGateway(
            reads = mapOf(
                341 to repeatedQueue(AtomicActionCodes.IDLE, 20),
                340 to repeatedQueue(AtomicActionCodes.IDLE, 20)
            )
        )
        val executor = AtomicActionExecutor(
            gateway = gateway,
            logger = {},
            startupConfirmTimeoutMs = 30L,
            diagnosisSnapshotProvider = { testSnapshot() }
        )

        val result = executor.execute(spec, infoValue = 0)

        assertTrue(result is AtomicActionResult.Timeout)
        val message = (result as AtomicActionResult.Timeout).message
        assertTrue(message.startsWith("初判责任=疑似下位机复位或命令丢失"))
        assertTrue(message.contains("命令值=0x0000（空闲/已清零）"))
        assertFalse(message.contains("信息值"))
    }

    @Test
    fun execute_shouldReturnStructuredDiagnosisForWriteInfoFailureWithInfoValue() = runBlocking {
        val spec = AtomicActionSpec("out1-test", 410, 411, 412, 1_000L, 10L, "out1：从烤盘取熟肠到穿签位")
        val gateway = FakeGateway(
            reads = mapOf(411 to queueOf(AtomicActionCodes.IDLE)),
            writeResult = AtomicIoResult.Failure("操作=写入，寄存器地址=412")
        )
        val executor = AtomicActionExecutor(
            gateway = gateway,
            logger = {},
            diagnosisSnapshotProvider = { testSnapshot() }
        )

        val result = executor.execute(spec, infoValue = 8)

        assertTrue(result is AtomicActionResult.ModbusError)
        val message = (result as AtomicActionResult.ModbusError).message
        assertTrue(message.startsWith("初判责任=上位机写入下位机信息寄存器失败"))
        assertTrue(message.contains("信息寄存器=412"))
        assertTrue(message.contains("信息值=8"))
    }

    @Test
    fun execute_shouldReturnStructuredDiagnosisForKnownControllerErrorCode() = runBlocking {
        val gateway = FakeGateway(
            reads = mapOf(351 to queueOf(AtomicActionCodes.IDLE, AtomicActionCodes.IR_THROW_FAILED))
        )
        val executor = AtomicActionExecutor(
            gateway = gateway,
            logger = {},
            diagnosisSnapshotProvider = { testSnapshot() }
        )

        val result = executor.execute(AtomicActions.in5, infoValue = 0)

        assertTrue(result is AtomicActionResult.Failed)
        val message = (result as AtomicActionResult.Failed).message
        assertTrue(message.startsWith("初判责任=下位机明确返回动作错误码"))
        assertTrue(message.contains("错误说明=下平台抛肠后残肠红外失败"))
    }

    @Test
    fun execute_shouldReturnStructuredDiagnosisForUnknownResultCode() = runBlocking {
        val spec = AtomicActionSpec("out2-test", 420, 421, 422, 1_000L, 10L, "out2：执行穿签")
        val gateway = FakeGateway(
            reads = mapOf(421 to queueOf(AtomicActionCodes.IDLE, 0x0003))
        )
        val executor = AtomicActionExecutor(
            gateway = gateway,
            logger = {},
            diagnosisSnapshotProvider = { testSnapshot() }
        )

        val result = executor.execute(spec, infoValue = 0)

        assertTrue(result is AtomicActionResult.ModbusError)
        val message = (result as AtomicActionResult.ModbusError).message
        assertTrue(message.startsWith("初判责任=上下位机协议口径不一致"))
        assertTrue(message.contains("结果值=0x0003（当前值）"))
        assertFalse(message.contains("信息值"))
    }

    @Test
    fun execute_shouldReturnStructuredDiagnosisForTimeoutWithoutRunningObserved() = runBlocking {
        val spec = AtomicActionSpec("out2-test", 420, 421, 422, 40L, 10L, "out2：执行穿签")
        val gateway = FakeGateway(
            reads = mapOf(421 to repeatedQueue(AtomicActionCodes.IDLE, 20))
        )
        val executor = AtomicActionExecutor(
            gateway = gateway,
            logger = {},
            startupConfirmTimeoutMs = 100L,
            diagnosisSnapshotProvider = { testSnapshot() }
        )

        val result = executor.execute(spec, infoValue = 0)

        assertTrue(result is AtomicActionResult.Timeout)
        val message = (result as AtomicActionResult.Timeout).message
        assertTrue(message.startsWith("初判责任=下位机未启动或未回写执行中"))
        assertTrue(message.contains("已观察到执行中=否"))
        assertFalse(message.contains("信息值"))
    }

    @Test
    fun execute_shouldReturnStructuredDiagnosisForTimeoutAfterRunningObserved() = runBlocking {
        val spec = AtomicActionSpec("out2-test", 420, 421, 422, 40L, 10L, "out2：执行穿签")
        val gateway = FakeGateway(
            reads = mapOf(421 to repeatedQueue(AtomicActionCodes.RUNNING, 20))
        )
        val executor = AtomicActionExecutor(
            gateway = gateway,
            logger = {},
            diagnosisSnapshotProvider = { testSnapshot() }
        )

        val result = executor.execute(spec, infoValue = 0)

        assertTrue(result is AtomicActionResult.Timeout)
        val message = (result as AtomicActionResult.Timeout).message
        assertTrue(message.startsWith("初判责任=下位机执行超时未回写完成"))
        assertTrue(message.contains("已观察到执行中=是"))
        assertFalse(message.contains("信息值"))
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
        assertTrue(failed.message.contains("初判责任=下位机明确返回动作错误码"))
        assertTrue(failed.message.contains("错误说明=下平台抛肠后残肠红外失败"))
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
        assertTrue(failed.message.contains("初判责任=下位机明确返回动作错误码"))
        assertTrue(failed.message.contains("错误说明=in3 的 X 复位失败"))
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
    fun messageOf_shouldDescribeOutExitSausageNotDetectedWithoutCustomerTimeoutWording() {
        assertEquals(
            "售卖口未检测到烤肠（出肠检测失败）",
            AtomicActionExecutor.messageOf(AtomicActionCodes.OUT_EXIT_SAUSAGE_NOT_DETECTED)
        )
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
        val finishedTelemetry = telemetry.first { it.eventPhase == "finished" }
        assertEquals("300_400", finishedTelemetry.actionProtocolVersion)
        assertEquals("session-1", finishedTelemetry.sessionId)
        assertEquals("in7", finishedTelemetry.actionName)
        assertEquals(370, finishedTelemetry.registerAddress)
        assertEquals(371, finishedTelemetry.resultRegister)
        assertEquals(17, finishedTelemetry.boxNo)
        assertEquals(2, finishedTelemetry.panNo)
        assertEquals("order-1", finishedTelemetry.orderId)
        assertEquals("BOTTOM|LIFT|ABOVE", finishedTelemetry.resourceLocks)
        assertEquals(null, finishedTelemetry.errorCode)
    }

    @Test
    fun execute_shouldEmitStructuredTelemetryForStartedRunningAndFinishedPhases() = runBlocking {
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
        assertEquals(listOf("started", "running_confirmed", "finished"), telemetry.map { it.eventPhase })
        assertEquals(3, telemetry.map { it.eventId }.distinct().size)
        assertTrue(telemetry.all { it.eventId.isNotBlank() })
        assertTrue(telemetry.all { it.reportedAt > 0L })
        assertEquals(2, telemetry.first { it.eventPhase == "started" }.infoValue)
        assertEquals(AtomicActionCodes.RUNNING, telemetry.first { it.eventPhase == "started" }.commandValue)
        assertEquals(AtomicActionCodes.RUNNING, telemetry.first { it.eventPhase == "running_confirmed" }.resultCode)
        assertEquals(COMPLETED, telemetry.first { it.eventPhase == "finished" }.resultCode)
    }

    @Test
    fun execute_shouldAttachPhysicalCheckpointOnlyToSuccessfulFinishedActions() = runBlocking {
        val cases = listOf(
            Triple(AtomicActions.in2, 5, "SUPPLEMENT_PICKED_FROM_BOX" to "DRUM"),
            Triple(AtomicActions.in5, 0, "SUPPLEMENT_THROWN_TO_LIFT" to "LIFT"),
            Triple(AtomicActions.in7, 9, "SUPPLEMENT_PLACED_ON_PAN" to "PAN"),
            Triple(AtomicActions.out1, 9, "OUT_REMOVED_FROM_PAN" to "SKEWERING_POSITION")
        )

        cases.forEach { (spec, infoValue, expected) ->
            val telemetry = mutableListOf<ActionGroupTelemetry>()
            val gateway = FakeGateway(
                reads = mapOf(spec.resultRegister to queueOf(AtomicActionCodes.IDLE, AtomicActionCodes.RUNNING, COMPLETED))
            )
            val executor = AtomicActionExecutor(
                gateway = gateway,
                logger = {},
                telemetryContext = ActionGroupTelemetryContext(
                    sessionId = "session-${spec.name}",
                    boxNo = 5,
                    panNo = 9,
                    orderId = "order-1"
                ),
                telemetrySink = { telemetry += it }
            )

            assertEquals(AtomicActionResult.Success, executor.execute(spec, infoValue))

            val finishedTelemetry = telemetry.first { it.eventPhase == "finished" }
            assertEquals(expected.first, finishedTelemetry.physicalCheckpoint)
            assertEquals(expected.second, finishedTelemetry.physicalLocation)
            telemetry.filterNot { it.eventPhase == "finished" }.forEach {
                assertNull(it.physicalCheckpoint)
                assertNull(it.physicalLocation)
            }
        }
    }

    @Test
    fun execute_shouldNotAttachPhysicalCheckpointToFailedFinishedAction() = runBlocking {
        val telemetry = mutableListOf<ActionGroupTelemetry>()
        val gateway = FakeGateway(
            reads = mapOf(
                321 to queueOf(
                    AtomicActionCodes.IDLE,
                    AtomicActionCodes.RUNNING,
                    AtomicActionCodes.IR_PICK_FAILED
                )
            )
        )
        val executor = AtomicActionExecutor(
            gateway = gateway,
            logger = {},
            telemetryContext = ActionGroupTelemetryContext(
                sessionId = "session-failed",
                boxNo = 5,
                panNo = 9
            ),
            telemetrySink = { telemetry += it }
        )

        val result = executor.execute(AtomicActions.in2, infoValue = 5)

        assertTrue(result is AtomicActionResult.Failed)
        val finishedTelemetry = telemetry.first { it.eventPhase == "finished" }
        assertNull(finishedTelemetry.physicalCheckpoint)
        assertNull(finishedTelemetry.physicalLocation)
    }

    @Test
    fun execute_shouldNotAttachPhysicalCheckpointToUnmappedSuccessfulAction() = runBlocking {
        val telemetry = mutableListOf<ActionGroupTelemetry>()
        val gateway = FakeGateway(
            reads = mapOf(331 to queueOf(AtomicActionCodes.IDLE, AtomicActionCodes.RUNNING, COMPLETED))
        )
        val executor = AtomicActionExecutor(
            gateway = gateway,
            logger = {},
            telemetrySink = { telemetry += it }
        )

        assertEquals(AtomicActionResult.Success, executor.execute(AtomicActions.in3, infoValue = 0))

        val finishedTelemetry = telemetry.first { it.eventPhase == "finished" }
        assertNull(finishedTelemetry.physicalCheckpoint)
        assertNull(finishedTelemetry.physicalLocation)
    }

    @Test
    fun execute_shouldNotAttachPhysicalCheckpointToTimeoutOrModbusErrorFinishedAction() = runBlocking {
        val timeoutTelemetry = mutableListOf<ActionGroupTelemetry>()
        val timeoutGateway = FakeGateway(
            reads = mapOf(411 to repeatedQueue(AtomicActionCodes.RUNNING, 20))
        )
        val timeoutExecutor = AtomicActionExecutor(
            gateway = timeoutGateway,
            logger = {},
            telemetrySink = { timeoutTelemetry += it }
        )

        val timeoutResult = timeoutExecutor.execute(AtomicActions.out1.copy(timeoutMs = 30L, pollDelayMs = 10L), infoValue = 9)

        assertTrue(timeoutResult is AtomicActionResult.Timeout)
        timeoutTelemetry.first { it.eventPhase == "finished" }.let {
            assertNull(it.physicalCheckpoint)
            assertNull(it.physicalLocation)
        }

        val modbusTelemetry = mutableListOf<ActionGroupTelemetry>()
        val modbusExecutor = AtomicActionExecutor(
            gateway = FakeGateway(
                reads = mapOf(371 to queueOf(AtomicActionCodes.IDLE)),
                writeResult = AtomicIoResult.Failure("写命令失败")
            ),
            logger = {},
            telemetrySink = { modbusTelemetry += it }
        )

        val modbusResult = modbusExecutor.execute(AtomicActions.in7, infoValue = 9)

        assertTrue(modbusResult is AtomicActionResult.ModbusError)
        modbusTelemetry.first { it.eventPhase == "finished" }.let {
            assertNull(it.physicalCheckpoint)
            assertNull(it.physicalLocation)
        }
    }

    @Test
    fun execute_shouldEmitRunningConfirmedWhenTakingOverAlreadyRunningAction() = runBlocking {
        val gateway = FakeGateway(
            reads = mapOf(321 to queueOf(AtomicActionCodes.RUNNING, COMPLETED))
        )
        val telemetry = mutableListOf<ActionGroupTelemetry>()
        val executor = AtomicActionExecutor(
            gateway = gateway,
            logger = {},
            telemetryContext = ActionGroupTelemetryContext(
                sessionId = "session-takeover",
                boxNo = 5,
                panNo = 9,
                resourceLocks = "BOTTOM|LIFT|ABOVE"
            ),
            telemetrySink = { telemetry += it }
        )

        val result = executor.execute(AtomicActions.in2, infoValue = 5)

        assertEquals(AtomicActionResult.Success, result)
        assertEquals(listOf("running_confirmed", "finished"), telemetry.map { it.eventPhase })
        assertEquals(AtomicActionCodes.RUNNING, telemetry.first { it.eventPhase == "running_confirmed" }.resultCode)
        assertEquals(COMPLETED, telemetry.first { it.eventPhase == "finished" }.resultCode)
    }

    @Test
    fun execute_shouldLogCommunicationSummaryForSuccessfulAction() = runBlocking {
        val gateway = FakeGateway(
            reads = mapOf(311 to queueOf(AtomicActionCodes.IDLE, AtomicActionCodes.RUNNING, COMPLETED))
        )
        val logs = mutableListOf<String>()
        val executor = AtomicActionExecutor(
            gateway = gateway,
            logger = { logs += it },
            diagnosisSnapshotProvider = { testSnapshot() },
            modbusServerAddress = 7
        )

        val result = executor.execute(AtomicActions.in1, infoValue = 17)

        assertEquals(AtomicActionResult.Success, result)
        assertTrue(
            logs.any {
                it.contains("【动作组通信】启动前读取") &&
                    it.contains("动作=in1") &&
                    it.contains("结果寄存器=311") &&
                    it.contains("结果值=0x0000")
            }
        )
        assertTrue(
            logs.any {
                it.contains("【动作组通信】下发完成") &&
                    it.contains("信息寄存器=312") &&
                    it.contains("信息值=17") &&
                    it.contains("命令寄存器=310") &&
                    it.contains("命令值=0x0001")
            }
        )
        assertTrue(
            logs.any {
                it.contains("【动作组通信】动作结束") &&
                    it.contains("结果=成功") &&
                    it.contains("结果值=0x0002（完成）")
            }
        )
        val communicationLogs = logs.filter { it.contains("【动作组通信】") }
        assertTrue(communicationLogs.isNotEmpty())
        assertFalse(communicationLogs.any { it.contains("串口地址=") })
        assertFalse(communicationLogs.any { it.contains("波特率=") })
        assertFalse(communicationLogs.any { it.contains("从站地址=") })
        assertFalse(communicationLogs.any { it.contains("连接状态=") })
    }

    @Test
    fun definitions_shouldUse300And400Registers() {
        assertEquals(AtomicActionSpec("in1", 310, 311, 312, 80_000L, 200L, "in1：移动到肠箱预推送位置"), AtomicActions.in1)
        assertEquals(AtomicActionSpec("in7", 370, 371, 372, 120_000L, 500L, "in7：上平台从升降台夹肠并放入烤盘"), AtomicActions.in7)
        assertEquals(AtomicActionSpec("out5", 450, 451, 452, 90_000L, 500L, "out5：关门回零/出餐收尾"), AtomicActions.out5)
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

        fun testSnapshot(): VMModbusHelper.ModbusDiagnosisSnapshot =
            VMModbusHelper.ModbusDiagnosisSnapshot(
                connected = false,
                port = "/dev/ttyS9",
                baudRate = 9600
            )
    }
}
