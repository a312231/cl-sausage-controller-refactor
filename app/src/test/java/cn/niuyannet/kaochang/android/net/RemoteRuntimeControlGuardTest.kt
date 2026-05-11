package cn.niuyannet.kaochang.android.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
