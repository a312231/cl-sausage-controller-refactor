package cn.niuyannet.kaochang.android.ui

import cn.niuyannet.kaochang.android.fault.FaultCode
import cn.niuyannet.kaochang.android.init.AppConfigBean

internal object OrderDeliveryFaultPolicy {

    fun applySingleDeliveryExceptionFault(config: AppConfigBean, exception: Exception) {
        config.errorStatus = 2
        config.onlineStatus = 3
        if (hasStructuredFault(config)) {
            return
        }
        config.errorCode = FaultCode.ORDER_DELIVERY_ERROR.name
        config.errorCategory = FaultCode.ORDER_DELIVERY_ERROR.category.display
        config.errorReason = "单根出餐未捕获异常：${exception.javaClass.simpleName}：${exception.message}"
    }

    private fun hasStructuredFault(config: AppConfigBean): Boolean {
        val code = config.errorCode?.trim().orEmpty()
        return config.errorStatus != AppConfigBean.ERROR_STATUS_NORMAL &&
            code.isNotEmpty() &&
            code != FaultCode.UNKNOWN.name
    }
}
