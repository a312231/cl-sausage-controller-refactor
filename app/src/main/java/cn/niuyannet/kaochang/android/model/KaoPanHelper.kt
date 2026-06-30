package cn.niuyannet.kaochang.android.model

import cn.niuyannet.kaochang.android.init.AppConfig.getAppConfig
import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.init.BusinessTime
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.model.bean.Taste
import cn.niuyannet.kaochang.android.services.BusinessTimeWindowHelper
import cn.niuyannet.kaochang.android.services.KaoPanAsyncSaveGeneration
import cn.niuyannet.kaochang.android.services.KaoPanCacheInitPolicy
import cn.niuyannet.kaochang.android.services.SourceBoxPositionPolicy
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionCodes
import cn.niuyannet.kaochang.android.utils.LogUtils
import cn.niuyannet.kaochang.android.utils.PreferenceUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale


/**
 * 烤盘与烤肠箱本地缓存辅助类。
 *
 * 负责烤盘、烤肠箱、口味列表的本地缓存与异步持久化。
 */
object KaoPanHelper {
    /**
     * 对象内部统一后台作用域。
     *
     * 烤盘和烤肠箱列表的保存不需要绑定页面生命周期，但也不应继续使用 GlobalScope。
     * 因此统一走对象级后台作用域，避免失控的全局协程。
     */
    private val helperScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val FILE_SP_URL = "file_kaopan_helper"
    private const val KEY_KAO_PAN_LIST = "key_kao_pan_list"
    private const val KEY_KAO_PAN_BOX_LIST = "key_kao_pan_box_list"
    private const val KEY_KAO_PAN_CONFIG = "key_kao_pan_config"
    private const val KEY_TASTE_LIST = "key_taste_list" // 添加口味列表的键
    private const val DEFAULT_BAKING_TIME_MINUTES = 40
    private const val DEFAULT_DISCARD_TIME_HOURS = 6
    private val kaoPanSaveGeneration = KaoPanAsyncSaveGeneration()
    /** 默认烤制时长，40 分钟。 */
    val bakingTime = 40 * 60 * 1000L

    /** 默认保温起始时间，实际进入保温时会重新赋值。 */
    val holdingTime = 0L

    /** 默认丢弃时长，6 小时后必须自动丢弃。 */
    val discardTime = 6 * 60 * 60 * 1000L

    /**
     * 自动补肠/调度口径下的可用库存。
     *
     * IR_PICK_FAILED 表示本地已跳过该箱自动取肠，物理库存 num 仍保留给维护和云端展示。
     */
    fun isSupplyBoxAvailableForAutoSupplement(box: KaoPanBox): Boolean {
        return supplyBoxAutoSupplementExclusionReasons(box).isEmpty()
    }

    internal fun supplyBoxAutoSupplementExclusionReasons(box: KaoPanBox): List<String> {
        val reasons = mutableListOf<String>()
        if (getAppConfig().supplementEnabled == AppConfigBean.SUPPLEMENT_ENABLED_NO) {
            reasons += "supplementEnabled=0"
        }
        if (box.num <= 0) {
            reasons += "num<=0"
        }
        if (box.tasteCode.isNullOrBlank()) {
            reasons += "tasteCode空"
        }
        if (box.cmdStatusTake == AtomicActionCodes.IR_PICK_FAILED) {
            reasons += "IR_PICK_FAILED"
        }
        if (!SourceBoxPositionPolicy.isValidSourceBox(box.positionSn)) {
            reasons += "非法源箱位"
        }
        return reasons
    }

    fun availableSupplyNumForAutoSupplement(box: KaoPanBox): Int {
        return if (isSupplyBoxAvailableForAutoSupplement(box)) box.num else 0
    }

    /** 当前口味目录缓存。 */
    private var tasteList = mutableListOf<Taste>()

    /** 当前烤盘状态缓存。 */
    private var kaoPanList = mutableListOf<KaoPan>()

    /** 当前烤肠箱状态缓存。 */
    private var kaoPanBoxList = mutableListOf<KaoPanBox>()

    /**
     * 统一可售判定。
     *
     * pendingDiscardPositions（待丢弃烤盘位）里的烤盘即使 status=2（保温可售）
     * 且 holdingTime>0，也不能再被二维码、订单、云端快照视为可售。
     */
    fun isSellable(kaoPan: KaoPan, pendingDiscardPositions: Collection<Int> = emptyList()): Boolean {
        return kaoPan.isHasSausage &&
            kaoPan.status == 2 &&
            kaoPan.holdingTime > 0L &&
            kaoPan.positionSn !in pendingDiscardPositions
    }

