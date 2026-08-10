package cn.niuyannet.kaochang.android.services

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
