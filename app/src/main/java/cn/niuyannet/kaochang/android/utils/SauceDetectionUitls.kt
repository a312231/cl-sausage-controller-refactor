package cn.niuyannet.kaochang.android.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Size
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCapture.OnImageCapturedCallback
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import cn.niuyannet.kaochang.android.MyApp.Companion.instance
import cn.niuyannet.kaochang.android.detecition.SauceDetectionProcessor
import cn.niuyannet.kaochang.android.detecition.SauceDetector
import cn.niuyannet.kaochang.android.services.CameraMonitor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

object SauceDetectionUitls {
    private fun logPhoto(tag: String, message: String) = LogUtils.d(tag, "[售卖口拍照] $message")
    private fun logPhotoError(tag: String, message: String) = LogUtils.e(tag, "[售卖口拍照] $message")
    private fun logDetect(message: String) = LogUtils.d("[售卖口识别] $message")
    private fun logDetectWarn(message: String) = LogUtils.w("[售卖口识别] $message")
    private fun logDetectError(message: String) = LogUtils.e("[售卖口识别] $message")

    // 烤肠检测处理器
    var processor: SauceDetectionProcessor? = null
    // 历史 CameraX 路径保留，当前主链路已切后台服务拍照
    var cameraProvider: ProcessCameraProvider? = null
    var imageCapture: ImageCapture? = null
    var camera: Camera? = null
    val executor: Executor = Executors.newSingleThreadExecutor()
    var lifecycleOwner: LifecycleOwner? = null

    private val captureMutex = Mutex()
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<SauceDetectionProcessor.SausageInfo?>>()

    /**
     * 协程风格拍照检测。
     * roiFlag:
     * 0 -> 运输台
     * 1 -> 售卖口
     */
    suspend fun captureAndDetect(
        roiFlag: Int,
        retry: Int = 3,
        timeoutMs: Long = 20000,
        retryDelayMs: Long = 3000L,
        captureTimeoutMs: Long = 5000L,
        tag: String = "KaoChangAlgorithm"
    ): SauceDetectionProcessor.SausageInfo? = withContext(Dispatchers.IO) {
        logPhoto(tag, "开始拍照检测，roiFlag: $roiFlag, retry: $retry")
        val requestId = "${tag}_${System.currentTimeMillis()}_${(0..1000).random()}"
        return@withContext try {
            withTimeoutOrNull(timeoutMs) {
                val deferred = CompletableDeferred<SauceDetectionProcessor.SausageInfo?>()
                pendingRequests[requestId] = deferred
                try {
                    captureMutex.withLock {
                        logPhoto(tag, "获取拍照锁，开始拍照")
                        suspendCancellableCoroutine { continuation ->
                            CameraMonitor.instance.takePhotoQueue(
                                roiFlag,
                                retryDelayMs,
                                captureTimeoutMs,
                                retry,
                                tag != "lunXun"
                            ) { sausageInfo ->
                                logPhoto(tag, "拍照检测回调完成，结果: ${sausageInfo != null}")
                                pendingRequests.remove(requestId)?.let { deferredRef ->
                                    if (deferredRef.isActive) {
                                        deferredRef.complete(sausageInfo)
                                    }
                                }
                                if (continuation.isActive) {
                                    continuation.resume(Unit)
                                }
                            }
                            continuation.invokeOnCancellation {
                                logPhoto(tag, "拍照被取消，清理资源")
                                pendingRequests.remove(requestId)
                            }
                        }
                    }
                    val result = deferred.await()
                    logPhoto(tag, "收到拍照结果: ${result != null}")
                    result
                } finally {
                    pendingRequests.remove(requestId)
                }
            }
        } catch (e: TimeoutCancellationException) {
            logPhotoError(tag, "拍照超时，耗时超过${timeoutMs}ms")
            null
        } catch (e: Exception) {
            logPhotoError(tag, "拍照异常: ${e.message}")
            null
        }
    }

    /**
     * 旧版 CameraX 拍照路径，保留兼容。
     */
    @Deprecated("使用后台服务版本", ReplaceWith("captureAndDetect(roiFlag)"))
    private fun captureAndDetectOld(roiFlag: Int): SauceDetectionProcessor.SausageInfo? {
        processor?.roiFlag = roiFlag

        if (cameraProvider == null || imageCapture == null) {
            logPhoto("KaoChangAlgorithm", "相机未初始化，等待初始化完成")
            val initLatch = CountDownLatch(1)
            GlobalScope.launch(Dispatchers.IO) {
                var waited = 0
                while (waited < 10000 && (cameraProvider == null || imageCapture == null)) {
                    kotlinx.coroutines.delay(100)
                    waited += 100
                }
                initLatch.countDown()
            }
            try {
                initLatch.await(10, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                logPhoto("KaoChangAlgorithm", "等待相机初始化被中断")
            }
            if (cameraProvider == null || imageCapture == null) {
                logPhoto("KaoChangAlgorithm", "相机初始化超时，返回空结果")
                return null
            }
            logPhoto("KaoChangAlgorithm", "相机初始化完成，继续拍照流程")
        }

        if (imageCapture == null) {
            logPhoto("KaoChangAlgorithm", "相机未初始化")
            return null
        }
        logPhoto("KaoChangAlgorithm", "正在拍照")

        val latch = CountDownLatch(1)
        var result: SauceDetectionProcessor.SausageInfo? = null

        if (camera == null || imageCapture == null) {
            val rebound = rebindCamera()
            if (!rebound) {
                logPhotoError("KaoChangAlgorithm", "Camera 或 ImageCapture 未绑定，无法拍照")
                result = null
                latch.countDown()
            }
        }

        if (camera != null && imageCapture != null) {
            var didRetry = false
            imageCapture?.takePicture(executor, object : OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    try {
                        val img = imageProxyToMat(imageProxy)
                        result = if (img == null) {
                            logPhotoError("KaoChangAlgorithm", "图像处理失败")
                            null
                        } else {
                            detectSausageSync(img)
                        }
                    } catch (e: Exception) {
                        logPhotoError("KaoChangAlgorithm", "处理图像时出错: ${e.message}")
                        result = null
                    } finally {
                        imageProxy.close()
                        latch.countDown()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    logPhotoError("KaoChangAlgorithm", "拍照失败，尝试重新拍照: ${exception.message}")
                    if (!didRetry) {
                        didRetry = true
                        val rebound = rebindCamera()
                        if (rebound && imageCapture != null) {
                            imageCapture?.takePicture(executor, this)
                            return
                        }
                    }
                    result = null
                    latch.countDown()
                }
            })
        }

        return try {
            if (latch.await(10, TimeUnit.SECONDS)) {
                result
            } else {
                logPhotoError("KaoChangAlgorithm", "拍照检测超时")
                null
            }
        } catch (_: InterruptedException) {
            logPhotoError("KaoChangAlgorithm", "拍照检测被中断")
            null
        }
    }