    fun pendingDiscardPositionsOrEmpty(): Collection<Int> {
        return runCatching { getAppConfig().pendingDiscardPositions }.getOrDefault(emptyList())
    }

    /**
     * 初始化烤盘配置。
     *
     * 优先读取本地缓存；若本地还没有保存过，则按默认工位顺序生成 33 个烤盘并立即落盘。
     */
    fun init() {
        if (kaoPanList.isNotEmpty()) {
            return
        }
        val savedKaoPanList = PreferenceUtils.getListPreference(FILE_SP_URL, KEY_KAO_PAN_LIST, KaoPan::class.java)
        val config = getAppConfig()
        val bakingTimeMinutes = config.bakingTime.takeIf { it > 0 } ?: DEFAULT_BAKING_TIME_MINUTES
        val discardTimeHours = config.discardTime.takeIf { it > 0 } ?: DEFAULT_DISCARD_TIME_HOURS
        val defaultPans = if (savedKaoPanList.isNullOrEmpty()) {
            createDefaultKaoPanList(bakingTimeMinutes, discardTimeHours)
        } else {
            emptyList()
        }
        kaoPanList = KaoPanCacheInitPolicy.resolveInitialPans(
            currentPans = kaoPanList,
            storedPans = savedKaoPanList,
            defaultPans = defaultPans
        )
        if (savedKaoPanList.isNullOrEmpty()) {
            saveKaoPanList(kaoPanList)
        }
    }

    fun resetKaoPanCacheAfterManualClear() {
        kaoPanSaveGeneration.advance()
        val config = getAppConfig()
        val bakingTimeMinutes = config.bakingTime.takeIf { it > 0 } ?: DEFAULT_BAKING_TIME_MINUTES
        val discardTimeHours = config.discardTime.takeIf { it > 0 } ?: DEFAULT_DISCARD_TIME_HOURS
        kaoPanList = KaoPanCacheInitPolicy.resolveResetPansAfterManualClear(
            createDefaultKaoPanList(bakingTimeMinutes, discardTimeHours)
        )
        saveKaoPanListSync(kaoPanList)
    }

    internal fun createDefaultKaoPanList(
        bakingTimeMinutes: Int = DEFAULT_BAKING_TIME_MINUTES,
        discardTimeHours: Int = DEFAULT_DISCARD_TIME_HOURS
    ): MutableList<KaoPan> {
        val normalizedBakingTimeMinutes = bakingTimeMinutes.coerceAtLeast(0)
        val normalizedDiscardTimeHours = discardTimeHours.coerceAtLeast(0)
        val positions = mutableListOf<Int>()
        for (i in 1..31 step 3) {
            positions.add(i)
        }

        for (i in 2..32 step 3) {
            positions.add(i)
        }

        for (i in 3..33 step 3) {
            positions.add(i)
        }

        return mutableListOf<KaoPan>().apply {
            for (i in 0 until 33) {
                val positionSn = positions[i]
                val region = when (positionSn) {
                    in 1..9 -> 1
                    in 10..12 -> 4
                    in 13..21 -> 2
                    in 22..24 -> 4
                    else -> 3
                }

                val kaoPan = KaoPan()
                kaoPan.id = i
                kaoPan.positionSn = positionSn
                kaoPan.positionRegion = region
                kaoPan.startTime = 0L
                kaoPan.bakingTime = normalizedBakingTimeMinutes * 60L * 1000L
                kaoPan.holdingTime = holdingTime
                kaoPan.closeTime = normalizedDiscardTimeHours * 60L * 60L * 1000L
                kaoPan.taste = null
                kaoPan.temperature = 0
                kaoPan.status = 0
                kaoPan.isHasSausage = false
                kaoPan.isHasGrilling = false
                kaoPan.cmdValueMove = positionSn
                kaoPan.cmdValueTake = positionSn + 100
                kaoPan.cmdStatusMove = 0
                kaoPan.cmdStatusTake = 0
                kaoPan.cmdValueDiscard = positionSn + 300
                kaoPan.cmdStatusDiscard = 0
                add(kaoPan)
            }
        }
    }

    internal fun restoreKaoPanBoxList(savedKaoPanBoxList: List<KaoPanBox>?): MutableList<KaoPanBox> {
        return savedKaoPanBoxList?.toMutableList() ?: mutableListOf()
    }

