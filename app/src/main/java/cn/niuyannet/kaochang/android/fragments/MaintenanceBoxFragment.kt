package cn.niuyannet.kaochang.android.fragments

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Size
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import cn.niuyannet.kaochang.android.R
import cn.niuyannet.kaochang.android.adapter.KaoPanBoxAdapter
import cn.niuyannet.kaochang.android.databinding.FragmentMaintenanceBoxBinding
import cn.niuyannet.kaochang.android.model.KaoPanHelper
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.model.bean.Taste
import cn.niuyannet.kaochang.android.net.NetApi
import cn.niuyannet.kaochang.android.services.KaoChangOperate
import cn.niuyannet.kaochang.android.utils.LogUtils
import cn.niuyannet.kaochang.android.utils.SauceDetectionUitls
import cn.niuyannet.kaochang.android.utils.dismissLoadingExt
import cn.niuyannet.kaochang.android.utils.showLoadingExt
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.customview.customView
import com.afollestad.materialdialogs.customview.getCustomView
import com.afollestad.materialdialogs.lifecycle.lifecycleOwner
import com.alibaba.fastjson.JSON
import com.blankj.utilcode.util.ToastUtils
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutionException

class MaintenanceBoxFragment : Fragment() {
    private var _binding: FragmentMaintenanceBoxBinding? = null
    private val binding get() = _binding!!
    private var refreshJob: Job? = null
    private lateinit var kaoPanBoxAdapter: KaoPanBoxAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMaintenanceBoxBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupGrid()
        setupButtons()

    }


    /**
     * 设置按钮点击事件
     */
    private fun setupButtons() {
        binding.btnMoveToKaopan.setOnClickListener {
            moveOvenToKaoPan()
        }
        binding.btnClearAll.setOnClickListener {
            clearOven()
        }
    }
    /**
     * 移动烤箱到指定烤盘位置
     */
    private fun moveOvenToKaoPan() {
        val selectedBoxes = KaoPanHelper.getKaoPanBoxList().filter { box ->
            box.checkStatus == true
        }
        if (selectedBoxes.isEmpty()) {
            ToastUtils.showShort("请先选择烤肠箱")
            return
        }
        if (selectedBoxes.size>1) {
            ToastUtils.showShort("只能选择一个烤肠箱")
            return
        }

        MaterialDialog(requireContext()).show {
            title(text = "请选择目标烤盘位置")
            //开启摄像头
            customView(R.layout.dialog_number_picker, noVerticalPadding = true)
            positiveButton(text = "确定") { dialog ->
                val numberPicker = dialog.getCustomView().findViewById<android.widget.NumberPicker>(R.id.number_picker)
                val targetPosition: KaoPan = KaoPanHelper.getKaoPanList().first { it.positionSn == numberPicker.value }
                val boxPosition:KaoPanBox = selectedBoxes[0]
                showLoadingExt("移动中...")
                GlobalScope.launch(Dispatchers.IO) {
                    // 将选中的烤肠箱移动到目标烤盘位置
                    targetPosition.taste = Taste().apply {
                       tasteCode = boxPosition.tasteCode
                    }

                    val result = KaoChangOperate.moveSausageToKaoPan(targetPosition,boxPosition)
                    GlobalScope.launch(Dispatchers.Main) {
                        dismissLoadingExt()
                        //关闭摄像头
                        if (result) {
                            ToastUtils.showShort("移动成功")
                        } else {
                            ToastUtils.showShort("移动失败")
                        }
                    }
                }


            }
            negativeButton(text = "取消")
            lifecycleOwner(viewLifecycleOwner)
        }.apply {
            val numberPicker = getCustomView().findViewById<android.widget.NumberPicker>(R.id.number_picker)
            numberPicker.minValue = 1
            numberPicker.maxValue = 33
            numberPicker.value = 1
            numberPicker.wrapSelectorWheel = false
        }
    }
    /**
     * 一键清空烤箱
     */
    private fun clearOven() {
         val selectedBoxes = KaoPanHelper.getKaoPanBoxList().filter { box ->
                box.checkStatus == true
            }
            if (selectedBoxes.isEmpty()) {
                ToastUtils.showShort("请先选择需要清空的烤肠箱")
            } else {
                showClearConfirmDialog(selectedBoxes)
            }

    }
    /**
     * 显示清空确认对话框
     */
    private fun showClearConfirmDialog(selectedBoxes: List<KaoPanBox>) {
        MaterialDialog(requireContext()).show {
            title(text = "确认清空选中的烤肠箱吗？")
            positiveButton(text = "确定") { dialog ->
                //KaoChangOperate.clearSausageBoxes(selectedBoxes)
                dialog.dismiss()
                //清空选择的烤肠箱，并且更新同步到后台数据库
                // 更新选中的烤盘箱
                for (box in selectedBoxes) {
                    box.productId=null
                    box.tasteCode = null
                    box.tasteName = null
                    box.checkStatus = false
                    box.num = 0
                }
                // 保存更新并刷新UI
                showLoadingExt("设置中...")
                NetApi.clearStock(JSON.toJSONString( KaoPanHelper.getKaoPanBoxList())) { code, content ->
                    dismissLoadingExt()
                    if (code==0){
                        val json=JSON.parseObject(content)
                        if (json.getIntValue("code")==200){
                            KaoPanHelper.saveKaoPanBoxList( KaoPanHelper.getKaoPanBoxList())
                            kaoPanBoxAdapter.notifyDataSetChanged()
                            ToastUtils.showShort("已清空烤肠箱")
                            dialog.dismiss()
                        }else{
                            ToastUtils.showShort(json.getString("msg"))
                        }

                    }else{
                        ToastUtils.showShort("设置失败")
                    }
                }

            }
            negativeButton(text = "取消")
            lifecycleOwner(viewLifecycleOwner)
        }
    }

    /**
     * 启动2秒定时刷新
     */
    private fun startRefreshTimer() {
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            flow {
                while (true) {
                    emit(Unit)
                    delay(1000)
                }
            }.collect {
                kaoPanBoxAdapter.setData(KaoPanHelper.getKaoPanBoxList())
            }
        }
    }
    /**
     * 设置数据
     */
    @SuppressLint("NotifyDataSetChanged")
    private fun setupGrid() {
        // 设置烤盘箱列表适配器
        kaoPanBoxAdapter = KaoPanBoxAdapter {
            kaoPanBoxAdapter.notifyDataSetChanged()
        }
        binding.rvKaopanBox.apply {
            layoutManager = GridLayoutManager(context, 9) //
            adapter = kaoPanBoxAdapter
        }
        startRefreshTimer()

        binding.btnRefill.setOnClickListener {
            val selectedBoxes = KaoPanHelper.getKaoPanBoxList().filter { box ->
                box.checkStatus == true
            }
            if (selectedBoxes.isEmpty()) {
                ToastUtils.showShort("请先选择需要加满的烤盘箱")
                return@setOnClickListener
            } else {
                showTasteSelectDialog(selectedBoxes)
            }
        }
    }
    
    /**
     * 显示美观的口味选择对话框
     */
    @SuppressLint("NotifyDataSetChanged", "SetTextI18n")
    private fun showTasteSelectDialog(selectedBoxes: List<KaoPanBox>) {
        val dialog = MaterialDialog(requireContext()).apply {
            customView(R.layout.dialog_taste_select, noVerticalPadding = true)
            cancelOnTouchOutside(true)
        }

        val customView = dialog.getCustomView()
        val radioGroup = customView.findViewById<RadioGroup>(R.id.radio_group)
        val btnCancel = customView.findViewById<MaterialButton>(R.id.btn_cancel)
        val btnConfirm = customView.findViewById<MaterialButton>(R.id.btn_confirm)

        // 获取口味列表
        val tastes = KaoPanHelper.getTasteList()

        // 动态添加RadioButton
        tastes.forEachIndexed { index, taste ->
            val radioButton = RadioButton(requireContext()).apply {
                id = View.generateViewId()
                text = taste.productName+"("+taste.tasteName+")"
                textSize = 16f
                setTextColor(resources.getColorStateList(R.color.radio_text_selector, null))
                background = ContextCompat.getDrawable(requireContext(), R.drawable.radio_button_selector)
                buttonDrawable = null // 移除默认的圆形按钮
                layoutParams = RadioGroup.LayoutParams(
                    RadioGroup.LayoutParams.MATCH_PARENT,
                    RadioGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 8.dpToPx(), 0, 8.dpToPx())
                }
                setPadding(16.dpToPx(), 12.dpToPx(), 16.dpToPx(), 12.dpToPx())
                
                // 如果是第一个口味，默认选中
                isChecked = index == 0
            }
            radioGroup.addView(radioButton)
        }

        // 设置按钮点击事件
        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnConfirm.setOnClickListener {
            val selectedId = radioGroup.checkedRadioButtonId
            val selectedView = radioGroup.findViewById<RadioButton>(selectedId)
            val selectedIndex = radioGroup.indexOfChild(selectedView)
            val selectedTaste = tastes[selectedIndex]
            
            // 更新选中的烤盘箱
            for (box in selectedBoxes) {
                box.productId=selectedTaste.productId.toString()
                box.tasteCode = selectedTaste.tasteCode
                box.tasteName = selectedTaste.productName+"("+selectedTaste.tasteName+")"
                box.checkStatus = false
                box.num = 12
            }
            // 保存更新并刷新UI
            showLoadingExt("设置中...")
            NetApi.updateStock(JSON.toJSONString( KaoPanHelper.getKaoPanBoxList())) { code, content ->
                dismissLoadingExt()
                if (code==0){
                    val json=JSON.parseObject(content)
                    if (json.getIntValue("code")==200){
                        KaoPanHelper.saveKaoPanBoxList( KaoPanHelper.getKaoPanBoxList())
                        kaoPanBoxAdapter.notifyDataSetChanged()
                        ToastUtils.showShort("已成功加满选中的烤盘箱")
                        dialog.dismiss()
                    }else{
                        ToastUtils.showShort(json.getString("msg"))
                    }

                }else{
                    ToastUtils.showShort("添加失败")
                }
            }


        }

        dialog.show()
    }
    
    /**
     * dp转px工具方法
     */
    private fun Int.dpToPx(): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            this.toFloat(),
            resources.displayMetrics
        ).toInt()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        refreshJob?.cancel()
        _binding = null
    }
}