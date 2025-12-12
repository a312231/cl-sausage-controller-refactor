package cn.niuyannet.kaochang.android.detecition;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.opencv.android.Utils;
import org.opencv.aruco.Aruco;
import org.opencv.aruco.DetectorParameters;
import org.opencv.aruco.Dictionary;
import org.opencv.calib3d.Calib3d;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import cn.niuyannet.kaochang.android.R;

/**
 * ArUco 标记检测和相机标定辅助类
 * 实现 Python cv_init.py 的功能
 */
public class ArucoHelper {
    private static final String TAG = "ArucoHelper";
    private static final int DEFAULT_WIDTH = 832;
    private static final int DEFAULT_HEIGHT = 448;
    private static final String PREF_NAME = "aruco_helper_prefs";
    private static final String KEY_CORNERS = "corners_data";
    private static final int TARGET_MARKER_ID = 1; // 目标标记ID，与Python代码保持一致
    
    private Context context;
    private Mat cameraMatrix;
    private Mat distCoeffs;
    private SharedPreferences sharedPreferences;
    
    /**
     * 构造函数
     * @param context 应用上下文
     */
    public ArucoHelper(Context context) {
        this.context = context;
        this.sharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        loadCalibrationData();
    }
    
    /**
     * 从 raw 资源目录中加载相机标定数据
     */
    private void loadCalibrationData() {
        try {
            // 从 raw 资源目录中获取 calibration_data.npz 文件
            InputStream inputStream = context.getResources().openRawResource(R.raw.calibration_data);
            
            // 解析 NPZ 文件（实际上是 ZIP 格式）
            Map<String, byte[]> npzData = readNpzFile(inputStream);
            
            if (npzData.containsKey("camera_matrix.npy") && npzData.containsKey("dist_coeffs.npy")) {
                // 解析相机矩阵
                byte[] cameraMatrixBytes = npzData.get("camera_matrix.npy");
                cameraMatrix = parseNumpyArray(cameraMatrixBytes, 3, 3);
                
                // 解析畸变系数
                byte[] distCoeffsBytes = npzData.get("dist_coeffs.npy");
                distCoeffs = parseNumpyArray(distCoeffsBytes, 1, 5);
                
                Log.d(TAG, "Camera calibration data loaded successfully from raw resource");
            } else {
                Log.e(TAG, "Required data not found in calibration_data.npz");
            }
        } catch (Resources.NotFoundException e) {
            Log.e(TAG, "Calibration data file not found in raw resources: " + e.getMessage());
            e.printStackTrace();
        } catch (IOException e) {
            Log.e(TAG, "Error loading calibration data: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 读取 NPZ 文件（实际上是 ZIP 格式）
     * @param inputStream NPZ 文件输入流
     * @return 包含 NPZ 文件内容的 Map
     */
    private Map<String, byte[]> readNpzFile(InputStream inputStream) throws IOException {
        Map<String, byte[]> result = new HashMap<>();
        ZipInputStream zipInputStream = new ZipInputStream(inputStream);
        
        ZipEntry entry;
        while ((entry = zipInputStream.getNextEntry()) != null) {
            String name = entry.getName();
            
            // 读取文件内容
            int size = (int) entry.getSize();
            if (size == -1) {
                // 如果大小未知，使用缓冲区读取
                ByteBuffer buffer = ByteBuffer.allocate(1024);
                int bytesRead;
                byte[] tempBuffer = new byte[1024];
                
                while ((bytesRead = zipInputStream.read(tempBuffer)) != -1) {
                    if (buffer.position() + bytesRead > buffer.capacity()) {
                        // 扩展缓冲区
                        ByteBuffer newBuffer = ByteBuffer.allocate(buffer.capacity() * 2);
                        buffer.flip();
                        newBuffer.put(buffer);
                        buffer = newBuffer;
                    }
                    buffer.put(tempBuffer, 0, bytesRead);
                }
                
                buffer.flip();
                byte[] data = new byte[buffer.limit()];
                buffer.get(data);
                result.put(name, data);
            } else {
                // 如果大小已知，直接分配足够的空间
                byte[] data = new byte[size];
                int bytesRead = 0;
                int offset = 0;
                while (offset < size && (bytesRead = zipInputStream.read(data, offset, size - offset)) != -1) {
                    offset += bytesRead;
                }
                result.put(name, data);
            }
            
            zipInputStream.closeEntry();
        }
        
        zipInputStream.close();
        return result;
    }
    
    /**
     * 解析 NumPy 数组数据
     * @param data NumPy 数组数据
     * @param rows 行数
     * @param cols 列数
     * @return OpenCV Mat 对象
     */
    private Mat parseNumpyArray(byte[] data, int rows, int cols) {
        // NumPy 文件头部分包含元数据，我们需要跳过它
        // 简单起见，我们假设数据从第 128 个字节开始
        // 实际情况可能需要更精确的解析
        int headerSize = 128;
        if (data.length <= headerSize) {
            Log.e(TAG, "Invalid NumPy array data");
            return null;
        }
        
        // 创建 Mat 对象
        Mat mat = new Mat(rows, cols, CvType.CV_64F);
        
        // 解析数据部分
        ByteBuffer buffer = ByteBuffer.wrap(data, headerSize, data.length - headerSize);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        
        double[] matData = new double[rows * cols];
        for (int i = 0; i < rows * cols; i++) {
            if (buffer.remaining() >= 8) {
                matData[i] = buffer.getDouble();
            }
        }
        
        mat.put(0, 0, matData);
        return mat;
    }
    
    /**
     * 检测结果类，包含处理后的图像和检测信息
     */
    public static class DetectionResult {
        public Bitmap resultImage;
        public int markersCount;
        public List<Integer> markerIds;
        public boolean targetFound;
        public List<List<Double>> targetCorners;
        
        public DetectionResult(Bitmap resultImage) {
            this.resultImage = resultImage;
            this.markersCount = 0;
            this.markerIds = new ArrayList<>();
            this.targetFound = false;
            this.targetCorners = new ArrayList<>();
        }
    }
    
    /**
     * 检测图像中的 ArUco 标记并保存角点坐标
     * @param bitmap 输入图像
     * @param targetId 目标 ArUco 标记 ID
     * @return 处理后的图像和检测结果信息
     */
    public DetectionResult detectArucoMarkers(Bitmap bitmap, int targetId) {
        if (cameraMatrix == null || distCoeffs == null) {
            Log.e(TAG, "Camera calibration data not loaded");
            return new DetectionResult(bitmap);
        }
        
        // 转换 Bitmap 为 OpenCV Mat
        Mat imageMat = new Mat();
        Utils.bitmapToMat(bitmap, imageMat);
        
        // 确保图像格式正确（转换为 BGR 格式）
        Mat bgrMat = new Mat();
        if (imageMat.channels() == 4) { // RGBA 格式
            Imgproc.cvtColor(imageMat, bgrMat, Imgproc.COLOR_RGBA2BGR);
        } else if (imageMat.channels() == 1) { // 灰度图
            Imgproc.cvtColor(imageMat, bgrMat, Imgproc.COLOR_GRAY2BGR);
        } else if (imageMat.channels() == 3) { // 可能是BGR格式
            // 确保是BGR格式而不是RGB格式
            bgrMat = imageMat.clone();
        } else {
            // 处理其他可能的格式，转换为BGR
            Log.w(TAG, "未知图像格式，通道数: " + imageMat.channels() + "，尝试转换为BGR");
            bgrMat = new Mat();
            if (imageMat.channels() > 0) {
                Imgproc.cvtColor(imageMat, bgrMat, Imgproc.COLOR_RGB2BGR);
            } else {
                // 如果无法确定格式，创建一个空的BGR图像
                bgrMat = new Mat(imageMat.rows(), imageMat.cols(), CvType.CV_8UC3);
            }
        }
        
        // 检查图像是否有效
        if (bgrMat.empty()) {
            Log.e(TAG, "转换后的BGR图像为空");
            return new DetectionResult(bitmap);
        }
        
        // 获取图像尺寸
        int width = bgrMat.cols();
        int height = bgrMat.rows();
        
        // 调整图像大小为默认尺寸，与Python代码保持一致
        Mat resizedImage = new Mat();
        Imgproc.resize(bgrMat, resizedImage, new Size(DEFAULT_WIDTH, DEFAULT_HEIGHT));
        
        // 设置 ArUco 字典和参数 - 与Python代码保持一致
        Dictionary dictionary = Aruco.getPredefinedDictionary(Aruco.DICT_4X4_50);
        DetectorParameters parameters = DetectorParameters.create();
        
        // 设置检测参数 - 与Python代码保持一致
        parameters.set_adaptiveThreshConstant(4);
        parameters.set_minMarkerPerimeterRate(0.01);
        parameters.set_maxMarkerPerimeterRate(4.0);
        parameters.set_minDistanceToBorder(3);
        
        // 检测标记
        List<Mat> corners = new ArrayList<>();
        Mat ids = new Mat();
        List<Mat> rejectedCandidates = new ArrayList<>();
        Aruco.detectMarkers(resizedImage, dictionary, corners, ids, parameters, rejectedCandidates);
        
        // 创建检测结果对象
        DetectionResult result = new DetectionResult(bitmap);
        
        // 绘制检测到的标记
        Mat outputImage = resizedImage.clone();
        
        // 如果检测到标记
        if (!ids.empty()) {
            // 绘制检测到的标记
            Aruco.drawDetectedMarkers(outputImage, corners, ids);
            
            // 记录检测到的标记数量
            result.markersCount = ids.rows();
            
            // 记录所有检测到的标记ID
            for (int i = 0; i < ids.rows(); i++) {
                int id = (int) ids.get(i, 0)[0];
                result.markerIds.add(id);
                
                // 如果是目标ID - 与Python代码保持一致，特别处理ID=1的标记
                if (id == targetId || id == TARGET_MARKER_ID) {
                    result.targetFound = true;
                    
                    // 获取角点坐标
                    Mat cornerMat = corners.get(i);
                    saveCorners(cornerMat);
                    
                    // 保存角点坐标到结果对象
                    for (int row = 0; row < cornerMat.rows(); row++) {
                        for (int col = 0; col < cornerMat.cols(); col++) {
                            List<Double> point = new ArrayList<>();
                            double[] values = cornerMat.get(row, col);
                            for (double value : values) {
                                point.add(value);
                            }
                            result.targetCorners.add(point);
                        }
                    }
                }
            }
        } else {
            // 没有检测到标记
            Log.d(TAG, "No ArUco markers detected");
        }
        
        // 转换回 Bitmap
        Bitmap resultBitmap = Bitmap.createBitmap(outputImage.cols(), outputImage.rows(), Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(outputImage, resultBitmap);
        result.resultImage = resultBitmap;
        
        // 释放资源
        imageMat.release();
        bgrMat.release();
        resizedImage.release();
        outputImage.release();
        
        return result;
    }
    
    /**
     * 保存角点坐标到 SharedPreferences
     * @param cornerMat 角点矩阵
     */
    private void saveCorners(Mat cornerMat) {
        try {
            // 将 Mat 转换为 List<List<Double>>
            List<List<Double>> cornersList = new ArrayList<>();
            
            for (int i = 0; i < cornerMat.rows(); i++) {
                for (int j = 0; j < cornerMat.cols(); j++) {
                    List<Double> point = new ArrayList<>();
                    double[] values = cornerMat.get(i, j);
                    for (double value : values) {
                        point.add(value);
                    }
                    cornersList.add(point);
                }
            }
            
            // 使用 Gson 将 List 转换为 JSON 字符串
            Gson gson = new Gson();
            String cornersJson = gson.toJson(cornersList);
            
            // 保存到 SharedPreferences
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putString(KEY_CORNERS, cornersJson);
            editor.apply();
            
            Log.d(TAG, "Corner coordinates saved to SharedPreferences");
        } catch (Exception e) {
            Log.e(TAG, "Error saving corner coordinates: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 从 SharedPreferences 加载保存的角点坐标
     * @return 角点坐标矩阵
     */
    public Mat loadCorners() {
        try {
            // 从 SharedPreferences 获取 JSON 字符串
            String cornersJson = sharedPreferences.getString(KEY_CORNERS, null);
            if (cornersJson == null) {
                Log.e(TAG, "Corners data not found in SharedPreferences");
                return null;
            }
            
            // 使用 Gson 将 JSON 字符串转换回 List<List<Double>>
            Gson gson = new Gson();
            Type type = new TypeToken<List<List<Double>>>(){}.getType();
            List<List<Double>> cornersList = gson.fromJson(cornersJson, type);
            
            if (cornersList == null || cornersList.isEmpty()) {
                Log.e(TAG, "Invalid corners data format");
                return null;
            }
            
            // 创建 Mat 并填充数据
            // 假设角点是 4x2 矩阵 (4个点，每个点有x,y坐标)
            Mat corners = new Mat(4, 2, CvType.CV_32F);
            float[] cornersData = new float[8];
            
            int index = 0;
            for (List<Double> point : cornersList) {
                if (index >= 8) break; // 防止越界
                for (Double value : point) {
                    if (index >= 8) break; // 防止越界
                    cornersData[index++] = value.floatValue();
                }
            }
            
            corners.put(0, 0, cornersData);
            return corners;
        } catch (Exception e) {
            Log.e(TAG, "Error loading corner coordinates: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }
}
