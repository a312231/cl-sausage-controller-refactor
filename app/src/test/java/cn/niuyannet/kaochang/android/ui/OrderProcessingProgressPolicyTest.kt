package cn.niuyannet.kaochang.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class OrderProcessingProgressPolicyTest {

    @Test
    fun initialProgressText_shouldUseCurrentOrderTotalCount() {
        val orderMessage =
            """{"goods_items":"[{\"quantity\":3}]"}"""

        assertEquals("1/3", OrderProcessingProgressPolicy.initialProgressText(orderMessage))
    }

    @Test
    fun initialProgressText_shouldFallbackToOneWhenOrderPayloadIsInvalid() {
        assertEquals("1/1", OrderProcessingProgressPolicy.initialProgressText("{bad-json"))
    }
}
