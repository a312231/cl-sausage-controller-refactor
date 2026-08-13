package cn.niuyannet.kaochang.android.services.scheduler

import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupSessionType
import cn.niuyannet.kaochang.scheduler.SchedulerProfileSwitchBlocker
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SchedulerProfileSwitchReadinessTest {

    @Test
    fun safeEvidence_isAvailableAndHasNoRuntimeBlocker() {
        val result = SchedulerProfileSwitchReadinessMapper.evaluate(safeEvidence())

        assertEquals(SchedulerProfileSwitchReadinessCode.AVAILABLE, result.code)
        assertTrue(result.issues.isEmpty())
        assertTrue(result.diagnostics.isEmpty())
        val runtime = requireNotNull(result.runtimeSnapshot)
        assertTrue(runtime.deviceIdle)
        assertFalse(runtime.hasActiveOrder)
        assertFalse(runtime.hasWholeOrderClaim)
        assertFalse(runtime.hasOutActionSession)
        assertFalse(runtime.hasSupplementOwner)
        assertFalse(runtime.hasCleaningOwner)
        assertFalse(runtime.hasMaintenanceOwner)
    }

    @Test
    fun allOwnerCategories_areMappedWithoutDroppingConcurrentBlockers() {
        val result = SchedulerProfileSwitchReadinessMapper.evaluate(
            safeEvidence().copy(
                config = safeConfig().copy(moveStatus = 5, onlineStatus = 3, errorStatus = 2),
                owners = safeOwners().copy(
                    hasProcessingOrder = true,
                    hasWholeOrderClaim = true,
                    actionGroupSessionType = ActionGroupSessionType.OUT_SELL,
                    hasPausedSupplementSession = true,
                    resourceLocksIdle = false,
                    actionGroupRecoveryVerified = false
                )
            )
        )

        val runtime = requireNotNull(result.runtimeSnapshot)
        assertEquals(SchedulerProfileSwitchReadinessCode.BLOCKED, result.code)
        assertFalse(runtime.deviceIdle)
        assertTrue(runtime.hasActiveOrder)
        assertTrue(runtime.hasWholeOrderClaim)
        assertTrue(runtime.hasOutActionSession)
        assertTrue(runtime.hasSupplementOwner)
        assertTrue(runtime.hasCleaningOwner)
        assertTrue(runtime.hasMaintenanceOwner)
        assertEquals(
            listOf(
                SchedulerProfileSwitchBlocker.DEVICE_NOT_IDLE,
                SchedulerProfileSwitchBlocker.ACTIVE_ORDER,
                SchedulerProfileSwitchBlocker.WHOLE_ORDER_CLAIM,
                SchedulerProfileSwitchBlocker.OUT_ACTION_SESSION,
                SchedulerProfileSwitchBlocker.SUPPLEMENT_OWNER,
                SchedulerProfileSwitchBlocker.CLEANING_OWNER,
                SchedulerProfileSwitchBlocker.MAINTENANCE_OWNER
            ),
            result.diagnostics.map { it.blocker }
        )
    }

    @Test
    fun everyLegacyFlowResidue_blocksTheExpectedCategory() {
        val deviceBusyCases = listOf(
            safeEvidence().copy(config = safeConfig().copy(isEnable = 1)),
            safeEvidence().copy(owners = safeOwners().copy(algorithmIsEnabled = true)),
            safeEvidence().copy(config = safeConfig().copy(onlineStatus = 4)),
            safeEvidence().copy(owners = safeOwners().copy(hasActiveCookingBatch = true)),
            safeEvidence().copy(config = safeConfig().copy(transitionMode = 1)),
            safeEvidence().copy(config = safeConfig().copy(highConcurrencyResidualDrain = 1)),
            safeEvidence().copy(config = safeConfig().copy(faultHeatPauseActive = 1)),
            safeEvidence().copy(config = safeConfig().copy(hasFaultHeatRecoveryJournal = true)),
            safeEvidence().copy(config = safeConfig().copy(hasPendingDiscardPositions = true)),
            safeEvidence().copy(config = safeConfig().copy(pendingDisableActionGroupV2 = 1)),
            safeEvidence().copy(owners = safeOwners().copy(hasPanMoveJournal = true)),
            safeEvidence().copy(owners = safeOwners().copy(hasDiscardJournal = true)),
            safeEvidence().copy(owners = safeOwners().copy(hasEmergencyResetFence = true))
        )
        deviceBusyCases.forEach { evidence ->
            assertFalse(requireNotNull(SchedulerProfileSwitchReadinessMapper.evaluate(evidence).runtimeSnapshot).deviceIdle)
        }

        val outCases = listOf(
            safeOwners().copy(actionGroupSessionType = ActionGroupSessionType.OUT_MANUAL),
            safeOwners().copy(manualTakeTaskActive = true),
            safeOwners().copy(manualOutRecoveryReserved = true)
        )
        outCases.forEach { owners ->
            assertTrue(requireNotNull(SchedulerProfileSwitchReadinessMapper.evaluate(safeEvidence().copy(owners = owners)).runtimeSnapshot).hasOutActionSession)
        }

        val supplementCases = listOf(
            safeOwners().copy(actionGroupSessionType = ActionGroupSessionType.IN_SUPPLEMENT),
            safeOwners().copy(hasPausedSupplementSession = true),
            safeOwners().copy(hasPrefetchSession = true),
            safeOwners().copy(hasSupplementBatchIntentEvidence = true),
            safeOwners().copy(supplementResumeRunning = true)
        )
        supplementCases.forEach { owners ->
            assertTrue(requireNotNull(SchedulerProfileSwitchReadinessMapper.evaluate(safeEvidence().copy(owners = owners)).runtimeSnapshot).hasSupplementOwner)
        }

        val maintenanceCases = listOf(
            safeEvidence().copy(config = safeConfig().copy(inspectionMode = 1)),
            safeEvidence().copy(config = safeConfig().copy(onlineStatus = 3)),
            safeEvidence().copy(config = safeConfig().copy(errorStatus = 1)),
            safeEvidence().copy(config = safeConfig().copy(useActionGroupV2 = 0)),
            safeEvidence().copy(owners = safeOwners().copy(actionGroupRecoveryVerified = false)),
            safeEvidence().copy(owners = safeOwners().copy(hasGuardedSupplementCloseoutResidue = true))
        )
        maintenanceCases.forEach { evidence ->
            assertTrue(requireNotNull(SchedulerProfileSwitchReadinessMapper.evaluate(evidence).runtimeSnapshot).hasMaintenanceOwner)
        }
    }

    @Test
    fun illegalConfigValues_areUnavailableInsteadOfBeingFoldedIntoIdle() {
        val illegalConfigs = listOf(
            safeConfig().copy(moveStatus = -1),
            safeConfig().copy(moveStatus = 6),
            safeConfig().copy(onlineStatus = -1),
            safeConfig().copy(onlineStatus = 5),
            safeConfig().copy(inspectionMode = 2),
            safeConfig().copy(errorStatus = 3),
            safeConfig().copy(isEnable = 2),
            safeConfig().copy(modeType = 0),
            safeConfig().copy(modeType = 3),
            safeConfig().copy(transitionMode = 3),
            safeConfig().copy(highConcurrencyResidualDrain = 2),
            safeConfig().copy(faultHeatPauseActive = 2),
            safeConfig().copy(pendingDisableActionGroupV2 = 2),
            safeConfig().copy(useActionGroupV2 = 2)
        )

        illegalConfigs.forEach { config ->
            val result = SchedulerProfileSwitchReadinessMapper.evaluate(safeEvidence().copy(config = config))
            assertEquals(SchedulerProfileSwitchReadinessCode.UNAVAILABLE, result.code)
            assertNull(result.runtimeSnapshot)
            assertEquals(listOf(SchedulerProfileSwitchReadinessIssue.INVALID_RUNTIME_CONFIG), result.issues)
        }
    }

    @Test
    fun anyEvidenceIssue_makesTheWholeResultUnavailableAndCollectionsAreImmutable() {
        val result = SchedulerProfileSwitchReadinessMapper.evaluate(
            safeEvidence().copy(
                issues = listOf(
                    SchedulerProfileSwitchReadinessIssue.ACTION_GROUP_SESSION_CORRUPTED,
                    SchedulerProfileSwitchReadinessIssue.ORDER_CLAIM_READ_FAILED
                )
            )
        )

        assertEquals(SchedulerProfileSwitchReadinessCode.UNAVAILABLE, result.code)
        assertNull(result.runtimeSnapshot)
        assertEquals(2, result.issues.size)
        try {
            (result.issues as MutableList).add(SchedulerProfileSwitchReadinessIssue.CONFIG_READ_FAILED)
            fail("issues must be immutable")
        } catch (_: UnsupportedOperationException) {
        }

        val available = SchedulerProfileSwitchReadinessMapper.evaluate(
            safeEvidence().copy(config = safeConfig().copy(moveStatus = 1))
        )
        assertEquals(SchedulerProfileSwitchReadinessCode.BLOCKED, available.code)
        try {
            (available.diagnostics as MutableList).add(SchedulerProfileSwitchReadinessDiagnostic.ACTIVE_ORDER)
            fail("diagnostics must be immutable")
        } catch (_: UnsupportedOperationException) {
        }
    }

    @Test
    fun evidenceCapture_onlyConvertsExpectedBusinessExceptions() {
        val issues = mutableListOf<SchedulerProfileSwitchReadinessIssue>()
        val value = captureSchedulerProfileSwitchEvidence<Unit>(
            issue = SchedulerProfileSwitchReadinessIssue.ORDER_CLAIM_READ_FAILED,
            issues = issues
        ) { throw IllegalStateException("broken") }
        assertNull(value)
        assertEquals(listOf(SchedulerProfileSwitchReadinessIssue.ORDER_CLAIM_READ_FAILED), issues)

        assertPropagates<AssertionError> {
            captureSchedulerProfileSwitchEvidence<Unit>(
                SchedulerProfileSwitchReadinessIssue.ORDER_CLAIM_READ_FAILED,
                mutableListOf()
            ) { throw AssertionError("fatal") }
        }
        assertPropagates<CancellationException> {
            captureSchedulerProfileSwitchEvidence<Unit>(
                SchedulerProfileSwitchReadinessIssue.ORDER_CLAIM_READ_FAILED,
                mutableListOf()
            ) { throw CancellationException("cancel") }
        }

        Thread.interrupted()
        try {
            assertPropagates<InterruptedException> {
                captureSchedulerProfileSwitchEvidence<Unit>(
                    SchedulerProfileSwitchReadinessIssue.ORDER_CLAIM_READ_FAILED,
                    mutableListOf()
                ) { throw InterruptedException("interrupt") }
            }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    private fun safeEvidence() = SchedulerProfileSwitchEvidence(
        config = safeConfig(),
        owners = safeOwners(),
        issues = emptyList()
    )

    private fun safeConfig() = SchedulerProfileSwitchConfigEvidence(
        moveStatus = 0,
        onlineStatus = 0,
        inspectionMode = 0,
        errorStatus = 0,
        isEnable = 0,
        modeType = 1,
        transitionMode = 0,
        highConcurrencyResidualDrain = 0,
        faultHeatPauseActive = 0,
        hasFaultHeatRecoveryJournal = false,
        hasPendingDiscardPositions = false,
        pendingDisableActionGroupV2 = 0,
        useActionGroupV2 = 1
    )

    private fun safeOwners() = SchedulerProfileSwitchOwnerEvidence(
        algorithmIsEnabled = false,
        hasActiveCookingBatch = false,
        hasProcessingOrder = false,
        hasOrderPause = false,
        hasWholeOrderClaim = false,
        actionGroupSessionType = null,
        hasPausedSupplementSession = false,
        hasPrefetchSession = false,
        hasSupplementBatchIntentEvidence = false,
        resourceLocksIdle = true,
        hasPanMoveJournal = false,
        hasDiscardJournal = false,
        hasEmergencyResetFence = false,
        manualTakeTaskActive = false,
        manualOutRecoveryReserved = false,
        supplementResumeRunning = false,
        actionGroupRecoveryVerified = true,
        hasGuardedSupplementCloseoutResidue = false
    )

    private inline fun <reified T : Throwable> assertPropagates(block: () -> Unit) {
        var captured: Throwable? = null
        try {
            block()
        } catch (error: Throwable) {
            captured = error
        }
        if (captured == null) fail("expected ${T::class.java.simpleName}")
        if (captured !is T) throw requireNotNull(captured)
    }
}
