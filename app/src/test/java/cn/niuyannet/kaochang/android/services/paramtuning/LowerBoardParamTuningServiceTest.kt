package cn.niuyannet.kaochang.android.services.paramtuning

import cn.niuyannet.kaochang.android.services.actiongroup.AtomicIoResult
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicReadResult
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicRegistersReadResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LowerBoardParamTuningServiceTest {

    @Test
    fun floatWords_shouldUseLowWordFirst() {
        val words = LowerBoardParamCodec.floatToWords(100.0f)

        assertEquals(0x0000, words.low)
        assertEquals(0x42C8, words.high)
        assertEquals(100.0f, LowerBoardParamCodec.wordsToFloat(0x0000, 0x42C8), 0.0001f)
    }

    @Test
    fun readAll_shouldReadConfiguredBlocksAndParseCrossBlockFloat() = runBlocking {
        val gateway = FakeParamGateway().apply {
            batchReads[1120 to 5] = intArrayOf(0x0001, 0, 120, 0x0001, 0x0002)
            batchReads[1200 to 32] = IntArray(32).also { regs ->
                regs[0] = 0x0020
                putFloat(regs, 1201, 1200, 10.0f)
                putFloat(regs, 1229, 1200, 30.5f)
                regs[31] = LowerBoardParamCodec.floatToWords(40.25f).low
            }
            batchReads[1232 to 13] = IntArray(13).also { regs ->
                regs[0] = LowerBoardParamCodec.floatToWords(40.25f).high
                putFloat(regs, 1233, 1232, 50.5f)
            }
            batchReads[1300 to 15] = IntArray(15).also { regs ->
                regs[0] = 1
                putFloat(regs, 1301, 1300, 11.5f)
            }
            batchReads[1400 to 17] = IntArray(17).also { regs ->
                regs[0] = 0
                regs[1] = 2
                regs[2] = 3
                regs[3] = 0
                putFloat(regs, 1410, 1400, 101.25f)
                regs[16] = 0x00E2
            }
        }
        val service = LowerBoardParamTuningService(gateway, pollDelayMs = 0L)

        val result = service.readAll()

        assertTrue(result is ParamTuningReadResult.Success)
        val snapshot = (result as ParamTuningReadResult.Success).snapshot
        assertEquals(listOf(1120 to 5, 1200 to 32, 1232 to 13, 1300 to 15, 1400 to 17), gateway.readBlocks)
        assertEquals(0x0001, snapshot.status.paramStatus1120)
        assertEquals(120, snapshot.status.rawWordCount1122)
        assertEquals(0x00010002, snapshot.status.paramVersion)
        assertEquals(0x0020, (snapshot.upperParams.getValue("z_reset_sgthrs") as ParamValue.UInt16Value).value)
        assertEquals(10.0f, (snapshot.upperParams.getValue("bakeware_x_origin") as ParamValue.Float32Value).value, 0.0001f)
        assertEquals(40.25f, (snapshot.upperParams.getValue("discard_x") as ParamValue.Float32Value).value, 0.0001f)
        assertEquals(1, (snapshot.lowerParams.getValue("bottom_load_ir_check_enable") as ParamValue.UInt16Value).value)
        assertEquals(11.5f, (snapshot.lowerParams.getValue("exit_x") as ParamValue.Float32Value).value, 0.0001f)
        assertEquals(101.25f, snapshot.calibration.currentX, 0.0001f)
        assertEquals(0x00E2, snapshot.calibration.lastError1416)
    }

    @Test
    fun writeParam_shouldWriteUInt16AndFloatWithLowWordFirst() = runBlocking {
        val gateway = FakeParamGateway()
        val service = LowerBoardParamTuningService(gateway, pollDelayMs = 0L)

        assertTrue(service.writeParam("z_reset_sgthrs", ParamInput.UInt16Input(0x0033)) is ParamTuningResult.Success)
        assertTrue(service.writeParam("stick_x", ParamInput.FloatInput(100.0f)) is ParamTuningResult.Success)

        assertEquals(
            listOf(
                1200 to 0x0033,
                1219 to 0x0000,
                1220 to 0x42C8
            ),
            gateway.writes
        )
    }

    @Test
    fun writeParam_shouldRejectCoordinateOutOfRange() = runBlocking {
        val gateway = FakeParamGateway()
        val service = LowerBoardParamTuningService(gateway, pollDelayMs = 0L)

        val result = service.writeParam("stick_x", ParamInput.FloatInput(900.0f))

        assertTrue(result is ParamTuningResult.Failure)
        assertTrue((result as ParamTuningResult.Failure).message.contains("-500.0~800.0"))
        assertTrue(gateway.writes.isEmpty())
    }

    @Test
    fun saveFlash_shouldPoll1120UntilSaveSuccessOrFailure() = runBlocking {
        val successGateway = FakeParamGateway().apply {
            readQueues[1120] = ArrayDeque(listOf(0x0001, 0x0002))
        }
        val successService = LowerBoardParamTuningService(successGateway, pollDelayMs = 0L)

        assertTrue(successService.saveFlash() is ParamTuningResult.Success)
        assertEquals(listOf(1121 to 1), successGateway.writes)
        assertEquals(listOf(1120, 1120), successGateway.singleReads)

        val failureGateway = FakeParamGateway().apply {
            readQueues[1120] = ArrayDeque(listOf(0x0001, 0x8001))
        }
        val failureService = LowerBoardParamTuningService(failureGateway, pollDelayMs = 0L)

        val failure = failureService.saveFlash()

        assertTrue(failure is ParamTuningResult.Failure)
        assertEquals(0x8001, (failure as ParamTuningResult.Failure).code)
    }

    @Test
    fun saveFlash_shouldFailWhenPollingTimesOut() = runBlocking {
        var now = 0L
        val gateway = FakeParamGateway().apply {
            readQueues[1120] = ArrayDeque(listOf(0x0001, 0x0001, 0x0001))
        }
        val service = LowerBoardParamTuningService(
            gateway = gateway,
            pollDelayMs = 0L,
            flashTimeoutMs = 1L,
            nowMs = {
                now += 1L
                now
            }
        )

        val result = service.saveFlash()

        assertTrue(result is ParamTuningResult.Failure)
        assertTrue((result as ParamTuningResult.Failure).message.contains("超时"))
    }

    @Test
    fun saveFlash_defaultTimeoutShouldAllowTenSecondFlashWrite() = runBlocking {
        var now = 0L
        val gateway = FakeParamGateway().apply {
            readQueues[1120] = ArrayDeque(List(10) { 0x0001 } + 0x0002)
        }
        val service = LowerBoardParamTuningService(
            gateway = gateway,
            pollDelayMs = 0L,
            nowMs = {
                now += 1_000L
                now
            }
        )

        val result = service.saveFlash()

        assertTrue(result is ParamTuningResult.Success)
        assertEquals(listOf(1121 to 1), gateway.writes)
        assertEquals(11, gateway.singleReads.count { it == 1120 })
    }

    @Test
    fun restoreDefaultsToRam_shouldPoll1120UntilResetOk() = runBlocking {
        val gateway = FakeParamGateway().apply {
            readQueues[1120] = ArrayDeque(listOf(0x0001, 0x0003))
        }
        val service = LowerBoardParamTuningService(gateway, pollDelayMs = 0L)

        val result = service.restoreDefaultsToRam()

        assertTrue(result is ParamTuningResult.Success)
        assertEquals(listOf(1121 to 2), gateway.writes)
        assertEquals(listOf(1120, 1120), gateway.singleReads)
    }

    @Test
    fun moveTo_shouldWriteInputCoordinatesThenCommandAndStopOnErrorCode() = runBlocking {
        val successGateway = FakeParamGateway().apply {
            readQueues[1401] = ArrayDeque(listOf(0x0001, 0x0002))
        }
        val successService = LowerBoardParamTuningService(successGateway, pollDelayMs = 0L)

        assertTrue(successService.moveTo(100.0f, 200.0f, 30.5f) is ParamTuningResult.Success)
        assertEquals(
            listOf(
                1404 to 0x0000,
                1405 to 0x42C8,
                1406 to 0x0000,
                1407 to 0x4348,
                1408 to 0x0000,
                1409 to 0x41F4,
                1400 to 1
            ),
            successGateway.writes
        )

        val busyGateway = FakeParamGateway().apply {
            readQueues[1401] = ArrayDeque(listOf(0x00E1))
        }
        val busyService = LowerBoardParamTuningService(busyGateway, pollDelayMs = 0L)

        val busy = busyService.moveTo(10.0f, 20.0f, 30.0f)

        assertTrue(busy is ParamTuningResult.Failure)
        assertEquals(0x00E1, (busy as ParamTuningResult.Failure).code)
    }

    @Test
    fun moveTo_shouldStopWritingWhenFirstFrameFails() = runBlocking {
        val gateway = FakeParamGateway().apply {
            failedWrites += 1404
        }
        val service = LowerBoardParamTuningService(gateway, pollDelayMs = 0L)

        val result = service.moveTo(100.0f, 200.0f, 30.5f)

        assertTrue(result is ParamTuningResult.Failure)
        assertEquals(listOf(1404 to 0x0000), gateway.writes)
        assertTrue(gateway.singleReads.isEmpty())
    }

    @Test
    fun captureTarget_shouldRequireArrivedStatusAndValidateSlot() = runBlocking {
        val gateway = FakeParamGateway().apply {
            readQueues[1401] = ArrayDeque(listOf(0x0002, 0x0001, 0x0003))
        }
        val service = LowerBoardParamTuningService(gateway, pollDelayMs = 0L)

        val result = service.captureTarget(CalibrationTarget.Stick)

        assertTrue(result is ParamTuningResult.Success)
        assertEquals(listOf(1402 to 3, 1403 to 0, 1400 to 2), gateway.writes)
        assertEquals(listOf(1401, 1401, 1401), gateway.singleReads)

        val notArrivedGateway = FakeParamGateway().apply {
            readQueues[1401] = ArrayDeque(listOf(0x0000))
        }
        val notArrivedService = LowerBoardParamTuningService(notArrivedGateway, pollDelayMs = 0L)

        val notArrived = notArrivedService.captureTarget(CalibrationTarget.Lift)

        assertTrue(notArrived is ParamTuningResult.Failure)
        assertTrue((notArrived as ParamTuningResult.Failure).message.contains("1401=0x0002"))

        val invalidSlot = service.captureTarget(CalibrationTarget.BakewareSlot(34))
        assertTrue(invalidSlot is ParamTuningResult.Failure)
    }

    @Test
    fun homeCalibration_shouldWriteHomeCommandAndPollUntilCompleted() = runBlocking {
        val gateway = FakeParamGateway().apply {
            readQueues[1401] = ArrayDeque(listOf(0x0001, 0x0002))
        }
        val service = LowerBoardParamTuningService(gateway, pollDelayMs = 0L)

        val result = service.homeCalibration()

        assertTrue(result is ParamTuningResult.Success)
        assertEquals(listOf(1400 to 3), gateway.writes)
        assertEquals(listOf(1401, 1401), gateway.singleReads)
    }

    @Test
    fun writeParam_shouldAllowNonCoordinateParamsOutsideRange() = runBlocking {
        val gateway = FakeParamGateway()
        val service = LowerBoardParamTuningService(gateway, pollDelayMs = 0L)

        val disResult = service.writeParam("bakeware_dis", ParamInput.FloatInput(900.0f))
        assertTrue("bakeware_dis=900 should succeed (non-coordinate)", disResult is ParamTuningResult.Success)

        val pushOfsResult = service.writeParam("x_push_ofs", ParamInput.FloatInput(1200.0f))
        assertTrue("x_push_ofs=1200 should succeed (non-coordinate)", pushOfsResult is ParamTuningResult.Success)

        val reversePushOfsResult = service.writeParam("x_reverse_push_ofs", ParamInput.FloatInput(-600.0f))
        assertTrue("x_reverse_push_ofs=-600 should succeed (non-coordinate)", reversePushOfsResult is ParamTuningResult.Success)
    }

    @Test
    fun writeParam_shouldStillRejectCoordinateParamsOutOfRange() = runBlocking {
        val gateway = FakeParamGateway()
        val service = LowerBoardParamTuningService(gateway, pollDelayMs = 0L)

        val stickResult = service.writeParam("stick_x", ParamInput.FloatInput(900.0f))
        assertTrue("stick_x=900 should be rejected (coordinate)", stickResult is ParamTuningResult.Failure)
        assertTrue(gateway.writes.isEmpty())
    }

    @Test
    fun calibrationTarget_shouldMarkPushTargetsAsXYOnly() {
        assertFalse(CalibrationTarget.PushTo.supportsZ)
        assertFalse(CalibrationTarget.PushBack.supportsZ)
        assertTrue(CalibrationTarget.Stick.supportsZ)
    }

    private fun putFloat(registers: IntArray, registerAddress: Int, blockStart: Int, value: Float) {
        val words = LowerBoardParamCodec.floatToWords(value)
        val index = registerAddress - blockStart
        registers[index] = words.low
        registers[index + 1] = words.high
    }

    private class FakeParamGateway : LowerBoardParamGateway {
        val batchReads = linkedMapOf<Pair<Int, Int>, IntArray>()
        val readQueues = mutableMapOf<Int, ArrayDeque<Int>>()
        val failedWrites = mutableSetOf<Int>()
        val writes = mutableListOf<Pair<Int, Int>>()
        val readBlocks = mutableListOf<Pair<Int, Int>>()
        val singleReads = mutableListOf<Int>()

        override suspend fun readRegister(address: Int): AtomicReadResult {
            singleReads += address
            val queue = readQueues[address]
            return if (queue != null && queue.isNotEmpty()) {
                AtomicReadResult.Success(queue.removeFirst())
            } else {
                AtomicReadResult.Failure("未配置单寄存器读取：$address")
            }
        }

        override suspend fun readRegisters(startAddress: Int, quantity: Int): AtomicRegistersReadResult {
            readBlocks += startAddress to quantity
            val values = batchReads[startAddress to quantity]
            return if (values != null) {
                AtomicRegistersReadResult.Success(values)
            } else {
                AtomicRegistersReadResult.Failure("未配置批量读取：$startAddress,$quantity")
            }
        }

        override suspend fun writeRegister(address: Int, value: Int): AtomicIoResult {
            writes += address to value
            if (address in failedWrites) {
                return AtomicIoResult.Failure("写入失败")
            }
            return AtomicIoResult.Success
        }
    }
}
