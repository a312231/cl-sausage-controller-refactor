package cn.niuyannet.kaochang.android.mqtt

import cn.niuyannet.kaochang.android.init.AppConfig
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject

object SendServerHelper {

    /**
     * 更新订单状态
     */
    fun publishServiceUpdateOrder(msgObj:JSONObject){
        // 发送MQTT消息通知后端
        VMMqttHelper.publishService(TopicMessage().apply {
            action=1
            content= JSON.toJSONString(msgObj)
        })
    }
    /**
     * 更新上位机状态
     * 状态（0待付款，1夹取中 2请取肠 3已完成 4已取消 5退款中，6退款完成）
     */
    fun publishServiceUpdateStatus() {
        VMMqttHelper.publishService(TopicMessage().apply {
            action=2
            content= JSON.toJSONString(AppConfig.getAppConfig())
        })

    }

}