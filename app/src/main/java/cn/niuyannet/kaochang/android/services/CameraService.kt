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
    companion object {
        private const val CAMERA_OWNER = "camera_service"
    }

    private val binder = ClientBinder()
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null

    private val isCameraInitialized = AtomicBoolean(false)
    private val isCameraInitializing = AtomicBoolean(false)
    private val isCameraReady = AtomicBoolean(false)
    private val cameraLock = Any()
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    private val autoReleaseRunnable = Runnable {
        LogUtils.d("【相机服务】相机预热超时未使用，自动释放相机资源")
        releaseNow()
    }

    inner class ClientBinder : Binder() {
        val service: CameraService
            get() = this@CameraService
    }

    override fun onCreate() {
        super.onCreate()
        LogUtils.d("【相机服务】后台相机服务已创建")
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
        releaseNow()
        cameraExecutor.shutdown()
        LogUtils.d("【相机服务】后台相机服务已销毁")
        super.onDestroy()
    }

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
            if (!CameraAccessCoordinator.tryAcquire(CAMERA_OWNER)) {
                LogUtils.w(
                    "【相机服务】初始化被拒绝，当前相机占用者=${CameraAccessCoordinator.currentOwner()}",
                    null
                )
                onComplete(false)
                return
            }
            isCameraInitialized.set(true)
            isCameraInitializing.set(true)
        }

        try {
            LogUtils.d("【相机服务】开始初始化 CameraX")
            val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
            cameraProviderFuture.addListener({
                try {
                    val provider = cameraProviderFuture.get()
                    val cameraSelector = selectAnyAvailableCamera(provider)
                    if (cameraSelector == null) {
                        resetStateAndReleaseOwner()
                        LogUtils.e("【相机服务】初始化失败：未找到可用摄像头")
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
                        provider.bindToLifecycle(this, cameraSelector, imageCapture)
                        cameraProvider = provider
                        isCameraInitializing.set(false)
                        isCameraReady.set(true)
                    }
                    LogUtils.d("【相机服务】初始化成功并绑定到生命周期")
                    onComplete(true)
                } catch (e: Exception) {
                    resetStateAndReleaseOwner()
                    LogUtils.e("【相机服务】初始化失败：${e.message}")
                    onComplete(false)
                }
            }, ContextCompat.getMainExecutor(this))
        } catch (e: Exception) {
            resetStateAndReleaseOwner()
            LogUtils.e("【相机服务】获取 CameraProvider 失败：${e.message}")
            onComplete(false)
        }
    }

    private fun resetStateAndReleaseOwner() {
        synchronized(cameraLock) {
            cameraProvider = null
            imageCapture = null
            isCameraInitialized.set(false)
            isCameraInitializing.set(false)
            isCameraReady.set(false)
        }
        CameraAccessCoordinator.release(CAMERA_OWNER)
    }

    private fun releaseCamera() {
        synchronized(cameraLock) {
            handler.removeCallbacks(autoReleaseRunnable)
            val providerToUnbind = cameraProvider
            cameraProvider = null
            imageCapture = null
            isCameraInitialized.set(false)
            isCameraInitializing.set(false)
            isCameraReady.set(false)

            try {
                if (providerToUnbind != null) {
                    providerToUnbind.unbindAll()
                    LogUtils.d("【相机服务】相机资源已释放")
                }
            } catch (e: Exception) {
                LogUtils.e("【相机服务】释放相机资源失败：${e.message}")
            } finally {
                CameraAccessCoordinator.release(CAMERA_OWNER)
            }
        }
    }

    fun releaseNow(callback: Runnable? = null) {
        handler.removeCallbacks(autoReleaseRunnable)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            releaseCamera()
            callback?.run()
        } else {
            handler.post {
                releaseCamera()
                callback?.run()
            }
        }
    }

    fun prewarm(holdMs: Long = 8_000L, callback: ((Boolean) -> Unit)? = null) {
        handler.removeCallbacks(autoReleaseRunnable)
        initCamera { success ->
            if (success) {
                LogUtils.d("【相机服务】预热完成，预计保持 ${holdMs}ms")
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
                    LogUtils.d("【相机服务】开始拍照")
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
                            releaseNow()
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        try {
                            callback.onError(exception)
                        } finally {
                            releaseNow()
                        }
                    }
                })
            } catch (e: Exception) {
                releaseNow()
                LogUtils.e("【相机服务】拍照异常：${e.message}")
                callback.onError(ImageCaptureException(ImageCapture.ERROR_UNKNOWN, "拍照异常", e))
            }
        }
    }

    private fun selectAnyAvailableCamera(cameraProvider: ProcessCameraProvider): CameraSelector? {
        return try {
            when {
                cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> {
                    LogUtils.d("【相机服务】选择后置摄像头")
                    CameraSelector.DEFAULT_BACK_CAMERA
                }
                cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> {
                    LogUtils.d("【相机服务】选择前置摄像头")
                    CameraSelector.DEFAULT_FRONT_CAMERA
                }
                cameraProvider.availableCameraInfos.isNotEmpty() -> {
                    LogUtils.d("【相机服务】使用第一个可用摄像头")
                    cameraProvider.availableCameraInfos.first().cameraSelector
                }
                else -> {
                    LogUtils.e("【相机服务】未找到任何可用摄像头")
                    null
                }
            }
        } catch (e: Exception) {
            LogUtils.e("【相机服务】选择摄像头失败：${e.message}")
            null
        }
    }
}
