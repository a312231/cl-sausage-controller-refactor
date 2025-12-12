package cn.niuyannet.kaochang.android.mqtt

import android.util.Log
import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.MyApp
import com.alibaba.fastjson.JSON
import org.eclipse.paho.android.service.MqttAndroidClient
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage

/**
 * MQTT 操作类
 */
open class VMMqtt {
    var mqttClient: MqttAndroidClient?=null
    val TAG = "AndroidMqttClient"
    val topic_by_server: String = "/cl/devices/{device_id}/get" //服务器发送给上位机
    val topic_by_device: String = "/cl/devices/{device_id}/service" //监听上位机发送过来的消息
    /**
     * 连接状态
     */
    fun connectStatus():Boolean{
        return mqttClient?.isConnected == true
    }

    /**
     * 连接mqtt服务
     */
    fun connect(callback: (status: Boolean) -> Unit) {
        try {
            if (connectStatus()){
                callback.invoke(true)
                return
            }
            val appConfigBean= AppConfig.getAppConfig()
            val serverURI = "tcp://${appConfigBean.mqttHost}:${appConfigBean.mqttPort}"
            val username = appConfigBean.mqttUsername
            val password = appConfigBean.mqttPassword
            mqttClient = MqttAndroidClient(MyApp.instance().applicationContext, serverURI, "client_${AppConfig.getDeviceId()}")
            // 配置连接选项
            val options = MqttConnectOptions()
            options.userName = username
            options.password = password.toCharArray()
            options.keepAliveInterval = 60 // 设置心跳为60秒
            options.isCleanSession = true
            mqttClient?.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.d(TAG, "Connection success")
                    allSubScribe()//订阅所有的主题
                    callback.invoke(true)

                }
                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.d(TAG, "Connection failure")
                    callback.invoke(false)
                }
            })
        } catch (e: MqttException) {
            e.printStackTrace()
        }
    }

    /**
     * 订阅主题
     */
    fun subscribe(topic: String, qos: Int = 1) {
        try {
            mqttClient?.subscribe(topic, qos, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.d(TAG, "Subscribed to $topic")
                }
                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.d(TAG, "Failed to subscribe $topic")
                }
            })
        } catch (e: MqttException) {
            e.printStackTrace()
        }
    }

    /**
     * 取消订阅主题
     */
    fun unsubscribe(topic: String) {
        try {
            mqttClient?.unsubscribe(topic, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.d(TAG, "Unsubscribed to $topic")
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.d(TAG, "Failed to unsubscribe $topic")
                }
            })
        } catch (e: MqttException) {
            e.printStackTrace()
        }
    }

    /**
     * 发布消息到主题
     */
    fun publish(topic: String, msg: String, qos: Int = 1, retained: Boolean = false) {
        try {
            val message = MqttMessage()
            message.payload = msg.toByteArray()
            message.qos = qos
            message.isRetained = retained
            mqttClient?.publish(topic, message, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.d(TAG, "$msg published to $topic")
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.d(TAG, "Failed to publish $msg to $topic")
                }
            })
        } catch (e: MqttException) {
            e.printStackTrace()
        }
    }

    /**
     * 断开mqtt连接
     */
    fun disconnect() {
        try {
            mqttClient?.disconnect(null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.d(TAG, "Disconnected")
                }
                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.d(TAG, "Failed to disconnect")
                }
            })
        } catch (e: MqttException) {
            e.printStackTrace()
        }
    }


    /**
     * 发送消息给服务端
     */
    fun publishService(it: TopicMessage) {
        publish(AppConfig.getTopicDeviceIdUrl(topic_by_device), JSON.toJSONString(it))
    }

    /**
     * 连接成功，订阅所有的主题
     */
    fun allSubScribe(){
       subscribe(AppConfig.getTopicDeviceIdUrl(VMMqttHelper.topic_by_server))
    }
    /**
     * 取消所有的订阅
     */
    fun unAllSubscribe() {
        unsubscribe(AppConfig.getTopicDeviceIdUrl(VMMqttHelper.topic_by_server))
    }

}