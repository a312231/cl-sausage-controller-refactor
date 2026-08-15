package cn.niuyannet.kaochang.android.services

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BusinessCloseoutSourceTest {

    @Test
    fun poll_shouldCaptureAndHandleCloseoutBeforeOrdinaryEarlyReturns() {
        val poll = functionBlock(
            algorithmSource(),
            "private suspend fun pollKaoPanStatus()",
            "private fun settleMaturedZonesBeforeDispatch("
        )
        val capture = poll.indexOf("captureBusinessCloseoutBeforeEarlyReturns(")
        val faultReturn = poll.indexOf("if (config.errorStatus != AppConfigBean.ERROR_STATUS_NORMAL)")
        val processPending = poll.indexOf("processBusinessCloseoutPendingIfAny(")
        val businessHoursReturn = poll.indexOf("if (!isWithinBusinessHours)")

        assertTrue("营业结束请求必须在故障早退前捕获", capture >= 0 && capture < faultReturn)
        assertTrue("pending 必须在普通营业时间分支前接管调度", processPending > faultReturn)
        assertTrue("pending 接管后不能进入普通高低并发调度", processPending < businessHoursReturn)
    }

    @Test
    fun businessStartRecovery_shouldFenceOnPendingBeforeStartingBusiness() {
        val recover = functionBlock(
            algorithmSource(),
            "fun recoverBusinessStartIfNeeded(",
            "fun restoreRoastAlgorithmState()"
        )
        val pendingFence = recover.indexOf("businessCloseoutPendingRepository.isPending()")
        val startBusiness = recover.indexOf("KaoChangOperate.startBusinessHours(")

        assertTrue("启动恢复必须读取营业结束 pending", pendingFence >= 0)
        assertTrue("pending 栅栏必须早于自动营业恢复", startBusiness > pendingFence)
    }

    @Test
    fun closeout_shouldStopOnFirstDiscardFailureAndCommitLowBeforeClearingPending() {
        val closeout = functionBlock(
            algorithmSource(),
            "private suspend fun processBusinessCloseoutPendingIfAny(",
            "private fun finalizeBusinessCloseoutIfAllClear("
        )
        val discard = closeout.indexOf("KaoChangOperate.discardSausage(")
        val discardFailure = closeout.indexOf("if (!discarded)", discard)
        val returnAfterFailure = closeout.indexOf("return", discardFailure)

        assertTrue("收口必须逐盘调用现有丢弃链路", discard >= 0)
        assertTrue("每盘丢弃都必须检查 false", discardFailure > discard)
        assertTrue("首盘失败后必须立即停止本轮", returnAfterFailure > discardFailure)

        val finalize = functionBlock(
            algorithmSource(),
            "private fun finalizeBusinessCloseoutIfAllClear(",
            "private fun settleMaturedZonesBeforeDispatch("
        )
        val modeLow = finalize.indexOf("modeType = 1")
        val transitionClear = finalize.indexOf("transitionMode = 0")
        val residualClear = finalize.indexOf("highConcurrencyResidualDrain = 0")
        val configCommit = finalize.indexOf("AppConfig.saveAppConfigDurable(")
        val clearPending = finalize.indexOf("businessCloseoutPendingRepository.clearDurably()")

        assertTrue(modeLow >= 0 && transitionClear > modeLow && residualClear > transitionClear)
        assertTrue("必须先耐久提交低并发", configCommit > residualClear)
        assertTrue("只有低并发提交成功后才清 pending", clearPending > configCommit)
    }

    @Test
    fun closeout_shouldReuseExistingRecoveryEvidenceWithoutNewWaitLoop() {
        val source = algorithmSource()
        val closeout = functionBlock(
            source,
            "private suspend fun processBusinessCloseoutPendingIfAny(",
            "private fun finalizeBusinessCloseoutIfAllClear("
        )
        val discardGate = functionBlock(
            source,
            "private fun currentBusinessCloseoutDiscardGate(",
            "private suspend fun processBusinessCloseoutPendingIfAny("
        )
        val finalize = functionBlock(
            source,
            "private fun finalizeBusinessCloseoutIfAllClear(",
            "private fun settleMaturedZonesBeforeDispatch("
        )

        assertTrue(closeout.contains("currentBusinessCloseoutDiscardGate("))
        assertTrue(discardGate.contains("KaoChangOperate.isActionGroupRecoveryBlocked()"))
        assertTrue(discardGate.contains("KaoChangOperate.hasPendingEmergencyResetRecovery()"))
        assertTrue(closeout.contains("KaoChangOperate.maintenanceKaoPanIntentForUi()"))
        assertTrue(finalize.contains("KaoChangOperate.isActionGroupRecoveryBlocked()"))
        assertFalse("营业结束协调器不得新增等待循环", closeout.contains("while ("))
        assertFalse("营业结束协调器不得新增 delay", closeout.contains("delay("))
    }

    @Test
    fun closeout_shouldCommitRestUnderExistingLockAndPauseOnlyAfterFullGate() {
        val source = algorithmSource()
        val restGate = functionBlock(
            source,
            "private fun ensureBusinessCloseoutRestGate(",
            "private fun currentBusinessCloseoutDiscardGate("
        )
        val closeout = functionBlock(
            source,
            "private suspend fun processBusinessCloseoutPendingIfAny(",
            "private fun finalizeBusinessCloseoutIfAllClear("
        )
        val safetyGate = closeout.indexOf("currentBusinessCloseoutDiscardGate(")
        val pause = closeout.indexOf("KaoChangOperate.pauseBusiness()")

        assertTrue(restGate.contains("KaoChangOperate.withFaultHeatTransitionLock"))
        assertTrue(restGate.contains("SupplementPauseController.shouldPauseForOrder()"))
        assertTrue("停热必须晚于完整机械安全门禁", safetyGate >= 0 && pause > safetyGate)
        assertTrue("停热失败必须保留 pending 并结束本轮", closeout.contains("if (!KaoChangOperate.pauseBusiness())"))
    }

    @Test
    fun closeoutDiscard_shouldUseDedicatedLockedAdmissionAndContainOrdinaryException() {
        val algorithm = algorithmSource()
        val closeout = functionBlock(
            algorithm,
            "private suspend fun processBusinessCloseoutPendingIfAny(",
            "private fun finalizeBusinessCloseoutIfAllClear("
        )
        val operate = functionBlock(
            operateSource(),
            "suspend fun discardSausage(",
            "private fun isAutomaticMechanicalCommandStillAllowed("
        )
        val cancellation = closeout.indexOf("catch (cancelled: CancellationException)")
        val ordinary = closeout.indexOf("catch (error: Exception)")

        assertTrue(closeout.contains("businessCloseout = true"))
        assertTrue(operate.contains("isBusinessCloseoutDiscardStillAllowed"))
        assertTrue(operate.contains("admitDiscardCommandIntent("))
        assertTrue("CancellationException 必须继续抛出", cancellation >= 0 && closeout.indexOf("throw cancelled", cancellation) > cancellation)
        assertTrue("普通异常必须在收口层失败关闭", ordinary > cancellation)
    }

    @Test
    fun discard_shouldNotRefreshTemperatureForBusinessCloseout() {
        val discard = functionBlock(
            operateSource(),
            "suspend fun discardSausage(",
            "private fun isAutomaticMechanicalCommandStillAllowed("
        )
        val gate = discard.indexOf("shouldRefreshTemperatureAfterDiscard(businessCloseout)")
        val refresh = discard.indexOf("refreshTemperatureAfterStablePanMutation(")

        assertTrue("营业收口必须通过专用策略跳过温控刷新", gate >= 0)
        assertTrue("温控刷新必须位于营业收口策略门禁内", refresh > gate)
    }

    @Test
    fun closeoutDiscardAdmission_shouldUseLocalModbusSnapshotWithoutProbe() {
        val gate = functionBlock(
            operateSource(),
            "private fun isBusinessCloseoutDiscardStillAllowed(",
            "/**\n     * 读取烤盘温度"
        )

        assertTrue(gate.contains("VMModbusHelper.currentDiagnosisSnapshot().connected"))
        assertFalse("状态锁内不得主动探测 Modbus", gate.contains("VMModbusHelper.connectStatus()"))
    }

    @Test
    fun maintenancePanClear_shouldTakeOverBusinessCloseoutDiscardBeforeWaitingForIdle() {
        val operate = operateSource()
        val takeover = functionBlock(
            operate,
            "suspend fun takeOverBusinessCloseoutDiscardForManualPanClear(",
            "suspend fun confirmDiscardJournalRecovery("
        )
        assertTrue(takeover.contains("DiscardPhysicalResult.DISCARDED"))

        val maintenance = maintenanceSource()
        val clearAll = functionBlock(
            maintenance,
            "private fun performClearAll(",
            "private fun showConfirmDiscardAndResetPanDialog()"
        )
        val reset = functionBlock(
            maintenance,
            "private fun performDiscardAndResetPan()",
            "@SuppressLint(\"NotifyDataSetChanged\")"
        )

        assertTakeoverBeforeIdleWait(clearAll)
        assertTakeoverBeforeIdleWait(reset)
    }

    @Test
    fun maintenancePanClear_shouldFinalizePendingAsLowConcurrencyAfterPanClear() {
        val finalize = functionBlock(
            algorithmSource(),
            "fun finalizeBusinessCloseoutAfterManualPanClear()",
            "fun recoverBusinessStartIfNeeded("
        )
        assertTrue(finalize.contains("businessCloseoutPendingRepository.isPending()"))
        assertTrue(finalize.contains("KaoPanHelper.getKaoPanList().any { it.isHasSausage }"))
        assertTrue(finalize.contains("foldBusinessCloseoutAfterManualPanClear(config)"))
        val durableSave = finalize.indexOf("AppConfig.saveAppConfigDurable(config)")
        val clearPending = finalize.indexOf("businessCloseoutPendingRepository.clearDurably()")
        assertTrue("必须先耐久提交低并发再清 pending", durableSave >= 0 && clearPending > durableSave)

        val maintenance = maintenanceSource()
        val clearAll = functionBlock(
            maintenance,
            "private fun performClearAll(",
            "private fun showConfirmDiscardAndResetPanDialog()"
        )
        val reset = functionBlock(
            maintenance,
            "private fun performDiscardAndResetPan()",
            "@SuppressLint(\"NotifyDataSetChanged\")"
        )
        assertFinalizeAfterPanClear(clearAll)
        assertFinalizeAfterPanClear(reset)
    }

    @Test
    fun pauseBusiness_shouldRequireAllExistingWritesToSucceed() {
        val pause = functionBlock(
            operateSource(),
            "fun pauseBusiness()",
            "/**\n     * 营业时间开始"
        )

        assertTrue(pause.contains("Boolean"))
        assertTrue(pause.contains("area1.isSuccess && area2.isSuccess && area3.isSuccess"))
    }

    @Test
    fun legacyEndBusinessHours_shouldOnlyRequestDurableCloseout() {
        val endBusiness = functionBlock(
            operateSource(),
            "suspend fun endBusinessHours(",
            "/**\n     * 读取寄存器的值"
        )

        assertTrue(endBusiness.contains("KaoChangAlgorithm.requestBusinessCloseout("))
        assertFalse(endBusiness.contains("KaoChangAlgorithm.resetKaoPan("))
        assertFalse(endBusiness.contains("config.onlineStatus = 2"))
    }

    private fun algorithmSource(): String = sourceText(
        "src/main/java/cn/niuyannet/kaochang/android/services/KaoChangAlgorithm.kt"
    )

    private fun operateSource(): String = sourceText(
        "src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt"
    )

    private fun maintenanceSource(): String = sourceText(
        "src/main/java/cn/niuyannet/kaochang/android/fragments/MaintenanceFragment.kt"
    )

    private fun assertTakeoverBeforeIdleWait(block: String) {
        val takeover = block.indexOf("takeOverBusinessCloseoutDiscardIfNeeded(")
        val idleWait = block.indexOf("waitUntilMachineIdleOrTimeout(")
        assertTrue("人工接管营业收口残留必须早于机械空闲等待", takeover >= 0 && idleWait > takeover)
    }

    private fun assertFinalizeAfterPanClear(block: String) {
        val panClear = block.indexOf("KaoPanHelper.clearKaoPan()")
        val finalize = block.indexOf("finalizeBusinessCloseoutAfterManualPanClear()")
        assertTrue("人工清盘后必须完成营业收口低并发提交", panClear >= 0 && finalize > panClear)
    }

    private fun sourceText(relativePath: String): String {
        val candidates = listOf(File(relativePath), File("app/$relativePath"))
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到源码文件：$relativePath，cwd=${File(".").absolutePath}")
        return file.readText()
    }

    private fun functionBlock(source: String, start: String, end: String): String {
        val startIndex = source.indexOf(start)
        check(startIndex >= 0) { "找不到函数起点：$start" }
        val endIndex = source.indexOf(end, startIndex + start.length)
        check(endIndex > startIndex) { "找不到函数终点：$end" }
        return source.substring(startIndex, endIndex)
    }
}
