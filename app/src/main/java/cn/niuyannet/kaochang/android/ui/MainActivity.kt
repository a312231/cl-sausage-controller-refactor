package cn.niuyannet.kaochang.android.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.opengl.GLES11Ext
import android.os.Bundle
import android.util.Log
import android.view.View
import cn.niuyannet.kaochang.android.R
import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.mqtt.KcOrderItem
import cn.niuyannet.kaochang.android.mqtt.SendServerHelper
import cn.niuyannet.kaochang.android.mqtt.VMMqttHelper
import cn.niuyannet.kaochang.android.net.DataManagementAPI
import cn.niuyannet.kaochang.android.services.CameraUvc
import cn.niuyannet.kaochang.android.services.KaoChangAlgorithm
import cn.niuyannet.kaochang.android.utils.ConnectManageHelper
import cn.niuyannet.kaochang.android.utils.LogUtils
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject
import com.blankj.utilcode.util.ToastUtils
import com.bumptech.glide.Glide
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.IDeviceConnectCallBack
import com.jiangdg.ausbc.camera.CameraUVC
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.render.env.RotateType
import com.jiangdg.usb.USBMonitor
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.exitProcess

/**
 * 主界面
 */
class MainActivity : OrderActivity(), ICameraStateCallBack {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 启动后台拍照服务
//        PhotoCaptureService.startService(this)
        initView()

        // 初始化
        CameraUvc.instance.init(this) { findFirstCamera(this) }
        initCameraData()
        // 启动后台拍照服务
//        CameraMonitor.instance.startService(this)
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

    override fun onResume() {
        super.onResume()
        if(mCameraClient == null) {
            initCameraData()
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
        clearCamera()
//        PhotoCaptureService.stopService(this)
        exitProcess(0)
    }

    /**
     *  启动
     */
    companion object {

        init {
            System.loadLibrary("UVCCamera")
        }

        fun start(context: Context?) {
            context?.startActivity(Intent(context,MainActivity::class.java))
        }
    }

    /// =================================== 摄像头初始化 =====================
    private var mCameraClient: MultiCameraClient? = null
    private val mCameraMap = ConcurrentHashMap<Int, MultiCameraClient.ICamera>()

    private fun initCameraData() {
        mCameraClient = MultiCameraClient(this, object : IDeviceConnectCallBack {
            override fun onAttachDev(device: UsbDevice?) {
                device ?: return
                generateCamera(this@MainActivity, device).apply {
                    mCameraMap[device.deviceId] = this
                    onCameraAttached(this)
                }
                mCameraClient?.requestPermission(device)
            }

            override fun onDetachDec(device: UsbDevice?) {
                mCameraMap.remove(device?.deviceId)?.apply {
                    setUsbControlBlock(null)
                    onCameraDetached(this)
                }
            }

            override fun onConnectDev(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
                device ?: return
                ctrlBlock ?: return
                mCameraMap[device.deviceId]?.apply {
                    setUsbControlBlock(ctrlBlock)
                    onCameraConnected(this)
                }
            }

            override fun onDisConnectDec(
                device: UsbDevice?,
                ctrlBlock: USBMonitor.UsbControlBlock?
            ) {
                mCameraMap[device?.deviceId]?.apply {
                    onCameraDisConnected(this)
                }
            }

            override fun onCancelDev(device: UsbDevice?) {
                mCameraMap[device?.deviceId]?.apply {
                    onCameraDisConnected(this)
                }
            }
        })
        mCameraClient?.register()
    }

    /**
     * 获取并打开第一个摄像头
     */
    private fun findFirstCamera(callback: ICameraStateCallBack?): MultiCameraClient.ICamera? {
        try {
            mCameraMap.values.first().let { camera ->
                if(!camera.isCameraOpened()) {
                    camera.setCameraStateCallBack(callback)
                    camera.openCamera(SurfaceTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES), getCameraRequest())
                }
                return camera
            }
        } catch (_: Exception) {}
        return null
    }

    // ========================== 摄像头相关 ===================================================
    private fun generateCamera(ctx: Context, device: UsbDevice): MultiCameraClient.ICamera {
        return CameraUVC(ctx, device)
    }
    private fun onCameraAttached(camera: MultiCameraClient.ICamera) {
        LogUtils.d("摄像头附加 -> ${camera.device.deviceName}")
    }
    private fun onCameraConnected(camera: MultiCameraClient.ICamera) {
        LogUtils.d("摄像头连接 -> ${camera.device.deviceName}")
    }
    private fun onCameraDetached(camera: MultiCameraClient.ICamera) {
        camera.closeCamera()
        LogUtils.d("摄像头断开 -> ${camera.device.deviceName}")
    }
    private fun onCameraDisConnected(camera: MultiCameraClient.ICamera) {
        camera.closeCamera()
    }

    private fun getCameraRequest(): CameraRequest {
        return CameraRequest.Builder()
            .setPreviewWidth(1280)
            .setPreviewHeight(720)
            .setDefaultRotateType(RotateType.ANGLE_0)
            .setAudioSource(CameraRequest.AudioSource.NONE)
            .create()
    }

    private fun clearCamera() {
        mCameraMap.values.forEach {
            Log.d("Main", "close camera: ${it.device.deviceId}")
            it.closeCamera()
        }
        mCameraMap.clear()
        mCameraClient?.unRegister()
        mCameraClient?.destroy()
        mCameraClient = null
    }

    override fun onCameraState(
        self: MultiCameraClient.ICamera,
        code: ICameraStateCallBack.State,
        msg: String?
    ) {
        LogUtils.d("摄像头状态: ${code.name} - msg: $msg")
    }
}