    /**
     * 重置烤盘
     */
    fun resetKaoPan() {
        val config = getAppConfig()
        val bakingTimeMinutes = config.bakingTime
        val discardTimeHours = config.discardTime
        for (kaoPan in kaoPanList) {
            kaoPan.startTime = 0L
            kaoPan.bakingTime = bakingTimeMinutes * 60L * 1000L
            kaoPan.holdingTime = holdingTime
            kaoPan.closeTime = discardTimeHours * 60L * 60L * 1000L
            kaoPan.taste = null
            kaoPan.temperature = 0
            kaoPan.status = 0
            kaoPan.isHasSausage = false
            kaoPan.isHasGrilling = false
            kaoPan.cmdStatusMove = 0
            kaoPan.cmdStatusTake = 0
            kaoPan.cmdStatusDiscard = 0
        }
        // 保存更新后的烤盘列表
        saveKaoPanList(kaoPanList)
    }

    /**
     * 获取烤盘列表
     */
    fun getKaoPanList():List<KaoPan>{
        if (kaoPanList.size>0){
            return kaoPanList
        }
        val savedKaoPanList = PreferenceUtils.getListPreference(FILE_SP_URL, KEY_KAO_PAN_LIST, KaoPan::class.java)
        this.kaoPanList = if (!savedKaoPanList.isNullOrEmpty()) {
            savedKaoPanList.toMutableList()
        } else {
            createDefaultKaoPanList()
        }
        return kaoPanList
    }

    /**
     * 获取烤肠箱列表
     */
    fun getKaoPanBoxList():List<KaoPanBox>{
        if (this.kaoPanBoxList.size>0){
            return this.kaoPanBoxList
        }
        val savedKaoPanBoxList = PreferenceUtils.getListPreference(FILE_SP_URL, KEY_KAO_PAN_BOX_LIST, KaoPanBox::class.java)
        this.kaoPanBoxList = restoreKaoPanBoxList(savedKaoPanBoxList)
        if (this.kaoPanBoxList.isEmpty()) {
            LogUtils.w("本地烤肠箱缓存为空，等待云端库存同步后恢复；首次安装或清空数据后可能出现")
        }
        return this.kaoPanBoxList
    }
    /**
     * 获取口味列表
     */
    fun getTasteList():List<Taste>{
        if (tasteList.size>0){
            return tasteList
        }
        val savedTasteList = PreferenceUtils.getListPreference(FILE_SP_URL, KEY_TASTE_LIST, Taste::class.java)
        tasteList = savedTasteList?.toMutableList() ?: mutableListOf()
        if (tasteList.isEmpty()) {
            LogUtils.w("本地口味缓存为空，等待云端产品列表同步后恢复；首次安装或清空数据后可能出现")
        }
        return tasteList;
    }
    /**
     * 保存烤盘列表
     */
    fun saveKaoPanList(list: List<KaoPan>) {
        val capturedGeneration = kaoPanSaveGeneration.current()
        helperScope.launch {
            if (kaoPanSaveGeneration.isCurrent(capturedGeneration)) {
                saveKaoPanListSync(list)
            } else {
                LogUtils.w("跳过过期烤盘异步保存：保存任务 generation=$capturedGeneration，当前已清空重建")
            }
        }
    }

    fun saveKaoPanListSync(list: List<KaoPan>) {
        kaoPanList = list.toMutableList()
        PreferenceUtils.saveListPreference(FILE_SP_URL, KEY_KAO_PAN_LIST, kaoPanList)
    }


    /**
     * 保存烤箱列表
     */
    fun saveKaoPanBoxList(list: List<KaoPanBox>) {
        helperScope.launch {
            saveKaoPanBoxListSync(list)
        }
    }

    fun saveKaoPanBoxListSync(list: List<KaoPanBox>) {
        kaoPanBoxList = list.toMutableList()
        PreferenceUtils.saveListPreference(FILE_SP_URL, KEY_KAO_PAN_BOX_LIST, kaoPanBoxList)
    }


    /**
     * 保存口味列表
     */
    fun saveTasteList(list: List<Taste>) {
        tasteList = list.toMutableList()
        PreferenceUtils.saveListPreference(FILE_SP_URL, KEY_TASTE_LIST, tasteList)
    }

