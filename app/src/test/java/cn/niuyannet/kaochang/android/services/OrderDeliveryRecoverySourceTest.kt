package cn.niuyannet.kaochang.android.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OrderDeliveryRecoverySourceTest {

    @Test
    fun pendingDiscard_shouldUseStrictOrphanDeliveryBusyRecoveryBeforeScanner() {
        val operate = sourceText(
            "app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt"
        )
        val algorithm = sourceText(
            "app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangAlgorithm.kt"
        )
        val policy = sourceTextOrEmpty(
            "app/src/main/java/cn/niuyannet/kaochang/android/services/actiongroup/OrphanDeliveryBusyRecoveryPolicy.kt"
        )
        val pollStart = algorithm.indexOf("private suspend fun pollKaoPanStatus()")
        val pollEnd = algorithm.indexOf("private fun settleMaturedZonesBeforeDispatch", pollStart)
        val poll = algorithm.substring(pollStart, pollEnd)
        val recoveryGate = poll.indexOf("val actionGroupRecoveryBlocked =")
        val strictRecovery = poll.indexOf("recoverOrphanDeliveryBusyForPendingDiscard()")
        val scanner = poll.indexOf("OverKeepWarmDiscardScanner.scanAndDiscard(1..33, \"pending_discard_priority\")")

        assertTrue("必须存在孤儿出餐忙态专用收口入口", operate.contains("recoverOrphanDeliveryBusyForPendingDiscard"))
        assertTrue("自动收口必须在动作恢复门禁通过之后", strictRecovery > recoveryGate)
        assertTrue("自动收口必须先于待丢弃机械扫描", strictRecovery < scanner)
        assertTrue("自动收口必须使用两次完整空闲快照策略", operate.contains("OrphanDeliveryBusyRecoveryPolicy.evaluate"))
        assertTrue("本地订单和证据门禁必须先于串口快照，避免无效占用串口", operate.contains("val precheckRejection = withFaultHeatTransitionLock"))
        assertTrue("本地证据读取异常不得停止算法轮询", operate.contains("孤儿出餐忙态本地证据读取异常，拒绝恢复"))
        assertTrue("取消后的协程不得继续写入 moveStatus", operate.contains("callerContext.ensureActive()"))
        assertTrue("取消异常不得被本地证据门禁吞掉", operate.contains("catch (cancellation: CancellationException)"))
        assertTrue("自动收口只允许 moveStatus=2", policy.contains("moveStatus != 2"))
        assertTrue("快照必须同时验证命令与结果寄存器", policy.contains("commandStates") && policy.contains("resultStates"))
        assertTrue("claim 读取异常必须保持阻断", operate.contains("订单出餐 claim 损坏"))
    }

    @Test
    fun legacyRecovery_shouldAtomicallyRespectOrderClaimBeforeClearingMoveStatus() {
        val operate = sourceText(
            "app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt"
        )
        val maintenance = sourceText(
            "app/src/main/java/cn/niuyannet/kaochang/android/fragments/MaintenanceFragment.kt"
        )
        val idleGate = functionBlock(
            operate,
            "fun isActionGroupIdleForMoveStatusRecovery()",
            "fun abandonSupplementPrefetchForManualPanClear("
        )
        val atomicRecovery = functionBlock(
            operate,
            "fun clearMoveStatusForLegacy201Recovery(",
            "fun abandonSupplementPrefetchForManualPanClear("
        )
        val operateLegacyRecovery = functionBlock(
            operate,
            "private fun tryRecoverIdleMoveStatus(reason: String): Boolean",
            "private suspend fun waitUntilMoveStatusIdleOrTimeout("
        )
        val maintenanceLegacyRecovery = functionBlock(
            maintenance,
            "private suspend fun tryRecoverIdleMoveStatus(",
            "private suspend fun evaluateManualRecoveryHeating("
        )

        assertTrue("恢复空闲门禁必须先检查订单 claim", idleGate.contains("restoreClaimedOrderMoveStatusIfNeeded"))
        assertTrue("订单 claim 存在时必须 fail closed", idleGate.contains("OrderDeliveryClaimRecovery.NO_CLAIM"))
        assertTrue("归零必须在既有短临界区内再次检查 claim", atomicRecovery.contains("restoreClaimedOrderMoveStatusIfNeeded"))
        assertTrue("归零必须使用耐久状态写入", atomicRecovery.contains("mechanicalMoveStatusStore.saveDurable(0)"))
        assertTrue("上位机旧恢复必须走原子归零入口", operateLegacyRecovery.contains("clearMoveStatusForLegacy201Recovery(statusReadings)"))
        assertTrue("维护页旧恢复必须走原子归零入口", maintenanceLegacyRecovery.contains("KaoChangOperate.clearMoveStatusForLegacy201Recovery(statusReadings)"))
        assertFalse("上位机旧恢复不得锁外直接清零", operateLegacyRecovery.contains("config.moveStatus = 0"))
        assertFalse("维护页旧恢复不得锁外直接清零", maintenanceLegacyRecovery.contains("config.moveStatus = 0"))
    }

    @Test
    fun legacyRecovery_shouldRequireAbsentActionAndPausedEvidence() {
        val operate = sourceText(
            "app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt"
        )
        val idleCheck = functionBlock(
            operate,
            "private fun isActionGroupIdleForMoveStatusRecoveryWithoutOrderClaim()",
            "data class OrphanDeliveryBusyRecoveryResult("
        )

        assertTrue(
            "损坏的动作组会话不得按无会话清零",
            idleCheck.contains("actionGroupSessionStore.readEvidence() is DurableEvidenceReadResult.Absent")
        )
        assertTrue(
            "损坏或残留的暂停补肠会话不得按空闲清零",
            idleCheck.contains("pausedSupplementSessionStore.readEvidence() is DurableEvidenceReadResult.Absent")
        )
        assertFalse(
            "旧 201 空闲判断不得继续使用把损坏证据吞成 null 的 load()",
            idleCheck.contains("actionGroupSessionStore.load() == null")
        )
    }

    @Test
    fun genericRecovery_shouldBlockOrderClaimBeforeRecoveryMutation() {
        val operate = sourceText(
            "app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt"
        )
        val recovery = functionBlock(
            operate,
            "suspend fun recoverActionGroupStateAfterModbusReady(",
            "private fun automaticRecoveryOrderClaimBlockReason()"
        )
        val claimGate = recovery.indexOf("automaticRecoveryOrderClaimBlockReason()")
        val discardJournal = recovery.indexOf("discardJournalStore.load()")
        val panMoveRecovery = recovery.indexOf("recoverPanMoveJournalAfterModbusReady(reason)")
        val bootstrap = recovery.indexOf("ActionGroupRecoveryBootstrap(")

        assertTrue("通用恢复必须先读取订单 claim", claimGate >= 0)
        assertTrue("订单 claim 门禁必须早于丢弃 journal 恢复路径", claimGate < discardJournal)
        assertTrue("订单 claim 门禁必须早于搬盘恢复", claimGate < panMoveRecovery)
        assertTrue("订单 claim 门禁必须早于动作组接管", claimGate < bootstrap)
        assertTrue(
            "动作组接管写入前必须在既有短锁内重查订单 claim",
            recovery.contains("automaticRecoveryOrderClaimBlockReason() == null")
        )
        assertTrue(
            "Idle 清理 moveStatus 前必须再次在既有短锁内重查订单 claim",
            recovery.contains("val idleClaimBlockReason = withFaultHeatTransitionLock")
        )
    }

    @Test
    fun genericAndManualRecovery_shouldUseSameClaimOnlyCloseoutBeforeGenericMoveStatusCleanup() {
        val operate = sourceText(
            "app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt"
        )
        val genericRecovery = functionBlock(
            operate,
            "suspend fun recoverActionGroupStateAfterModbusReady(",
            "private fun automaticRecoveryOrderClaimBlockReason()"
        )
        val manualRecovery = functionBlock(
            operate,
            "suspend fun confirmManualDeviceRecoveryResidue(",
            "private fun confirmManualDeviceRecoveryResidueLocked("
        )

        val autoCloseout = genericRecovery.indexOf("tryAutoCloseStaleOrderDeliveryClaim(")
        val claimBlock = genericRecovery.indexOf("automaticRecoveryOrderClaimBlockReason()")
        val manualClaimCloseout = manualRecovery.indexOf("closeStaleOrderDeliveryClaimForMaintenance(")
        val manualGenericCloseout = manualRecovery.indexOf("confirmManualDeviceRecoveryResidueLocked(")
        assertTrue("自动claim收口必须先于通用claim阻断", autoCloseout >= 0 && autoCloseout < claimBlock)
        assertTrue(
            "恢复正常状态必须复用同一个订单claim收口入口",
            manualClaimCloseout >= 0
        )
        assertTrue(
            "恢复正常状态必须先处理claim，再进入无claim的通用收口",
            manualClaimCloseout < manualGenericCloseout
        )
    }

    @Test
    fun manualRecovery_shouldTryEmergencyResetCloseoutBeforeOrdinaryClaimCloseout() {
        val operate = sourceText(
            "app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt"
        )
        val manualRecovery = functionBlock(
            operate,
            "suspend fun confirmManualDeviceRecoveryResidue(",
            "private fun confirmManualDeviceRecoveryResidueLocked("
        )
        val emergencyCloseout = manualRecovery.indexOf("closeMechanicalStateAfterEmergencyReset(")
        val ordinaryCloseout = manualRecovery.indexOf("closeStaleOrderDeliveryClaimForMaintenance(")

        assertTrue("人工恢复必须先尝试紧急复位专用收口", emergencyCloseout >= 0)
        assertTrue("无匹配凭据时才回退普通 claim 收口", emergencyCloseout < ordinaryCloseout)
    }

    @Test
    fun emergencyResetCloseout_shouldUseTwoSnapshotsAndAtomicMultiKeyCommit() {
        val operate = sourceText(
            "app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt"
        )
        val closeout = functionBlock(
            operate,
            "private suspend fun closeMechanicalStateAfterEmergencyReset(",
            "private suspend fun closeStaleOrderDeliveryClaim("
        )
        val appConfig = sourceText(
            "app/src/main/java/cn/niuyannet/kaochang/android/init/AppConfig.kt"
        )
        val atomicCommit = functionBlock(
            appConfig,
            "fun saveEmergencyResetForceCloseoutDurable(",
            "fun hasValidDeviceConfig()"
        )
        val idleSnapshot = functionBlock(
            operate,
            "private suspend fun buildEmergencyResetIdleSnapshot(",
            "suspend fun confirmClearActionGroupRecoveryResidue("
        )

        assertEquals(
            2,
            Regex.escape("buildEmergencyResetIdleSnapshot(").toRegex().findAll(closeout).count()
        )
        assertTrue(idleSnapshot.contains("action_address_move_result"))
        assertTrue(closeout.contains("panMoveResults203 = snapshots.map"))
        assertTrue(closeout.contains("delay(500L)"))
        assertTrue(closeout.contains("EmergencyResetForceCloseoutPolicy.evaluate("))
        assertTrue(closeout.contains("withFaultHeatTransitionLock"))
        assertTrue(closeout.contains("initialFence.hasCrossedProcessBoundary("))
        assertTrue(closeout.contains("VMModbusHelper.clearEmergencyResetMechanicalWriteBlock()"))
        assertTrue(closeout.contains("AppConfig.saveEmergencyResetForceCloseoutDurable("))
        assertTrue(atomicCommit.contains("KEY_APP_CONFIG_JSON to JSON.toJSONString(next)"))
        assertTrue(atomicCommit.contains("claimKey to \"\""))
        assertTrue(atomicCommit.contains("actionGroupSessionKey to \"\""))
        assertTrue(atomicCommit.contains("pausedSupplementSessionKey to \"\""))
        assertTrue(atomicCommit.contains("prefetchSessionKey to \"\""))
        assertTrue(atomicCommit.contains("fenceKey to \"\""))
        assertTrue(atomicCommit.contains("PreferenceUtils.saveStringPreferencesDurable("))
    }

    @Test
    fun claimCloseout_shouldPersistClaimMoveAndWorkingStatusTogether() {
        val claimStore = sourceText(
            "app/src/main/java/cn/niuyannet/kaochang/android/services/actiongroup/OrderDeliveryClaim.kt"
        )
        val appConfig = sourceText(
            "app/src/main/java/cn/niuyannet/kaochang/android/init/AppConfig.kt"
        )
        val preferences = sourceText(
            "app/src/main/java/cn/niuyannet/kaochang/android/utils/PreferenceUtils.java"
        )

        val claimCommit = functionBlock(
            claimStore,
            "fun commitIdleMoveStatusAndClearIfCurrent(",
            "private fun save(claim: OrderDeliveryClaim)"
        )
        val appCommit = functionBlock(
            appConfig,
            "fun saveOrderClaimCloseoutDurable(",
            "fun hasValidDeviceConfig()"
        )

        assertTrue(claimCommit.contains("AppConfig.saveOrderClaimCloseoutDurable("))
        assertTrue(appCommit.contains("next.moveStatus = 0"))
        assertTrue(appCommit.contains("next.onlineStatus = nextOnlineStatus"))
        assertTrue(appCommit.contains("KEY_APP_CONFIG_JSON to JSON.toJSONString(next)"))
        assertTrue(appCommit.contains("claimKey to \"\""))
        assertTrue(appCommit.contains("PreferenceUtils.saveStringPreferencesDurable("))
        assertTrue("关联状态必须由同一个Editor.commit提交", preferences.contains("return editor.commit();"))
        assertTrue(appConfig.contains("@Synchronized\n    fun saveAppConfig("))
        assertTrue(appConfig.contains("@Synchronized\n    fun saveAppConfigDurable("))
        assertTrue(appConfig.contains("@Synchronized\n    fun saveOrderClaimCloseoutDurable("))
        assertFalse("原子收口不能替换全局bean并留下旧引用", appCommit.contains("this.appConfigBean = next"))
        assertTrue(appCommit.contains("current.moveStatus = next.moveStatus"))
        assertTrue(appCommit.contains("current.onlineStatus = next.onlineStatus"))
    }

    @Test
    fun claimCloseout_shouldPreserveTerminalOrderAndReleaseMatchingEphemeralState() {
        val operate = sourceText(
            "app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt"
        )
        val closeout = functionBlock(
            operate,
            "private suspend fun closeStaleOrderDeliveryClaim(",
            "private fun staleOrderClaimCloseoutRejectionSafely("
        )

        assertTrue(
            "已有FINISHED终态不能被自动收口降级为FAILED",
            closeout.contains("OrderStateManager.ensureTerminalForStaleCloseoutDurable(claim.orderNo)")
        )
        assertTrue(closeout.contains("OrderStateManager.releaseProcessing(claim.orderNo)"))
        assertTrue(closeout.contains("SupplementPauseController.clearOrderPause(claim.orderNo)"))
        assertTrue(closeout.contains("manualOutOrderRecoveryCoordinator.release(claim.orderNo)"))
    }

    @Test
    fun claimCloseout_shouldRejectCorruptedOrderTerminalEvidence() {
        val orderState = sourceText(
            "app/src/main/java/cn/niuyannet/kaochang/android/utils/OrderStateManager.kt"
        )

        assertTrue(orderState.contains("getPersistedOrderStateForStaleCloseout(orderNo)"))
        assertTrue(orderState.contains("订单耐久终态损坏，拒绝 claim 自动收口"))
        assertTrue(orderState.contains("state.state == STATE_FINISHED || state.state == STATE_FAILED"))
        assertTrue(orderState.contains("catch (error: IllegalStateException)"))
    }

    @Test
    fun claimAutoCloseout_shouldConvertEvidenceAndModbusExceptionsToFailClosedResults() {
        val operate = sourceText(
            "app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt"
        )
        val closeout = functionBlock(
            operate,
            "private suspend fun closeStaleOrderDeliveryClaim(",
            "private fun staleOrderClaimCloseoutRejection("
        )

        assertTrue(
            "本地证据解析异常必须转为拒绝原因",
            closeout.contains("staleOrderClaimCloseoutRejectionSafely(")
        )
        assertTrue(
            "Modbus快照读取异常必须转为拒绝原因",
            closeout.contains("订单 claim 收口读取下位机状态失败")
        )
        assertTrue(
            "原子commit异常必须保持阻断",
            closeout.contains("订单 claim 原子收口提交异常")
        )
    }

    private fun functionBlock(source: String, startMarker: String, endMarker: String): String {
        val start = source.indexOf(startMarker)
        assertTrue("缺少函数：$startMarker", start >= 0)
        val end = source.indexOf(endMarker, start + startMarker.length)
        assertTrue("缺少函数结束标记：$endMarker", end > start)
        return source.substring(start, end)
    }

    private fun sourceText(path: String): String {
        val sourceFile = listOf(File(path), File(path.removePrefix("app/"))).first { it.exists() }
        return sourceFile.readText().replace("\r\n", "\n")
    }

    private fun sourceTextOrEmpty(path: String): String {
        val sourceFile = listOf(File(path), File(path.removePrefix("app/"))).firstOrNull { it.exists() }
            ?: return ""
        return sourceFile.readText().replace("\r\n", "\n")
    }
}
