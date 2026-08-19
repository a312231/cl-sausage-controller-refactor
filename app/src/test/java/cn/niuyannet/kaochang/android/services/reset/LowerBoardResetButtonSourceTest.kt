package cn.niuyannet.kaochang.android.services.reset

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LowerBoardResetButtonSourceTest {

    @Test
    fun normalReset_shouldUseUnifiedCoordinatorWithoutIdleGuard() {
        val source = source()
        val start = source.indexOf("private fun resetLowerBoardSystem()")
        val end = source.indexOf("private fun emergencyResetLowerBoardSystem()", start)
        val body = source.substring(start, end)

        assertTrue(body.contains("executeLowerBoardReset(ResetKind.NORMAL)"))
        assertFalse(body.contains("readLowerBoardMaintenanceGuard"))
        assertFalse(body.contains("canRunMaintenance"))
    }

    @Test
    fun emergencyReset_shouldShareTheSameCoordinator() {
        val source = source()
        val start = source.indexOf("private fun executeEmergencyResetLowerBoardSystem()")
        val end = source.indexOf("private fun setLowerBoardMaintenanceButtonsEnabled", start)
        val body = source.substring(start, end)

        assertTrue(body.contains("executeLowerBoardReset(ResetKind.EMERGENCY)"))
    }

    @Test
    fun temporaryBusinessState_shouldUsePersistedEffectiveResetKind() {
        val source = source()
        val start = source.indexOf("prepareAndApplyTemporaryBlock =")
        val end = source.indexOf("confirmServerBlock =", start)
        val body = source.substring(start, end)

        assertTrue(body.contains("prepared.kind == ResetKind.EMERGENCY"))
        assertFalse(Regex("(?<!\\.)\\bkind == ResetKind\\.EMERGENCY").containsMatchIn(body))
    }

    private fun source(): String = listOf(
        File("src/main/java/cn/niuyannet/kaochang/android/fragments/TestFragment.kt"),
        File("app/src/main/java/cn/niuyannet/kaochang/android/fragments/TestFragment.kt")
    ).first { it.exists() }.readText()
}
