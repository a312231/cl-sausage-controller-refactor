package cn.niuyannet.kaochang.android.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HighCurrentTrafficModeSourceTest {

    @Test
    fun highInitialization_shouldUseTypedCommonSupplementEntry() {
        val source = sourceText()
        val resume = functionBlock(source, "private suspend fun resumeHighInitialization(", "/**\n     * 低并发 -> 高并发过渡")
        val initialize = functionBlock(source, "private suspend fun initHighTrafficMode(", "/**\n     * 向 25~33")

        assertTrue(resume.contains("batchTypeOverride = SupplementBatchType.HIGH_INITIALIZATION_1_21"))
        assertTrue(initialize.contains("batchTypeOverride = SupplementBatchType.HIGH_INITIALIZATION_1_21"))
    }

    @Test
    fun newHighHeatBatchEntrypoints_shouldAllowValidStockLimitedPlanBeforeHeating() {
        val source = sourceText()
        val transition = functionBlock(source, "private suspend fun lowTrafficModeTransition(", "/**\n     * 低转高期间")
        val stable = functionBlock(source, "private suspend fun addHighHeatSausage(", "private suspend fun resumeIncompleteHighHeatSupplement(")

        listOf(transition, stable).forEach { block ->
            val guard = block.indexOf("HighHeatSupplementResumePlanner.isValidInitialPlan(")
            val heating = block.indexOf("KaoChangOperate.heating(3)")
            assertTrue("新批次必须先检查非空且位置关系安全的实际目标", guard >= 0)
            assertTrue("实际目标安全检查必须早于三区加热", heating > guard)
            assertFalse("不能继续要求库存凑满完整九目标", block.contains("isCompleteInitialPlan("))
        }
    }

    @Test
    fun highHeatResumeEntrypoints_shouldUsePersistedBatchStateAfterRestart() {
        val source = sourceText()
        val stable = functionBlock(source, "suspend fun highTrafficMode(", "suspend fun drainHighConcurrencyResidual(")
        val transition = functionBlock(source, "private suspend fun lowTrafficModeTransition(", "/**\n     * 低转高期间")

        listOf(stable, transition).forEach { block ->
            val persistedEvidence = block.indexOf("classifyPersistedHighHeatBatch(")
            val pendingHandler = block.indexOf("handlePendingSupplementBatchIfAny(")
            val pendingSafetyGate = block.indexOf("shouldBlockPendingHighHeatRecovery(")
            val resumeDecision = block.indexOf("shouldResumeIncompleteHighHeatBatch(", persistedEvidence)
            val evidenceArgument = block.indexOf("persistedBatchState =", resumeDecision)

            assertTrue("两个入口都必须分类持久化批次证据", persistedEvidence >= 0)
            assertTrue("持久化批次分类必须早于 pending handler", pendingHandler > persistedEvidence)
            assertTrue("pending handler 前必须使用不可信意图阻断门", pendingSafetyGate in (persistedEvidence + 1) until pendingHandler)
            assertTrue("持久化批次分类必须先于续补判断", resumeDecision > persistedEvidence)
            assertTrue("续补判断必须显式接收三态持久化证据", evidenceArgument > resumeDecision)
        }

        val stableEvidence = stable.indexOf("classifyPersistedHighHeatBatch(")
        val stableSafetyGate = stable.indexOf("shouldBlockPendingHighHeatRecovery(", stableEvidence)
        val completedBindingCleanup = stable.indexOf("clearCompletedHeatReturnBindingIfSourcesCleared(")
        val matureCloseout = stable.indexOf("checkHighHeatSausage(list)", stableSafetyGate)
        assertTrue("稳态入口必须先分类，再决定是否允许清理 completed binding", stableSafetyGate > stableEvidence)
        assertTrue("不可信批次必须在 completed binding 清理前失败关闭", completedBindingCleanup > stableSafetyGate)
        assertTrue("不可信批次只能阻断续补，不能阻断既有烤肠成熟收口", matureCloseout > stableSafetyGate)
    }

    @Test
    fun stableHighHeat_shouldReconcilePhysicallyLoadedTargetsBeforeMatureReturnAndPendingResume() {
        val source = sourceText()
        val stable = functionBlock(source, "suspend fun highTrafficMode(", "suspend fun drainHighConcurrencyResidual(")

        val reconcile = stable.indexOf("reconcilePendingSupplementTargetsAlreadyOnPans(")
        val matureReturn = stable.indexOf("checkHighHeatSausage(list)", reconcile)
        val pendingResume = stable.indexOf("handlePendingSupplementBatchIfAny(", reconcile)

        assertTrue("稳态入口必须在成熟回搬前把物理已上盘但落盘滞后的目标对账为 completed", reconcile >= 0)
        assertTrue("物理上盘对账必须早于25~33成熟回搬，避免回搬后又被判定为 remaining", matureReturn > reconcile)
        assertTrue("完整 pending 续补仍应位于成熟收口之后，不能因对账前移而阻塞成熟回搬", pendingResume > matureReturn)
    }

    @Test
    fun stableHighHeat_blockedPendingBatch_shouldContinueMatureAndDiscardCloseoutWithoutNewSupplement() {
        val stable = functionBlock(sourceText(), "suspend fun highTrafficMode(", "suspend fun drainHighConcurrencyResidual(")
        val pending = stable.indexOf("val pendingBatchHandling")
        val resumedOnlyReturn = stable.indexOf("completedMechanicalWorkThisRound", pending)
        val supplementBlockFlag = stable.indexOf("pendingHighHeatBatchBlocksSupplement", pending)
        val discard = stable.indexOf("checkHighHeatDiscard()", pending)
        val immediateSwitchToLow = stable.indexOf("switchHighToLow(", pending)
        val scheduledSwitchToLow = stable.indexOf("checkSwitchToLow(", pending)

        assertTrue("只有真正续补后才能结束本轮", resumedOnlyReturn > pending)
        assertTrue("BLOCKED/WAITING 必须转成只禁新补肠的门禁", supplementBlockFlag > resumedOnlyReturn)
        assertTrue("pending BLOCKED 后仍必须能到达过期丢弃", discard > supplementBlockFlag)
        assertTrue(
            "pending BLOCKED 时不能走即时高转低",
            stable.lastIndexOf("!pendingHighHeatBatchBlocksSupplement", immediateSwitchToLow) in (pending + 1) until immediateSwitchToLow
        )
        assertTrue(
            "pending BLOCKED 时不能走定时高转低",
            stable.lastIndexOf("!pendingHighHeatBatchBlocksSupplement", scheduledSwitchToLow) in (pending + 1) until scheduledSwitchToLow
        )
        assertFalse("稳定态不能再对所有非 NONE 直接返回", stable.contains("pendingBatchHandling != KaoChangOperate.PendingSupplementBatchHandling.NONE"))
    }

    @Test
    fun residualDrain_shouldReconcileTrustedPhysicalTargetsBeforeMatureReturnWithoutResumingSupplement() {
        val source = sourceText()
        val drain = functionBlock(
            source,
            "suspend fun drainHighConcurrencyResidual(",
            "private suspend fun resumeHighInitialization("
        )

        val persistedEvidence = drain.indexOf("classifyPersistedHighHeatBatch(")
        val safetyGate = drain.indexOf("shouldBlockPendingHighHeatRecovery(", persistedEvidence)
        val reconcile = drain.indexOf("reconcilePendingSupplementTargetsAlreadyOnPans(", safetyGate)
        val matureReturn = drain.indexOf("checkHighHeatSausage(list)", reconcile)

        assertTrue("残留消化必须先分类持久化25~33批次", persistedEvidence >= 0)
        assertTrue("残留消化只允许对账可信批次", safetyGate > persistedEvidence)
        assertTrue("残留消化必须在成熟回搬前补记物理已上盘目标", reconcile in (safetyGate + 1) until matureReturn)
        assertFalse("残留消化不得因此恢复补肠", drain.contains("handlePendingSupplementBatchIfAny("))
    }

    @Test
    fun physicalTargetReconciliation_shouldOnlyPersistCompletionAndBeReusedByPendingHandler() {
        val source = operateSourceText()
        val reconcile = functionBlock(
            source,
            "fun reconcilePendingSupplementTargetsAlreadyOnPans(",
            "suspend fun handlePendingSupplementBatchIfAny("
        )
        val pendingHandler = functionBlock(
            source,
            "suspend fun handlePendingSupplementBatchIfAny(",
            "private fun isInitializationBatch("
        )

        assertTrue("对账必须只处理当前允许类型的持久化批次", reconcile.contains("intent.type !in allowedTypes"))
        assertTrue("对账依据必须是 remaining 目标对应烤盘已实际有肠", reconcile.contains("intent.remainingTargets()") && reconcile.contains("isHasSausage == true"))
        assertTrue("对账必须绑定分类时看到的同一批次，避免状态切换后误写新批次", reconcile.contains("intent.batchId != expectedBatchId"))
        assertTrue("物理已上盘目标必须使用保留人工故障证据的 completed 写入", reconcile.contains("supplementBatchIntentRepository.markCompletedFromPhysicalReconciliation("))
        assertTrue("pending handler 必须复用同一对账方法，避免两套语义漂移", pendingHandler.contains("reconcilePendingSupplementTargetsAlreadyOnPans("))
        assertTrue("对账后批次若已切换必须失败关闭，不能继续处理新批次", pendingHandler.contains("refreshedIntent.batchId != intent.batchId"))
        val pendingReconcile = pendingHandler.indexOf("reconcilePendingSupplementTargetsAlreadyOnPans(")
        val refreshedEmptyCheck = pendingHandler.indexOf("remainingTargets().isEmpty()", pendingReconcile)
        val manualConfirmationGate = pendingHandler.indexOf("if (refreshedIntent.requiresManualConfirm)")
        val businessResumeGate = pendingHandler.indexOf("if (!schedulingEnabled || !boxSupplyAvailable")
        assertTrue("物理事实对账必须早于人工确认门，确认只约束续补机械动作", manualConfirmationGate > pendingReconcile)
        assertTrue("对账后必须重新读取并先判断是否已全部上盘", refreshedEmptyCheck in (pendingReconcile + 1) until manualConfirmationGate)
        assertTrue("物理事实对账必须早于库存/调度/故障/停业门，避免被挡住后成熟回搬又重复补肠", businessResumeGate > refreshedEmptyCheck)
        assertFalse("同步对账不能启动加热", reconcile.contains("heating("))
        assertFalse("同步对账不能派发搬盘", reconcile.contains("moveKaoPan"))
        assertFalse("同步对账不能新增协程或延迟", reconcile.contains("launch(") || reconcile.contains("withContext(") || reconcile.contains("delay("))
    }

    @Test
    fun lowToHighTransition_shouldNotLetPendingHighHeatBatchBlockOldLowHeatReturn() {
        val source = sourceText()
        val functionStart = source.indexOf("private suspend fun lowTrafficModeTransition(")
        assertTrue("必须存在低转高过渡调度函数", functionStart >= 0)
        val functionEnd = source.indexOf("/**\n     * 低转高期间", functionStart)
            .let { if (it >= 0) it else source.length }
        val functionBlock = source.substring(functionStart, functionEnd)

        val pendingHandling = functionBlock.indexOf("handlePendingSupplementBatchIfAny(")
        val resumedOnlyReturn = functionBlock.indexOf("PendingSupplementBatchHandling.RESUMED", pendingHandling)
        val supplementBlockFlag = functionBlock.indexOf("pendingHighHeatBatchBlocksSupplement", pendingHandling)
        val oldLowHeatReturn = functionBlock.indexOf("planOldLowHeatReturnGroups(", pendingHandling)

        assertTrue("低转高入口必须先检查未完成 25~33 补肠批次", pendingHandling >= 0)
        assertTrue("只有实际续补 RESUMED 后才应提前 return", resumedOnlyReturn > pendingHandling)
        assertTrue("等待人工确认/blocked 只能阻止继续补 25~33，不能阻止 13~18 回搬判断", supplementBlockFlag > resumedOnlyReturn)
        assertTrue("13~18 旧补热区回搬判断必须位于 pending 批次检查之后并仍可执行", oldLowHeatReturn > supplementBlockFlag)
    }

    @Test
    fun lowToHighTransition_shouldReclassifyPersistedBatchAfterPhysicalReconciliation() {
        val source = sourceText()
        val transition = functionBlock(
            source,
            "private suspend fun lowTrafficModeTransition(",
            "/**\n     * 低转高期间"
        )

        val pendingHandler = transition.indexOf("handlePendingSupplementBatchIfAny(")
        val refreshedIntent = transition.indexOf("activeSupplementBatchIntentForUi()", pendingHandler)
        val refreshedClassification = transition.indexOf("classifyPersistedHighHeatBatch(", refreshedIntent)
        val layoutResumeDecision = transition.indexOf("shouldResumeIncompleteHighHeatBatch(", pendingHandler)
        val refreshedStateArgument = transition.indexOf("persistedBatchState = persistedBatchState", layoutResumeDecision)

        assertTrue("低转高 pending 对账后必须重读持久化意图", refreshedIntent > pendingHandler)
        assertTrue("低转高必须用对账后的意图重新分类", refreshedClassification > refreshedIntent)
        assertTrue("布局续补判断必须晚于对账后重新分类", layoutResumeDecision > refreshedClassification)
        assertTrue("布局续补只能使用对账后的三态，不能复用对账前 REMAINING", refreshedStateArgument > layoutResumeDecision)
    }

    @Test
    fun lowToHighOldLowHeatReturn_shouldRecalculatePhysicalZoneAfterMaturedStateUpdate() {
        val source = sourceText()
        val functionBlock = functionBlock(
            source,
            "private suspend fun moveFinishedOldLowHeatGroupToFront(",
            "/**\n     * 高并发 -> 低并发过渡"
        )

        val stateUpdate = functionBlock.indexOf("sourcePans.forEach { sourcePan ->")
        val temperatureRefresh = functionBlock.indexOf("applyConfiguredTemperatureByPanState(", stateUpdate)
        val moveDispatch = functionBlock.indexOf("sourcePans.forEach { kaoPan ->", temperatureRefresh)

        assertTrue("旧补热小组必须先更新为成熟等待回搬状态", stateUpdate >= 0)
        assertTrue("成熟状态更新后必须按整个物理温区重算温控", temperatureRefresh > stateUpdate)
        assertTrue("温控重算必须发生在开始逐根回搬之前", moveDispatch > temperatureRefresh)
        assertFalse("不能因单个逻辑小组成熟就直接把整个二区切到保温", functionBlock.contains("KaoChangOperate.keepWarm(2)"))
        assertFalse("等待回搬日志不能误报整个二区正在保温", functionBlock.contains("保持2区硬件保温等待"))
    }

    @Test
    fun highToLowTransition_shouldPreserveManualHighHeatBatchIntentForTenMinuteWindow() {
        val source = sourceText()
        val functionStart = source.indexOf("private suspend fun highTrafficModeTransition(")
        assertTrue("必须存在高转低过渡调度函数", functionStart >= 0)
        val functionEnd = source.indexOf("private suspend fun finishHighToLowCloseout(", functionStart)
            .let { if (it >= 0) it else source.length }
        val functionBlock = source.substring(functionStart, functionEnd)

        val manualConfirmCheck = functionBlock.indexOf("pendingSupplementBatchRequiresManualConfirm()")
        val highHeatTypeCheck = functionBlock.indexOf("SupplementBatchType.HIGH_HEAT_25_33", manualConfirmCheck)
        val clearIntent = functionBlock.indexOf("clearPendingSupplementBatchIntentUnlessHighHeatManualConfirmation(", manualConfirmCheck)
        val persistedEvidence = functionBlock.indexOf("classifyPersistedHighHeatBatch(", clearIntent)
        val pendingSafetyGate = functionBlock.indexOf("shouldBlockPendingHighHeatRecovery(", persistedEvidence)
        val physicalReconcile = functionBlock.indexOf("reconcilePendingSupplementTargetsAlreadyOnPans(", pendingSafetyGate)
        val pendingResume = functionBlock.indexOf("handlePendingSupplementBatchIfAny(", clearIntent)
        val checkHighHeat = functionBlock.indexOf("checkHighHeatSausage(list)", manualConfirmCheck)

        assertTrue("高转低必须先检查25~33人工确认批次", manualConfirmCheck >= 0 && highHeatTypeCheck > manualConfirmCheck)
        assertTrue("高转低不能在25~33人工确认批次存在时无条件清意图", clearIntent > highHeatTypeCheck)
        assertTrue("高转低已确认批次续补前必须分类持久化证据", persistedEvidence > clearIntent)
        assertTrue("高转低不可信批次必须在 pending handler 前失败关闭", pendingSafetyGate in (persistedEvidence + 1) until pendingResume)
        assertTrue("高转低等待人工确认时也必须在成熟回搬前对账可信的物理已上盘目标", physicalReconcile in (pendingSafetyGate + 1) until checkHighHeat)
        assertTrue("高转低已确认的25~33未完成故障批次必须先尝持续补，再判断残缺回搬", pendingResume > clearIntent)
        assertTrue("25~33成熟/残缺回搬判断必须在续补入口之后执行，才能避免确认后拆批", checkHighHeat > pendingResume)
    }

    @Test
    fun highHeatIntentCleanup_shouldUseManualConfirmationGuardForAllTwentyFiveToThirtyThreePaths() {
        val highSource = sourceText()
        val operateSource = operateSourceText()

        assertTrue(
            "高并发模式内的25~33清批次必须使用人工确认保护方法",
            highSource.contains("clearPendingSupplementBatchIntentUnlessHighHeatManualConfirmation(\"高转低过渡不再启动新的25~33补肠\")") &&
                highSource.contains("clearPendingSupplementBatchIntentUnlessHighHeatManualConfirmation(\"写入高转低过渡态后停止25~33未完成补肠意图\")")
        )
        assertTrue(
            "过保丢弃触发高转低/残留消化时也必须保护25~33人工确认批次",
            operateSource.contains("clearPendingSupplementBatchIntentUnlessHighHeatManualConfirmation(\"过保丢弃进入高转低/残留消化，停止25~33未完成补肠意图\")")
        )
    }

    @Test
    fun highHeatIntentCleanupGuard_shouldPreserveConfirmedFaultRecoveryBatchUntilResumeCompletes() {
        val source = operateSourceText()
        val functionStart = source.indexOf("fun clearPendingSupplementBatchIntentUnlessHighHeatManualConfirmation(")
        assertTrue("必须存在25~33清批次保护方法", functionStart >= 0)
        val functionEnd = source.indexOf("fun activeSupplementBatchTargetPositions(", functionStart)
            .let { if (it >= 0) it else source.length }
        val functionBlock = source.substring(functionStart, functionEnd)

        assertTrue("保护条件必须限定25~33补热批次", functionBlock.contains("intent?.type == SupplementBatchType.HIGH_HEAT_25_33"))
        assertTrue("10分钟内确认后 requiresManualConfirm=false 但 remaining 仍在，不能清掉待续补目标", functionBlock.contains("intent.remainingTargets().isNotEmpty()"))
        assertTrue("保护条件不能只看 requiresManualConfirm，还必须保留已确认但带 failureAtMs 的故障恢复批次", functionBlock.contains("(intent.requiresManualConfirm || intent.failureAtMs > 0L)"))
    }

    @Test
    fun transitionBranches_shouldPersistPanStateAfterStateOnlyCorrections() {
        val source = sourceText()
        val functionStart = source.indexOf("suspend fun highTrafficMode(")
        assertTrue("必须存在高并发主处理器", functionStart >= 0)
        val functionEnd = source.indexOf("/**\n     * 低并发内部高并发残留收口专用收尾。", functionStart)
            .let { if (it >= 0) it else source.length }
        val functionBlock = source.substring(functionStart, functionEnd)

        assertTrue(
            "低转高分支可能只做 13~18 状态纠偏而不搬盘，返回前必须保存烤盘",
            functionBlock.contains("lowTrafficModeTransition(config, list, listBox, boxSupplyAvailable, schedulingEnabled)\n                KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())")
        )
        assertTrue(
            "高转低分支可能只做等待回搬/过保状态处理，返回前必须保存烤盘",
            functionBlock.contains("highTrafficModeTransition(config, list, listBox, boxSupplyAvailable, schedulingEnabled)\n                KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())")
        )
    }

    @Test
    fun highHeatSupplementPaths_shouldDurablyDisarmTimerBeforeHardwareDispatch() {
        val source = sourceText()
        val stableHigh = functionBlock(
            source,
            "suspend fun highTrafficMode(",
            "/**\n     * 低并发内部高并发残留收口专用收尾。"
        )
        val lowToHigh = functionBlock(
            source,
            "private suspend fun lowTrafficModeTransition(",
            "private suspend fun moveOldLowHeatGroupToFront("
        )
        val highToLow = functionBlock(
            source,
            "private suspend fun highTrafficModeTransition(",
            "private suspend fun finishHighToLowCloseout("
        )
        val normalLaunch = functionBlock(
            source,
            "private suspend fun addHighHeatSausage(",
            "private suspend fun resumeIncompleteHighHeatSupplement("
        )
        val resumeLaunch = functionBlock(
            source,
            "private suspend fun resumeIncompleteHighHeatSupplement(",
            "/**\n     * 检查 25~33 补热区是否已经烤熟。"
        )

        assertDisarmBeforeHighHeatMove(lowToHigh, "低转高25~33新批次")
        assertDisarmBeforeHighHeatMove(normalLaunch, "高并发稳态25~33新批次")
        assertDisarmBeforeHighHeatMove(resumeLaunch, "25~33未完成批次续补")
        assertPendingDisarmBeforeResume(stableHigh, "高并发稳态pending续补")
        assertPendingDisarmBeforeResume(lowToHigh, "低转高pending续补")
        assertPendingDisarmBeforeResume(highToLow, "高转低pending续补")
    }

    @Test
    fun highToLowTimerRestore_shouldCheckRecoveryGateBeforeTrustingCachedAnchor() {
        val source = sourceText()
        val restoreBlock = functionBlock(
            source,
            "private fun restoreOrArmHighToLowTimerIfSafe(",
            "private fun clearLowToHighPartialWaitSnapshot("
        )
        val recoveryGate = restoreBlock.indexOf("isActionGroupRecoveryBlocked()")
        val cachedAnchor = restoreBlock.indexOf("shouldReuseHighToLowTimerAnchor(")
        val disarm = restoreBlock.indexOf("disarmHighToLowTimerDurably(")
        val disarmBlock = functionBlock(
            source,
            "private fun disarmHighToLowTimerDurably(",
            "private fun disarmHighToLowTimerBeforeHighHeatMove("
        )
        val clearMemoryAnchor = disarmBlock.indexOf("lastFillPitTime = 0L")
        val durableWrite = disarmBlock.indexOf("highToLowTimerRepository.disarmDurably()")

        assertTrue("必须读取当前动作恢复门禁", recoveryGate >= 0)
        assertTrue("必须先检查恢复门禁再信任内存锚点", cachedAnchor > recoveryGate)
        assertTrue("恢复门禁阻塞时必须失效当前计时", disarm > recoveryGate)
        assertTrue("即使落盘失败也必须先作废本进程锚点", clearMemoryAnchor >= 0)
        assertTrue("本进程锚点必须在耐久写入前作废", durableWrite > clearMemoryAnchor)
    }

    @Test
    fun highHeatReturn_shouldClearBindingBeforeArmingTimer() {
        val source = sourceText()
        val returnBlock = functionBlock(
            source,
            "private suspend fun checkHighHeatSausage(",
            "private fun highHeatReturnPauseReason("
        )
        val returnCleared = returnBlock.indexOf("if (returnCleared)")
        val clearBinding = returnBlock.indexOf(
            "clearCompletedHeatReturnBindingIfSourcesCleared(",
            returnCleared
        )
        val armTimer = returnBlock.indexOf("armHighToLowTimerIfSafe(", returnCleared)

        assertTrue("必须在25~33整批回搬清空后才处理计时", returnCleared >= 0)
        assertTrue("必须先清理回搬 binding", clearBinding > returnCleared)
        assertTrue("必须在 binding 收口后才允许计时", armTimer > clearBinding)
    }

    @Test
    fun highToLowTimeout_shouldRestoreOrArmDurableTimerInsteadOfUsingZeroMemoryAnchor() {
        val source = sourceText()
        val timeoutBlock = functionBlock(
            source,
            "private fun checkSwitchToLow(",
            "/**\n     * 计算距离当日营业结束的剩余时间。"
        )

        assertTrue(
            "高转低判断必须先恢复或安全建立耐久锚点",
            timeoutBlock.contains("restoreOrArmHighToLowTimerIfSafe(")
        )
        assertFalse(
            "不能再以内存0值直接无条件建立锚点",
            timeoutBlock.contains("if (lastFillPitTime <= 0L)")
        )
    }

    private fun assertDisarmBeforeHighHeatMove(functionBlock: String, pathName: String) {
        val disarm = functionBlock.indexOf("disarmHighToLowTimerBeforeHighHeatMove(")
        val hardwareMove = listOf(
            functionBlock.indexOf("moveSausagesToKaoPansByTaste("),
            functionBlock.indexOf("moveSausagesToKaoPansForResume(")
        ).filter { it >= 0 }.minOrNull() ?: -1
        assertTrue("$pathName 必须存在计时失效入口", disarm >= 0)
        assertTrue("$pathName 必须在硬件补肠调用前完成耐久失效", hardwareMove > disarm)
    }

    private fun assertPendingDisarmBeforeResume(functionBlock: String, pathName: String) {
        val disarm = functionBlock.indexOf("disarmHighToLowTimerForPendingHighHeatIntent(")
        val pendingResume = functionBlock.indexOf("handlePendingSupplementBatchIfAny(")
        assertTrue("$pathName 必须存在计时失效入口", disarm >= 0)
        assertTrue("$pathName 必须在pending恢复入口前完成耐久失效", pendingResume > disarm)
    }

    private fun sourceText(): String {
        val sourceFile = listOf(
            File("src/main/java/cn/niuyannet/kaochang/android/services/HighCurrentTrafficMode.kt"),
            File("app/src/main/java/cn/niuyannet/kaochang/android/services/HighCurrentTrafficMode.kt")
        ).first { it.exists() }
        return sourceFile.readText().replace("\r\n", "\n")
    }

    private fun operateSourceText(): String {
        val sourceFile = listOf(
            File("src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt"),
            File("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        ).first { it.exists() }
        return sourceFile.readText().replace("\r\n", "\n")
    }

    private fun functionBlock(source: String, startMarker: String, endMarker: String): String {
        val start = source.indexOf(startMarker)
        assertTrue("缺少函数：$startMarker", start >= 0)
        val end = source.indexOf(endMarker, start).let { if (it >= 0) it else source.length }
        return source.substring(start, end)
    }
}