    /**
     * 按最新配置统一回刷所有烤盘的保温超时阈值。
     *
     * 这里只更新 closeTime，不改 holdingTime，避免把“已经保温了多久”重新计时。
     * 后台修改丢弃时间后，当前保温中的烤肠也应立即按新阈值执行自动丢弃。
     *
     * @param discardTimeHours 丢弃时间，单位小时
     * @return 实际回刷的烤盘数量
     */
    fun refreshDiscardCloseTime(discardTimeHours: Int): Int {
        val normalizedHours = discardTimeHours.coerceAtLeast(0).toLong()
        val closeTimeMillis = normalizedHours * 60L * 60L * 1000L
        val currentPans = getKaoPanList()
        var correctedCount = 0
        currentPans.forEach { kaoPan ->
            if (kaoPan.closeTime != closeTimeMillis) {
                kaoPan.closeTime = closeTimeMillis
                correctedCount += 1
            }
        }
        if (correctedCount > 0) {
            saveKaoPanList(currentPans)
        }
        return correctedCount
    }

    fun refreshActiveBakingTime(pans: List<KaoPan>, bakingTimeMinutes: Int): Int {
        val normalizedMinutes = bakingTimeMinutes.coerceAtLeast(0).toLong()
        val bakingTimeMillis = normalizedMinutes * 60L * 1000L
        var correctedCount = 0
        pans.forEach { kaoPan ->
            if (
                kaoPan.isHasSausage &&
                kaoPan.status == 1 &&
                kaoPan.startTime > 0L &&
                kaoPan.bakingTime != bakingTimeMillis
            ) {
                kaoPan.bakingTime = bakingTimeMillis
                correctedCount += 1
            }
        }
        return correctedCount
    }

    fun refreshActiveBakingTime(bakingTimeMinutes: Int): Int {
        val currentPans = getKaoPanList()
        val correctedCount = refreshActiveBakingTime(currentPans, bakingTimeMinutes)
        if (correctedCount > 0) {
            saveKaoPanList(currentPans)
        }
        return correctedCount
    }

    fun normalizeKaoPanTasteMetadata(): Boolean {
        val currentPans = getKaoPanList()
        var changed = false
        currentPans.forEach { kaoPan ->
            if (!kaoPan.isHasSausage || kaoPan.taste == null) {
                return@forEach
            }
            val normalizedTaste = normalizeTasteMetadata(kaoPan.taste)
            if (normalizedTaste == null) {
                return@forEach
            }
            if (!isSameTasteMetadata(kaoPan.taste, normalizedTaste)) {
                kaoPan.taste = normalizedTaste
                changed = true
            }
        }
        if (changed) {
            saveKaoPanList(currentPans)
        }
        return changed
    }

    fun normalizeKaoPanBoxTasteMetadata(): Boolean {
        val currentBoxes = getKaoPanBoxList()
        val changedCount = normalizeKaoPanBoxTasteMetadata(currentBoxes, getTasteList())
        if (changedCount > 0) {
            saveKaoPanBoxList(currentBoxes)
        }
        return changedCount > 0
    }

    internal fun normalizeKaoPanBoxTasteMetadata(
        boxes: List<KaoPanBox>,
        tastes: List<Taste>
    ): Int {
        if (boxes.isEmpty() || tastes.isEmpty()) {
            return 0
        }
        var changedCount = 0
        boxes.forEach { box ->
            val productId = box.productId?.toIntOrNull()?.takeIf { it > 0 } ?: return@forEach
            val tasteCode = normalizeTasteCode(box.tasteCode)
            if (tasteCode.isBlank()) {
                return@forEach
            }
            val resolvedTaste = tastes.firstOrNull {
                it.productId == productId && normalizeTasteCode(it.tasteCode) == tasteCode
            } ?: return@forEach
            val normalizedTasteName = "${resolvedTaste.productName.orEmpty()}(${resolvedTaste.tasteName.orEmpty()})"
            if (box.tasteName != normalizedTasteName) {
                box.tasteName = normalizedTasteName
                changedCount += 1
            }
        }
        return changedCount
    }

    fun buildTasteFromBox(kaoPanBox: KaoPanBox, fallbackTaste: Taste? = null): Taste {
        val normalizedByCatalog = findTasteFromCatalog(
            productId = kaoPanBox.productId?.toIntOrNull(),
            tasteCode = kaoPanBox.tasteCode,
            tasteName = kaoPanBox.tasteName,
            productName = fallbackTaste?.productName
        )
        return mergeTasteMetadata(
            baseTaste = fallbackTaste,
            resolvedTaste = normalizedByCatalog,
            fallbackProductId = kaoPanBox.productId?.toIntOrNull(),
            fallbackTasteCode = kaoPanBox.tasteCode,
            fallbackTasteName = kaoPanBox.tasteName,
            fallbackProductName = fallbackTaste?.productName
        )
    }

