package cn.niuyannet.kaochang.android.mqtt

import android.util.Log
import cn.niuyannet.kaochang.android.init.AppConfig
import com.alibaba.fastjson.JSON
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttMessage

/**
 * MQTT 操作类
 */
object VMMqttHelper: VMMqtt() {


    /**
     * 设置订阅消息回调
     */
    fun setTopicMessageCallBack(topicName: String, callback: (bean: TopicMessage) -> Unit) {
        mqttClient?.setCallback(object : MqttCallback {
            override fun messageArrived(topic: String?, message: MqttMessage?) {
                Log.d(TAG, "Receive message: ${message.toString()} from topic: $topic")
                if (topicName == topic){
                   callback.invoke(JSON.parseObject(message.toString(),TopicMessage::class.java))
                }
            }
            override fun connectionLost(cause: Throwable?) {
                Log.d(TAG, "Connection lost ${cause.toString()}")
            }
            override fun deliveryComplete(token: IMqttDeliveryToken?) {

            }
        })
    }


}