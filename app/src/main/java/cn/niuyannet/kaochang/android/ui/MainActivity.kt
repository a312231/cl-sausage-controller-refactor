package cn.niuyannet.kaochang.android.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import cn.niuyannet.kaochang.android.R
import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.mqtt.KcOrderItem
import cn.niuyannet.kaochang.android.mqtt.SendServerHelper
import cn.niuyannet.kaochang.android.mqtt.VMMqttHelper
import cn.niuyannet.kaochang.android.net.DataManagementAPI
import cn.niuyannet.kaochang.android.services.KaoChangAlgorithm
import cn.niuyannet.kaochang.android.services.PhotoCaptureService
import cn.niuyannet.kaochang.android.utils.ConnectManageHelper
import cn.niuyannet.kaochang.android.utils.LogUtils
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject
import com.blankj.utilcode.util.ToastUtils
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

/**
 * 主界面
 */
class MainActivity : OrderActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 启动后台拍照服务
        PhotoCaptureService.startService(this)
        initView()
    }
    override fun initData() {
        initAdsData()//获取广告数据
        loadDeviceInfo()//获取设备初始化信息
        KaoChangAlgorithm.runServer()//运行烤肠服务
        //开始定时更新拉去任务
        ConnectManageHelper.lunStatus{
            if (!isFinishing&&!isDestroyed){
                setDataToView()//绑定数据
                mqttMessage()//接受mqtt消息
            }
            DataManagementAPI.getDeviceInfo()
            DataManagementAPI.uploadDataToServer()
        }
    }

    /**
     * 接受mqtt消息
     */
    private fun mqttMessage(){
        VMMqttHelper.setTopicMessageCallBack(AppConfig.getTopicDeviceIdUrl(VMMqttHelper.topic_by_server)){ data->
            when(data.action){
                1->{
                    val orderMessage = data.content
                    //下单取烤肠
                    var appState = AppConfig.getAppConfig().moveStatus
                    if (appState == 2) {
                        LogUtils.d("上位机正在运行，无法下单，用户退款")
                        val json = JSON.parseObject(orderMessage)
                        val orderNo = json.getString("order_no")
                        val goodsList =
                            JSON.parseArray(json.getString("goods_items"), KcOrderItem::class.java)
                        // 遍历商品列表
                        goodsList.forEach { item ->
                            // 根据数量循环取出烤肠
                            repeat(item.quantity) {
                                // 构建MQTT消息
                                SendServerHelper.publishServiceUpdateOrder(JSONObject().apply {
                                    put("order_no", orderNo)
                                    put("item_id", item.id)
                                    put("product_id", item.productId)
                                    put("taste_code", item.tasteCode)
                                    put("status", 5) // 退钱
                                })
                            }
                        }
                    }
                    else{
                        purchaseOrder(orderMessage)
                    }
                }
            }

        }
    }

    /**
     * 初始化view
     * {"msg":"操作成功","code":200,"data":{"createBy":null,"createTime":"2025-05-19 13:33:53","updateBy":null,"updateTime":"2025-05-22 19:14:40","remark":null,"id":100,"deviceCode":"5145a0380ff17f38","qrcodeUrl":"https://image.cheerluck.com/images/qcode/2025/05/19/1747632832808_temp_1747632832755.png","name":"牛研设备1号","description":"","merchantId":100,"agentId":100,"deviceStatus":"{}","businessHours":"{\"mode\":\"custom\",\"customTimes\":[{\"day\":1,\"enabled\":true,\"timeRange\":[\"22:42:48\",\"23:42:48\"]},{\"day\":2,\"enabled\":true,\"timeRange\":[\"22:42:48\",\"23:42:48\"]},{\"day\":3,\"enabled\":true,\"timeRange\":[\"22:42:48\",\"23:42:48\"]},{\"day\":4,\"enabled\":true,\"timeRange\":[\"22:42:48\",\"23:42:48\"]},{\"day\":5,\"enabled\":true,\"timeRange\":[\"22:42:48\",\"23:42:48\"]},{\"day\":6,\"enabled\":true,\"timeRange\":[\"22:42:48\",\"23:42:48\"]},{\"day\":7,\"enabled\":true,\"timeRange\":[\"22:42:48\",\"23:42:48\"]}]}","address":"湖南省长沙市岳麓区麓谷企业广场","longitude":0E-7,"latitude":0E-7,"mqttUrl":"mqtt.cheerluck.com","mqttUsername":"5145a0380ff17f38","mqttPassword":"yJe%0Tea2&IH","modbusAddress":"","totalSales":0,"totalSalesAmount":0.00,"status":"0","onlineStatus":"0"}}
     *
     */
    private fun initView() {
        binding.refreshButton.setOnClickListener {
            loadDeviceInfo()
        }
        binding.configButton.setOnClickListener {
            // 弹出对话框，输入设备密码才能进入到调试界面
            goToSetting()
        }
        binding.tvGly.setOnClickListener {
            goToSetting()
        }
    }
    private fun goToSetting(){
        val editText = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD or
                    android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
            hint = "请输入密码"
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("设备管理密码")
            .setView(editText)
            .setPositiveButton("确定") { _, _ ->
                val password = editText.text.toString()
                if (password == "changle@leye") {
                //if (password == "1") {
                    SettingActivity.start(this)
                } else {
                    ToastUtils.showShort("密码错误")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 设置绑定view
     */
    @SuppressLint("SetTextI18n")
    override fun setDataToView(){
        val appConfigBean=AppConfig.getAppConfig()
      // 在线状态（0离线 1运营中 2休息中 3维护中 4工作中（正在出烤肠））
        if (appConfigBean.status==1){
            when( appConfigBean.onlineStatus){
                0->{
                    binding.deviceStatusText.text="未启用"
                    binding.deviceStatusText.setTextColor(resources.getColor(R.color.status_offline))
                }
                1->{
                    binding.deviceStatusText.text="运营中"
                    binding.deviceStatusText.setTextColor(resources.getColor(R.color.status_operating))
                }
                2->{
                    binding.deviceStatusText.text="休息中"
                    binding.deviceStatusText.setTextColor(resources.getColor(R.color.status_resting))
                }
                3->{
                    binding.deviceStatusText.text="维护中"
                    binding.deviceStatusText.setTextColor(resources.getColor(R.color.status_maintaining))
                }
                4->{
                    binding.deviceStatusText.text="正在出烤肠"
                    binding.deviceStatusText.setTextColor(resources.getColor(R.color.status_working))
                }
            }
        }else{
            binding.deviceStatusText.text="离线"
            binding.deviceStatusText.setTextColor(resources.getColor(R.color.status_offline))
        }

        binding.deviceNameText.text=appConfigBean.deviceName+"("+appConfigBean.deviceCode+")"
        if (AppConfig.getAppConfig().onlineStatus == 4) {
            // 正在出烤肠时隐藏二维码
            binding.qrCodeImage.visibility = View.GONE
        } else {
            // 恢复显示并加载图片
            binding.qrCodeImage.visibility = View.VISIBLE
            Glide.with(this).load(AppConfig.getAppConfig().qcodeURL).into(binding.qrCodeImage)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ConnectManageHelper.disConnect()
        KaoChangAlgorithm.stopServer()
        // 停止后台拍照服务
        PhotoCaptureService.stopService(this)
        exitProcess(0)
    }

    /**
     *  启动
     */
    companion object {
        fun start(context: Context?) {
            context?.startActivity(Intent(context,MainActivity::class.java))
        }
    }

}
