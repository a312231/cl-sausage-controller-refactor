package cn.niuyannet.kaochang.android.mqtt

import cn.niuyannet.kaochang.android.MyApp
import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.utils.LogUtils
import com.alibaba.fastjson.JSON
import org.eclipse.paho.android.service.MqttAndroidClient
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage

/**
 * MQTT 基础操作封装。
 *
 * 这里只负责连接、订阅、发布和断开，不承载上层业务状态机。
 */
open class VMMqtt {
    var mqttClient: MqttAndroidClient? = null

    private val topicByServer = "/cl/devices/{device_id}/get"      // 服务端发给上位机的主题
    private val topicByDevice = "/cl/devices/{device_id}/service"  // 上位机上报给服务端的主题

    /**
     * 当前 MQTT 是否已连接。
     */
    fun connectStatus(): Boolean {
        return mqttClient?.isConnected == true
    }

    /**
     * 连接 MQTT 服务。
     */
    fun connect(callback: (status: Boolean) -> Unit) {
        try {
            if (connectStatus()) {
                LogUtils.d("【MQTT连接】已连接，跳过重复连接")
                callback(true)
                return
            }

            val appConfigBean = AppConfig.getAppConfig()
            val serverURI = "tcp://${appConfigBean.mqttHost}:${appConfigBean.mqttPort}"
            val username = appConfigBean.mqttUsername
            val passwordText = appConfigBean.mqttPassword ?: ""
            val clientId = "client_${AppConfig.getDeviceId()}"

            LogUtils.d("【MQTT连接】开始连接：server=$serverURI, clientId=$clientId")
            mqttClient = MqttAndroidClient(
                MyApp.instance().applicationContext,
                serverURI,
                clientId
            )

            val options = MqttConnectOptions().apply {
                userName = username
                password = passwordText.toCharArray()
                keepAliveInterval = 60
                isCleanSession = true
            }

            mqttClient?.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    LogUtils.d("【MQTT连接】连接成功：server=$serverURI, clientId=$clientId")
                    allSubScribe()
                    callback(true)
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    LogUtils.e("【MQTT连接】连接失败：server=$serverURI, clientId=$clientId, error=${exception?.message}")
                    callback(false)
                }
            })
        } catch (e: MqttException) {
            LogUtils.e("【MQTT连接】连接异常：error=${e.message}", e)
            callback(false)
        }
    }

    /**
     * 订阅主题。
     */
    fun subscribe(topic: String, qos: Int = 1) {
        try {
            LogUtils.d("【MQTT订阅】开始订阅：topic=$topic, qos=$qos")
            mqttClient?.subscribe(topic, qos, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    LogUtils.d("【MQTT订阅】订阅成功：topic=$topic, qos=$qos")
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    LogUtils.e("【MQTT订阅】订阅失败：topic=$topic, qos=$qos, error=${exception?.message}")
                }
            })
        } catch (e: MqttException) {
            LogUtils.e("【MQTT订阅】订阅异常：topic=$topic, qos=$qos, error=${e.message}", e)
        }
    }

    /**
     * 取消订阅主题。
     */
    fun unsubscribe(topic: String) {
        try {
            LogUtils.d("【MQTT订阅】开始取消订阅：topic=$topic")
            mqttClient?.unsubscribe(topic, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    LogUtils.d("【MQTT订阅】取消订阅成功：topic=$topic")
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    LogUtils.e("【MQTT订阅】取消订阅失败：topic=$topic, error=${exception?.message}")
                }
            })
        } catch (e: MqttException) {
            LogUtils.e("【MQTT订阅】取消订阅异常：topic=$topic, error=${e.message}", e)
        }
    }

    /**
     * 发布消息到指定主题。
     */
    fun publish(topic: String, msg: String, qos: Int = 1, retained: Boolean = false): Boolean {
        val client = mqttClient
        if (client == null) {
            LogUtils.w("【MQTT发布】跳过发布：客户端未初始化，topic=$topic")
            return false
        }
        if (!client.isConnected) {
            LogUtils.w("【MQTT发布】跳过发布：客户端未连接，topic=$topic")
            return false
        }
        return try {
            LogUtils.d("【MQTT发布】开始发布：topic=$topic, qos=$qos, retained=$retained")
            val message = MqttMessage().apply {
                payload = msg.toByteArray()
                this.qos = qos
                isRetained = retained
            }
            client.publish(topic, message, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    LogUtils.d("【MQTT发布】发布成功：topic=$topic")
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    LogUtils.e("【MQTT发布】发布失败：topic=$topic, error=${exception?.message}")
                }
            })
            true
        } catch (e: Throwable) {
            LogUtils.e("【MQTT发布】发布异常：topic=$topic, error=${e.message}", e)
            false
        }
    }

    /**
     * 断开 MQTT 连接。
     */
    fun disconnect() {
        try {
            LogUtils.d("【MQTT连接】开始断开连接")
            mqttClient?.disconnect(null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    LogUtils.d("【MQTT连接】断开连接成功")
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    LogUtils.e("【MQTT连接】断开连接失败：error=${exception?.message}")
                }
            })
        } catch (e: MqttException) {
            LogUtils.e("【MQTT连接】断开连接异常：error=${e.message}", e)
        }
    }

    /**
     * 向服务端主题发布消息。
     */
    fun publishService(it: TopicMessage): Boolean {
        return publish(AppConfig.getTopicDeviceIdUrl(topicByDevice), JSON.toJSONString(it))
    }

    /**
     * 连接成功后订阅当前设备对应的服务端主题。
     */
    fun allSubScribe() {
        subscribe(AppConfig.getTopicDeviceIdUrl(topicByServer))
    }

    /**
     * 取消当前设备的全部订阅。
     */
    fun unAllSubscribe() {
        unsubscribe(AppConfig.getTopicDeviceIdUrl(topicByServer))
    }
}
