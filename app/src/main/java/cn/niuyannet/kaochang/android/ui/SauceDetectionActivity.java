package cn.niuyannet.kaochang.android.ui;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraInfoUnavailableException;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ExecutionError;
import com.google.common.util.concurrent.ListenableFuture;

import org.opencv.android.Utils;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import cn.niuyannet.kaochang.android.MyApp;
import cn.niuyannet.kaochang.android.R;
import cn.niuyannet.kaochang.android.detecition.ArucoHelper;
import cn.niuyannet.kaochang.android.detecition.SauceDetectionProcessor;
import cn.niuyannet.kaochang.android.detecition.SauceDetector;

/**
 * 烤肠检测示例活动
 */
public class SauceDetectionActivity extends AppCompatActivity {
    private static final String TAG = "SauceDetectionActivity";
    private static final int REQUEST_CODE_PERMISSIONS = 10;
    private static final String[] REQUIRED_PERMISSIONS = new String[]{
            Manifest.permission.CAMERA
    };

    private ImageView backButton;
    private ImageView imageView;
    private PreviewView previewView;
    private TextView resultTextView;
    private Button detectButton;
    private Button arUcoButton;
    private Button transportButton;
    private Button salesButton;

    // 识别区域标识
    private int roiFlag = 0; // 0: 运输台; 1: 售卖台
    
    // 烤肠检测处理器
    private SauceDetectionProcessor processor;
    
