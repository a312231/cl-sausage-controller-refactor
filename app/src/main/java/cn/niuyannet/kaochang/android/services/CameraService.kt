package cn.niuyannet.kaochang.android.services

import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import cn.niuyannet.kaochang.android.utils.LogUtils
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class CameraService : LifecycleService() {

    // binder this
    private val mBinder = ClientBinder()

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null

    private val runStatus = AtomicBoolean(true)
    private val isCameraInitialized = AtomicBoolean(false)
    private val isCameraReady = AtomicBoolean(false)
    private val cameraLock = Any()

    // 使用线程池而
    private val cameraExecutor: ExecutorService = Executors.newFixedThreadPool(1)
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    // 用于Activity和service通讯
    inner class ClientBinder : Binder() {
        val service: CameraService
            get() = this@CameraService
    }

    override fun onCreate() {
        super.onCreate()
        LogUtils.d("摄像头后台服务创建")
        handler.postDelayed(runnable, 500)
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return mBinder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runStatus.set(false)
        handler.removeCallbacks(runnable)
        releaseCamera()
        cameraExecutor.shutdown()
        LogUtils.d("摄像头后台服务销毁")
        super.onDestroy()
    }

    private val runnable = Runnable {
        if(runStatus.get()) {
            initCamera()
        }
    }

    /**
     * 初始化相机
     */
    private fun initCamera() {
        synchronized(cameraLock) {
            if (isCameraInitialized.get()) {
                return
            } else isCameraInitialized.set(true)
            try {
                LogUtils.d("开始初始化相机")
                //
                val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
                cameraProviderFuture.addListener({
                    try {
                        val provider = cameraProviderFuture.get()
                        // 配置ImageCapture
                        imageCapture = ImageCapture.Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                            .setTargetRotation(Surface.ROTATION_0)
                            .setTargetResolution(Size(832, 448))
                            .build()
                        // 摄像头
                        val cameraSelector = selectAnyAvailableCamera(provider)!!
                        // 绑定用例到生命周期
                        provider.bindToLifecycle(
                            this,
                            cameraSelector,
                            imageCapture
                        )
                        //
                        cameraProvider = provider
                        isCameraReady.set(true)
                        //
                        LogUtils.d("相机初始化成功并绑定到生命周期")
                    } catch (e: Exception) {
                        LogUtils.e("相机初始化失败: ${e.message}")
                        isCameraInitialized.set(false)
                    }
                }, ContextCompat.getMainExecutor(this))
            } catch (e: Exception) {
                LogUtils.e("获取CameraProvider失败: ${e.message}")
                isCameraInitialized.set(false)
            }
        }
    }

    /**
     * 释放
     *
     */
    private fun releaseCamera() {
        synchronized(cameraLock) {
            try {
                cameraProvider?.unbindAll()
                cameraProvider = null
                imageCapture = null
                //
                isCameraInitialized.set(false)
                isCameraReady.set(false)
                LogUtils.d("释放相机资源")
            } catch (e: Exception) {
                LogUtils.e("释放相机资源失败: ${e.message}")
            }
        }
    }

    /**
     * 拍照
     */
    fun takePhoto(callback: ImageCapture.OnImageCapturedCallback) {
        synchronized(cameraLock) {
            if (!isCameraReady.get() || imageCapture == null) {
                callback.onError(ImageCaptureException(ImageCapture.ERROR_CAMERA_CLOSED, "相机未就绪", Exception("相机未就绪")))
                return
            }
            try {
                LogUtils.d("开始拍照...")
                isCameraReady.set(false) // 拍照中 标记为忙
                //
                imageCapture!!.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        try {
                            callback.onCaptureSuccess(image)
                        } finally {
                            isCameraReady.set(true)
                        }
                    }
                    override fun onError(exception: ImageCaptureException) {
                        callback.onError(exception)
                    }
                })
            } catch (e: Exception) {
                LogUtils.e("拍照异常: ${e.message}")
            }
        }
//        try {
//            LogUtils.d("正在拍照... ${initializer.get()}")
//            initCamera()
//            imageCapture!!.takePicture(cameraExecutor, callback)
//        } catch (e: Exception) {
//            callback.onError(ImageCaptureException(ImageCapture.ERROR_UNKNOWN, "imageCapture == null", e))
//        }
    }

    private fun selectAnyAvailableCamera(cameraProvider: ProcessCameraProvider): CameraSelector? {
        return try {
            if (cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                LogUtils.d("找到并选择‘后置’摄像头")
                return CameraSelector.DEFAULT_BACK_CAMERA
            }
            if (cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                LogUtils.d("找到并选择‘前置’摄像头")
                return CameraSelector.DEFAULT_FRONT_CAMERA
            }
            val availableCameraInfos = cameraProvider.availableCameraInfos
            if (availableCameraInfos.isNotEmpty()) {
                LogUtils.d("使用第一个摄像头")
                return availableCameraInfos.first().cameraSelector
            }
            LogUtils.e("未找到任何可用的摄像头！")
            null
        } catch (e: Exception) {
            LogUtils.e("选择摄像头时发生异常: ${e.message}")
            null
        }
    }
}