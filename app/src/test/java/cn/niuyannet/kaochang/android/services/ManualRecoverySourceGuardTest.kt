package cn.niuyannet.kaochang.android.services

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualRecoverySourceGuardTest {

    @Test
    fun confirmClearActionGroupRecoveryResidue_shouldNotClearDeviceStatusFields() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val body = extractFunctionBody(source, "fun confirmClearActionGroupRecoveryResidue")

        assertTrue("confirmClearActionGroupRecoveryResidue 不允许写 errorStatus（设备故障状态）", !body.contains("errorStatus"))
        assertTrue("confirmClearActionGroupRecoveryResidue 不允许写 onlineStatus（设备营业状态）", !body.contains("onlineStatus"))
        assertTrue("confirmClearActionGroupRecoveryResidue 不允许写 isEnable（烤肠算法开关）", !body.contains("isEnable"))
    }

    @Test
    fun manualRecoveryCloseout_shouldUseCasAndPreciseOwnerRelease() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val body = extractFunctionBody(source, "fun confirmManualDeviceRecoveryResidueLocked")

        assertTrue("双 lane 会话必须按 sessionId + revision CAS 清理", body.contains("clearIfRevision"))
        assertTrue("人工恢复不得清空全局资源锁", !body.contains("actionGroupLocks.clear"))
        assertTrue("人工恢复必须拒绝未知资源 owner", body.contains("actionGroupLocks.ownerIds()"))
        assertTrue("人工恢复必须核对 owner 的资源 mask", body.contains("actionGroupLocks.ownedMask("))
        assertTrue("人工恢复必须精确释放双 lane owner", body.contains("actionGroupLocks.release(prefetchSession.sessionId)"))
        assertTrue("订单暂停只能按匹配订单号解除", body.contains("SupplementPauseController.clearOrderPause(pendingOrderNo)"))
        assertTrue("恢复失败必须补发忙态", body.contains("publishRuntimeStateWithHttpFallback"))
        assertTrue(
            "回搬 intent 必须在 moveStatus/session 收口前确认",
            body.indexOf("confirmManualSafetyPreservingRemainingTargets") in 0 until body.indexOf("val persistIdle")
        )
        assertTrue("无订单号 session 也必须能按当前暂停解除", body.contains("currentOrderNo()?.let"))
    }

    @Test
    fun genericManualRecovery_shouldRejectGuardedEpisodeInsideTransitionLock() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val wrapper = extractFunctionBody(source, "fun confirmManualDeviceRecoveryResidue")
        val body = extractFunctionBody(source, "fun confirmManualDeviceRecoveryResidueLocked")

        assertTrue("普通恢复的状态处理必须复用 faultHeatTransitionLock", wrapper.contains("withFaultHeatTransitionLock"))
        assertTrue("持锁 helper 必须校验调用方持有统一锁", body.contains("Thread.holdsLock(faultHeatTransitionLock)"))
        assertTrue("普通恢复必须显式拒绝 guarded episode", body.contains("hasOpenGuardedIsolation"))
        assertTrue(
            "guarded 门禁必须先于 moveStatus 清零",
            body.indexOf("hasOpenGuardedIsolation") in 0 until body.indexOf("val persistIdle")
        )
    }

    @Test
    fun maintenanceIsolationConfirmation_shouldUseDedicatedGuardedCloseoutEntry() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/fragments/MaintenanceFragment.kt")
        val clickBody = extractFunctionBody(source, "binding.btnConfirmSupplementSafety.setOnClickListener")

        assertTrue(clickBody.contains("confirmGuardedSerialSupplementFailureRecovery"))
    }

    @Test
    fun dedicatedGuardedRecovery_shouldRequireTwoFreshLowerBoardIdleSnapshots() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val body = extractFunctionBody(source, "fun confirmGuardedSerialSupplementFailureRecovery")

        assertTrue(body.contains("buildActionGroupRecoveryResidueEvidence"))
        assertTrue(body.contains("delay(500L)"))
        assertTrue(body.contains("ManualDeviceRecoveryCloseoutPolicy.evaluate"))
        assertTrue(body.contains("firstEvidence"))
        assertTrue(body.contains("secondEvidence"))
        assertTrue(
            "下位机证据必须先取得，再进入短状态锁提交",
            body.indexOf("firstEvidence") in 0 until body.indexOf("withFaultHeatTransitionLock")
        )
    }

    @Test
    fun emergencyResetForceCloseout_shouldClearCoordinationResidueWithoutIdentityMatching() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val body = extractFunctionBody(source, "fun closeMechanicalStateAfterEmergencyReset")

        assertTrue(body.contains("saveEmergencyResetForceCloseoutDurable"))
        assertTrue(body.contains("actionGroupLocks.clear()"))
        assertTrue(body.contains("initialFence.hasCrossedProcessBoundary("))
        assertTrue(body.contains("VMModbusHelper.clearEmergencyResetMechanicalWriteBlock()"))
        assertTrue(!body.contains("pendingOrderNo =="))
        assertTrue(!body.contains("sessionId =="))
        assertTrue(!body.contains("revision =="))
        assertTrue(!body.contains("KaoPanHelper"))
        assertTrue(!body.contains("pendingDiscardPositions"))
        assertTrue(!body.contains("panMoveJournalStore.clear"))
        assertTrue(!body.contains("discardJournalStore.clear"))
    }

    @Test
    fun emergencyResetForceCloseout_shouldPreserveSupplementBatchFacts() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val body = extractFunctionBody(source, "fun closeMechanicalStateAfterEmergencyReset")

        assertTrue(body.contains("saveEmergencyResetForceCloseoutDurable"))
        assertTrue(!body.contains("supplementBatchIntentRepository.loadActive()"))
        assertTrue(!body.contains("markManualConfirmStrategyPrefetchPreserveRemaining"))
        assertTrue(!body.contains("confirmManualSafetyPreservingRemainingTargets"))
    }

    @Test
    fun emergencyResetRecovery_shouldTakePriorityOverPhysicalJournalsWithoutDeletingThem() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/fragments/MaintenanceFragment.kt")
        val body = extractFunctionBody(source, "binding.btnErroStatus.setOnClickListener")
        val emergencyCheck = body.indexOf("val emergencyResetPendingAtStart")
        val discardCheck = body.indexOf("pendingDiscardJournalForRecovery()")

        assertTrue("紧急复位 fence 必须先于丢弃 journal 分流", emergencyCheck >= 0 && emergencyCheck < discardCheck)
        assertTrue(body.contains("if (emergencyResetPendingAtStart) null else"))
    }

    @Test
    fun actionGroupBootstrap_shouldNeverClearAllResourceOwners() {
        val source = sourceFile(
            "app/src/main/java/cn/niuyannet/kaochang/android/services/actiongroup/ActionGroupRecoveryBootstrap.kt"
        )

        assertTrue("动作组恢复不得全局清空资源 owner", !source.contains("resourceLocks.clear()"))
    }

    @Test
    fun discardJournalRecovery_shouldUseStable201AndExistingOwnerFencedRelease() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val body = extractFunctionBody(source, "fun confirmDiscardJournalRecovery")

        assertTrue("丢弃残留收口必须使用专用策略", body.contains("DiscardJournalRecoveryPolicy.evaluate"))
        assertTrue(
            "丢弃残留收口必须连续读取两次 201",
            Regex("readHoldingRegisters\\([^)]*action_address_status[^)]*\\)").findAll(body).count() == 2
        )
        assertTrue("丢弃残留收口必须核对全部 300/400 动作组结果", body.contains("ActionGroupRecoveryBootstrap.allSpecs"))
        assertTrue("动作组读取失败必须拒绝收口", body.contains("AtomicReadResult.Failure"))
        val policySource = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/DiscardJournal.kt")
        assertTrue(
            "动作组结果必须严格限制为 0（空闲）或 2（完成）",
            policySource.contains("it != AtomicActionCodes.IDLE && it != AtomicActionCodes.COMPLETED")
        )
        assertTrue("丢弃残留必须复用 owner 条件释放", body.contains("releaseMechanicalMove"))
        assertTrue("丢弃残留不得直接删除 journal", !body.contains("discardJournalStore.clearDurable"))
        assertTrue("丢弃残留不得清空全局资源锁", !body.contains("actionGroupLocks.clear"))
    }

    @Test
    fun discardJournalRecovery_shouldRestorePanMemoryWhenDurableSaveFails() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val body = extractFunctionBody(source, "fun confirmDiscardJournalRecovery")
        val snapshotIndex = body.indexOf("val previousCmdStatusTake")
        val applyIndex = body.indexOf("applySuccessfulTakePanState")
        val restoreIndex = body.indexOf("pan.cmdStatusTake = previousCmdStatusTake")

        assertTrue("清空盘位前必须保存内存状态", snapshotIndex >= 0 && snapshotIndex < applyIndex)
        assertTrue("耐久保存失败必须恢复盘位内存状态", restoreIndex > applyIndex)
    }

    @Test
    fun discardJournalRecovery_shouldFinishDurableBusinessStateBeforeOwnerRelease() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val body = extractFunctionBody(source, "fun confirmDiscardJournalRecovery")
        val discardStatusIndex = body.indexOf("pan.cmdStatusDiscard = 0")
        val demandGateIndex = body.indexOf("handleSuccessfulDiscardDemandGate")
        val pendingDiscardIndex = body.indexOf("clearDiscardPositionStateAndAlertIfFinished")
        val eventIndex = body.indexOf("publishConsumableEvent")
        val releaseIndex = body.indexOf("releaseMechanicalMove")

        assertTrue("确认丢弃后必须归零丢弃动作状态", discardStatusIndex >= 0)
        assertTrue("原始来源调度收口必须发生在 owner 释放前", demandGateIndex in 0 until releaseIndex)
        assertTrue("待丢弃状态耐久收口必须发生在 owner 释放前", pendingDiscardIndex in 0 until releaseIndex)
        assertTrue("稳定耗材事件必须在 journal 删除前进入发送链路", eventIndex in 0 until releaseIndex)
        assertTrue("恢复路径必须复用 journal 内耗材事件键", body.contains("initialJournal.wasteConsumableEvent"))
        assertTrue("MQTT 入队结果不得成为机械释放门槛", !body.contains("check(SendServerHelper.publishConsumableEvent"))
    }

    @Test
    fun discardJournalRecovery_shouldClearDiscardErrorAfterConfirmingNotDiscarded() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val body = extractFunctionBody(source, "fun confirmDiscardJournalRecovery")
        val notDiscardedIndex = body.indexOf("confirmedResult == DiscardPhysicalResult.NOT_DISCARDED")
        val clearIndex = body.indexOf("pan.cmdStatusDiscard = 0", notDiscardedIndex)
        val durableSaveIndex = body.indexOf("KaoPanHelper.saveKaoPanListDurable", clearIndex)
        val releaseIndex = body.indexOf("releaseMechanicalMove")
        val reportIndex = body.indexOf("PanPhysicalStateDeltaReporter.reportPansChanged")

        assertTrue("确认未丢弃必须有独立收口分支", notDiscardedIndex >= 0)
        assertTrue("确认未丢弃必须清除丢弃错误状态", clearIndex > notDiscardedIndex)
        assertTrue("确认未丢弃必须先耐久保存盘位状态", durableSaveIndex > clearIndex && durableSaveIndex < releaseIndex)
        assertTrue("确认未丢弃后的盘位状态必须上报", reportIndex > releaseIndex)
    }

    @Test
    fun successfulDiscard_shouldReuseJournalConsumableEvent() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val body = extractFunctionBody(source, "suspend fun discardSausage(")

        assertTrue("丢弃 journal 必须在命令前保存稳定耗材事件", body.contains("wasteConsumableEvent = wasteConsumableEvent"))
        assertTrue("正常成功链必须复用 journal 的稳定事件键", body.contains("publishConsumableEvent(wasteConsumableEvent)"))
    }

    @Test
    fun expiredDiscardDemandCloseout_shouldRetryPendingIntentCleanupAfterPartialCommit() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val body = extractFunctionBody(source, "fun handleSuccessfulDiscardDemandGate")
        val cleanupIndex = body.indexOf("clearPendingSupplementBatchIntentUnlessHighHeatManualConfirmation")
        val durableSaveIndex = body.indexOf("AppConfig.saveAppConfigDurable")
        val unchangedReturnIndex = body.indexOf("if (!result.changedModeState && !result.blockedFrontPositionsChanged)")

        assertTrue("补肠意图清理必须先于无变化提前返回，保证失败后可重试", cleanupIndex in 0 until unchangedReturnIndex)
        assertTrue("并发态耐久保存必须先于无变化提前返回，保证失败后可重试", durableSaveIndex in 0 until unchangedReturnIndex)
    }

    @Test
    fun maintenanceRecoveryClick_shouldHandleDiscardJournalBeforeGenericRecovery() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/fragments/MaintenanceFragment.kt")
        val clickBody = extractFunctionBody(source, "binding.btnErroStatus.setOnClickListener")
        val discardIndex = clickBody.indexOf("pendingDiscardJournalForRecovery")
        val genericRecoveryIndex = clickBody.indexOf("evaluateManualRecoveryHeating")

        assertTrue("恢复正常状态入口必须识别丢弃残留", discardIndex >= 0)
        assertTrue("丢弃残留必须先于通用恢复处理", discardIndex < genericRecoveryIndex)
    }

    @Test
    fun maintenanceRecoveryClick_shouldGoThroughManualRecoveryGateBeforeApplyingRecoveredState() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/fragments/MaintenanceFragment.kt")
        val clickBody = extractFunctionBody(source, "binding.btnErroStatus.setOnClickListener")
        val gateIndex = clickBody.indexOf("evaluateManualRecoveryHeating(")
        val applyIndex = clickBody.indexOf("applyManualRecoveredState(")

        assertTrue("恢复正常状态路径必须先调用 evaluateManualRecoveryHeating 恢复闸门", gateIndex >= 0)
        assertTrue("恢复正常状态路径必须通过 applyManualRecoveredState 统一写状态", applyIndex >= 0)
        assertTrue("恢复闸门必须先于状态写入方法调用", gateIndex < applyIndex)
    }

    @Test
    fun maintenanceRecoveryClick_shouldUseSingleManualCloseoutWithoutResidueDialog() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/fragments/MaintenanceFragment.kt")
        val clickBody = extractFunctionBody(source, "binding.btnErroStatus.setOnClickListener")
        val gateBody = extractFunctionBody(source, "suspend fun evaluateManualRecoveryHeating")

        assertTrue(clickBody.contains("evaluateManualRecoveryHeating"))
        assertTrue(gateBody.contains("confirmManualDeviceRecoveryResidue"))
        assertTrue(!clickBody.contains("showConfirmClearActionGroupResidueDialog"))
        assertTrue(!source.contains("动作组残留清理条件已变化"))
    }

    @Test
    fun applyManualRecoveredState_shouldNotBlockLocalRecoveryOnCloudAck() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/fragments/MaintenanceFragment.kt")
        val body = extractFunctionBody(source, "private suspend fun applyManualRecoveredState")
        val cloudIndex = body.indexOf("confirmManualRecoveryInCloud")
        val failureIndex = body.indexOf("return false", cloudIndex)

        assertTrue("本机恢复后的云端 ACK 只能记录，不得阻塞恢复", cloudIndex >= 0 && failureIndex < 0)
    }

    @Test
    fun applyManualRecoveredState_shouldRestoreHeatingBeforePublishingRecoveredState() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/fragments/MaintenanceFragment.kt")
        val body = extractFunctionBody(source, "fun applyManualRecoveredState")

        assertTrue(
            "applyManualRecoveredState 必须通过原子恢复入口先恢复温控再清故障",
            body.contains("recoverFaultHeatingAndRuntimeState")
        )
        assertTrue(
            "applyManualRecoveredState 不允许在恢复成功后再次停热",
            !body.contains("stopHeatingForFault")
        )
    }

    @Test
    fun maintenanceRecovery_shouldConfirmCloudBeforeShowingSuccess() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/fragments/MaintenanceFragment.kt")
        val applyBody = extractFunctionBody(source, "fun applyManualRecoveredState")
        val confirmIndex = applyBody.indexOf("confirmManualRecoveryInCloud")
        val successIndex = applyBody.indexOf("设备已恢复正常状态")

        assertTrue("本地恢复后必须确认云端恢复状态", confirmIndex >= 0)
        assertTrue("只有云端确认后才能提示设备恢复成功", successIndex > confirmIndex)
    }

    @Test
    fun maintenanceRecovery_shouldAllowCloudOnlyRetryWhenLocalStateAlreadyRecovered() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/fragments/MaintenanceFragment.kt")
        val clickBody = extractFunctionBody(source, "binding.btnErroStatus.setOnClickListener")
        val localDecisionIndex = clickBody.indexOf("needsLocalFaultRecovery")
        val cloudRetryIndex = clickBody.indexOf("confirmManualRecoveryInCloud")
        val modbusIndex = clickBody.indexOf("MaintenanceModbusRecoveryConnector.ensureConnected")

        assertTrue("恢复入口必须先判断是否仍需本地硬件恢复", localDecisionIndex >= 0)
        assertTrue("本机已恢复时必须支持仅补交云端恢复确认", cloudRetryIndex > localDecisionIndex)
        assertTrue("仅补交云端恢复必须发生在 Modbus 检查之前", cloudRetryIndex < modbusIndex)
    }

    @Test
    fun manualRecoveryCloudConfirmation_shouldUseFreshRuntimeAckWithoutHttpOverwrite() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/fragments/MaintenanceFragment.kt")
        val body = extractFunctionBody(source, "fun confirmManualRecoveryInCloud")

        assertTrue("人工恢复必须强制分配新 runtimeSeq", body.contains("forceNewSeq = true"))
        assertTrue("人工恢复必须等待对应 runtime_state_ack", body.contains("ackListener ="))
        assertTrue("页面销毁时必须注销人工恢复 ACK 等待", body.contains("invokeOnCancellation"))
        assertTrue("人工恢复确认不得用 HTTP 旧正常态覆盖新故障", !body.contains("uploadDeviceInfo"))
        assertTrue("人工恢复确认不得依赖额外 HTTP 读取", !body.contains("preflightRemoteDeviceState"))
    }

    @Test
    fun manualRecoveryAckTimeout_shouldBeIndependentFromSharedRuntimePendingState() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/mqtt/SendServerHelper.kt")
        val body = extractFunctionBody(source, "fun scheduleRuntimeStateAckCallbackTimeout")

        assertTrue("人工恢复 ACK 必须使用独立固定超时", body.contains("runtimeStateAckCallbackRegistry.timeout"))
        assertTrue("人工恢复 ACK 超时不得依赖共享 pending", !body.contains("pendingRuntimeStateAcks"))
        assertTrue("人工恢复 ACK 超时不得依赖全局最新 ACK", !body.contains("lastAckedRuntimeStateSeq"))
    }

    @Test
    fun algorithmPreflight_shouldBeReadOnlyAndCallbacksShouldBeGenerationFenced() {
        val apiSource = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/net/DataManagementAPI.kt")
        val preflightBody = extractFunctionBody(apiSource, "fun preflightRemoteDeviceState")
        val fragmentSource = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/fragments/MaintenanceFragment.kt")
        val setupBody = extractFunctionBody(fragmentSource, "fun setupButtons")

        assertTrue("算法开启预检不得合并远端配置", !preflightBody.contains("mergeRemoteConfig"))
        assertTrue("算法开启预检不得触发运行态迁移", !preflightBody.contains("handleRemoteOnlineStatusTransition"))
        assertTrue("维护页必须使用只读预检", setupBody.contains("preflightRemoteDeviceState"))
        assertTrue("迟到回调必须校验操作代次", setupBody.contains("operationGate.isCurrent(operationToken)"))
    }

    @Test
    fun algorithmDisable_shouldShareFaultHeatTransitionLock() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangAlgorithm.kt")
        val body = extractFunctionBody(source, "fun disableRoastAlgorithm")

        assertTrue("关闭算法必须与开启/故障恢复共享 transition lock", body.contains("withFaultHeatTransitionLock"))
    }

    @Test
    fun staleFaultRecoveryFinally_shouldNotResetNewerOperationState() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/fragments/MaintenanceFragment.kt")
        val body = extractFunctionBody(source, "private fun finishFaultRecoveryOperation")
        val finishToken = body.indexOf("if (!operationGate.finish(token)) return")
        val clearRecovery = body.indexOf("manualRecoveryInProgress.set(false)")
        val releaseBusy = body.indexOf("status.set(true)")

        assertTrue("迟到 finally 必须先通过操作代次校验", finishToken >= 0)
        assertTrue("只有当前恢复操作才能清除恢复中标记", clearRecovery > finishToken)
        assertTrue("只有当前恢复操作才能释放维护忙标记", releaseBusy > finishToken)
    }

    @Test
    fun algorithmCooldown_shouldRestoreAllPreflightDisabledControls() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/fragments/MaintenanceFragment.kt")
        val body = extractFunctionBody(source, "fun startAlgorithmSwitchCooldown()")
        val restoreCount = Regex("setFaultRecoveryConflictingControlsEnabled\\(true\\)")
            .findAll(body)
            .count()

        assertTrue("冷却超时和正常结束都必须恢复预检阶段禁用的全部冲突控件", restoreCount == 2)
    }

    private fun sourceFile(path: String): String {
        val direct = File(path)
        if (direct.exists()) {
            return direct.readText()
        }
        val fromParent = File("..", path)
        if (fromParent.exists()) {
            return fromParent.readText()
        }
        throw AssertionError("找不到源码文件：$path，cwd=${File(".").absolutePath}")
    }

    private fun extractFunctionBody(source: String, functionSignature: String): String {
        val start = source.indexOf(functionSignature)
        assertTrue("找不到函数：$functionSignature", start >= 0)
        val bodyStart = source.indexOf('{', start)
        assertTrue("找不到函数体：$functionSignature", bodyStart >= 0)
        var depth = 0
        for (index in bodyStart until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return source.substring(bodyStart + 1, index)
                    }
                }
            }
        }
        throw AssertionError("函数体未闭合：$functionSignature")
    }
}
