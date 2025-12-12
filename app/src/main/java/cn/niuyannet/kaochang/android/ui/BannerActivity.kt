package cn.niuyannet.kaochang.android.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.viewpager.widget.ViewPager
import androidx.viewpager2.widget.ViewPager2
import cn.niuyannet.kaochang.android.adapter.PlayAdapter
import cn.niuyannet.kaochang.android.adapter.PlayResourcesBean
import cn.niuyannet.kaochang.android.base.BaseActivity
import cn.niuyannet.kaochang.android.databinding.MainActivityBinding
import cn.niuyannet.kaochang.android.net.NetApi
import cn.niuyannet.kaochang.android.utils.ConnectManageHelper
import com.alibaba.fastjson.JSON
import com.blankj.utilcode.util.ToastUtils
import com.shuyu.gsyvideoplayer.GSYVideoManager
import com.youth.banner.listener.OnPageChangeListener

abstract class BannerActivity: LoadNetActivity() {

    val loopTime=6000L
    //列表+前后两页过渡页的坐标
    var currentPos= 1
    val taskHandler by lazy{
        Handler(Looper.getMainLooper())
    }
    private val resourcesList by lazy{
        arrayListOf<PlayResourcesBean>().apply {
        }
    }
    val adapter by lazy{
        PlayAdapter(this,resourcesList, this)
    }
    abstract fun initData();
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = MainActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initBanner()
        initData()

    }
    /**
     * 获取网络数据
     * {"msg":"操作成功","code":200,"data":[{"createBy":null,"createTime":"2025-05-21 23:04:36","updateBy":null,"updateTime":"2025-05-21 23:23:20","remark":null,"id":100,"title":"图一","mediaUrl":"https://image.cheerluck.com/images/2025/05/21/1747839801963_微信图片_20250520131833_9.png","mediaType":"image","linkUrl":null,"sort":3,"status":"0"},{"createBy":null,"createTime":"2025-05-21 23:16:59","updateBy":null,"updateTime":"2025-05-21 23:25:36","remark":null,"id":101,"title":"视频1","mediaUrl":"https://image.cheerluck.com/images/2025/05/21/1747840596811_oceans.mp4","mediaType":"video","linkUrl":null,"sort":4,"status":"0"}]}
     */
    @SuppressLint("NotifyDataSetChanged")
    fun initAdsData() {
        NetApi.getAds { code, content ->
            if (code==0){
                val json=JSON.parseObject(content)
                if (json.getIntValue("code")==200){
                    resourcesList.clear()
                    val jsonArray=json.getJSONArray("data")
                    for (i in 0 until jsonArray.size) {
                        val item = jsonArray.getJSONObject(i)
                        val mediaType = item.getString("mediaType")
                        resourcesList.add(
                            PlayResourcesBean(
                                isImg = mediaType == "image",
                                title = item.getString("title"),
                                url = item.getString("mediaUrl")
                            )
                        )
                    }
                    adapter.setDatas(resourcesList)
                    adapter.notifyDataSetChanged()
                    // 如果只有一个视频，设置Banner不自动轮播
                    try {
                        if (resourcesList.size == 1 && !resourcesList[0].isImg) {
                            binding.banner.isAutoLoop(false)
                            currentPos=0
                            binding.banner.postDelayed({
                                if(adapter.getVHMap()[currentPos]!=null){
                                    val holder = (adapter.getVHMap()[currentPos] as PlayAdapter.VideoHolder)
                                    GSYVideoManager.onPause()
                                    holder.video.isLooping=true
                                    holder.video.startPlayLogic()
                                }
                            },2000)
                        }
                    }catch (e:Exception){
                        e.printStackTrace()
                    }
                }
            }else{
                ToastUtils.showShort("网络错误")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        taskHandler.post(task)
    }
    private fun initBanner() {
        // 添加生命周期管理，确保在适当的生命周期内开始和停止轮播
        binding.banner.addBannerLifecycleObserver(this)
            .setAdapter(adapter,true) //是否开启无限循环
            .setLoopTime(loopTime) //轮播时间
        // 设置轮播图的点击事件监听器
        binding.banner.addOnPageChangeListener(object :
            ViewPager.OnPageChangeListener, OnPageChangeListener {
            /**
             * 滑动中的监听，当页面在滑动的时候会调用此方法，在滑动被停止之前，此方法会一直得到调用
             */
            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {

            }
            /**
             * 监听滑动到对应索引值的页面，第一个页面不执行
             */
            override fun onPageSelected(position: Int) {
                currentPos = when (position) {
                    binding.banner.realCount - 1 -> {
                        binding.banner.realCount
                    }
                    0 -> {
                        1
                    }
                    else -> {
                        position + 1
                    }
                }
            }
            /**
             * 滑动状态监听
             */
            override fun onPageScrollStateChanged(state: Int) {
                // Banner跳转之后再去控制视频播放
                if (state == ViewPager2.SCROLL_STATE_IDLE) {
                    if (binding.banner.isInfiniteLoop) {
                        taskHandler.post(task)
                    }
                }
            }
        })
        //重新设置banner数据
        //banner.setDatas(resourcesList)
    }
    val task = Runnable {
        //可能是首尾切换页，两个页面循环跳转
        if(adapter.getVHMap().containsKey(currentPos)){
            if(adapter.getVHMap()[currentPos] is PlayAdapter.VideoHolder){
                val holder = (adapter.getVHMap()[currentPos] as PlayAdapter.VideoHolder)
                GSYVideoManager.onPause()
                holder.video.startPlayLogic()
                // 如果只有一个视频，不需要自动轮播
                if (resourcesList.size <= 1 && !resourcesList[0].isImg) {
                    binding.banner.isAutoLoop(false)
                    binding.banner.stop()
                } else {
                    binding.banner.isAutoLoop(true)
                    binding.banner.stop()
                    binding.banner.isAutoLoop(false)
                }
            }else{
                GSYVideoManager.onPause()
                binding.banner.isAutoLoop(true)
                binding.banner.start()
            }
        }else{
            GSYVideoManager.onPause()
            binding.banner.isAutoLoop(true)
            binding.banner.start()
        }
    }
    override fun onDestroy() {
        super.onDestroy()
        binding.banner.destroy()
    }
}