package cn.niuyannet.kaochang.android.services

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupplementBatchHeatPositionWiringSourceTest {

    @Test
    fun lowTraffic_shouldKeepPendingBeforeStopAndStopDeduplicationOrder() {
        val source = sourceText("LowCurrentTrafficMode.kt")
        val pendingReturn = source.indexOf(
            "if (pendingBatchHandling != KaoChangOperate.PendingSupplementBatchHandling.NONE"
        )
        val stopBranch = source.indexOf(
            "if (supplementPlan.action == LowHeatAction.STOP_ZONE_2 && TEMPERATURE_OFF != 1)"
        )
        val setStopped = source.indexOf("TEMPERATURE_OFF = 1", stopBranch)
        val stopHeating = source.indexOf("KaoChangOperate.stopHeating(2)", setStopped)

        assertTrue(pendingReturn >= 0)
        assertTrue(stopBranch > pendingReturn)
        assertTrue(setStopped > stopBranch)
        assertTrue(stopHeating > setStopped)
        assertTrue(source.contains("batchHeatPositionStrategy.planLowHeat("))
        assertFalse(source.contains("val p13_18 = p13_15 + p16_18"))
    }

    @Test
    fun highTraffic_shouldConsumeSteadyPlanOnlyAfterRecoveryInitializationAndBlockedFrontCheck() {
        val source = sourceText("HighCurrentTrafficMode.kt")
        val pending = source.indexOf("val pendingBatchHandling = if (")
        val incomplete = source.indexOf("HighHeatSupplementResumePlanner.hasIncompleteBatch(list25_33)")
        val initialization = source.indexOf("else if (totalEmpty == 30)")
        val steadyBranch = source.indexOf("else if (p1_21 >= 9)")
        val blockedFront = source.indexOf("blockedEmptyFrontPositions", steadyBranch)
        val corePlan = source.indexOf("batchHeatPositionStrategy.planHighHeatSteady(", steadyBranch)

        assertTrue(pending >= 0)
        assertTrue(incomplete > pending)
        assertTrue(initialization > incomplete)
        assertTrue(steadyBranch > initialization)
        assertTrue(blockedFront > steadyBranch)
        assertTrue(corePlan > blockedFront)
    }

    @Test
    fun highPlanner_shouldValidateAllTargetsBeforeWritingTaste() {
        val source = sourceText("HighHeatSupplementResumePlanner.kt")
        val functionStart = source.indexOf("fun planInitialTargets(")
        val functionEnd = source.indexOf("fun planResumeTargets(", functionStart)
        val body = source.substring(functionStart, functionEnd)
        val corePlan = body.indexOf("batchHeatPositionStrategy.planPositions(")
        val validation = body.indexOf("if (!isValidInitialPlan(targets)")
        val validatedMappings = body.indexOf("validateInitialTargetsBeforeTasteWrite(")
        val firstTasteWrite = body.indexOf("returnPan.taste = Taste(")

        assertTrue(corePlan >= 0)
        assertTrue(validation > corePlan)
        assertTrue(validatedMappings > validation)
        assertTrue(firstTasteWrite > validatedMappings)
        assertFalse(body.substring(0, validation).contains(".taste = Taste("))
        val validationBodyStart = source.indexOf("internal fun validateInitialTargetsBeforeTasteWrite(")
        val validationBody = source.substring(validationBodyStart)
        assertTrue(validationBody.contains("targetPan !== target.kaoPan || targetPan.isHasSausage"))
        assertTrue(validationBody.contains("returnPosition in excludedReturnPositions"))
        assertTrue(validationBody.contains("returnPosition in committedReturnTasteByPosition"))
        assertTrue(validationBody.contains("returnPosition !in futureReturnPositions && returnPan.isHasSausage"))
        assertTrue(body.contains("frontPan !== write.expectedPan"))
        assertTrue(body.contains("frontPan.positionSn != write.expectedPositionSn"))
        assertTrue(body.contains("!frontPan.taste?.tasteCode.isNullOrBlank()"))
    }

    @Test
    fun highTraffic_shouldPassDeferredReservationWritesIntoBothInitialPlanners() {
        val source = sourceText("HighCurrentTrafficMode.kt")
        val firstPlan = source.indexOf("HighHeatSupplementResumePlanner.planInitialTargets(")
        val firstReservationPlan = source.lastIndexOf(
            "TrafficModeHelper.planMissingTasteReservations",
            firstPlan
        )
        val firstDeferredWrite = source.indexOf("frontTasteWritesBeforeTargetCommit", firstPlan)
        val secondPlan = source.indexOf("HighHeatSupplementResumePlanner.planInitialTargets(", firstPlan + 1)
        val secondReservationPlan = source.lastIndexOf(
            "TrafficModeHelper.planMissingTasteReservations",
            secondPlan
        )
        val secondDeferredWrite = source.indexOf("frontTasteWritesBeforeTargetCommit", secondPlan)

        assertTrue(firstReservationPlan >= 0 && firstReservationPlan < firstPlan)
        assertTrue(firstDeferredWrite > firstPlan)
        assertTrue(secondReservationPlan > firstPlan && secondReservationPlan < secondPlan)
        assertTrue(secondDeferredWrite > secondPlan)
    }

    private fun sourceText(name: String): String {
        val direct = File("src/main/java/cn/niuyannet/kaochang/android/services/$name")
        if (direct.exists()) return direct.readText().replace("\r\n", "\n")
        return File(
            "app/src/main/java/cn/niuyannet/kaochang/android/services/$name"
        ).readText().replace("\r\n", "\n")
    }
}
