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
import cn.niuyannet.kaochang.android.MyApp.Companion.instance
import cn.niuyannet.kaochang.android.detecition.SauceDetectionProcessor
import cn.niuyannet.kaochang.android.detecition.SauceDetector
import cn.niuyannet.kaochang.android.services.PhotoCaptureService
import androidx.lifecycle.LifecycleOwner
import cn.niuyannet.kaochang.android.services.CameraMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.util.concurrent.Executor
import java.util.concurrent.Executors

object SauceDetectionUitls {
    // 烤肠检测处理器
     var processor: SauceDetectionProcessor? = null
    // 相机相关（保留用于向后兼容，但不再使用）
    var cameraProvider: ProcessCameraProvider? = null
    var imageCapture: ImageCapture? = null
    var camera: Camera? = null
    val executor: Executor = Executors.newSingleThreadExecutor()
    var lifecycleOwner: LifecycleOwner? = null
    
    /**
     * 拍照并检测返回结果，为空即没有结果
     * roiFlag 0: 运输台; 1: 售卖台
     * 对于售卖台：0拍照失败；1为未识别到，需要关门；2为识别到烤肠，需要丢弃
     * 
     * 使用后台服务进行拍照和检测
     */
    fun captureAndDetect(roiFlag: Int, retry: Int = 5, tag: String = "KaoChangAlgorithm"): SauceDetectionProcessor.SausageInfo? {
        LogUtils.d(tag,"使用后台服务进行拍照检测，roiFlag: $roiFlag")
        
        val latch = CountDownLatch(1)
        val lock = Any()
        var result: SauceDetectionProcessor.SausageInfo? = null
        var callbackInvoked = false

        CameraMonitor.instance.takePhoto(roiFlag, 3000L, retry) { sausageInfo ->
            synchronized(lock) {
                if (!callbackInvoked) {
                    callbackInvoked = true
                    result = sausageInfo
                    LogUtils.d(tag,"拍照检测回调完成，结果: ${if (sausageInfo != null) "成功" else "失败"}")
                    latch.countDown()
                }
            }
        }
//        PhotoCaptureService.captureAndDetect(instance(), roiFlag, retry) { sausageInfo ->
//            synchronized(lock) {
//                if (!callbackInvoked) {
//                    callbackInvoked = true
//                    result = sausageInfo
//                    LogUtils.d(tag,"拍照检测回调完成，结果: ${if (sausageInfo != null) "成功" else "失败"}")
//                    latch.countDown()
//                }
//            }
//        }
        
        // 等待检测完成，最多等待15秒
        try {
            val completed = latch.await(20, TimeUnit.SECONDS)
            //var completed = latch.await(20, TimeUnit.SECONDS)
            //completed = !retry
            if (completed) {
                LogUtils.d(tag,"拍照检测完成，返回结果")
                //PhotoCaptureService.getInstance()?.restartCamera()
                return result
            } else {
                LogUtils.d(tag,"拍照检测超时（20秒），返回null")
                //PhotoCaptureService.getInstance()?.restartCamera()
                // 确保回调被调用（防止服务端未调用回调）
                synchronized(lock) {
                    if (!callbackInvoked) {
                        callbackInvoked = true
                        latch.countDown()
                    }
                }
//                if (retry) {
//                    LogUtils.d(tag,"超时，尝试重启相机并重试一次")
//                    // 重启 CameraX
//                    //PhotoCaptureService.getInstance()?.restartCamera()
//                    PhotoCaptureService.getInstance()?.stopServicePublic(){success ->
//                        PhotoCaptureService.startService(instance())
//                    }
                    //PhotoCaptureService.stopService(instance())
                    //PhotoCaptureService.stopService(instance())
                    //LogUtils.d("开启服务")
                    //PhotoCaptureService.startService(instance())
                    // 重试一次，不再递归调用避免无限循环
//                    return captureAndDetect(roiFlag, retry = 0, tag = tag)
//                }
                return null
            }
        } catch (e: InterruptedException) {
            LogUtils.e(tag,"拍照检测被中断",e)
            //PhotoCaptureService.getInstance()?.restartCamera()
            // 确保回调被调用
            synchronized(lock) {
                if (!callbackInvoked) {
                    callbackInvoked = true
                    latch.countDown()
                }
            }
            return null
        }
    }
    
    /**
     * 旧的拍照检测方法（已废弃，保留用于向后兼容）
     * @deprecated 使用后台服务版本
     */
    @Deprecated("使用后台服务版本", ReplaceWith("captureAndDetect(roiFlag)"))
    private fun captureAndDetectOld(roiFlag: Int): SauceDetectionProcessor.SausageInfo? {
        processor!!.roiFlag = roiFlag

        // 检查相机是否未初始化完成，如果没有完成，等待初始化完成
        if (cameraProvider == null || imageCapture == null) {
            LogUtils.d("相机未初始化，等待初始化完成...")
            // 最多等待10秒
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
            } catch (e: InterruptedException) {
                LogUtils.d("等待相机初始化被中断")
            }
            if (cameraProvider == null || imageCapture == null) {
                LogUtils.d("相机初始化超时，返回空")
                return null
            }
            LogUtils.d("相机初始化完成，继续拍照流程")
        }

        if (imageCapture == null) {
            LogUtils.d("相机未初始化")
            return null
        }
        LogUtils.d("正在拍照...")
        
        // 创建一个 CountDownLatch，初始计数为 1，用于阻塞主线程直到拍照及检测完成
        val latch = CountDownLatch(1)
        var result: SauceDetectionProcessor.SausageInfo? = null
        