    // 相机相关
    private ProcessCameraProvider cameraProvider;
    private ImageCapture imageCapture;
    private Camera camera;
    private final Executor executor = Executors.newSingleThreadExecutor();
    private ArucoHelper arucoHelper;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sauce_detection);

        // 初始化视图
        backButton = findViewById(R.id.btn_back);
        imageView = findViewById(R.id.image_view);
        previewView = findViewById(R.id.preview_view);
        resultTextView = findViewById(R.id.result_text_view);
        detectButton = findViewById(R.id.detect_button);
        arUcoButton = findViewById(R.id.arUco_button);
        transportButton = findViewById(R.id.transport_button);
        salesButton = findViewById(R.id.sales_button);
        arucoHelper=new ArucoHelper(this);
        // 初始化烤肠检测处理器
        processor = new SauceDetectionProcessor(this);

        // 返回按钮
        backButton.setOnClickListener(v -> finish());

        // 设置检测按钮点击事件 - 拍照并检测
        detectButton.setOnClickListener(v -> captureAndDetect());
        
        // 设置运输台按钮点击事件
        transportButton.setOnClickListener(v -> {
            roiFlag = 0;
            processor.setRoiFlag(roiFlag);
            Toast.makeText(this, "已切换到运输台检测模式", Toast.LENGTH_SHORT).show();
            updateButtonState();
        });
        
        // 设置售卖台按钮点击事件
        salesButton.setOnClickListener(v -> {
            roiFlag = 1;
            processor.setRoiFlag(roiFlag);
            Toast.makeText(this, "已切换到售卖台检测模式", Toast.LENGTH_SHORT).show();
            updateButtonState();
        });
        arUcoButton.setOnClickListener(view -> {
            // 显示正在处理的提示
            Toast.makeText(SauceDetectionActivity.this, "正在检测 ArUco 标记...", Toast.LENGTH_SHORT).show();

            // 使用与检测烤肠相同的方式拍照
            if (imageCapture == null) {
                Toast.makeText(SauceDetectionActivity.this, "相机未初始化", Toast.LENGTH_SHORT).show();
                return;
            }

            // 拍照
            imageCapture.takePicture(executor, new ImageCapture.OnImageCapturedCallback() {
                @Override
                public void onCaptureSuccess(@NonNull ImageProxy imageProxy) {
                    // 将 ImageProxy 转换为 Bitmap
                    ByteBuffer buffer = imageProxy.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);

                    Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);

                    // 根据图像旋转角度旋转位图
                    int rotation = imageProxy.getImageInfo().getRotationDegrees();
                    if (rotation != 0) {
                        Matrix matrix = new Matrix();
                        matrix.postRotate(rotation);
                        bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                    }

                    // 执行 ArUco 标记检测
                    final Bitmap finalBitmap = bitmap;
                    runOnUiThread(() -> {
                        ArucoHelper.DetectionResult result;
                        try {
                            // 隐藏预览，显示图像视图
                            previewView.setVisibility(View.GONE);
                            imageView.setVisibility(View.VISIBLE);

                            // 检测 ArUco 标记
                            result = arucoHelper.detectArucoMarkers(finalBitmap, 1);

                            // 显示图像结果
                            if (result != null && result.resultImage != null) {
                                imageView.setImageBitmap(result.resultImage);
                            } else {
                                // 如果处理失败，显示原始图像
                                imageView.setImageBitmap(finalBitmap);
                                resultTextView.setText("⚠️ ArUco标记检测失败，请重试");
                                return;
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "ArUco检测失败: " + e.getMessage(), e);
                            // 显示原始图像
                            imageView.setImageBitmap(finalBitmap);
                            resultTextView.setText("⚠️ ArUco标记检测失败: " + e.getMessage());
                            return;
                        }

                        // 构建详细的检测结果文本
                        StringBuilder resultText = new StringBuilder();

                        if (result != null && result.markersCount > 0) {
                            resultText.append("✅ 检测到 ").append(result.markersCount).append(" 个 ArUco 标记\n");
                            resultText.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
                            resultText.append("📋 标记 ID: ");

                            for (int i = 0; i < result.markerIds.size(); i++) {
                                resultText.append(result.markerIds.get(i));
                                if (i < result.markerIds.size() - 1) {
                                    resultText.append(", ");
                                }
                            }

                            if (result.targetFound) {
                                resultText.append("\n\n🎯 目标标记 (ID=1) 已找到");
                                if (!result.targetCorners.isEmpty()) {
                                    resultText.append("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                                    resultText.append("\n📍 角点坐标:");
                                    for (int i = 0; i < result.targetCorners.size() && i < 4; i++) {
                                        List<Double> point = result.targetCorners.get(i);
                                        if (point.size() >= 2) {
                                            resultText.append("\n   ").append(i + 1).append(": (")
                                                    .append(String.format("%.1f", point.get(0))).append(", ")
                                                    .append(String.format("%.1f", point.get(1))).append(")");
                                        }
                                    }

                                    // 添加中心点计算
                                    if (result.targetCorners.size() >= 4) {
                                        double centerX = 0, centerY = 0;
                                        for (int i = 0; i < 4; i++) {
                                            List<Double> point = result.targetCorners.get(i);
                                            if (point.size() >= 2) {
                                                centerX += point.get(0);
                                                centerY += point.get(1);
                                            }
                                        }
                                        centerX /= 4;
                                        centerY /= 4;

                                        resultText.append("\n\n🔴 标记中心点: (")
                                                .append(String.format("%.1f", centerX)).append(", ")
                                                .append(String.format("%.1f", centerY)).append(")");
                                    }
                                }
                            } else {
                                resultText.append("\n\n❌ 未找到目标标记 (ID=1)");
                            }
                        } else {
                            resultText.append("❌ 未检测到任何 ArUco 标记\n");
                            resultText.append("请确保图像中包含清晰可见的 ArUco 标记");
                        }

                        // 显示结果文本
                        resultTextView.setText(resultText.toString());

                        // 记录日志
                        Log.d(TAG, "ArUco 检测结果: " + resultText.toString());

                        // 修改检测按钮文本为"返回相机"
                        detectButton.setText("返回相机");
                        // 修改检测按钮点击事件为返回相机预览
                        detectButton.setOnClickListener(v -> returnToCamera());
                    });

                    // 关闭 ImageProxy
                    imageProxy.close();
                }

                @Override
                public void onError(@NonNull ImageCaptureException exception) {
                    Log.e(TAG, "拍照失败: " + exception.getMessage());
                    runOnUiThread(() -> Toast.makeText(SauceDetectionActivity.this, "拍照失败: " + exception.getMessage(), Toast.LENGTH_SHORT).show());
                }
            });
        });
        // 初始化按钮状态
        updateButtonState();
        
        // 检查相机权限
        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
        }
    }
    
    /**
     * 检查是否已授予所有所需权限
     */
    private boolean allPermissionsGranted() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera();
            } else {
                Toast.makeText(this, "需要相机权限才能使用此功能", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    /**
     * 更新按钮状态
     */
    private void updateButtonState() {
        if (roiFlag == 0) {
            transportButton.setEnabled(false);
            transportButton.setAlpha(0.5f);
            salesButton.setEnabled(true);
            salesButton.setAlpha(1.0f);
            resultTextView.setText("当前模式：运输台检测");
        } else {
            transportButton.setEnabled(true);
            transportButton.setAlpha(1.0f);
            salesButton.setEnabled(false);
            salesButton.setAlpha(0.5f);
            resultTextView.setText("当前模式：售卖台检测");
        }
    }

    /**
     * 初始化相机
     */
    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        
        cameraProviderFuture.addListener(() -> {
            try {
                // 获取相机提供者
                cameraProvider = cameraProviderFuture.get();
                
                // 设置预览
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                
                // 设置图像捕获
                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setTargetRotation(Surface.ROTATION_0)
                        .setTargetResolution(new Size(832, 448)) // 与处理器中的尺寸保持一致
                        .build();
                // 选择后置相机
                CameraSelector cameraSelector = selectAnyAvailableCamera(cameraProvider);
                if (cameraSelector != null) {
                    // 绑定生命周期
                    cameraProvider.unbindAll();
                    camera = cameraProvider.bindToLifecycle(
                            this,
                            cameraSelector,
                            preview,
                            imageCapture);
                    Log.d(TAG, "相机初始化成功");
                } else throw new ExecutionException(new Throwable("没有可使用的相机"));
                //
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "相机初始化失败: " + e.getMessage());
                Toast.makeText(this, "相机初始化失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            } catch (CameraInfoUnavailableException e) {
                Log.e(TAG, "相机枚举失败: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }
    
    /**
     * 拍照并检测
     */
    private void captureAndDetect() {
        if (imageCapture == null) {
            Toast.makeText(this, "相机未初始化", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // 显示正在拍照的提示
        Toast.makeText(this, "正在拍照...", Toast.LENGTH_SHORT).show();
        
        // 拍照
        imageCapture.takePicture(executor, new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(@NonNull ImageProxy imageProxy) {
                // 将ImageProxy转换为Mat
                Mat img = imageProxyToMat(imageProxy);
                if (img == null) {
                    runOnUiThread(() -> Toast.makeText(SauceDetectionActivity.this, "图像处理失败", Toast.LENGTH_SHORT).show());
                    imageProxy.close();
                    return;
                }
                
                // 在UI线程中处理检测结果
                runOnUiThread(() -> {
                    // 隐藏预览，显示图像视图
                    previewView.setVisibility(View.GONE);
                    imageView.setVisibility(View.VISIBLE);
                    
                    // 执行检测
                    detectSausage(img);
                });
                
                // 关闭ImageProxy
                imageProxy.close();
            }
            
            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                Log.e(TAG, "拍照失败: " + exception.getMessage());
                runOnUiThread(() -> Toast.makeText(SauceDetectionActivity.this, "拍照失败: " + exception.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }
    
    /**
     * 将ImageProxy转换为Mat
     */
    private Mat imageProxyToMat(ImageProxy imageProxy) {
        try {
            ByteBuffer buffer = imageProxy.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            
            // 根据图像旋转角度旋转位图
            int rotation = imageProxy.getImageInfo().getRotationDegrees();
            if (rotation != 0) {
                Matrix matrix = new Matrix();
                matrix.postRotate(rotation);
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            }
            
            // 转换为Mat
            Mat mat = new Mat();
            Utils.bitmapToMat(bitmap, mat);
            
            // 确保图像是BGR格式
            if (mat.channels() == 4) { // RGBA格式
                Mat bgrMat = new Mat();
                Imgproc.cvtColor(mat, bgrMat, Imgproc.COLOR_RGBA2BGR);
                mat.release();
                mat = bgrMat;
            } else if (mat.channels() == 1) { // 灰度图
                Mat bgrMat = new Mat();
                Imgproc.cvtColor(mat, bgrMat, Imgproc.COLOR_GRAY2BGR);
                mat.release();
                mat = bgrMat;
            }
            
            // 保存原始图像用于调试
            if (SauceDetector.SAVE_DEBUG_IMAGES) {
                SauceDetector.saveDebugImage(mat, "camera_captured_image");
            }
            
            return mat;
        } catch (Exception e) {
            Log.e(TAG, "图像转换失败: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 执行烤肠检测
     */
    private void detectSausage(Mat img) {
        try {
            if (img == null) {
                Toast.makeText(this, "图像为空", Toast.LENGTH_SHORT).show();
                return;
            }

            // 显示调试信息
            String platformType = processor.getRoiFlag() == 0 ? "运输台" : "售卖台";
            Toast.makeText(this, "正在检测" + platformType + "上的烤肠...", Toast.LENGTH_SHORT).show();
            Log.d(TAG, "开始检测" + platformType + "上的烤肠，图像尺寸: " + img.width() + "x" + img.height() + ", 通道数: " + img.channels());

            // 使用SauceDetectionProcessor处理图像，传递roiFlag参数
            SauceDetectionProcessor.DetectionResult result = processor.processImage(img);
            
            // 显示处理后的图像
            displayImage(result.processedImage);
            
            // 显示检测结果
            if (result.sausages.isEmpty()) {
                resultTextView.setText("未检测到烤肠");
                Log.d(TAG, platformType + "未检测到烤肠");
            } else {
                // 显示第一个检测到的烤肠信息
                SauceDetectionProcessor.SausageInfo sausage = result.sausages.get(0);
                String resultText = String.format("检测到烤肠:\n像素坐标: (%d, %d)\n世界坐标: (%.2f, %.2f, %.2f)",
                        sausage.pixelX, sausage.pixelY, sausage.worldX, sausage.worldY, sausage.worldZ);
                resultTextView.setText(resultText);
                
                Log.d(TAG, platformType + "检测到烤肠: " + result.sausages.size() + "个");
                for (int i = 0; i < result.sausages.size(); i++) {
                    SauceDetectionProcessor.SausageInfo s = result.sausages.get(i);
                    Log.d(TAG, String.format("烤肠 #%d - 像素坐标: (%d, %d), 世界坐标: (%.2f, %.2f, %.2f)",
                            i+1, s.pixelX, s.pixelY, s.worldX, s.worldY, s.worldZ));
                }
            }
            
            // 显示调试图像保存位置
            if (SauceDetector.SAVE_DEBUG_IMAGES) {
                String cacheDir = MyApp.Companion.instance().getCacheDir().getAbsolutePath();
                Log.d(TAG, "调试图像已保存到: " + cacheDir);
                Toast.makeText(this, "调试图像已保存到: " + cacheDir, Toast.LENGTH_LONG).show();
            }
            
            // 释放资源
            img.release();
            if (result.processedImage != null && !result.processedImage.equals(img)) {
                result.processedImage.release();
            }
        } catch (Exception e) {
            Log.e(TAG, "检测烤肠时出错: " + e.getMessage());
            Toast.makeText(this, "检测失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 如果已经有权限，重新启动相机
        if (allPermissionsGranted() && cameraProvider == null) {
            startCamera();
        }
        // 恢复相机预览
        previewView.setVisibility(View.VISIBLE);
        imageView.setVisibility(View.GONE);
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        // 释放相机资源
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
    }
    
    /**
     * 显示图像
     */
    private void displayImage(Mat img) {
        try {
            Bitmap bitmap = Bitmap.createBitmap(img.cols(), img.rows(), Bitmap.Config.ARGB_8888);
            Utils.matToBitmap(img, bitmap);
            imageView.setImageBitmap(bitmap);
            
            // 修改检测按钮文本为"返回相机"
            detectButton.setText("返回相机");
            // 修改检测按钮点击事件为返回相机预览
            detectButton.setOnClickListener(v -> returnToCamera());
        } catch (Exception e) {
            Log.e(TAG, "Error displaying image: " + e.getMessage());
        }
    }
    
    /**
     * 返回相机预览
     */
    private void returnToCamera() {
        // 隐藏图像视图，显示预览
        imageView.setVisibility(View.GONE);
        previewView.setVisibility(View.VISIBLE);
        
        // 恢复检测按钮文本和点击事件
        detectButton.setText("开始检测");
        detectButton.setOnClickListener(v -> captureAndDetect());
        
        // 清空结果文本
        resultTextView.setText("当前模式：" + (roiFlag == 0 ? "运输台检测" : "售卖台检测"));
    }

    /**
     * 动态选择摄像头
     * @param cameraProvider
     * @return
     * @throws CameraInfoUnavailableException
     */
    private CameraSelector selectAnyAvailableCamera(ProcessCameraProvider cameraProvider) throws CameraInfoUnavailableException {
        if (cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
            Log.d(TAG, "找到并选择‘后置’摄像头");
            return CameraSelector.DEFAULT_BACK_CAMERA;
        }
        if (cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
            Log.d(TAG, "找到并选择‘前置’摄像头");
            return CameraSelector.DEFAULT_FRONT_CAMERA;
        }
        List<CameraInfo> infos = cameraProvider.getAvailableCameraInfos();
        if (!infos.isEmpty()) {
            Log.d(TAG, "使用第一个摄像头");
            return infos.get(0).getCameraSelector();
        }
        Log.e(TAG, "未找到任何可用的摄像头！");
        return null;
    }
}