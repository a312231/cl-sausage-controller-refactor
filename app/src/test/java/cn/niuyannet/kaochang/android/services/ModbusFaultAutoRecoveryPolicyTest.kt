package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.fault.FaultCode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModbusFaultAutoRecoveryPolicyTest {

    @Test
    fun isModbusAutoRecoverableFault_shouldAllowOnlyModbusDisconnect() {
        assertTrue(KaoChangAlgorithm.isModbusAutoRecoverableFault(FaultCode.MODBUS_DISCONNECT.name))
        assertFalse(KaoChangAlgorithm.isModbusAutoRecoverableFault(FaultCode.ACTION_GROUP_FAILED.name))
        assertFalse(KaoChangAlgorithm.isModbusAutoRecoverableFault(FaultCode.MODBUS_COMM_DROP.name))
        assertFalse(KaoChangAlgorithm.isModbusAutoRecoverableFault(FaultCode.KAOPAN_MOVE_FAILED.name))
        assertFalse(KaoChangAlgorithm.isModbusAutoRecoverableFault(null))
        assertFalse(KaoChangAlgorithm.isModbusAutoRecoverableFault(""))
    }
}
