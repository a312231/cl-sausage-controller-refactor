package cn.niuyannet.kaochang.android.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OrderDeliveryRecoverySourceTest {

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
}
