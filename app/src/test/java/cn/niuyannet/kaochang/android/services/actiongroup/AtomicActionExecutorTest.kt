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
