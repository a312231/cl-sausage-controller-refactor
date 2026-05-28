package cn.niuyannet.kaochang.android.net

import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.init.AppConfigBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteRuntimeControlGuardTest {
    @Test
    fun rejectReason_shouldRejectOldControlSeq() {
        val reason = RemoteRuntimeControlGuard.rejectReason(
            controlSeq = 100,
            lastAppliedControlSeq = 100,
            incomingConfigVersion = 2,
            localConfigVersion = 1
        )

        assertEquals("stale_control_seq", reason)
    }

    @Test
    fun rejectReason_shouldRejectOldConfigVersion() {
        val reason = RemoteRuntimeControlGuard.rejectReason(
            controlSeq = 101,
            lastAppliedControlSeq = 100,
            incomingConfigVersion = 1,
            localConfigVersion = 2
        )

        assertEquals("stale_version", reason)
    }

    @Test
    fun rejectReason_shouldAllowFreshControl() {
        val reason = RemoteRuntimeControlGuard.rejectReason(
            controlSeq = 101,
            lastAppliedControlSeq = 100,
            incomingConfigVersion = 2,
            localConfigVersion = 2
        )

        assertNull(reason)
    }

    @Test
    fun rejectFaultLatchedRestoreReason_shouldRejectRemoteRestoreForActionGroupFault() {
        val config = AppConfigBean().apply {
            onlineStatus = 3
            errorStatus = 2
            errorCode = "ACTION_GROUP_FAILED"
            isEnable = 0
        }
        val snapshot = AppConfig.FaultRuntimeSnapshot.from(config)

        val reason = RemoteRuntimeControlGuard.rejectFaultLatchedRestoreReason(
            snapshot = snapshot,
            requestedOnlineStatus = 1,
            requestedErrorStatus = 0,
            requestedIsEnable = 1
        )

        assertEquals("fault_latched_requires_manual_recovery", reason)
    }

    @Test
    fun rejectFaultLatchedRestoreReason_shouldAllowMaintenanceAndAlgorithmOffForActionGroupFault() {
        val config = AppConfigBean().apply {
            onlineStatus = 3
            errorStatus = 2
            errorCode = "ACTION_GROUP_FAILED"
            isEnable = 0
        }
        val snapshot = AppConfig.FaultRuntimeSnapshot.from(config)

        val reason = RemoteRuntimeControlGuard.rejectFaultLatchedRestoreReason(
            snapshot = snapshot,
            requestedOnlineStatus = 3,
            requestedErrorStatus = null,
            requestedIsEnable = 0
        )

        assertNull(reason)
    }

    @Test
    fun rejectFaultLatchedRestoreReason_shouldAllowModbusDisconnectAutoRestore() {
        val config = AppConfigBean().apply {
            onlineStatus = 3
            errorStatus = 2
            errorCode = "MODBUS_DISCONNECT"
            isEnable = 0
        }
        val snapshot = AppConfig.FaultRuntimeSnapshot.from(config)

        val reason = RemoteRuntimeControlGuard.rejectFaultLatchedRestoreReason(
            snapshot = snapshot,
            requestedOnlineStatus = 1,
            requestedErrorStatus = 0,
            requestedIsEnable = 0
        )

        assertNull(reason)
        assertTrue(snapshot.isFaultLatched)
        assertFalse(snapshot.isNonModbusAutoRecoverableFault)
    }
}
