package cn.niuyannet.kaochang.android.services.reset

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ResetOrderWiringSourceTest {

    @Test
    fun incomingOrder_shouldPersistEvidenceBeforeMechanicalAdmission() {
        val source = source("app/src/main/java/cn/niuyannet/kaochang/android/ui/MainActivity.kt")
        val persist = source.indexOf("prepareResetOrderEvidence")
        val admission = source.indexOf("acceptIncomingOrderForMechanicalDispatch")

        assertTrue(persist >= 0)
        assertTrue(admission > persist)
    }

    @Test
    fun incomingOrder_shouldReconcileOnlyDurablyTerminalStaleEvidenceBeforeAdmission() {
        val source = source("app/src/main/java/cn/niuyannet/kaochang/android/ui/OrderActivity.kt")
        val prepareFunction = source.substring(
            source.indexOf("protected fun prepareResetOrderEvidence"),
            source.indexOf("private fun reconcileTerminalResetOrderEvidence")
        )
        val reconcile = prepareFunction.indexOf("reconcileTerminalResetOrderEvidence")
        val prepare = prepareFunction.indexOf("prepareOrderDurable")
        val reconcileFunction = source.substring(
            source.indexOf("private fun reconcileTerminalResetOrderEvidence"),
            source.indexOf("protected fun clearResetOrderEvidence")
        )

        assertTrue(reconcile >= 0)
        assertTrue(prepare > reconcile)
        assertTrue(reconcileFunction.contains("readDurableTerminalStateForReset"))
        assertTrue(reconcileFunction.contains("STATE_FINISHED"))
        assertTrue(reconcileFunction.contains("STATE_FAILED"))
        assertTrue(reconcileFunction.contains("clearOrderDurable"))
    }

    @Test
    fun out4Success_shouldPersistResetEvidenceBeforeContinuingToOut5() {
        val orderSource = source("app/src/main/java/cn/niuyannet/kaochang/android/ui/OrderActivity.kt")
        val operateSource = source("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val telemetryFunction = operateSource.substring(
            operateSource.indexOf("private fun persistOutTerminalEventFromTelemetry"),
            operateSource.indexOf("suspend fun ensureActionGroupReadyForOrderDispatch")
        )
        val confirm = telemetryFunction.indexOf("confirmOut4Durable")
        val pendingOrderEvent = telemetryFunction.indexOf("savePendingActionGroupOrderEvent")

        assertFalse(orderSource.contains("confirmResetOrderOut4"))
        assertTrue(telemetryFunction.contains("isConfirmedOut4Delivery"))
        assertTrue(confirm >= 0)
        assertTrue(pendingOrderEvent > confirm)
    }

    private fun source(path: String): String {
        val appRelative = path.removePrefix("app/")
        return listOf(File(path), File(appRelative)).first { it.exists() }.readText()
    }
}
