package cn.niuyannet.kaochang.android.services

import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleService
import cn.niuyannet.kaochang.android.utils.LogUtils
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class CameraService : LifecycleService() {

    // binder this
    private val mBinder = ClientBinder()
    // 是否初始化(无用 不会有竞争)
    private val initialization = AtomicBoolean(false)
    // 拍照锁
    private val takeStatus = AtomicBoolean(true)

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    //
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // 用于Activity和service通讯
    inner class ClientBinder : Binder() {
        val service: CameraService
            get() = this@CameraService
    }

    override fun onCreate() {
        super.onCreate()
        LogUtils.d("摄像头后台服务创建")
        initCamera()
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
        LogUtils.d("摄像头后台服务销毁")
        releaseCamera()
        super.onDestroy()
    }

    /**
     * 初始化
     **/
    private fun initCamera() {
        if (!initialization.get()) {
            initialization.set(true)
            //
            try {
                LogUtils.d("初始化相机")
                val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetRotation(Surface.ROTATION_0)
                    .setTargetResolution(Size(832, 448))
                    .build()
                cameraProvider = cameraProviderFuture.get()!!.apply {
                    unbindAll()
                    bindToLifecycle(
                        this@CameraService,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        imageCapture
                    )
                }
                LogUtils.d("相机绑定完成")
            } catch (e: Exception) {
                initialization.set(false)
                LogUtils.e("相机初始化失败 -> ${e.message}")
            }
        }
    }

    /**
     * 释放
     *
     */
    private fun releaseCamera() {
        try {
            cameraProvider?.unbindAll()
            cameraExecutor.shutdownNow()
        } catch (_: Exception) {}
    }

    /**
     * 拍照
     */
    fun takePhoto(callback: ImageCapture.OnImageCapturedCallback) {
        if (takeStatus.get()) {
            takeStatus.set(false)
            try {
                LogUtils.d("正在拍照...")
                imageCapture!!.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback(){
                    override fun onCaptureSuccess(image: ImageProxy) {
                        callback.onCaptureSuccess(image)
                        takeStatus.set(true)
                    }
                    override fun onError(exception: ImageCaptureException) {
                        callback.onError(exception)
                        takeStatus.set(true)
                    }
                })
            } catch (e: Exception) {
                callback.onError(ImageCaptureException(ImageCapture.ERROR_UNKNOWN, "imageCapture == null", e))
                takeStatus.set(true)
            }
        } else {
            callback.onError(ImageCaptureException(ImageCapture.ERROR_UNKNOWN, "等待上一个拍照任务结束", Exception()))
        }
    }
}