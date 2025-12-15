package cn.niuyannet.kaochang.android.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.*
import androidx.camera.core.ImageCapture.OnImageCapturedCallback
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import cn.niuyannet.kaochang.android.R
import cn.niuyannet.kaochang.android.detecition.SauceDetectionProcessor
import cn.niuyannet.kaochang.android.ui.MainActivity
import cn.niuyannet.kaochang.android.utils.LogUtils
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import cn.niuyannet.kaochang.android.MyApp.Companion.instance
import kotlinx.coroutines.time.delay

class PhotoCaptureService : LifecycleService() {
    
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var detectionProcessor: SauceDetectionProcessor? = null
    
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())


    companion object {
        private const val TAG = "PhotoCaptureService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "photo_capture_channel"
        private const val ACTION_INIT_SERVICE = "init_service"
        private const val ACTION_CAPTURE_AND_DETECT = "capture_and_detect"
        private const val ACTION_STOP_SERVICE = "stop_service"
        
        // 用于存储回调的 Map
        private val pendingCallbacks = mutableMapOf<String, (SauceDetectionProcessor.SausageInfo?) -> Unit>()
        private var callbackCounter = 0
        
        // 服务实例引用
        @Volatile
        private var serviceInstance: PhotoCaptureService? = null
        // 防止并发炸相机（比如按钮抖动 / 后台任务）
        @Volatile
        private var isCapturing = false
        
        /**
         * 启动并初始化服务（在应用启动时调用）
         */
        fun startService(context: Context) {
            val intent = Intent(context, PhotoCaptureService::class.java).apply {
                action = ACTION_INIT_SERVICE
            }
            ContextCompat.startForegroundService(context, intent)
        }
        
        /**
         * 停止服务
         */
        fun stopService(context: Context) {
            val intent = Intent(context, PhotoCaptureService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            context.stopService(intent)
        }

        // 提供公开方法获取实例
        fun getInstance(): PhotoCaptureService? {
            return serviceInstance
        }

        /**
         * 拍照并检测（异步）
         * @param context 上下文
         * @param roiFlag 0: 运输台; 1: 售卖台
         * @param callback 检测结果回调
         */
        fun captureAndDetect(
            context: Context,
            roiFlag: Int,
            callback: (SauceDetectionProcessor.SausageInfo?) -> Unit
        ) {
            val callbackId = "callback_${callbackCounter++}"
            pendingCallbacks[callbackId] = callback
            
            // 如果服务实例存在且已初始化，直接调用
            serviceInstance?.let { service ->
                if (service.isInitializedService) {
                    service.captureAndDetectInternal(roiFlag, callbackId)
                } else {
                    // 服务未初始化，等待初始化完成后再调用
                    LogUtils.d("等待服务初始化")
                    service.serviceScope.launch {
                        // 等待初始化完成，最多等待5秒
                        var waited = 0
                        while (!service.isInitializedService && waited < 5000) {
                            kotlinx.coroutines.delay(100)
                            waited += 100
                        }
                        if (service.isInitializedService) {
                            service.captureAndDetectInternal(roiFlag, callbackId)
                        } else {
                            Log.e(TAG, "服务初始化超时")
                            LogUtils.d("服务初始化超时")
                            invokeCallback(callbackId, null)
                        }
                    }
                }
            } ?: run {
                // 服务未启动，先启动服务
                val intent = Intent(context, PhotoCaptureService::class.java).apply {
                    action = ACTION_CAPTURE_AND_DETECT
                    putExtra("roiFlag", roiFlag)
                    putExtra("callbackId", callbackId)
                }
                ContextCompat.startForegroundService(context, intent)
            }
        }
        
        internal fun invokeCallback(callbackId: String, result: SauceDetectionProcessor.SausageInfo?) {
            val callback = pendingCallbacks.remove(callbackId)
            if (callback != null) {
                try {
                    callback(result)
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
        
        internal fun setServiceInstance(service: PhotoCaptureService?) {
            serviceInstance = service
        }
    }

    // 相机初始化
    @Volatile
    var isInitialized = false
        private set
    // 服务初始化
    var isInitializedService = false
    private val initializationLock = Any()
    // 专门为“拍照事务”准备的锁对象
    private val captureLock = Any()
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Companion.setServiceInstance(this)
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        
        when (intent?.action) {
            ACTION_INIT_SERVICE -> {
                // 初始化服务并保持运行
                startForegroundService()
                isInitializedService=true
//                serviceScope.launch {
//                    initializeCameraOnce()
//                }
            }
            ACTION_CAPTURE_AND_DETECT -> {
                val roiFlag = intent.getIntExtra("roiFlag", 0)
                val callbackId = intent.getStringExtra("callbackId") ?: ""
                
                // 如果服务未初始化，先初始化
                if (!isInitializedService) {
                    startForegroundService()
                    isInitializedService=true
                    serviceScope.launch {
                        //initializeCameraOnce()
                        captureAndDetectInternal(roiFlag, callbackId)
                    }
                } else {
                    // 直接拍照
                    captureAndDetectInternal(roiFlag, callbackId)
                }
            }
            ACTION_STOP_SERVICE -> {
                stopService()
            }
        }
        
        return START_STICKY
    }
    
    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "后台拍照服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "后台自动拍照服务通知"
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun startForegroundService() {
        val notification = createNotification("拍照服务运行中")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, 
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * “初始化相机”改成按需函数
     * **/
    private suspend fun ensureCameraReady() {
        synchronized(initializationLock) {
            if (isInitialized) return

            LogUtils.d("captureAndDetect：初始化相机")

            val cameraProviderFuture =
                ProcessCameraProvider.getInstance(this@PhotoCaptureService)
            cameraProvider = cameraProviderFuture.get()

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetRotation(Surface.ROTATION_0)
                .setTargetResolution(Size(832, 448))
                .build()

            cameraProvider!!.unbindAll()
            cameraProvider!!.bindToLifecycle(
                this@PhotoCaptureService,
                CameraSelector.DEFAULT_BACK_CAMERA,
                imageCapture
            )

            if (detectionProcessor == null) {
                detectionProcessor = SauceDetectionProcessor(this@PhotoCaptureService)
            }

            isInitialized = true
            LogUtils.d("相机 bind 完成")
        }
    }

    /**
     * 新增“释放相机”的统一出口
     * **/
    private fun releaseCamera() {
        synchronized(initializationLock) {
            LogUtils.d("captureAndDetect：释放相机")
            cameraProvider?.unbindAll()
            cameraProvider = null
            imageCapture = null
            isInitialized = false
        }
    }

    /**
     * 初始化相机（只初始化一次）
     */
    private suspend fun initializeCameraOnce() {
        synchronized(initializationLock) {
            if (isInitialized) {
                LogUtils.d("相机已初始化，跳过")
                return
            }
            
            try {
                LogUtils.d("开始初始化相机...")
                val cameraProviderFuture = ProcessCameraProvider.getInstance(this@PhotoCaptureService)
                cameraProvider = cameraProviderFuture.get()
                
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetRotation(Surface.ROTATION_0)
                    .setTargetResolution(Size(832, 448))
                    .build()
                
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    this@PhotoCaptureService,
                    cameraSelector,
                    imageCapture
                )
                
                // 初始化检测处理器
                if (detectionProcessor == null) {
                    detectionProcessor = SauceDetectionProcessor(this@PhotoCaptureService)
                }
                
                isInitialized = true
                LogUtils.d("相机初始化成功")
                updateNotification("拍照服务就绪")
            } catch (e: Exception) {
                Log.e(TAG, "初始化相机失败", e)
                updateNotification("相机初始化失败: ${e.message}")
            }
        }
    }
    
    private fun createNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("后台拍照服务")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
    
    private fun updateNotification(text: String) {
        val notification = createNotification(text)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    /**
     * 停止服务（内部方法）
     */
    private fun stopService() {
        // 如果正在拍照，直接强制结束
        synchronized(captureLock) {
            isCapturing = false
        }

        releaseCamera()          // ✅ 统一释放相机
        detectionProcessor = null
        isInitializedService = false

//        synchronized(initializationLock) {
//            isInitialized = false
//            cameraProvider?.unbindAll()
//            cameraProvider = null
//            imageCapture = null
//            detectionProcessor = null
//        }
        stopForeground(true)
        stopSelf()
    }
    // 重启服务
    fun stopServicePublic(context: Context) {
        serviceScope.launch {
            LogUtils.d("关闭服务")
            stopService()
            delay(500)

//            while(!isInitialized) {
//                LogUtils.d("开启服务")
//                startService(context)
//                delay(500)
//            }
        }
    }
    fun restartCamera() {
        serviceScope.launch {
            while (isCapturing) delay(500)
            releaseCamera()
        }
        serviceScope.launch {
            synchronized(initializationLock) {
                isInitialized = false
                cameraProvider?.unbindAll()
                imageCapture = null

                // 重新初始化 ImageCapture 并绑定
                val cameraProviderFuture = ProcessCameraProvider.getInstance(this@PhotoCaptureService)
                cameraProvider = cameraProviderFuture.get()

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetResolution(Size(832, 448))
                    .build()

                cameraProvider?.bindToLifecycle(
                    this@PhotoCaptureService,
                    cameraSelector,
                    imageCapture
                )

                isInitialized = true
                updateNotification("相机已重启")
                LogUtils.d("相机已重启")
            }
        }
    }
    /**
     * 拍照并检测（内部方法，直接使用已初始化的相机）
     */
    fun captureAndDetectInternal(roiFlag: Int, callbackId: String) {
        synchronized(captureLock) {
            if (isCapturing) {
                invokeCallback(callbackId, null)
                return
            }
            isCapturing = true
        }

        serviceScope.launch {
            try {
                // ① 用之前再初始化相机
                ensureCameraReady()

                detectionProcessor?.roiFlag = roiFlag
                updateNotification("正在拍照检测...")
                LogUtils.d("正在拍照...")

                imageCapture!!.takePicture(
                    cameraExecutor,
                    object : ImageCapture.OnImageCapturedCallback() {

                        override fun onCaptureSuccess(imageProxy: ImageProxy) {
                            try {
                                val img = imageProxyToMat(imageProxy)
                                val result = detectSausageSync(img)
                                invokeCallback(callbackId, result)
                            } catch (e: Exception) {
                                LogUtils.d(TAG,"处理图像时出错: " + e.message)
                                invokeCallback(callbackId, null)
                            } finally {
                                imageProxy.close()
                                //releaseCamera()   // ✅ 用完立刻解绑
                                updateNotification("拍照服务就绪")
                                // 释放
                                synchronized(captureLock) {
                                    isCapturing = false
                                }
                            }
                        }

                        override fun onError(exception: ImageCaptureException) {
                            Log.e(TAG, "拍照失败", exception)
                            invokeCallback(callbackId, null)
                            //releaseCamera()       // ✅ 出错也解绑
                            updateNotification("拍照失败")
                            // 释放
                            synchronized(captureLock) {
                                isCapturing = false
                            }
                        }
                    }
                )
            } catch (e: Exception) {
                //Log.e(TAG, "captureAndDetectInternal 异常", e)
                LogUtils.e(TAG, "captureAndDetectInternal 异常", e)
                invokeCallback(callbackId, null)
                //releaseCamera()
                // 释放
                synchronized(captureLock) {
                    isCapturing = false
                }
            }
//            finally {
//                releaseCamera()       // ✅ 出错也解绑
//            }
        }
    }

    /**
     * 拍照并检测（旧的内部方法，暂时弃用，直接使用已初始化的相机）
     */
    @Deprecated("使用每次拍照再开启相机的版本", ReplaceWith("captureAndDetectInternal(roiFlag, callbackId)"))
    fun captureAndDetectInternalOld(roiFlag: Int, callbackId: String) {
        // 确保相机已初始化
        if (!isInitialized || imageCapture == null || detectionProcessor == null) {
            LogUtils.d("相机未初始化，无法拍照")
            Log.e(TAG, "相机未初始化，无法拍照")
            invokeCallback(callbackId, null)
            return
        }
        
        detectionProcessor?.roiFlag = roiFlag
        updateNotification("正在拍照检测...")
        LogUtils.d("正在拍照...")

        LogUtils.d("executorAlive=${cameraExecutor.isShutdown}, terminated=${cameraExecutor.isTerminated}")
        LogUtils.d("imageCapture hash=${imageCapture}")
        imageCapture!!.takePicture(
            cameraExecutor,
            object : OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    try {
                        LogUtils.d("成功进入回调 onCaptureSuccess")
                        val img = imageProxyToMat(imageProxy)
                        if (img == null) {
                            LogUtils.d("图像处理失败")
                            invokeCallback(callbackId, null)
                        } else {
                            LogUtils.d("正在检测...")
                            val result = detectSausageSync(img)
                            invokeCallback(callbackId, result)
                        }
                    } catch (e: Exception) {
                        LogUtils.d("处理图像时出错: " + e.message)
                        invokeCallback(callbackId, null)
                    } finally {
                        imageProxy.close()
                        LogUtils.d("拍照服务就绪")
                        updateNotification("拍照服务就绪")
                    }
                }
                
                override fun onError(exception: ImageCaptureException) {
                    LogUtils.e(TAG, "拍照失败", exception)
                    Log.e(TAG, "拍照失败", exception)
                    invokeCallback(callbackId, null)
                    updateNotification("拍照失败: ${exception.message}")
                }
            }
        )
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
    
    override fun onDestroy() {
        super.onDestroy()

        // 如果正在拍照，直接强制结束
        synchronized(captureLock) {
            isCapturing = false
        }
        releaseCamera()
        detectionProcessor = null
        isInitializedService = false
//        synchronized(initializationLock) {
//            isInitialized = false
//            cameraProvider?.unbindAll()
//            cameraProvider = null
//            imageCapture = null
//            detectionProcessor = null
//        }
        cameraExecutor.shutdown()
        serviceScope.cancel()
        Companion.setServiceInstance(null)
    }
}

