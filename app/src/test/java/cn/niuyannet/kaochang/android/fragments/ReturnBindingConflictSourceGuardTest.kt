package cn.niuyannet.kaochang.android.fragments

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReturnBindingConflictSourceGuardTest {

    private val fragment = source("app/src/main/java/cn/niuyannet/kaochang/android/fragments/MaintenanceFragment.kt")
    private val operate = source("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")

    @Test
    fun maintenanceConflictBranch_shouldExplainClearOnlyAndNeverExposeManualMove() {
        val click = body(fragment, "binding.btnConfirmSupplementSafety.setOnClickListener")

        assertTrue(click.contains("returnBindingConflictSnapshot"))
        assertTrue(click.contains("普通确认不能解除"))
        assertTrue(click.contains("清空烤盘"))
        assertTrue(click.contains("丢弃所有烤肠并重置烤盘"))
        assertFalse(click.contains("moveKaoPanToKaoPanManual"))
    }

    @Test
    fun maintenanceClearPaths_shouldClearPansBeforeDedicatedConflictCloseout() {
        val clear = body(fragment, "private fun performClearAll")
        val discard = body(fragment, "private fun performDiscardAndResetPan")

        assertTrue(clear.indexOf("KaoPanHelper.clearKaoPan()") < clear.indexOf("resolveReturnBindingConflictAfterAllSourcesCleared"))
        assertTrue(discard.indexOf("KaoPanHelper.clearKaoPan()") < discard.indexOf("resolveReturnBindingConflictAfterAllSourcesCleared"))
        assertTrue(clear.contains("expectedReturnConflict"))
        assertTrue(discard.contains("expectedReturnConflict"))
    }

    @Test
    fun centralMoveGate_shouldRejectConflictBeforeJournalAndRegisterPath() {
        val move = body(operate, "private suspend fun moveKaoPanToKaoPanInternal")

        assertTrue(move.contains("enforceReturnBindingConflictMaintenanceLocked"))
        assertTrue(move.indexOf("enforceReturnBindingConflictMaintenanceLocked") < move.indexOf("panMoveJournalStore.saveDurable"))
        assertFalse(fragment.contains("moveKaoPanToKaoPanManual"))
    }

    @Test
    fun ordinaryClearAndConfirmation_shouldRejectDedicatedConflict() {
        val clear = body(operate, "fun clearPendingSupplementBatchIntent(reason: String)")
        val confirm = body(operate, "suspend fun confirmPendingSupplementBatchSafety")

        assertTrue(clear.contains("returnBindingConflictSnapshot"))
        assertTrue(clear.contains("return"))
        assertTrue(confirm.contains("returnBindingConflictActive"))
        assertTrue(confirm.contains("普通确认不能解除"))
    }

    @Test
    fun everyPollIntentQuery_shouldRelockMaintenanceAndPublishRuntimeAndAvailable() {
        val accessor = body(operate, "fun activeSupplementBatchIntentForUi()")
        val enforce = body(operate, "private fun enforceReturnBindingConflictMaintenanceLocked")

        assertTrue(accessor.contains("enforceReturnBindingConflictMaintenanceLocked"))
        assertTrue(enforce.contains("publishServiceUpdateStatus"))
        assertTrue(enforce.contains("publishAvailableState"))
        assertTrue(enforce.contains("DataManagementAPI.uploadDeviceInfo"))
    }

    @Test
    fun genericResume_shouldUseAdmissionResultAndStartHeatingOnlyAfterAdmission() {
        val low = source("app/src/main/java/cn/niuyannet/kaochang/android/services/LowCurrentTrafficMode.kt")
        val high = source("app/src/main/java/cn/niuyannet/kaochang/android/services/HighCurrentTrafficMode.kt")
        val lowResume = body(low, "private suspend fun resumeLowHeatSupplementGroup")
        val highResume = body(high, "private suspend fun resumeIncompleteHighHeatSupplement")

        assertTrue(lowResume.contains("moveSausagesToKaoPansForResume"))
        assertTrue(lowResume.contains("attempt.admitted"))
        assertTrue(lowResume.contains("onAdmitted"))
        assertTrue(highResume.contains("moveSausagesToKaoPansForResume"))
        assertTrue(highResume.contains("attempt.admitted"))
        assertTrue(highResume.contains("onAdmitted"))
    }

    private fun source(path: String): String {
        val direct = File(path)
        val file = if (direct.exists()) direct else File("..", path)
        require(file.exists()) { "Missing source: $path" }
        return file.readText().replace("\r\n", "\n")
    }

    private fun body(text: String, signature: String): String {
        val start = text.indexOf(signature)
        require(start >= 0) { "Missing function: $signature" }
        val brace = text.indexOf('{', start)
        var depth = 0
        for (index in brace until text.length) {
            when (text[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(brace + 1, index)
            }
        }
        error("Unclosed function: $signature")
    }
}
