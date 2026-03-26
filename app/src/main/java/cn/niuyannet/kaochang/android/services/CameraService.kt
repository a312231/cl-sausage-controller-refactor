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

    private val binder = ClientBinder()

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null

    private val isCameraInitialized = AtomicBoolean(false)
    private val isCameraInitializing = AtomicBoolean(false)
    private val isCameraReady = AtomicBoolean(false)
    private val cameraLock = Any()
    private val autoReleaseRunnable = Runnable {
        LogUtils.d("相机预热超时未使用，自动释放相机资源")
        releaseCamera()
    }

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    inner class ClientBinder : Binder() {
        val service: CameraService
            get() = this@CameraService
    }

    override fun onCreate() {
        super.onCreate()
        LogUtils.d("摄像头后台服务创建")
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        releaseCamera()
        cameraExecutor.shutdown()
        LogUtils.d("摄像头后台服务销毁")
        super.onDestroy()
    }

    /**
     * 仅在需要拍照前初始化 CameraX，用完立即释放，避免长期占用系统相机。
     */
    private fun initCamera(onComplete: (Boolean) -> Unit) {
        synchronized(cameraLock) {
            if (isCameraReady.get() && cameraProvider != null && imageCapture != null) {
                onComplete(true)
                return
            }
            if (isCameraInitializing.get()) {
                handler.postDelayed({ initCamera(onComplete) }, 200)
                return
            }
            isCameraInitialized.set(true)
            isCameraInitializing.set(true)
        }

        try {
            LogUtils.d("开始初始化相机")
            val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
            cameraProviderFuture.addListener({
                try {
                    val provider = cameraProviderFuture.get()
                    val cameraSelector = selectAnyAvailableCamera(provider)
                    if (cameraSelector == null) {
                        synchronized(cameraLock) {
                            cameraProvider = null
                            imageCapture = null
                            isCameraInitialized.set(false)
                            isCameraInitializing.set(false)
                            isCameraReady.set(false)
                        }
                        LogUtils.e("相机初始化失败: 未找到可用摄像头")
                        onComplete(false)
                        return@addListener
                    }

                    synchronized(cameraLock) {
                        provider.unbindAll()
                        imageCapture = ImageCapture.Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                            .setTargetRotation(Surface.ROTATION_0)
                            .setTargetResolution(Size(832, 448))
                            .build()
                        provider.bindToLifecycle(
                            this,
                            cameraSelector,
                            imageCapture
                        )
                        cameraProvider = provider
                        isCameraInitializing.set(false)
                        isCameraReady.set(true)
                    }
                    LogUtils.d("相机初始化成功并绑定到生命周期")
                    onComplete(true)
                } catch (e: Exception) {
                    synchronized(cameraLock) {
                        cameraProvider = null
                        imageCapture = null
                        isCameraInitialized.set(false)
                        isCameraInitializing.set(false)
                        isCameraReady.set(false)
                    }
                    LogUtils.e("相机初始化失败: ${e.message}")
                    onComplete(false)
                }
            }, ContextCompat.getMainExecutor(this))
        } catch (e: Exception) {
            synchronized(cameraLock) {
                isCameraInitialized.set(false)
                isCameraInitializing.set(false)
                isCameraReady.set(false)
            }
            LogUtils.e("获取CameraProvider失败: ${e.message}")
            onComplete(false)
        }
    }

    private fun releaseCamera() {
        synchronized(cameraLock) {
            try {
                handler.removeCallbacks(autoReleaseRunnable)
                cameraProvider?.unbindAll()
                cameraProvider = null
                imageCapture = null
                isCameraInitialized.set(false)
                isCameraInitializing.set(false)
                isCameraReady.set(false)
                LogUtils.d("释放相机资源")
            } catch (e: Exception) {
                LogUtils.e("释放相机资源失败: ${e.message}")
            }
        }
    }

    fun prewarm(holdMs: Long = 8_000L, callback: ((Boolean) -> Unit)? = null) {
        handler.removeCallbacks(autoReleaseRunnable)
        initCamera { success ->
            if (success) {
                LogUtils.d("相机预热完成，预计保持 ${holdMs}ms")
                handler.removeCallbacks(autoReleaseRunnable)
                handler.postDelayed(autoReleaseRunnable, holdMs)
            }
            callback?.invoke(success)
        }
    }

    fun takePhoto(callback: ImageCapture.OnImageCapturedCallback) {
        initCamera { success ->
            if (!success) {
                callback.onError(
                    ImageCaptureException(
                        ImageCapture.ERROR_CAMERA_CLOSED,
                        "相机初始化失败",
                        Exception("相机初始化失败")
                    )
                )
                return@initCamera
            }

            val captureUseCase = synchronized(cameraLock) {
                if (!isCameraReady.get() || imageCapture == null) {
                    null
                } else {
                    LogUtils.d("开始拍照...")
                    isCameraReady.set(false)
                    imageCapture
                }
            }

            if (captureUseCase == null) {
                callback.onError(
                    ImageCaptureException(
                        ImageCapture.ERROR_CAMERA_CLOSED,
                        "相机未就绪",
                        Exception("相机未就绪")
                    )
                )
                return@initCamera
            }

            try {
                handler.removeCallbacks(autoReleaseRunnable)
                captureUseCase.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        try {
                            callback.onCaptureSuccess(image)
                        } finally {
                            releaseCamera()
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        try {
                            callback.onError(exception)
                        } finally {
                            releaseCamera()
                        }
                    }
                })
            } catch (e: Exception) {
                releaseCamera()
                LogUtils.e("拍照异常: ${e.message}")
                callback.onError(ImageCaptureException(ImageCapture.ERROR_UNKNOWN, "拍照异常", e))
            }
        }
    }

    private fun selectAnyAvailableCamera(cameraProvider: ProcessCameraProvider): CameraSelector? {
        return try {
            if (cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                LogUtils.d("找到并选择后置摄像头")
                CameraSelector.DEFAULT_BACK_CAMERA
            } else if (cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                LogUtils.d("找到并选择前置摄像头")
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                val availableCameraInfos = cameraProvider.availableCameraInfos
                if (availableCameraInfos.isNotEmpty()) {
                    LogUtils.d("使用第一个可用摄像头")
                    availableCameraInfos.first().cameraSelector
                } else {
                    LogUtils.e("未找到任何可用摄像头")
                    null
                }
            }
        } catch (e: Exception) {
            LogUtils.e("选择摄像头时发生异常: ${e.message}")
            null
        }
    }
}
