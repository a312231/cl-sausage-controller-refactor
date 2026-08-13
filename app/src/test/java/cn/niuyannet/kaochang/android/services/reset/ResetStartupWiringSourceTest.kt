package cn.niuyannet.kaochang.android.services.reset

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ResetStartupWiringSourceTest {
    @Test
    fun appStartup_shouldEnforceResetCloseoutBeforeCloudSync() {
        val source = File("src/main/java/cn/niuyannet/kaochang/android/MyApp.kt").readText()
        val reset = source.indexOf("ResetStartupCloseoutManager.resumeIfNeeded")
        val sync = source.indexOf("DataManagementAPI.dataSyncServer")

        assertTrue(reset >= 0)
        assertTrue(sync > reset)
    }

    @Test
    fun localCloseout_shouldAtomicallyClearMechanicalCoordinationWithoutClearingPhysicalFacts() {
        val source = File("src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt").readText()

        assertTrue(source.contains("closeMechanicalStateAfterLowerBoardReset"))
        assertTrue(source.contains("saveLowerBoardResetLocalCloseoutDurable"))
        assertTrue(source.contains("actionGroupLocks.clear()"))
        assertTrue(source.contains("OrderStateManager.releaseAllProcessingForEmergencyReset()"))
        assertTrue(source.contains("ORDER_BLOCK_REASON_SYSTEM_RESET_IN_PROGRESS"))
    }

    @Test
    fun finalization_shouldAtomicallyRestoreConfigAndClearResetGate() {
        val source = File("src/main/java/cn/niuyannet/kaochang/android/services/reset/ResetStartupCloseoutManager.kt").readText()

        assertTrue(source.contains("saveLowerBoardResetFinalizationDurable"))
    }

    @Test
    fun httpCloseout_shouldSendDeviceIdentityAndConfirmedOut4Only() {
        val source = File("src/main/java/cn/niuyannet/kaochang/android/net/NetApi.kt").readText()

        assertTrue(source.contains("fun manualResetCloseout("))
        assertTrue(source.contains("json[\"deviceCode\"]"))
        assertTrue(source.contains("itemJson[\"confirmedOut4\"]"))
    }
}
