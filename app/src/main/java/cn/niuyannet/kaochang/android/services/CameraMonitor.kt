package cn.niuyannet.kaochang.android.services

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import cn.niuyannet.kaochang.android.detecition.SauceDetectionProcessor
import cn.niuyannet.kaochang.android.utils.LogUtils
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 后台摄像头服务拍照
 * 如果窗口运行期间需要拍照 则在@onResume方法内调用startService(context: Context, restart: Boolean = false)启动服务
 * 在窗口销毁finish()或跳转窗口startActivity(...)之前调用close(callback: (() -> Unit)? = null)方法
 * 例:  CameraMonitor.instance.close { finish() }
 */
class CameraMonitor {

    companion object {
        private const val TAG = "CameraMonitor"
        val instance by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { CameraMonitor() }
    }

    private val handle by lazy { Handler(Looper.getMainLooper()) }
    //
    private var weakReference: WeakReference<Context>? = null
    private var cameraService: AtomicReference<CameraService?> = AtomicReference(null)

    // 拍照锁
    private val takeStatus = AtomicBoolean(true)

    // 检测
    private var detectionProcessor: SauceDetectionProcessor? = null
    // 用于存储回调的 Map
    private val pendingCallbacks = mutableMapOf<String, (SauceDetectionProcessor.SausageInfo?) -> Unit>()
    private var callbackCounter = 0

    // 拍照队列
    private val deque = ConcurrentLinkedDeque<QueueData>()

    /**
     * 重新启动 service
     */
    fun restartService() {
        weakReference?.get()?.let { startService(it, true) }
    }

    /**
     * 启动 service
     */
    fun startService(context: Context, restart: Boolean = false) {
        close()
        // 不是是重新启动的
        if (!restart) {
            weakReference = WeakReference(context)
        }
        if (cameraService.get() == null) {
            // 初始化
            val serviceIntent = Intent(context, CameraService::class.java)
            // 绑定服务
            context.bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
        // 初始化
        if (detectionProcessor == null) {
            detectionProcessor = SauceDetectionProcessor(context.applicationContext)
        }

        // 启动定时任务
        handle.removeCallbacks(runnable)
        handle.postDelayed(runnable, 1000)
    }

    // 服务
    private val serviceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(componentName: ComponentName, iBinder: IBinder) {
            val binder: CameraService.ClientBinder = iBinder as CameraService.ClientBinder
            // service
            cameraService.set(binder.service)
        }
        override fun onServiceDisconnected(componentName: ComponentName) {
            this@CameraMonitor.cameraService.set(null)
        }
    }

    /**
     * 定时任务
     */
    private val runnable = object : Runnable {
        override fun run() {
            if (takeStatus.get()) {
                deque.poll()?.let { takePhoto(it.roiFlag, it.delay, it.retry, it.callback) }
            }
            handle.postDelayed(this, 500)
        }
    }

    /**
     * 清空拍照队列
     */
    fun clearQueue() = deque.clear()

    /**
     * 添加拍照任务
     * @param roiFlag       0: 运输台; 1: 售卖台
     * @param delay         重试延迟
     * @param retry         重试次数
     * @param priority      优先
     * @param callback      检测结果回调
     */
    fun takePhotoQueue(roiFlag: Int,
                       delay: Long,
                       retry: Int,
                       priority: Boolean,
                       callback: (SauceDetectionProcessor.SausageInfo?) -> Unit
    ) {
        LogUtils.d("添加拍照任务到队列: ${deque.size}")
        if (deque.size < 10) {
            if (priority) {
                deque.offerFirst(QueueData(roiFlag, delay, retry, callback))
            } else {
                deque.offer(QueueData(roiFlag, delay, retry, callback))
            }
        } else if (priority) {
            deque.pollLast()
            deque.offerFirst(QueueData(roiFlag, delay, retry, callback))
        } else {
            LogUtils.d("拍照任务队列已满!")
            callback(null)
        }
    }

