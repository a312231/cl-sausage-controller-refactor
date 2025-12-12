package cn.niuyannet.kaochang.android.detecition;

import android.content.Context;

/**
 * 烤肠检测启动器
 * 将Python的sauce_detect.py主要功能翻译成Java代码
 */

import android.content.Intent;
import android.widget.Toast;

import cn.niuyannet.kaochang.android.ui.SauceDetectionActivity;

/**
 * 烤肠检测启动器
 * 用于从应用的任何地方启动烤肠检测功能
 */
public class SauceDetectionLauncher {

    /**
     * 启动烤肠检测活动
     *
     * @param context 上下文
     */
    public static void launch(Context context) {
        try {
            // 检查OpenCV是否可用
            if (!isOpenCVAvailable()) {
                Toast.makeText(context, "OpenCV库不可用，请确保已正确安装", Toast.LENGTH_LONG).show();
                return;
            }
            
            // 为SauceDetector设置应用上下文，用于保存调试图像
           // SauceDetector.setAppContext(context.getApplicationContext());
            
            // 启动烤肠检测活动
            Intent intent = new Intent(context, SauceDetectionActivity.class);
            context.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(context, "启动烤肠检测失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 检查OpenCV库是否可用
     *
     * @return 是否可用
     */
    public  static boolean isOpenCVAvailable() {
        try {
            // 尝试加载OpenCV类
            Class.forName("org.opencv.android.OpenCVLoader");
            return org.opencv.android.OpenCVLoader.initDebug();
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}