package cn.niuyannet.kaochang.android.services

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LowHeatSupplementSafetySourceTest {

    @Test
    fun singleSupplementWithReturnBinding_shouldCreateBatchIntent() {
        val source = sourceText("KaoChangOperate.kt")
        val publicStart = source.indexOf("suspend fun moveSausagesToKaoPansByTaste(")
        val internalStart = source.indexOf("private suspend fun moveSausagesToKaoPansByTasteInternal(", publicStart)
        val publicBlock = source.substring(publicStart, internalStart)

        assertTrue(
            publicBlock.contains(
                "createIntent = targets.size > 1 || targets.any { it.returnPositionSn != null }"
            )
        )
    }

    @Test
    fun pendingBatchRecovery_shouldReuseExistingBatchWithoutCreatingIntent() {
        val source = sourceText("KaoChangOperate.kt")
        val handlerStart = source.indexOf("suspend fun handlePendingSupplementBatchIfAny(")
        val handlerEnd = source.indexOf("private fun isInitializationBatch(", handlerStart)
        val handlerBlock = source.substring(handlerStart, handlerEnd)

        assertTrue(handlerBlock.contains("existingBatchId = intent.batchId"))
        assertTrue(handlerBlock.contains("createIntent = false"))
    }

    @Test
    fun lowHeatReturn_shouldRecalculateTemperatureBeforeMovingWithoutDirectKeepWarmWrites() {
        val source = sourceText("LowCurrentTrafficMode.kt")
        val functionStart = source.indexOf("private suspend fun moveFinishedLowHeatPansToFront(")
        val functionEnd = source.indexOf("private fun logUnfinishedLowHeatPans(", functionStart)
        val functionBlock = source.substring(functionStart, functionEnd)
        val refreshIndex = functionBlock.indexOf("applyConfiguredTemperatureByPanState(")
        val moveLoopIndex = functionBlock.indexOf("readyPans.forEach")

        assertTrue(refreshIndex >= 0)
        assertTrue(moveLoopIndex >= 0)
        assertTrue(refreshIndex < moveLoopIndex)
        assertFalse(functionBlock.contains("KaoChangOperate.keepWarm(1)"))
        assertFalse(functionBlock.contains("KaoChangOperate.keepWarm(2)"))
    }

    private fun sourceText(name: String): String {
        val direct = File("src/main/java/cn/niuyannet/kaochang/android/services/$name")
        if (direct.exists()) {
            return direct.readText().replace("\r\n", "\n")
        }
        val fromParent = File("app/src/main/java/cn/niuyannet/kaochang/android/services/$name")
        return fromParent.readText().replace("\r\n", "\n")
    }
}