    fun copyResolvedTaste(sourceTaste: Taste?): Taste? {
        return normalizeTasteMetadata(sourceTaste)
    }

    private fun normalizeTasteMetadata(sourceTaste: Taste?): Taste? {
        if (sourceTaste == null) {
            return null
        }
        val resolvedTaste = findTasteFromCatalog(
            productId = sourceTaste.productId.takeIf { it > 0 },
            tasteId = sourceTaste.tasteId.takeIf { it > 0 },
            tasteCode = sourceTaste.tasteCode,
            tasteName = sourceTaste.tasteName,
            productName = sourceTaste.productName
        )
        return mergeTasteMetadata(
            baseTaste = sourceTaste,
            resolvedTaste = resolvedTaste,
            fallbackProductId = sourceTaste.productId.takeIf { it > 0 },
            fallbackTasteId = sourceTaste.tasteId.takeIf { it > 0 },
            fallbackTasteCode = sourceTaste.tasteCode,
            fallbackTasteName = sourceTaste.tasteName,
            fallbackProductName = sourceTaste.productName
        )
    }

    private fun mergeTasteMetadata(
        baseTaste: Taste?,
        resolvedTaste: Taste?,
        fallbackProductId: Int? = null,
        fallbackTasteId: Int? = null,
        fallbackTasteCode: String? = null,
        fallbackTasteName: String? = null,
        fallbackProductName: String? = null
    ): Taste {
        return Taste().apply {
            tasteId = resolvedTaste?.tasteId
                ?: fallbackTasteId
                ?: baseTaste?.tasteId
                ?: 0
            tasteCode = resolvedTaste?.tasteCode
                ?: fallbackTasteCode
                ?: baseTaste?.tasteCode
                ?: ""
            tasteName = resolvedTaste?.tasteName
                ?: fallbackTasteName
                ?: baseTaste?.tasteName
                ?: ""
            productId = resolvedTaste?.productId
                ?: fallbackProductId
                ?: baseTaste?.productId
                ?: 0
            productName = resolvedTaste?.productName
                ?: fallbackProductName
                ?: baseTaste?.productName
                ?: ""
        }
    }

    private fun findTasteFromCatalog(
        productId: Int? = null,
        tasteId: Int? = null,
        tasteCode: String? = null,
        tasteName: String? = null,
        productName: String? = null
    ): Taste? {
        val currentTasteList = getTasteList()
        if (currentTasteList.isEmpty()) {
            return null
        }

        val normalizedTasteCode = normalizeTasteCode(tasteCode)
        val normalizedTasteName = normalizeLabel(tasteName)
        val normalizedProductName = normalizeLabel(productName)

        if (productId != null && productId > 0) {
            return currentTasteList.firstOrNull {
                it.productId == productId &&
                    tasteId != null && tasteId > 0 && it.tasteId == tasteId
            } ?: currentTasteList.firstOrNull {
                it.productId == productId &&
                    normalizedTasteCode.isNotBlank() && normalizeTasteCode(it.tasteCode) == normalizedTasteCode
            } ?: currentTasteList.firstOrNull {
                it.productId == productId &&
                    normalizedTasteName.isNotBlank() && normalizeLabel(it.tasteName) == normalizedTasteName
            } ?: currentTasteList.firstOrNull {
                it.productId == productId &&
                    normalizedProductName.isNotBlank() && normalizeLabel(it.productName) == normalizedProductName &&
                    normalizedTasteName.isNotBlank() && normalizeLabel(it.tasteName) == normalizedTasteName
            }
        }

        return currentTasteList.firstOrNull {
            productId != null && productId > 0 && it.productId == productId &&
                tasteId != null && tasteId > 0 && it.tasteId == tasteId
        } ?: currentTasteList.firstOrNull {
            productId != null && productId > 0 && it.productId == productId &&
                normalizedTasteCode.isNotBlank() && normalizeTasteCode(it.tasteCode) == normalizedTasteCode
        } ?: currentTasteList.firstOrNull {
            tasteId != null && tasteId > 0 && it.tasteId == tasteId
        } ?: currentTasteList.firstOrNull {
            normalizedTasteCode.isNotBlank() && normalizeTasteCode(it.tasteCode) == normalizedTasteCode
        } ?: currentTasteList.firstOrNull {
            normalizedTasteName.isNotBlank() && normalizeLabel(it.tasteName) == normalizedTasteName &&
                (productId == null || productId <= 0 || it.productId == productId)
        } ?: currentTasteList.firstOrNull {
            normalizedProductName.isNotBlank() && normalizeLabel(it.productName) == normalizedProductName &&
                normalizedTasteName.isNotBlank() && normalizeLabel(it.tasteName) == normalizedTasteName
        }
    }

