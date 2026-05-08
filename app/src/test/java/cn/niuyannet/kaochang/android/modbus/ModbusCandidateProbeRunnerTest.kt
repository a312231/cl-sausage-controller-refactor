package cn.niuyannet.kaochang.android.modbus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ModbusCandidateProbeRunnerTest {
    private class FakeMaster {
        var disconnected = false
    }

    @Test
    fun probeDisconnectsCandidateWhenOpenFailsAfterMasterCreated() {
        val fakeMaster = FakeMaster()

        val result = ModbusCandidateProbeRunner.probe(
            devicePath = "/dev/ttyUSB1",
            createMaster = { fakeMaster },
            connect = { throw IllegalStateException("Port busy") },
            hasResponse = { true },
            disconnect = { it.disconnected = true }
        )

        assertFalse(result.isSuccess)
        assertNull(result.master)
        assertTrue(fakeMaster.disconnected)
        assertEquals(ModbusCandidateProbeRunner.OPEN_FAILED, result.openResult)
        assertEquals(ModbusCandidateProbeRunner.PROBE_SKIPPED, result.probeResult)
        assertTrue(result.isPortBusy)
    }

    @Test
    fun probeDisconnectsCandidateWhenOpenedButNoModbusResponse() {
        val fakeMaster = FakeMaster()

        val result = ModbusCandidateProbeRunner.probe(
            devicePath = "/dev/ttyS7",
            createMaster = { fakeMaster },
            connect = { },
            hasResponse = { false },
            disconnect = { it.disconnected = true }
        )

        assertFalse(result.isSuccess)
        assertNull(result.master)
        assertTrue(fakeMaster.disconnected)
        assertEquals(ModbusCandidateProbeRunner.OPEN_SUCCESS, result.openResult)
        assertEquals(ModbusCandidateProbeRunner.PROBE_NO_RESPONSE, result.probeResult)
    }

    @Test
    fun probeKeepsCandidateWhenModbusResponseSucceeds() {
        val fakeMaster = FakeMaster()

        val result = ModbusCandidateProbeRunner.probe(
            devicePath = "/dev/ttyUSB0",
            createMaster = { fakeMaster },
            connect = { },
            hasResponse = { true },
            disconnect = { it.disconnected = true }
        )

        assertTrue(result.isSuccess)
        assertSame(fakeMaster, result.master)
        assertFalse(fakeMaster.disconnected)
        assertEquals(ModbusCandidateProbeRunner.OPEN_SUCCESS, result.openResult)
        assertEquals(ModbusCandidateProbeRunner.PROBE_SUCCESS, result.probeResult)
    }
}