        // 拍照前先检查 camera 与 imageCapture 是否已绑定到生命周期
        if (camera == null || imageCapture == null) {
            val rebound = rebindCamera()
            if (!rebound) {
                LogUtils.d("Camera 或 ImageCapture 未绑定，无法拍照")
                result = null
                latch.countDown()
            }
        }
        if (camera != null && imageCapture != null) {
            var didRetry = false
            imageCapture?.takePicture(executor, object : OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    try {
                        // 将ImageProxy转换为Mat
                        val img = imageProxyToMat(imageProxy)
                        if (img == null) {
                            LogUtils.d("图像处理失败")
                            result = null
                        } else {
                            // 执行检测
                            result = detectSausageSync(img)
                        }
                    } catch (e: Exception) {
                        LogUtils.d("处理图像时出错: " + e.message)
                        result = null
                    } finally {
                        // 关闭ImageProxy
                        imageProxy.close()
                        latch.countDown()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    LogUtils.d("拍照失败，再次进行拍照: " + exception.message)
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
        
        // 等待拍照和检测完成，最多等待10秒
        try {
            if (latch.await(10, TimeUnit.SECONDS)) {
                return result
            } else {
                LogUtils.d("拍照检测超时")
                return null
            }
        } catch (e: InterruptedException) {
            LogUtils.d("拍照检测被中断")
            return null
        }
    }
    
    /**
     * 旧的相机初始化方法（已废弃，保留用于向后兼容）
     * @deprecated 使用后台服务，不再需要手动初始化相机
     */
    @Deprecated("使用后台服务，不再需要手动初始化相机")
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
            LogUtils.d("相机重新绑定失败: " + e.message)
            false
        }
    }
    /**
     * 将ImageProxy转换为Mat
     */
    private fun imageProxyToMat(imageProxy: ImageProxy): Mat? {
        try {
            val buffer = imageProxy.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer[bytes]

            var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            // 根据图像旋转角度旋转位图
            val rotation = imageProxy.imageInfo.rotationDegrees
            if (rotation != 0) {
                val matrix = Matrix()
                matrix.postRotate(rotation.toFloat())
                bitmap =
                    Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            }
            // 转换为Mat
            var mat = Mat()
            Utils.bitmapToMat(bitmap, mat)


            // 确保图像是BGR格式
            if (mat.channels() == 4) { // RGBA格式
                val bgrMat = Mat()
                Imgproc.cvtColor(mat, bgrMat, Imgproc.COLOR_RGBA2BGR)
                mat.release()
                mat = bgrMat
            } else if (mat.channels() == 1) { // 灰度图
                val bgrMat = Mat()
                Imgproc.cvtColor(mat, bgrMat, Imgproc.COLOR_GRAY2BGR)
                mat.release()
                mat = bgrMat
            }
            // 保存原始图像用于调试
            if (SauceDetector.SAVE_DEBUG_IMAGES) {
                SauceDetector.saveDebugImage(mat, "camera_captured_image")
            }
            return mat
        } catch (e: Exception) {
            LogUtils.d("图像转换失败: " + e.message)
            return null
        }
    }
    /**
     * 执行烤肠检测（同步版本）
     * 对于售卖台：0拍照失败；1为未识别到，需要关门；2为识别到烤肠，需要丢弃
     */
    private fun detectSausageSync(img: Mat?): SauceDetectionProcessor.SausageInfo? {
        try {
            if (img == null) {
                LogUtils.d("执行烤肠检测-图像为空")
                return null
            }
            // 显示调试信息
            val platformType = if (processor!!.roiFlag == 0) "运输台" else "售卖台"
            LogUtils.d("开始检测" + platformType + "上的烤肠，图像尺寸: " + img.width() + "x" + img.height() + ", 通道数: " + img.channels())
            // 使用SauceDetectionProcessor处理图像，传递roiFlag参数
            val result = processor!!.processImage(img)
            // 显示检测结果

            if (processor!!.roiFlag == 1) {
                val sausage = result.sausages[0]
                val resultText = String.format(
                    "售卖台检测结果:\n结果: (%d) \n像素坐标: (%d, %d) \n世界坐标: (%.2f, %.2f, %.2f)",
                    sausage.code, sausage.pixelX, sausage.pixelY, sausage.worldX, sausage.worldY, sausage.worldZ
                )
                LogUtils.d(resultText)
                return sausage
            }

            return if (result.sausages.isEmpty()) {
                LogUtils.d(platformType + "未检测到烤肠")
                null
            } else {
                // 显示第一个检测到的烤肠信息
                val sausage = result.sausages[0]
                val resultText = String.format(
                    "检测到烤肠:\n像素坐标: (%d, %d)\n世界坐标: (%.2f, %.2f, %.2f)",
                    sausage.pixelX, sausage.pixelY, sausage.worldX, sausage.worldY, sausage.worldZ
                )
                LogUtils.d(resultText)
                sausage
            }
        } catch (e: Exception) {
            LogUtils.d("检测烤肠时出错: " + e.message)
            return null
        } finally {
            // 释放资源
            img?.release()
            // 显示调试图像保存位置
            if (SauceDetector.SAVE_DEBUG_IMAGES) {
                val cacheDir = instance().cacheDir.absolutePath
                LogUtils.d("调试图像已保存到: $cacheDir")
            }
        }
    }
    
    /**
     * 执行烤肠检测（异步版本，保留向后兼容）
     */
    private fun detectSausage(img: Mat?, callBack:((saInfo: SauceDetectionProcessor.SausageInfo?)->Unit)) {
        val result = detectSausageSync(img)
        callBack.invoke(result)
    }

}