    private fun isSameTasteMetadata(left: Taste?, right: Taste?): Boolean {
        if (left == null || right == null) {
            return left == right
        }
        return left.tasteId == right.tasteId &&
            normalizeTasteCode(left.tasteCode) == normalizeTasteCode(right.tasteCode) &&
            normalizeLabel(left.tasteName) == normalizeLabel(right.tasteName) &&
            left.productId == right.productId &&
            normalizeLabel(left.productName) == normalizeLabel(right.productName)
    }

    private fun normalizeTasteCode(code: String?): String {
        return code?.trim()?.uppercase(Locale.getDefault()) ?: ""
    }

    private fun normalizeLabel(label: String?): String {
        return label
            ?.trim()
            ?.replace(Regex("\\s+"), "")
            ?.uppercase(Locale.getDefault())
            ?: ""
    }

    /**
     * 根据位置获取烤盘
     */
    fun getKaoPanByPosition(positionSn: Int): KaoPan? {
        return kaoPanList.find { it.positionSn == positionSn }
    }

    /**
     * 清除所有数据
     */
    fun clearAllData() {
        PreferenceUtils.deletePreference(FILE_SP_URL)
    }

    /**
     * 清空烤盘
     */
    fun clearKaoPan() {
        PreferenceUtils.deletePreference(FILE_SP_URL,KEY_KAO_PAN_LIST)
        resetKaoPanCacheAfterManualClear()
    }

    /**
     * 低并发，高并发初烤肠初始化口味分配
     * listKaoPan 烤盘
     * listPanBox 烤箱
     * list_size 烤盘工位数
     */
    fun computeKaoPamRatio(listPanBox: List<KaoPanBox>, list_size: Int): MutableMap<String, Int> {
        logAutoSupplementRatioInput("computeKaoPamRatio", listPanBox, list_size)
        // 1. 统计每种口味总数量
        val flavorTypes2 = listPanBox
            .filter { isSupplyBoxAvailableForAutoSupplement(it) }
            .groupBy { it.tasteCode!! }
            .mapValues { (_, items) -> items.sumOf { it.num } }
        logAutoSupplementRatioEffectiveStock("computeKaoPamRatio", flavorTypes2)

        if (list_size <= 0 || flavorTypes2.isEmpty()) {
            logAutoSupplementRatioAllocation("computeKaoPamRatio", list_size, emptyMap())
            return mutableMapOf()
        }
        //烤箱库存总数
        val totalCount = flavorTypes2.values.sum().toFloat()
        if (totalCount <= 0f) {
            logAutoSupplementRatioAllocation("computeKaoPamRatio", list_size, emptyMap())
            return mutableMapOf()
        }

        // 3. 计算比例并排序；口味数超过目标工位数时，只保留库存最多的前 N 个口味。
        val sortedFlavors = selectAssignableFlavors("computeKaoPamRatio", flavorTypes2, list_size, totalCount)

        // 4. 按比例计算“理论分配值”
        val rawAllocations = sortedFlavors.associate { (flavor, ratio) ->
            flavor to (list_size * ratio)
        }

        // 5. 取整，并保证每种至少 1 个
        val allocation = rawAllocations.mapValues { (_, value) ->
            maxOf(1, value.toInt())
        }.toMutableMap()

        // 6. 修正总数，使分配总和严格等于 list_size
        var currentSum = allocation.values.sum()

        if (currentSum < list_size) {
            // 6.1 不足：按小数部分从大到小补
            val sortedByRemainder = rawAllocations
                .map { (flavor, value) ->
                    flavor to (value - value.toInt())
                }
                .sortedByDescending { it.second }

            var idx = 0
            while (currentSum < list_size) {
                val flavor = sortedByRemainder[idx % sortedByRemainder.size].first
                allocation[flavor] = allocation[flavor]!! + 1
                currentSum++
                idx++
            }

        } else if (currentSum > list_size) {
            // 6.2 超出：从分配最多的口味开始减，但不能减到 < 1
            val sortedByCount = allocation.entries
                .sortedByDescending { it.value }
                .map { it.key }

            var idx = 0
            while (currentSum > list_size) {
                if (idx >= sortedByCount.size) {
                    break
                }
                val flavor = sortedByCount[idx]
                if (allocation[flavor]!! > 1) {
                    allocation[flavor] = allocation[flavor]!! - 1
                    currentSum--
                }
                idx++
            }
        }

        logAutoSupplementRatioAllocation("computeKaoPamRatio", list_size, allocation)
        return allocation
    }


