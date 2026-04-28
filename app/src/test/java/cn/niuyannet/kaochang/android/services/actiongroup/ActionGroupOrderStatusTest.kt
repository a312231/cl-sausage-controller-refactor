package cn.niuyannet.kaochang.android.services.actiongroup

import cn.niuyannet.kaochang.android.services.KaoChangOperate
import org.junit.Assert.assertEquals
import org.junit.Test

class ActionGroupOrderStatusTest {

    @Test
    fun statusForTakeResult_shouldReportDiscardedAsCustomerTimeoutDiscarded() {
        assertEquals(2, ActionGroupOrderStatus.statusFor(KaoChangOperate.TakeSausageResult.TAKEN_BY_USER))
        assertEquals(6, ActionGroupOrderStatus.statusFor(KaoChangOperate.TakeSausageResult.DISCARDED))
        assertEquals(5, ActionGroupOrderStatus.statusFor(KaoChangOperate.TakeSausageResult.ERROR))
    }
}
