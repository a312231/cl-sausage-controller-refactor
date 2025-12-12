package cn.niuyannet.kaochang.android.base

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.fragment.app.FragmentActivity
import androidx.viewpager.widget.ViewPager
import androidx.viewpager2.widget.ViewPager2
import cn.niuyannet.kaochang.android.adapter.PlayAdapter
import cn.niuyannet.kaochang.android.adapter.PlayResourcesBean
import com.shuyu.gsyvideoplayer.GSYVideoManager

abstract class BaseActivity:FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 设置全屏显示
    }
    override fun onPause() {
        super.onPause()
        GSYVideoManager.onPause()
    }
    override fun onDestroy() {
        super.onDestroy()
        GSYVideoManager.releaseAllVideos()
    }


}