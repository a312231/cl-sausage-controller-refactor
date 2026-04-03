package cn.niuyannet.kaochang.android.mqtt

import cn.niuyannet.kaochang.android.init.AppConfig
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject

object SendServerHelper {

    /**
     * 通过 MQTT 上报订单动作事件（action=1）。
     * msgObj 内的 status 表示订单事件进度：
     * 1 = 开始夹取，2 = 单次夹取完成，5 = 单次夹取失败。
     */
    fun publishServiceUpdateOrder(msgObj: JSONObject): Boolean {
        return VMMqttHelper.publishService(
            TopicMessage().apply {
                action = 1
                content = JSON.toJSONString(msgObj)
            }
        )
    }

    /**
     * 通过 MQTT 上报设备状态（action=2）。
     * 这里上报的是 AppConfig，其中最关键的是 onlineStatus：
     * 0 未启用，1 运营中，2 休息中，3 维护中，4 工作中/正在出烤肠。
     * 注意：这不是订单事件状态，订单事件状态由 publishServiceUpdateOrder(action=1) 负责。
     */
    fun publishServiceUpdateStatus(): Boolean {
        val payload = JSON.parseObject(JSON.toJSONString(AppConfig.getAppConfig()))
        payload["isEnable"] = AppConfig.getAlgorithmEnableFlag()
        return VMMqttHelper.publishService(
            TopicMessage().apply {
                action = 2
                content = JSON.toJSONString(payload)
            }
        )
    }
}