    /**
     * (比例计算有问题，已弃用)
     * 低并发，高并发初烤肠初始化口味分配
     * listKaoPan 烤盘
     * listPanBox 烤箱
     * list_size 烤盘工位数
     */
    fun computeKaoPamRatio_old(listPanBox: List<KaoPanBox>, list_size: Int): MutableMap<String, Int> {
        // 1. 统计每种口味总数量
        val flavorTypes2 = listPanBox
            .filter { isSupplyBoxAvailableForAutoSupplement(it) && !it.tasteCode.isNullOrBlank() }
            .groupBy { it.tasteCode!! }
            .mapValues { (_, items) -> items.sumOf { it.num } }

        // 2. 旧算法不再用于实际初始化；保留给历史对比，不在这里输出业务告警。
        if (flavorTypes2.size > list_size) {
            return mutableMapOf()
        }
        if (list_size <= 0 || flavorTypes2.isEmpty()) {
            return mutableMapOf()
        }
        //烤箱库存总数
        val totalCount = flavorTypes2.values.sum().toFloat()
        if (totalCount <= 0f) {
            return mutableMapOf()
        }

        // 3. 计算比例并排序
        val sortedFlavors = flavorTypes2.map { (flavor, count) ->
            flavor to count / totalCount
        }.sortedByDescending { (_, ratio) -> ratio }

        // 4. 初始化每个口味至少1个位置
        val allocation = sortedFlavors.associate { (flavor, _) ->
            flavor to 1
        }.toMutableMap()

        // 5. 按比例分配剩余位置
        var remainingPositions = list_size - allocation.size
        if (remainingPositions > 0) {
            // 计算每个口味的额外分配
            val extraAllocations = sortedFlavors.map { (flavor, ratio) ->
                flavor to (remainingPositions * ratio).toInt()
            }.toMap()

            // 应用额外分配
            extraAllocations.forEach { (flavor, extra) ->
                allocation[flavor] = allocation[flavor]!! + extra
            }

            // 处理剩余位置（由于取整可能未完全分配）
            if(totalCount>0){
                var unallocated = remainingPositions - extraAllocations.values.sum()
                var index = 0
                while (unallocated > 0) {
                    val flavor = sortedFlavors[index].first
                    allocation[flavor] = allocation[flavor]!! + 1
                    unallocated--
                    index = (index + 1) % sortedFlavors.size
                }
            }
        }

        return allocation
    }



