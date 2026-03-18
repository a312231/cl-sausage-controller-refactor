package cn.niuyannet.kaochang.android.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.opengl.GLES11Ext
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.util.Log
import android.view.View
import android.widget.EditText
import cn.niuyannet.kaochang.android.R
import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.model.KaoPanHelper
import cn.niuyannet.kaochang.android.mqtt.VMMqttHelper
import cn.niuyannet.kaochang.android.net.DataManagementAPI
import cn.niuyannet.kaochang.android.services.CameraMonitor
import cn.niuyannet.kaochang.android.services.KaoChangAlgorithm
import cn.niuyannet.kaochang.android.utils.ConnectManageHelper
import cn.niuyannet.kaochang.android.utils.LogUtils
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
import kotlin.math.ceil
import kotlin.system.exitProcess

/**
 * 主界面
 */
class MainActivity : OrderActivity(), ICameraStateCallBack {
    private var runtimeStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initView()
        CameraMonitor.instance.startService(this)
    }

    override fun initData() {
        initAdsData()
        KaoPanHelper.init()

        val hasValidCachedConfig = AppConfig.hasValidDeviceConfig()
        if (hasValidCachedConfig) {
            setDataToView()
            startRuntimeAfterConfigLoaded("cached")
        }

        loadDeviceInfo { success ->
            if (success) {
                if (!isFinishing && !isDestroyed) {
                    setDataToView()
                }
                startRuntimeAfterConfigLoaded("remote")
            } else if (!hasValidCachedConfig) {
                LogUtils.w("设备配置拉取失败且本地无有效配置，跳过算法启动与状态同步，避免默认配置误上报")
            }
        }
    }

    private fun startRuntimeAfterConfigLoaded(source: String) {
        if (runtimeStarted) {
            LogUtils.d("运行链路已启动，忽略重复启动请求：source=$source")
            return
        }
        if (!AppConfig.hasValidDeviceConfig()) {
            LogUtils.w("设备配置仍无效，拒绝启动算法和状态同步：source=$source")
            return
        }
        runtimeStarted = true
        LogUtils.d("设备配置有效，开始启动算法与状态同步：source=$source")
        KaoChangAlgorithm.restoreRoastAlgorithmState()
        KaoChangAlgorithm.runServer()
        syncDeviceStatusToServer(retryDelaysMs = listOf(3000L))
        ConnectManageHelper.lunStatus {
            if (!isFinishing && !isDestroyed) {
                setDataToView()
                mqttMessage()
            }
            DataManagementAPI.getDeviceInfo()
            DataManagementAPI.uploadDataToServer()
        }
    }

    override fun onResume() {
        super.onResume()
        if (mCameraClient == null) {
            initCameraData()
        }
    }

    /**
     * 接受 mqtt 消息
     */
    private fun mqttMessage() {
        VMMqttHelper.setTopicMessageCallBack(AppConfig.getTopicDeviceIdUrl(VMMqttHelper.topic_by_server)) { data ->
            when (data.action) {
                1 -> {
                    val orderMessage = data.content
                    var appState = AppConfig.getAppConfig().moveStatus
                    if (appState == 2 || AppConfig.getAppConfig().onlineStatus == 4) {
                        LogUtils.d("上位机正在运行，无法下单，用户退款")
                    } else {
                        LogUtils.d("order 客户下单购买")
                        AppConfig.getAppConfig().onlineStatus = 4
                        AppConfig.saveAppConfig(AppConfig.getAppConfig())
                        syncDeviceStatusToServer(retryDelaysMs = listOf(2000L))
                        showProcessingDialog()
                        setDataToView()
                        purchaseOrder(orderMessage)
                    }
                }
            }
        }
    }

    private fun initView() {
        binding.refreshButton.setOnClickListener {
            loadDeviceInfo()
        }
        binding.configButton.setOnClickListener {
            goToSetting()
        }
        binding.tvGly.setOnClickListener {
            goToSetting()
        }
    }

    private fun goToSetting() {
        val editText = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            transformationMethod = PasswordTransformationMethod.getInstance()
            hint = "请输入密码"
        }
        AlertDialog.Builder(this)
            .setTitle("设备管理密码")
            .setView(editText)
            .setPositiveButton("确定") { _, _ ->
                val password = editText.text.toString()
                if (password == "changle@leye") {
                    SettingActivity.start(this)
                } else {
                    ToastUtils.showShort("密码错误")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    @SuppressLint("SetTextI18n")
    override fun setDataToView() {
        val appConfigBean = AppConfig.getAppConfig()
        if (appConfigBean.status == 1) {
            when (appConfigBean.onlineStatus) {
                0 -> {
                    binding.deviceStatusText.text = "未启用"
                    binding.deviceStatusText.setTextColor(resources.getColor(R.color.status_offline))
                }
                1 -> {
                    binding.deviceStatusText.text = "运营中"
                    binding.deviceStatusText.setTextColor(resources.getColor(R.color.status_operating))
                }
                2 -> {
                    binding.deviceStatusText.text = "休息中"
                    binding.deviceStatusText.setTextColor(resources.getColor(R.color.status_resting))
                }
                3 -> {
                    binding.deviceStatusText.text = "维护中"
                    binding.deviceStatusText.setTextColor(resources.getColor(R.color.status_maintaining))
                }
                4 -> {
                    binding.deviceStatusText.text = "正在出烤肠"
                    binding.deviceStatusText.setTextColor(resources.getColor(R.color.status_working))
                }
            }
        } else {
            binding.deviceStatusText.text = "离线"
            binding.deviceStatusText.setTextColor(resources.getColor(R.color.status_offline))
        }

        if (appConfigBean.onlineStatus == 3) {
            binding.tvScanTip.text = "设备维护中"
        } else {
            if (appConfigBean.available > 0) {
                val leftTime = appConfigBean.available
                if (leftTime >= 60_000) {
                    val minutes = ceil(appConfigBean.available / 60000.0).toInt()
                    binding.tvScanTip.text = "预计烤制 ${minutes} 分钟"
                } else {
                    val seconds = ceil(appConfigBean.available / 1000.0).toInt()
                    binding.tvScanTip.text = "预计烤制 ${seconds} 秒"
                }
            } else {
                binding.tvScanTip.text = "扫码下单 纯肉烤肠"
            }
        }

        binding.deviceNameText.text = appConfigBean.deviceName + "(" + appConfigBean.deviceCode + ")"
        if (appConfigBean.status == 1 && AppConfig.getAppConfig().onlineStatus == 1) {
            binding.qrCodeImage.visibility = View.VISIBLE
            Glide.with(this).load(AppConfig.getAppConfig().qcodeURL).into(binding.qrCodeImage)
        } else {
            binding.qrCodeImage.visibility = View.GONE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ConnectManageHelper.disConnect()
        KaoChangAlgorithm.stopServer()
        exitProcess(0)
    }

    companion object {
        init {
            System.loadLibrary("UVCCamera")
        }

        fun start(context: Context?) {
            context?.startActivity(Intent(context, MainActivity::class.java))
        }
    }

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

            override fun onDisConnectDec(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
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

    private fun findFirstCamera(callback: ICameraStateCallBack?): Pair<Boolean, MultiCameraClient.ICamera?> {
        try {
            mCameraMap.values.first().let { camera ->
                return if (!camera.isCameraOpened()) {
                    camera.setCameraStateCallBack(callback)
                    camera.openCamera(SurfaceTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES), getCameraRequest())
                    Pair(true, camera)
                } else {
                    Pair(false, camera)
                }
            }
        } catch (_: Exception) {
        }
        return Pair(false, null)
    }

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
        Log.d("Main", "clearCamera === ")
    }

    override fun onCameraState(
        self: MultiCameraClient.ICamera,
        code: ICameraStateCallBack.State,
        msg: String?
    ) {
        LogUtils.d("摄像头状态 ${code.name} - msg: $msg")
    }
}