    @Deprecated("使用后台服务版本，不再需要手动初始化相机")
    private fun rebindCamera(): Boolean {
        return try {
            val provider = cameraProvider ?: return false
            val owner = lifecycleOwner ?: return false
            provider.unbindAll()
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetRotation(Surface.ROTATION_0)
                .setTargetResolution(Size(832, 448))
                .build()
            val selector = CameraSelector.DEFAULT_BACK_CAMERA
            camera = provider.bindToLifecycle(owner, selector, imageCapture!!)
            true
        } catch (e: Exception) {
            logPhotoError("KaoChangAlgorithm", "相机重新绑定失败: ${e.message}")
            false
        }
    }

    private fun imageProxyToMat(imageProxy: ImageProxy): Mat? {
        return try {
            val buffer = imageProxy.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer[bytes]

            var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            val rotation = imageProxy.imageInfo.rotationDegrees
            if (rotation != 0) {
                val matrix = Matrix()
                matrix.postRotate(rotation.toFloat())
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            }

            var mat = Mat()
            Utils.bitmapToMat(bitmap, mat)

            if (mat.channels() == 4) {
                val bgrMat = Mat()
                Imgproc.cvtColor(mat, bgrMat, Imgproc.COLOR_RGBA2BGR)
                mat.release()
                mat = bgrMat
            } else if (mat.channels() == 1) {
                val bgrMat = Mat()
                Imgproc.cvtColor(mat, bgrMat, Imgproc.COLOR_GRAY2BGR)
                mat.release()
                mat = bgrMat
            }
            mat
        } catch (e: Exception) {
            logPhotoError("KaoChangAlgorithm", "图像转换失败: ${e.message}")
            null
        }
    }

    /**
     * 同步识别逻辑。
     * 对售卖口:
     * - code=1 未检测到烤肠
     * - code=2 检测到烤肠
     */
    private fun detectSausageSync(img: Mat?): SauceDetectionProcessor.SausageInfo? {
        try {
            if (img == null) {
                logDetect("执行烤肠检测失败：图像为空")
                return null
            }

            val currentProcessor = processor ?: return null
            val platformType = if (currentProcessor.roiFlag == 0) "运输台" else "售卖台"
            logDetect("开始检测${platformType}上的烤肠，图像尺寸: ${img.width()}x${img.height()}, 通道数: ${img.channels()}")

            val result = currentProcessor.processImage(img)
            if (currentProcessor.roiFlag == 1) {
                if (result.sausages.isNotEmpty()) {
                    val sausage = result.sausages[0]
                    val resultText = String.format(
                        "售卖台检测结果:\n结果: (%d) \n像素坐标: (%d, %d) \n世界坐标: (%.2f, %.2f, %.2f)",
                        sausage.code,
                        sausage.pixelX,
                        sausage.pixelY,
                        sausage.worldX,
                        sausage.worldY,
                        sausage.worldZ
                    )
                    logDetect(resultText)
                    return sausage
                }
                logDetectWarn("售卖台检测结果为空")
                return null
            }

            if (result.sausages.isEmpty()) {
                logDetect("${platformType}未检测到烤肠")
                return null
            }

            val sausage = result.sausages[0]
            val resultText = String.format(
                "检测到烤肠:\n像素坐标: (%d, %d)\n世界坐标: (%.2f, %.2f, %.2f)",
                sausage.pixelX,
                sausage.pixelY,
                sausage.worldX,
                sausage.worldY,
                sausage.worldZ
            )
            logDetect(resultText)
            return sausage
        } catch (e: Exception) {
            logDetectError("检测烤肠时出错: ${e.message}")
            return null
        } finally {
            img?.release()
            if (SauceDetector.SAVE_DEBUG_IMAGES) {
                val cacheDir = instance().cacheDir.absolutePath
                logDetect("调试图像已保存到: $cacheDir")
            }
        }
    }

    private fun detectSausage(img: Mat?, callBack: ((saInfo: SauceDetectionProcessor.SausageInfo?) -> Unit)) {
        val result = detectSausageSync(img)
        callBack.invoke(result)
    }
}