    /**
     * 低转高并发打标签初始化
     * listKaoPan 烤盘
     * listPanBox 烤箱
     * list_size 烤盘工位数
     */
    fun computeHighKaoPamRatio(listPanBox: List<KaoPanBox>, list_size: Int): MutableMap<String, Int> {
        logAutoSupplementRatioInput("computeHighKaoPamRatio", listPanBox, list_size)
        // 1. 统计每种口味总数量
        val flavorTypes2 = listPanBox
            .filter { isSupplyBoxAvailableForAutoSupplement(it) }
            .groupBy { it.tasteCode!! }
            .mapValues { (_, items) -> items.sumOf { it.num } }
        logAutoSupplementRatioEffectiveStock("computeHighKaoPamRatio", flavorTypes2)

        if (list_size <= 0 || flavorTypes2.isEmpty()) {
            logAutoSupplementRatioAllocation("computeHighKaoPamRatio", list_size, emptyMap())
            return mutableMapOf()
        }
        //烤箱库存总数
        val totalCount = flavorTypes2.values.sum().toFloat()
        if (totalCount <= 0f) {
            logAutoSupplementRatioAllocation("computeHighKaoPamRatio", list_size, emptyMap())
            return mutableMapOf()
        }

        // 3. 计算比例并排序；口味数超过目标工位数时，只保留库存最多的前 N 个口味。
        val sortedFlavors = selectAssignableFlavors("computeHighKaoPamRatio", flavorTypes2, list_size, totalCount)

        // 4. 初始化每个口味
        val allocation = mutableMapOf<String, Int>()

        // 5. 按比例分配剩余位置
        var remainingPositions = list_size
        if (remainingPositions > 0) {
            // 计算每个口味的额外分配
            val extraAllocations = sortedFlavors.map { (flavor, ratio) ->
                flavor to (remainingPositions * ratio).toInt()
            }.toMap()

            // 处理剩余位置（由于取整可能未完全分配）
            var unallocated = remainingPositions - extraAllocations.values.sum()
            var index = 0
            while (unallocated > 0) {
                // 循环遍历所有口味，按比例高低顺序分配
                val flavor = sortedFlavors[index % sortedFlavors.size].first
                // 增加该口味一个工位
                allocation[flavor] = (allocation[flavor] ?: 0) + 1
                unallocated--
                index++
            }
            // 将额外分配的结果合并到总分配中
            extraAllocations.forEach { (flavor, count) ->
                allocation[flavor] = (allocation[flavor] ?: 0) + count
            }
        }

        logAutoSupplementRatioAllocation("computeHighKaoPamRatio", list_size, allocation)
        return allocation
    }

    private fun selectAssignableFlavors(
        source: String,
        stockByTaste: Map<String, Int>,
        targetPanCount: Int,
        totalCount: Float
    ): List<Pair<String, Float>> {
        val sortedByStock = stockByTaste.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        if (sortedByStock.size > targetPanCount) {
            val dropped = sortedByStock
                .drop(targetPanCount)
                .joinToString(",") { "${it.key}=${it.value}" }
            safeLogInfo("【补肠】可用口味数超过目标烤盘数，按库存从高到低取前${targetPanCount}种：source=$source，被暂不分配口味=$dropped")
        }
        return sortedByStock
            .take(targetPanCount)
            .map { it.key to it.value / totalCount }
    }

    private fun logAutoSupplementRatioInput(
        source: String,
        boxes: List<KaoPanBox>,
        targetPanCount: Int
    ) {
        val rawBoxes = boxes
            .sortedBy { it.positionSn }
            .joinToString("；") { box ->
                val validSourceBox = SourceBoxPositionPolicy.isValidSourceBox(box.positionSn)
                val exclusionReasons = supplyBoxAutoSupplementExclusionReasons(box)
                    .joinToString("|")
                    .ifBlank { "无" }
                "箱${box.positionSn}(num=${box.num},tasteCode=${box.tasteCode ?: "null"}," +
                    "cmdStatusTake=${box.cmdStatusTake},validSourceBox=$validSourceBox," +
                    "排除原因=$exclusionReasons)"
            }
            .ifBlank { "空" }
        safeLogInfo("【补肠】比例计算入口：source=$source，目标烤盘数=$targetPanCount，原始箱列表=$rawBoxes")
    }

    private fun logAutoSupplementRatioEffectiveStock(
        source: String,
        effectiveStockByTaste: Map<String, Int>
    ) {
        val effectiveStock = effectiveStockByTaste.entries
            .sortedBy { it.key }
            .joinToString(",") { (tasteCode, count) -> "$tasteCode=$count" }
            .ifBlank { "空" }
        safeLogInfo("【补肠】比例计算有效库存：source=$source，按口味库存=$effectiveStock")
    }

    private fun logAutoSupplementRatioAllocation(
        source: String,
        targetPanCount: Int,
        allocation: Map<String, Int>
    ) {
        val allocationText = allocation.entries
            .sortedBy { it.key }
            .joinToString(",") { (tasteCode, count) -> "$tasteCode=$count" }
            .ifBlank { "空" }
        safeLogInfo("【补肠】比例计算结果：source=$source，目标烤盘数=$targetPanCount，分配=$allocationText")
    }

    private fun safeLogInfo(message: String) {
        runCatching { LogUtils.i(message) }
    }



    /**
     * 判断当前是否已进入营业结束前 minute 分钟的收口窗口。
     */
    fun isBeforeBusinessHours(businessTime: BusinessTime, minute: Int): Boolean {
        return BusinessTimeWindowHelper.isBeforeCloseWindow(
            BusinessTimeWindowHelper.currentTimeString(),
            businessTime,
            minute
        )
    }






}
