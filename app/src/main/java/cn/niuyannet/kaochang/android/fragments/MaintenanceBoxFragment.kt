package cn.niuyannet.kaochang.android.fragments

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.NumberPicker
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import cn.niuyannet.kaochang.android.R
import cn.niuyannet.kaochang.android.adapter.KaoPanBoxAdapter
import cn.niuyannet.kaochang.android.databinding.FragmentMaintenanceBoxBinding
import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.model.KaoPanHelper
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.model.bean.Taste
import cn.niuyannet.kaochang.android.net.DataManagementAPI
import cn.niuyannet.kaochang.android.net.NetApi
import cn.niuyannet.kaochang.android.services.BoxRefillEligibilityPolicy
import cn.niuyannet.kaochang.android.services.KaoChangOperate
import cn.niuyannet.kaochang.android.ui.AdminOperationLogger
import cn.niuyannet.kaochang.android.ui.resetAdminIdleTimer
import cn.niuyannet.kaochang.android.utils.LogUtils
import cn.niuyannet.kaochang.android.utils.dismissLoadingExt
import cn.niuyannet.kaochang.android.utils.showLoadingExt
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.customview.customView
import com.afollestad.materialdialogs.customview.getCustomView
import com.afollestad.materialdialogs.lifecycle.lifecycleOwner
import com.alibaba.fastjson.JSON
import com.blankj.utilcode.util.ToastUtils
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class MaintenanceBoxFragment : Fragment() {
    private companion object {
        const val BOX_GRID_SPAN_COUNT = 11
        const val REFILL_CATALOG_REFRESH_TIMEOUT_MS = 3_000L
    }

    private data class BoxClearSnapshot(
        val box: KaoPanBox,
        val productId: String?,
        val tasteCode: String?,
        val tasteName: String?,
        val checkStatus: Boolean?,
        val num: Int,
        val cmdStatusTake: Int
    )

    private var _binding: FragmentMaintenanceBoxBinding? = null
    private val binding get() = _binding!!
    private var refreshJob: Job? = null
    private var refillCatalogRefreshJob: Job? = null
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
        ensureBoxDataLoaded()
        refreshProductCatalogForPage()
    }

    /**
     * 设置顶部按钮点击事件。
     */
    private fun setupButtons() {
        binding.btnMoveToKaopan.setOnClickListener {
            moveOvenToKaoPan()
        }
        binding.btnClearAll.setOnClickListener {
            clearOven()
        }
        binding.btnClearAllBoxes.setOnClickListener {
            clearAllOvenBoxes()
        }
    }

    /**
     * 将选中的烤肠箱搬运到指定烤盘位置。
     */
    private fun moveOvenToKaoPan() {
        val selectedBoxes = KaoPanHelper.getKaoPanBoxList().filter { it.checkStatus == true }
        if (selectedBoxes.isEmpty()) {
            AdminOperationLogger.w(
                action = "烤肠箱搬运到烤盘",
                page = AdminOperationLogger.PAGE_BOX,
                result = AdminOperationLogger.RESULT_REJECT,
                detail = "拒绝原因=未选择烤肠箱"
            )
            ToastUtils.showShort("请先选择烤肠箱")
            return
        }
        if (selectedBoxes.size > 1) {
            AdminOperationLogger.w(
                action = "烤肠箱搬运到烤盘",
                page = AdminOperationLogger.PAGE_BOX,
                result = AdminOperationLogger.RESULT_REJECT,
                detail = "拒绝原因=只能选择一个烤肠箱，已选择=${boxPositionListText(selectedBoxes)}"
            )
            ToastUtils.showShort("只能选择一个烤肠箱")
            return
        }
        if (selectedBoxes[0].num <= 0) {
            AdminOperationLogger.w(
                action = "烤肠箱搬运到烤盘",
                page = AdminOperationLogger.PAGE_BOX,
                result = AdminOperationLogger.RESULT_REJECT,
                detail = "拒绝原因=烤肠箱库存不足，烤肠箱位=${selectedBoxes[0].positionSn}号"
            )
            ToastUtils.showShort("当前烤肠箱库存不足，无法继续补肠")
            return
        }

        MaterialDialog(requireContext()).show {
            title(text = "请选择目标烤盘位置")
            customView(R.layout.dialog_number_picker, noVerticalPadding = true)
            positiveButton(text = "确定") { dialog ->
                resetAdminIdleTimer("烤肠箱搬运弹窗确认")
                val numberPicker = dialog.getCustomView().findViewById<NumberPicker>(R.id.number_picker)
                val targetPosition = KaoPanHelper.getKaoPanList().first { it.positionSn == numberPicker.value }
                val boxPosition = selectedBoxes[0]
                AdminOperationLogger.i(
                    action = "烤肠箱搬运到烤盘",
                    page = AdminOperationLogger.PAGE_BOX,
                    result = AdminOperationLogger.RESULT_START,
                    detail = "烤肠箱位=${boxPosition.positionSn}号，目标烤盘位=${targetPosition.positionSn}，口味=${boxPosition.tasteName ?: "未知口味"}"
                )
                showLoadingExt("移动中...")
                viewLifecycleOwner.lifecycleScope.launch {
                    targetPosition.taste = Taste().apply {
                        tasteCode = boxPosition.tasteCode
                    }

                    val result = withContext(Dispatchers.IO) {
                        KaoChangOperate.moveSausageToKaoPan(targetPosition, boxPosition)
                    }
                    dismissLoadingExt()
                    if (result) {
                        AdminOperationLogger.i(
                            action = "烤肠箱搬运到烤盘",
                            page = AdminOperationLogger.PAGE_BOX,
                            result = AdminOperationLogger.RESULT_DONE,
                            detail = "烤肠箱位=${boxPosition.positionSn}号，目标烤盘位=${targetPosition.positionSn}，口味=${boxPosition.tasteName ?: "未知口味"}"
                        )
                        ToastUtils.showShort("移动成功")
                    } else {
                        AdminOperationLogger.e(
                            action = "烤肠箱搬运到烤盘",
                            page = AdminOperationLogger.PAGE_BOX,
                            result = AdminOperationLogger.RESULT_FAIL,
                            detail = "烤肠箱位=${boxPosition.positionSn}号，目标烤盘位=${targetPosition.positionSn}，口味=${boxPosition.tasteName ?: "未知口味"}"
                        )
                        ToastUtils.showShort("移动失败")
                    }
                }
            }
            negativeButton(text = "取消") {
                resetAdminIdleTimer("烤肠箱搬运弹窗取消")
            }
            lifecycleOwner(viewLifecycleOwner)
        }.apply {
            setOnShowListener {
                resetAdminIdleTimer("烤肠箱搬运弹窗显示")
            }
            val numberPicker = getCustomView().findViewById<NumberPicker>(R.id.number_picker)
            numberPicker.minValue = 1
            numberPicker.maxValue = 33
            numberPicker.value = 1
            numberPicker.wrapSelectorWheel = false
            numberPicker.setOnValueChangedListener { _, _, _ ->
                resetAdminIdleTimer("烤肠箱搬运弹窗选择烤盘")
            }
        }
    }

    /**
     * 一键清空选中的烤肠箱。
     */
    private fun clearOven() {
        val selectedBoxes = KaoPanHelper.getKaoPanBoxList().filter { it.checkStatus == true }
        if (selectedBoxes.isEmpty()) {
            AdminOperationLogger.w(
                action = "清空烤肠箱",
                page = AdminOperationLogger.PAGE_BOX,
                result = AdminOperationLogger.RESULT_REJECT,
                detail = "拒绝原因=未选择烤肠箱"
            )
            ToastUtils.showShort("请先选择需要清空的烤肠箱")
        } else {
            showClearConfirmDialog(selectedBoxes)
        }
    }

    /**
     * 清空所有烤肠箱，仅重置本地和后端库存数据，不触发下位机动作。
     */
    private fun clearAllOvenBoxes() {
        val allBoxes = KaoPanHelper.getKaoPanBoxList()
        if (allBoxes.isEmpty()) {
            AdminOperationLogger.w(
                action = "清空所有烤肠箱",
                page = AdminOperationLogger.PAGE_BOX,
                result = AdminOperationLogger.RESULT_REJECT,
                detail = "拒绝原因=烤肠箱列表为空"
            )
            ToastUtils.showShort("烤肠箱数据为空，请先同步库存")
            return
        }
        val stockBoxCount = allBoxes.count { it.num > 0 }
        val totalStock = allBoxes.sumOf { it.num }
        MaterialDialog(requireContext()).show {
            title(text = "确认清空所有烤肠箱吗？")
            message(
                text = "该操作会把全部烤肠箱的产品、口味、库存数量和取肠状态清空，并同步到后台。\n\n" +
                    "当前本地记录有库存箱位：$stockBoxCount 个\n" +
                    "当前本地记录总库存：$totalStock 根\n\n" +
                    "请确认现场已完成清空或允许把库存记录归零。"
            )
            positiveButton(text = "确认清空全部") { dialog ->
                resetAdminIdleTimer("清空所有烤肠箱弹窗确认")
                dialog.dismiss()
                executeClearBoxes(
                    targetBoxes = allBoxes,
                    actionName = "清空所有烤肠箱",
                    successMessage = "已清空所有烤肠箱"
                )
            }
            negativeButton(text = "取消") {
                resetAdminIdleTimer("清空所有烤肠箱弹窗取消")
            }
            lifecycleOwner(viewLifecycleOwner)
        }.apply {
            setOnShowListener {
                resetAdminIdleTimer("清空所有烤肠箱弹窗显示")
            }
        }
    }

    /**
     * 显示清空确认对话框。
     */
    private fun showClearConfirmDialog(selectedBoxes: List<KaoPanBox>) {
        MaterialDialog(requireContext()).show {
            title(text = "确认清空选中的烤肠箱吗？")
            positiveButton(text = "确定") { dialog ->
                resetAdminIdleTimer("清空烤肠箱弹窗确认")
                dialog.dismiss()
                executeClearBoxes(
                    targetBoxes = selectedBoxes,
                    actionName = "清空烤肠箱",
                    successMessage = "已清空烤肠箱"
                )
            }
            negativeButton(text = "取消") {
                resetAdminIdleTimer("清空烤肠箱弹窗取消")
            }
            lifecycleOwner(viewLifecycleOwner)
        }.apply {
            setOnShowListener {
                resetAdminIdleTimer("清空烤肠箱弹窗显示")
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun executeClearBoxes(
        targetBoxes: List<KaoPanBox>,
        actionName: String,
        successMessage: String
    ) {
        val targetText = boxPositionListText(targetBoxes)
        AdminOperationLogger.i(
            action = actionName,
            page = AdminOperationLogger.PAGE_BOX,
            result = AdminOperationLogger.RESULT_START,
            detail = "烤肠箱位=$targetText"
        )
        val snapshots = targetBoxes.map { box ->
            BoxClearSnapshot(
                box = box,
                productId = box.productId,
                tasteCode = box.tasteCode,
                tasteName = box.tasteName,
                checkStatus = box.checkStatus,
                num = box.num,
                cmdStatusTake = box.cmdStatusTake
            )
        }
        for (box in targetBoxes) {
            box.productId = null
            box.tasteCode = null
            box.tasteName = null
            box.checkStatus = false
            box.num = 0
            box.cmdStatusTake = 0
        }
        showLoadingExt("设置中...")
        NetApi.clearStock(JSON.toJSONString(KaoPanHelper.getKaoPanBoxList())) { code, content ->
            dismissLoadingExt()
            if (code == 0) {
                val json = JSON.parseObject(content)
                if (json.getIntValue("code") == 200) {
                    KaoPanHelper.saveKaoPanBoxList(KaoPanHelper.getKaoPanBoxList())
                    kaoPanBoxAdapter.notifyDataSetChanged()
                    AdminOperationLogger.i(
                        action = actionName,
                        page = AdminOperationLogger.PAGE_BOX,
                        result = AdminOperationLogger.RESULT_DONE,
                        detail = "烤肠箱位=$targetText"
                    )
                    ToastUtils.showShort(successMessage)
                } else {
                    AdminOperationLogger.w(
                        action = actionName,
                        page = AdminOperationLogger.PAGE_BOX,
                        result = AdminOperationLogger.RESULT_FAIL,
                        detail = "烤肠箱位=$targetText，失败原因=${json.getString("msg")}"
                    )
                    restoreClearSnapshots(snapshots)
                    ToastUtils.showShort(json.getString("msg"))
                }
            } else {
                AdminOperationLogger.w(
                    action = actionName,
                    page = AdminOperationLogger.PAGE_BOX,
                    result = AdminOperationLogger.RESULT_FAIL,
                    detail = "烤肠箱位=$targetText，失败原因=接口请求失败"
                )
                restoreClearSnapshots(snapshots)
                ToastUtils.showShort("设置失败")
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun restoreClearSnapshots(snapshots: List<BoxClearSnapshot>) {
        for (snapshot in snapshots) {
            snapshot.box.productId = snapshot.productId
            snapshot.box.tasteCode = snapshot.tasteCode
            snapshot.box.tasteName = snapshot.tasteName
            snapshot.box.checkStatus = snapshot.checkStatus
            snapshot.box.num = snapshot.num
            snapshot.box.cmdStatusTake = snapshot.cmdStatusTake
        }
        kaoPanBoxAdapter.notifyDataSetChanged()
    }

    /**
     * 启动定时刷新。
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
     * 初始化烤肠箱列表。
     */
    @SuppressLint("NotifyDataSetChanged")
    private fun setupGrid() {
        kaoPanBoxAdapter = KaoPanBoxAdapter {
            kaoPanBoxAdapter.notifyDataSetChanged()
        }
        binding.rvKaopanBox.apply {
            layoutManager = GridLayoutManager(context, BOX_GRID_SPAN_COUNT)
            adapter = kaoPanBoxAdapter
        }
        startRefreshTimer()

        binding.btnRefill.setOnClickListener {
            val selectedBoxes = KaoPanHelper.getKaoPanBoxList().filter { it.checkStatus == true }
            if (selectedBoxes.isEmpty()) {
                AdminOperationLogger.w(
                    action = "加满烤肠箱",
                    page = AdminOperationLogger.PAGE_BOX,
                    result = AdminOperationLogger.RESULT_REJECT,
                    detail = "拒绝原因=未选择烤肠箱"
                )
                ToastUtils.showShort("请先选择需要加满的烤肠箱")
                return@setOnClickListener
            }
            val refillDecision = BoxRefillEligibilityPolicy.evaluate(selectedBoxes)
            if (!refillDecision.allowed) {
                AdminOperationLogger.w(
                    action = "加满烤肠箱",
                    page = AdminOperationLogger.PAGE_BOX,
                    result = AdminOperationLogger.RESULT_REJECT,
                    detail = "拒绝原因=禁用箱位不允许加肠，烤肠箱位=${boxPositionListText(selectedBoxes)}，禁用箱位=${refillDecision.disabledBoxNos.joinToString("、")}"
                )
                ToastUtils.showShort(refillDecision.message)
                return@setOnClickListener
            }
            refreshCatalogThenShowTasteSelectDialog(selectedBoxes)
        }
    }

    private fun refreshProductCatalogForPage() {
        DataManagementAPI.refreshProductCatalog(
            force = true,
            logPrefix = "进入烤肠箱页同步商品目录"
        ) { result ->
            activity?.runOnUiThread {
                if (!isAdded || _binding == null) {
                    return@runOnUiThread
                }
                if (result.success) {
                    kaoPanBoxAdapter.setData(KaoPanHelper.getKaoPanBoxList())
                }
            }
        }
    }

    private fun refreshCatalogThenShowTasteSelectDialog(selectedBoxes: List<KaoPanBox>) {
        if (refillCatalogRefreshJob?.isActive == true) {
            return
        }
        showLoadingExt("同步商品目录中...")
        refillCatalogRefreshJob = viewLifecycleOwner.lifecycleScope.launch {
            var loadingDismissed = false
            try {
                val refreshSuccess = withTimeoutOrNull(REFILL_CATALOG_REFRESH_TIMEOUT_MS) {
                    val deferred = CompletableDeferred<Boolean>()
                    DataManagementAPI.refreshProductCatalog(
                        force = true,
                        logPrefix = "加满烤肠箱前同步商品目录"
                    ) { result ->
                        deferred.complete(result.success)
                    }
                    deferred.await()
                } ?: false

                dismissLoadingExt()
                loadingDismissed = true
                if (!isAdded || _binding == null) {
                    return@launch
                }

                when (
                    resolveRefillCatalogRefreshAction(
                        refreshSuccess = refreshSuccess,
                        localCatalogNotEmpty = KaoPanHelper.getTasteList().isNotEmpty()
                    )
                ) {
                    RefillCatalogRefreshAction.OPEN_DIALOG -> {
                        showTasteSelectDialog(selectedBoxes)
                    }

                    RefillCatalogRefreshAction.OPEN_DIALOG_WITH_CACHE_TOAST -> {
                        ToastUtils.showShort("商品目录同步失败，已使用本地缓存")
                        showTasteSelectDialog(selectedBoxes)
                    }

                    RefillCatalogRefreshAction.REJECT_WITH_NETWORK_TOAST -> {
                        ToastUtils.showShort("商品目录同步失败，请检查网络")
                    }
                }
            } finally {
                if (!loadingDismissed) {
                    dismissLoadingExt()
                }
                refillCatalogRefreshJob = null
            }
        }
    }

    private fun ensureBoxDataLoaded() {
        if (KaoPanHelper.getKaoPanBoxList().isNotEmpty()) {
            return
        }
        LogUtils.d("烤肠箱本地缓存为空，进入页面后主动补拉库存")
        showLoadingExt("同步烤肠箱中...")
        DataManagementAPI.refreshStockData(logPrefix = "维护页烤肠箱首屏补拉") { success, stockCount ->
            activity?.runOnUiThread {
                dismissLoadingExt()
                if (!isAdded || _binding == null) {
                    return@runOnUiThread
                }
                if (success) {
                    kaoPanBoxAdapter.setData(KaoPanHelper.getKaoPanBoxList())
                    LogUtils.d("烤肠箱首屏补拉完成：箱位数=$stockCount")
                } else {
                    ToastUtils.showShort("烤肠箱数据同步失败，请稍后重试")
                }
            }
        }
    }

    /**
     * 显示口味选择对话框。
     */
    @SuppressLint("NotifyDataSetChanged", "SetTextI18n")
    private fun showTasteSelectDialog(selectedBoxes: List<KaoPanBox>) {
        val dialog = MaterialDialog(requireContext()).apply {
            customView(R.layout.dialog_taste_select, noVerticalPadding = true)
            cancelOnTouchOutside(true)
            setOnShowListener {
                resetAdminIdleTimer("加满烤肠箱口味弹窗显示")
            }
        }

        val customView = dialog.getCustomView()
        val radioGroup = customView.findViewById<RadioGroup>(R.id.radio_group)
        val btnCancel = customView.findViewById<MaterialButton>(R.id.btn_cancel)
        val btnConfirm = customView.findViewById<MaterialButton>(R.id.btn_confirm)

        val tastes = KaoPanHelper.getTasteList()
        if (tastes.isEmpty()) {
            AdminOperationLogger.e(
                action = "加满烤肠箱",
                page = AdminOperationLogger.PAGE_BOX,
                result = AdminOperationLogger.RESULT_FAIL,
                detail = "烤肠箱位=${boxPositionListText(selectedBoxes)}，失败原因=口味列表为空"
            )
            ToastUtils.showShort("口味列表为空，请先检查商品同步或网络状态")
            dialog.dismiss()
            return
        }

        tastes.forEachIndexed { index, taste ->
            val radioButton = RadioButton(requireContext()).apply {
                id = View.generateViewId()
                text = "${taste.productName}(${taste.tasteName})"
                textSize = 16f
                setTextColor(resources.getColorStateList(R.color.radio_text_selector, null))
                background = ContextCompat.getDrawable(requireContext(), R.drawable.radio_button_selector)
                buttonDrawable = null
                layoutParams = RadioGroup.LayoutParams(
                    RadioGroup.LayoutParams.MATCH_PARENT,
                    RadioGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 8.dpToPx(), 0, 8.dpToPx())
                }
                setPadding(16.dpToPx(), 12.dpToPx(), 16.dpToPx(), 12.dpToPx())
                isChecked = index == 0
            }
            radioGroup.addView(radioButton)
        }
        radioGroup.setOnCheckedChangeListener { _, _ ->
            resetAdminIdleTimer("加满烤肠箱口味弹窗选择口味")
        }

        btnCancel.setOnClickListener {
            resetAdminIdleTimer("加满烤肠箱口味弹窗取消")
            dialog.dismiss()
        }

        btnConfirm.setOnClickListener {
            resetAdminIdleTimer("加满烤肠箱口味弹窗确认")
            val selectedId = radioGroup.checkedRadioButtonId
            val selectedView = radioGroup.findViewById<RadioButton>(selectedId)
            val selectedIndex = radioGroup.indexOfChild(selectedView)
            if (selectedIndex < 0 || selectedIndex >= tastes.size) {
                AdminOperationLogger.e(
                    action = "加满烤肠箱",
                    page = AdminOperationLogger.PAGE_BOX,
                    result = AdminOperationLogger.RESULT_FAIL,
                    detail = "烤肠箱位=${boxPositionListText(selectedBoxes)}，失败原因=未选择有效口味"
                )
                ToastUtils.showShort("请先选择有效口味")
                return@setOnClickListener
            }

            val selectedTaste = tastes[selectedIndex]
            val boxRefillTargetNum = AppConfig.getBoxRefillTargetNum()
            AdminOperationLogger.i(
                action = "加满烤肠箱",
                page = AdminOperationLogger.PAGE_BOX,
                result = AdminOperationLogger.RESULT_START,
                detail = "烤肠箱位=${boxPositionListText(selectedBoxes)}，口味=${selectedTaste.productName}(${selectedTaste.tasteName})，" +
                    "加满目标库存=$boxRefillTargetNum"
            )

            for (box in selectedBoxes) {
                box.productId = selectedTaste.productId.toString()
                box.tasteCode = selectedTaste.tasteCode
                box.tasteName = "${selectedTaste.productName}(${selectedTaste.tasteName})"
                box.checkStatus = false
                box.num = boxRefillTargetNum
                box.cmdStatusTake = 0
            }

            showLoadingExt("设置中...")
            NetApi.updateStock(JSON.toJSONString(KaoPanHelper.getKaoPanBoxList())) { code, content ->
                dismissLoadingExt()
                if (code == 0) {
                    val json = JSON.parseObject(content)
                    if (json.getIntValue("code") == 200) {
                        KaoPanHelper.saveKaoPanBoxList(KaoPanHelper.getKaoPanBoxList())
                        kaoPanBoxAdapter.notifyDataSetChanged()
                        AdminOperationLogger.i(
                            action = "加满烤肠箱",
                            page = AdminOperationLogger.PAGE_BOX,
                            result = AdminOperationLogger.RESULT_DONE,
                            detail = "烤肠箱位=${boxPositionListText(selectedBoxes)}，口味=${selectedTaste.productName}(${selectedTaste.tasteName})，" +
                                "加满目标库存=$boxRefillTargetNum"
                        )
                        ToastUtils.showShort("已成功加满选中的烤肠箱")
                        dialog.dismiss()
                    } else {
                        AdminOperationLogger.w(
                            action = "加满烤肠箱",
                            page = AdminOperationLogger.PAGE_BOX,
                            result = AdminOperationLogger.RESULT_FAIL,
                            detail = "烤肠箱位=${boxPositionListText(selectedBoxes)}，口味=${selectedTaste.productName}(${selectedTaste.tasteName})，" +
                                "失败原因=${json.getString("msg")}"
                        )
                        ToastUtils.showShort(json.getString("msg"))
                    }
                } else {
                    AdminOperationLogger.w(
                        action = "加满烤肠箱",
                        page = AdminOperationLogger.PAGE_BOX,
                        result = AdminOperationLogger.RESULT_FAIL,
                        detail = "烤肠箱位=${boxPositionListText(selectedBoxes)}，口味=${selectedTaste.productName}(${selectedTaste.tasteName})，" +
                            "失败原因=接口请求失败"
                    )
                    ToastUtils.showShort("添加失败")
                }
            }
        }

        dialog.show()
    }

    private fun boxPositionListText(boxes: List<KaoPanBox>): String {
        return boxes.joinToString("、") { "${it.positionSn}号" }
    }

    /**
     * dp 转 px。
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
        refillCatalogRefreshJob?.cancel()
        _binding = null
    }
}
