package cn.niuyannet.kaochang.android.ui

import cn.niuyannet.kaochang.android.fault.FaultCode
import cn.niuyannet.kaochang.android.init.AppConfigBean
import org.junit.Assert.assertEquals
import org.junit.Test

class OrderDeliveryFaultPolicyTest {

    @Test
    fun applySingleDeliveryException_shouldPreserveExistingStructuredFault() {
        val config = AppConfigBean().apply {
            onlineStatus = 4
            errorStatus = 2
            errorCode = FaultCode.ACTION_GROUP_FAILED.name
            errorCategory = FaultCode.ACTION_GROUP_FAILED.category.display
            errorReason = "正式出餐 动作组失败：out4"
        }

        OrderDeliveryFaultPolicy.applySingleDeliveryExceptionFault(
            config,
            IllegalStateException("单根出餐失败：orderNo=CL-A, itemId=1")
        )

        assertEquals(3, config.onlineStatus)
        assertEquals(2, config.errorStatus)
        assertEquals(FaultCode.ACTION_GROUP_FAILED.name, config.errorCode)
        assertEquals(FaultCode.ACTION_GROUP_FAILED.category.display, config.errorCategory)
        assertEquals("正式出餐 动作组失败：out4", config.errorReason)
    }

    @Test
    fun applySingleDeliveryException_shouldUseOrderDeliveryErrorWhenNoStructuredFaultExists() {
        val config = AppConfigBean().apply {
            onlineStatus = 4
            errorStatus = 0
        }

        OrderDeliveryFaultPolicy.applySingleDeliveryExceptionFault(
            config,
            IllegalStateException("单根出餐失败：orderNo=CL-B, itemId=2")
        )

        assertEquals(3, config.onlineStatus)
        assertEquals(2, config.errorStatus)
        assertEquals(FaultCode.ORDER_DELIVERY_ERROR.name, config.errorCode)
        assertEquals(FaultCode.ORDER_DELIVERY_ERROR.category.display, config.errorCategory)
        assertEquals(
            "单根出餐未捕获异常：IllegalStateException：单根出餐失败：orderNo=CL-B, itemId=2",
            config.errorReason
        )
    }

    @Test
    fun applySingleDeliveryException_shouldReplaceUnknownFaultWithOrderDeliveryError() {
        val config = AppConfigBean().apply {
            onlineStatus = 4
            errorStatus = 2
            errorCode = FaultCode.UNKNOWN.name
            errorCategory = FaultCode.UNKNOWN.category.display
            errorReason = "未提供原因，请检查上位机日志"
        }

        OrderDeliveryFaultPolicy.applySingleDeliveryExceptionFault(
            config,
            IllegalStateException("单根出餐失败：orderNo=CL-C, itemId=3")
        )

        assertEquals(FaultCode.ORDER_DELIVERY_ERROR.name, config.errorCode)
        assertEquals(FaultCode.ORDER_DELIVERY_ERROR.category.display, config.errorCategory)
    }
}
