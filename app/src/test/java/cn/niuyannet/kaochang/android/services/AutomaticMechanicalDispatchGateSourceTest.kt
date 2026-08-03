package cn.niuyannet.kaochang.android.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AutomaticMechanicalDispatchGateSourceTest {

    @Test
    fun orderPending_shouldBlockAutomaticDiscardBeforeNextPan() {
        val scanner = source("app/src/main/java/cn/niuyannet/kaochang/android/services/OverKeepWarmDiscardScanner.kt")
        val loop = scanner.substringAfter("for (pan in", "return ScanResult")

        assertTrue(loop.contains("evaluateMechanicalGate(AppConfig.getAppConfig(), allowMechanicalAction)"))
        assertTrue(loop.contains("if (!nextGate.canStart)"))
        assertTrue(loop.indexOf("nextGate") < loop.indexOf("attempted.add"))
    }

    @Test
    fun automaticDiscard_shouldUseSupplementStartGateButMaintenanceResetMayOverride() {
        val operate = source("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val discardStart = operate.indexOf("suspend fun discardSausage(")
        val discardEnd = operate.indexOf("private fun", discardStart)
        val discard = operate.substring(discardStart, if (discardEnd > discardStart) discardEnd else operate.length)
        assertTrue(discard.contains("automatic: Boolean = true"))
        assertTrue(discard.contains("requireSupplementGate = automatic"))
        assertTrue(discard.contains("isAutomaticDiscardStillAllowed()"))
        val algorithm = source("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangAlgorithm.kt")
        val reset = algorithm.substring(algorithm.indexOf("suspend fun resetKaoPan"), algorithm.indexOf("fun stopServer"))
        assertTrue(reset.contains("automatic = false"))
    }

    @Test
    fun orderPending_shouldBlockUnifiedAutomaticGate() {
        val decision = AutoMechanicalDispatchPolicy.evaluate(
            onlineStatus = 1,
            errorStatus = 0,
            inspectionMode = 0,
            moveStatus = 0,
            orderPending = true
        )
        assertFalse(decision.canStart)
    }

    @Test
    fun ownedAutomaticDiscard_shouldIgnoreItsOwnMoveStatusButRecheckRuntimeSafety() {
        assertTrue(
            AutoMechanicalDispatchPolicy.canIssueAutomaticCommandAfterClaim(
                onlineStatus = 1,
                errorStatus = 0,
                inspectionMode = 0,
                isEnable = 1,
                faultHeatPauseActive = 0,
                orderPending = false,
                lowerPlatformStatus = "normal",
                lowerPlatformIsolationActionName = ""
            )
        )
        assertFalse(
            AutoMechanicalDispatchPolicy.canIssueAutomaticCommandAfterClaim(
                onlineStatus = 1,
                errorStatus = 0,
                inspectionMode = 0,
                isEnable = 1,
                faultHeatPauseActive = 0,
                orderPending = true,
                lowerPlatformStatus = "normal",
                lowerPlatformIsolationActionName = ""
            )
        )
        assertFalse(
            AutoMechanicalDispatchPolicy.canIssueAutomaticCommandAfterClaim(
                onlineStatus = 4,
                errorStatus = 0,
                inspectionMode = 0,
                isEnable = 1,
                faultHeatPauseActive = 0,
                orderPending = false,
                lowerPlatformStatus = "normal",
                lowerPlatformIsolationActionName = ""
            )
        )
    }

    @Test
    fun sharedMechanicalClaim_shouldRejectWhenOrderPauseIsAlreadyVisible() {
        val operate = source("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val claimStart = operate.indexOf("private fun claimMechanicalMove(")
        val claimEnd = operate.indexOf("private fun releaseMechanicalMove(", claimStart)
        val claim = operate.substring(claimStart, claimEnd)

        assertTrue(claim.contains("!SupplementPauseController.shouldPauseForOrder()"))
    }

    @Test
    fun automaticCommands_shouldUseSharedIntentAdmissionBeforeRegisterWrite() {
        val operate = source("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val pauseStart = operate.indexOf("fun requestSupplementPauseForOrder(")
        val pauseEnd = operate.indexOf("suspend fun clearSupplementPauseAndResume", pauseStart)
        val pause = operate.substring(pauseStart, pauseEnd)
        assertTrue(pause.contains("withFaultHeatTransitionLock"))

        val panMoveStart = operate.indexOf("private suspend fun moveKaoPanToKaoPanInternal(")
        val panMoveEnd = operate.indexOf("suspend fun takeSausage(", panMoveStart)
        val panMove = operate.substring(panMoveStart, panMoveEnd)
        val panMoveIntent = panMove.indexOf("admitPanMoveCommandIntent(")
        val panMoveWrite = panMove.indexOf("writeSingleRegister2(action_address_move")
        assertTrue(panMoveIntent >= 0)
        assertTrue(panMoveIntent < panMoveWrite)

        val discardStart = operate.indexOf("suspend fun discardSausage(")
        val discardEnd = operate.indexOf("private fun", discardStart)
        val discard = operate.substring(discardStart, if (discardEnd > discardStart) discardEnd else operate.length)
        val discardIntent = discard.indexOf("admitDiscardCommandIntent(")
        val discardWrite = discard.indexOf("writeSingleRegister(action_address")

        assertTrue(discardIntent >= 0)
        assertTrue(discardIntent < discardWrite)

        val coordinator = source(
            "app/src/main/java/cn/niuyannet/kaochang/android/services/actiongroup/" +
                "AutomaticMechanicalCommandAdmissionCoordinator.kt"
        )
        assertFalse(coordinator.contains("delay("))
        assertFalse(coordinator.contains("writeSingleRegister"))
        assertFalse(coordinator.contains("ResourceLocks"))
        assertFalse(coordinator.contains("actionGroupLocks"))
    }

    @Test
    fun admittedCommands_shouldEnterMaintenanceOnUnexpectedException() {
        val operate = source("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")

        val panMoveStart = operate.indexOf("private suspend fun moveKaoPanToKaoPanInternal(")
        val panMoveEnd = operate.indexOf("suspend fun takeSausage(", panMoveStart)
        val panMove = operate.substring(panMoveStart, panMoveEnd)
        assertTrue(panMove.contains("catch (error: Exception)"))
        assertTrue(panMove.contains("搬盘 COMMAND_INTENT 已准入但流程异常"))

        val discardStart = operate.indexOf("suspend fun discardSausage(")
        val discardEnd = operate.indexOf("private fun", discardStart)
        val discard = operate.substring(discardStart, if (discardEnd > discardStart) discardEnd else operate.length)
        assertTrue(discard.contains("catch (error: Exception)"))
        assertTrue(discard.contains("过保丢弃 COMMAND_INTENT 已准入但流程异常"))
    }

    private fun source(path: String): String = listOf(File(path), File("..", path))
        .firstOrNull { it.isFile }
        ?.readText()
        ?.replace("\r\n", "\n")
        ?: error("source file not found: $path")
}
