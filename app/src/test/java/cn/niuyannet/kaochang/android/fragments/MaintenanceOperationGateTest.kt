package cn.niuyannet.kaochang.android.fragments

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaintenanceOperationGateTest {

    @Test
    fun newerOperation_shouldInvalidateLateHttpCallbackAndFinally() {
        val gate = MaintenanceOperationGate()
        val oldToken = gate.begin(MaintenanceOperationKind.ALGORITHM_PREFLIGHT)
        val recoveryToken = gate.begin(MaintenanceOperationKind.FAULT_RECOVERY)

        assertFalse(gate.isCurrent(oldToken))
        assertFalse(gate.finish(oldToken))
        assertTrue(gate.isCurrent(recoveryToken))
        assertTrue(gate.finish(recoveryToken))
    }

    @Test
    fun destroyView_shouldInvalidateAllOutstandingOperations() {
        val gate = MaintenanceOperationGate()
        val token = gate.begin(MaintenanceOperationKind.FAULT_RECOVERY)

        gate.invalidateAll()

        assertFalse(gate.isCurrent(token))
        assertFalse(gate.finish(token))
    }
}