    /**
     * 拍照 失败时重试
     * @param roiFlag 0: 运输台; 1: 售卖台
     * @param delay     重试延迟
     * @param retry     重试次数
     * @param callback 检测结果回调
     */
    private fun takePhoto(roiFlag: Int,
                          delay: Long,
                          retry: Int,
                          callback: (SauceDetectionProcessor.SausageInfo?) -> Unit
    ) {
        //
        val callbackId = "callback_${callbackCounter++}"
        pendingCallbacks[callbackId] = callback
        // 是否可以拍照
        if (takeStatus.get()) {
            takeStatus.set(false)
            // 设置属性
            detectionProcessor?.roiFlag = roiFlag
            // 开始拍照
            takePhoto(delay, retry, 4000) {
                // 回调
                invokeCallback(callbackId, it)
                takeStatus.set(true)
            }
        } else {
            LogUtils.d("请等待上一个拍照任务结束...")
            // 等待上一个拍照任务结束
            invokeCallback(callbackId, null)
        }
    }

    /**
     * 拍照
     * @param delay     重试延迟
     * @param retry     重试次数
     */
    @Deprecated("暂时弃用")
    private fun takePhoto(delay: Long, retry: Int, callback: (SauceDetectionProcessor.SausageInfo?) -> Unit) {
        try {
            cameraService.get()!!.takePhoto(object : ImageCapture.OnImageCapturedCallback(){
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        LogUtils.d("拍照成功 开始解析")
                        //
                        val img = imageProxyToMat(image)
                        val result = detectSausageSync(img)
                        callback(result)
                    } catch (e: Exception) {
                        LogUtils.d(TAG,"处理图像时出错: " + e.message)
                        callback(null)
                    } finally {
                        image.close()
                    }
                }
                override fun onError(e: ImageCaptureException) {
                    LogUtils.e(TAG,"拍照失败-$retry: " + e.message)
                    if (retry > 0) {
                        // 延迟递归
                        handle.postDelayed({ takePhoto(delay, retry - 1, callback) }, delay)
                    } else callback(null)
                }
            })
        } catch (e: Exception) {
            LogUtils.e(TAG,"服务未启动-$retry: " + e.message)
            if (retry > 0) {
                // 延迟递归
                handle.postDelayed({ takePhoto(delay, retry - 1, callback) }, delay)
            } else callback(null)
        }
    }


    /**
     * 拍照（带超时检查）
     * @param delay     重试延迟
     * @param retry     重试次数
     * @param timeoutMs 超时时间（毫秒）
     */
    private fun takePhoto(
        delay: Long,
        retry: Int,
        timeoutMs: Long,
        callback: (SauceDetectionProcessor.SausageInfo?) -> Unit
    ) {
        //
        val cameraService = cameraService.get()
        if (cameraService == null) {
            LogUtils.e(TAG, "相机服务未启动")
            handleTimeoutAndRetry(delay, retry, timeoutMs, callback)
            return
        }

        // 超时和重试状态管理
        val completed = AtomicBoolean(false)
        // 超时任务
        val timeoutRunnable = Runnable {
            if (!completed.get()) {
                completed.set(true)
                LogUtils.e("拍照超时，耗时超过${timeoutMs}ms")
                handleTimeoutAndRetry(delay, retry, timeoutMs, callback)
            }
        }
        //
        try {
            // 启动超时计时器
            handle.postDelayed(timeoutRunnable, timeoutMs)
            cameraService.takePhoto(object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    // 检查是否已经完成（防止超时和成功同时触发）
                    if (completed.get()) {
                        LogUtils.w("拍照成功回调被忽略（已超时）")
                        image.close()
                        return
                    }
                    //
                    completed.set(true)
                    handle.removeCallbacks(timeoutRunnable)
                    try {
                        LogUtils.d("拍照成功，开始解析")
                        val img = imageProxyToMat(image)
                        val result = detectSausageSync(img)
                        callback(result)
                    } catch (e: Exception) {
                        LogUtils.e("处理图像时出错: ${e.message}")
                        callback(null)
                    } finally {
                        image.close()
                    }
                }
                override fun onError(e: ImageCaptureException) {
                    // 检查是否已经完成
                    if (completed.get()) {
                        LogUtils.w("拍照错误回调被忽略（已超时）")
                        return
                    }
                    completed.set(true)
                    handle.removeCallbacks(timeoutRunnable)
                    //
                    LogUtils.e("拍照失败-$retry: ${e.message}")
                    handleTimeoutAndRetry(delay, retry, timeoutMs, callback)
                }
            })
        } catch (e: Exception) {
            // 移除超时任务
            handle.removeCallbacks(timeoutRunnable)
            LogUtils.e("服务调用异常-$retry: ${e.message}")
            handleTimeoutAndRetry(delay, retry, timeoutMs, callback)
        }
    }

    /**
     * 处理超时和重试的通用逻辑
     */
    private fun handleTimeoutAndRetry(
        delay: Long,
        retry: Int,
        timeoutMs: Long,
        callback: (SauceDetectionProcessor.SausageInfo?) -> Unit
    ) {
        if (retry > 0) {
            LogUtils.d("异常重试，剩余重试次数: ${retry - 1}")
            handle.postDelayed({
                takePhoto(delay, retry - 1, timeoutMs, callback)
            }, delay)
        } else {
            callback(null)
        }
    }

     /**
     * 解绑服务
     */
    fun close(callback: (() -> Unit)? = null) {
        handle.removeCallbacks(runnable)
        //
        if (cameraService.get() != null) {
            weakReference?.get()?.let {
                it.unbindService(serviceConnection)
                // null
                cameraService.set(null)
                callback?.invoke()
                return
            }
            callback?.invoke()
        } else callback?.invoke()
    }

    /// ========================================================================================
    /**
     * 回调
     */
    private fun invokeCallback(callbackId: String, result: SauceDetectionProcessor.SausageInfo?) {
        val callback = pendingCallbacks.remove(callbackId)
        if (callback != null) {
            try {
                callback(result)
                LogUtils.d("回调已调用，callbackId: $callbackId")
                Log.d(TAG, "回调已调用，callbackId: $callbackId")
            } catch (e: Exception) {
                LogUtils.d("调用回调时出错")
                Log.e(TAG, "调用回调时出错", e)
            }
        } else {
            LogUtils.d("未找到回调，callbackId: $callbackId")
            Log.w(TAG, "未找到回调，callbackId: $callbackId")
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
            LogUtils.d("图像转换失败: " + e.message)
            null
        }
    }

    @SuppressLint("DefaultLocale")
    private fun detectSausageSync(img: Mat?): SauceDetectionProcessor.SausageInfo? {
        return try {
            if (img == null || detectionProcessor == null) {
                LogUtils.d("执行烤肠检测-图像或处理器为空")
                return null
            }
            val platformType = if (detectionProcessor!!.roiFlag == 0) "运输台" else "售卖台"
            LogUtils.d("开始检测${platformType}上的烤肠，图像尺寸: ${img.width()}x${img.height()}, 通道数: ${img.channels()}")
            val result = detectionProcessor!!.processImage(img)
            // 售卖台检测逻辑
            if (detectionProcessor!!.roiFlag == 1) {
                // 售卖台：processImage 应该总是返回至少一个结果
                if (result.sausages.isNotEmpty()) {
                    val sausage = result.sausages[0]
                    val resultText = String.format(
                        "售卖台检测结果:\n结果: (%d) \n像素坐标: (%d, %d) \n世界坐标: (%.2f, %.2f, %.2f)",
                        sausage.code, sausage.pixelX, sausage.pixelY, sausage.worldX, sausage.worldY, sausage.worldZ
                    )
                    LogUtils.d(resultText)
                    return sausage
                } else {
                    LogUtils.d("售卖台检测结果为空")
                    return null
                }
            }

            // 运输台检测逻辑
            if (result.sausages.isEmpty()) {
                LogUtils.d("${platformType}未检测到烤肠")
                return null
            } else {
                val sausage = result.sausages[0]
                val resultText = String.format(
                    "检测到烤肠:\n像素坐标: (%d, %d)\n世界坐标: (%.2f, %.2f, %.2f)",
                    sausage.pixelX, sausage.pixelY, sausage.worldX, sausage.worldY, sausage.worldZ
                )
                LogUtils.d(resultText)
                return sausage
            }
        } catch (e: Exception) {
            LogUtils.d("检测烤肠时出错: " + e.message)
            null
        } finally {
            img?.release()
        }
    }
}