package cn.niuyannet.kaochang.recovery

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualFaultRecoveryCoordinatorTest {

    @Test
    fun success_shouldKeepCurrentOrderAndCodeFreeSuccessShape() {
        val events = mutableListOf<String>()
        val store = FakeStateStore(events)
        val result = coordinator(store).recover(
            targetOnlineStatus = 1,
            initialCondition = { true },
            commitGuard = { true },
            forceAlgorithmDisabled = { events += "algorithm-off" },
            restoreHeating = { events += "heating-restored"; true },
            stopHeating = { events += "heating-stopped"; true }
        )

        assertTrue(result is ManualFaultRecoveryResult.Success)
        assertEquals(listOf("algorithm-off", "heating-restored", "state-recovered"), events)
    }

    @Test
    fun returnedFailures_shouldHaveStableCodesAndPreserveRollbackOrder() {
        val cases = listOf(
            FailureCase(
                expectedCode = ManualFaultRecoveryCode.HEATING_RESTORE_EXCEPTION,
                expectedReason = "温控恢复异常",
                restoreHeating = { error("temperature exception") },
                commitGuard = { true },
                failSave = false
            ),
            FailureCase(
                expectedCode = ManualFaultRecoveryCode.HEATING_RESTORE_FAILED,
                expectedReason = "温控恢复未全部成功",
                restoreHeating = { false },
                commitGuard = { true },
                failSave = false
            ),
            FailureCase(
                expectedCode = ManualFaultRecoveryCode.COMMIT_GUARD_CHANGED,
                expectedReason = "温控恢复后运行态已变化",
                restoreHeating = { true },
                commitGuard = { false },
                failSave = false
            ),
            FailureCase(
                expectedCode = ManualFaultRecoveryCode.DURABLE_SAVE_FAILED,
                expectedReason = "恢复状态耐久保存失败",
                restoreHeating = { true },
                commitGuard = { true },
                failSave = true
            )
        )

        cases.forEach { case ->
            val events = mutableListOf<String>()
            val store = FakeStateStore(events, failRecoveredSave = case.failSave)
            val result = coordinator(store).recover(
                targetOnlineStatus = 1,
                initialCondition = { true },
                commitGuard = case.commitGuard,
                forceAlgorithmDisabled = { events += "algorithm-off" },
                restoreHeating = { events += "heating-attempt"; case.restoreHeating() },
                stopHeating = { events += "heating-stopped"; true }
            )

            val (code, reason, rollback) = when (result) {
                is ManualFaultRecoveryResult.Rejected -> Triple(result.code, result.reason, result.rollbackOutcome)
                is ManualFaultRecoveryResult.Failed -> Triple(result.code, result.reason, result.rollbackOutcome)
                is ManualFaultRecoveryResult.Success -> error("expected failure")
            }
            assertEquals(case.expectedCode, code)
            assertEquals(case.expectedReason, reason)
            assertEquals(true, rollback?.faultStateRestored)
            assertEquals(true, rollback?.targetTemperatureZeroWritesSucceeded)
            assertEquals(true, rollback?.allRollbackOperationsSucceeded)
            assertEquals("code=${case.expectedCode}", "state-restored", events[events.size - 2])
            assertEquals("code=${case.expectedCode}", "heating-stopped", events.last())
        }
    }

    @Test
    fun initialConditionFalse_shouldRejectWithoutAnyMutation() {
        val events = mutableListOf<String>()
        val store = FakeStateStore(events)

        val result = coordinator(store).recover(
            targetOnlineStatus = 1,
            initialCondition = { false },
            commitGuard = { events += "guard"; true },
            forceAlgorithmDisabled = { events += "algorithm-off" },
            restoreHeating = { events += "heating"; true },
            stopHeating = { events += "stop"; true }
        )

        assertTrue(result is ManualFaultRecoveryResult.Rejected)
        assertEquals(ManualFaultRecoveryCode.INITIAL_CONDITION_CHANGED, (result as ManualFaultRecoveryResult.Rejected).code)
        assertEquals("恢复提交前运行态已变化", result.reason)
        assertEquals(null, result.rollbackOutcome)
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun preMutationCallbackFailures_shouldReturnStableFailureWithoutRollback() {
        val initialError = IllegalStateException("initial")
        val initialEvents = mutableListOf<String>()
        val initialResult = coordinator(FakeStateStore(initialEvents)).recover(
            targetOnlineStatus = 1,
            initialCondition = { throw initialError },
            commitGuard = { initialEvents += "guard"; true },
            forceAlgorithmDisabled = { initialEvents += "algorithm-off" },
            restoreHeating = { initialEvents += "heating"; true },
            stopHeating = { initialEvents += "stop"; true }
        ) as ManualFaultRecoveryResult.Failed
        assertEquals(ManualFaultRecoveryCode.INITIAL_CONDITION_EXCEPTION, initialResult.code)
        assertEquals("恢复前运行态检查异常", initialResult.reason)
        assertSame(initialError, initialResult.cause)
        assertEquals(null, initialResult.rollbackOutcome)
        assertEquals(emptyList<String>(), initialEvents)

        val snapshotError = IllegalStateException("snapshot")
        val snapshotEvents = mutableListOf<String>()
        val snapshotResult = coordinator(FakeStateStore(snapshotEvents, snapshotError = snapshotError)).recover(
            targetOnlineStatus = 1,
            initialCondition = { true },
            commitGuard = { snapshotEvents += "guard"; true },
            forceAlgorithmDisabled = { snapshotEvents += "algorithm-off" },
            restoreHeating = { snapshotEvents += "heating"; true },
            stopHeating = { snapshotEvents += "stop"; true }
        ) as ManualFaultRecoveryResult.Failed
        assertEquals(ManualFaultRecoveryCode.STATE_SNAPSHOT_EXCEPTION, snapshotResult.code)
        assertEquals("故障状态快照读取异常", snapshotResult.reason)
        assertSame(snapshotError, snapshotResult.cause)
        assertEquals(null, snapshotResult.rollbackOutcome)
        assertEquals(emptyList<String>(), snapshotEvents)
    }

    @Test
    fun fatalSignalBeforeSnapshot_shouldPropagateWithoutMutationOrRollback() {
        val fatal = TestFatalError("initial")
        val events = mutableListOf<String>()

        val thrown = try {
            coordinator(FakeStateStore(events)).recover(
                targetOnlineStatus = 1,
                initialCondition = { throw fatal },
                commitGuard = { events += "guard"; true },
                forceAlgorithmDisabled = { events += "algorithm-off" },
                restoreHeating = { events += "heating"; true },
                stopHeating = { events += "stop"; true }
            )
            null
        } catch (error: TestFatalError) {
            error
        }

        assertSame(fatal, thrown)
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun fatalSignalAfterSnapshot_shouldBestEffortRollbackThenRethrowOriginal() {
        val fatal = TestFatalError("force")
        val events = mutableListOf<String>()

        val thrown = try {
            coordinator(FakeStateStore(events)).recover(
                targetOnlineStatus = 1,
                initialCondition = { true },
                commitGuard = { events += "guard"; true },
                forceAlgorithmDisabled = { events += "algorithm-attempt"; throw fatal },
                restoreHeating = { events += "heating"; true },
                stopHeating = { events += "zero-writes"; true }
            )
            null
        } catch (error: TestFatalError) {
            error
        }

        assertSame(fatal, thrown)
        assertEquals(
            listOf("algorithm-attempt", "state-restore-attempt", "state-restored", "zero-writes"),
            events
        )
    }

    @Test
    fun fatalSignalDuringFaultRestore_shouldStillAttemptZeroWritesThenRethrow() {
        val fatal = TestFatalError("restore")
        val events = mutableListOf<String>()

        val thrown = try {
            coordinator(FakeStateStore(events, restoreFaultError = fatal)).recover(
                targetOnlineStatus = 1,
                initialCondition = { true },
                commitGuard = { true },
                forceAlgorithmDisabled = { events += "algorithm-off" },
                restoreHeating = { false },
                stopHeating = { events += "zero-writes"; true }
            )
            null
        } catch (error: TestFatalError) {
            error
        }

        assertSame(fatal, thrown)
        assertEquals(listOf("algorithm-off", "state-restore-attempt", "zero-writes"), events)
    }

    @Test
    fun sameFatalFromBothRollbackPorts_shouldNotSelfSuppressOrReplaceOriginal() {
        val fatal = TestFatalError("shared")
        val events = mutableListOf<String>()

        val thrown = try {
            coordinator(FakeStateStore(events, restoreFaultError = fatal)).recover(
                targetOnlineStatus = 1,
                initialCondition = { true },
                commitGuard = { true },
                forceAlgorithmDisabled = { events += "algorithm-off" },
                restoreHeating = { false },
                stopHeating = { events += "zero-writes"; throw fatal }
            )
            null
        } catch (error: TestFatalError) {
            error
        }

        assertSame(fatal, thrown)
        assertEquals(listOf("algorithm-off", "state-restore-attempt", "zero-writes"), events)
    }

    @Test
    fun postSnapshotCallbackFailures_shouldFailClosedAndPreserveRootCause() {
        val forceError = IllegalStateException("force")
        val forceEvents = mutableListOf<String>()
        val forceResult = coordinator(FakeStateStore(forceEvents)).recover(
            targetOnlineStatus = 1,
            initialCondition = { true },
            commitGuard = { forceEvents += "guard"; true },
            forceAlgorithmDisabled = { forceEvents += "algorithm-attempt"; throw forceError },
            restoreHeating = { forceEvents += "heating"; true },
            stopHeating = { forceEvents += "zero-writes"; true }
        ) as ManualFaultRecoveryResult.Failed
        assertEquals(ManualFaultRecoveryCode.ALGORITHM_DISABLE_EXCEPTION, forceResult.code)
        assertEquals("关闭烤制算法异常", forceResult.reason)
        assertSame(forceError, forceResult.cause)
        assertEquals(true, forceResult.rollbackOutcome?.allRollbackOperationsSucceeded)
        assertEquals(
            listOf("algorithm-attempt", "state-restore-attempt", "state-restored", "zero-writes"),
            forceEvents
        )

        val guardError = IllegalStateException("guard")
        val guardEvents = mutableListOf<String>()
        val guardResult = coordinator(FakeStateStore(guardEvents)).recover(
            targetOnlineStatus = 1,
            initialCondition = { true },
            commitGuard = { guardEvents += "guard-attempt"; throw guardError },
            forceAlgorithmDisabled = { guardEvents += "algorithm-off" },
            restoreHeating = { guardEvents += "heating-restored"; true },
            stopHeating = { guardEvents += "zero-writes"; true }
        ) as ManualFaultRecoveryResult.Failed
        assertEquals(ManualFaultRecoveryCode.COMMIT_GUARD_EXCEPTION, guardResult.code)
        assertEquals("温控恢复后运行态复查异常", guardResult.reason)
        assertSame(guardError, guardResult.cause)
        assertEquals(true, guardResult.rollbackOutcome?.allRollbackOperationsSucceeded)
        assertEquals(
            listOf(
                "algorithm-off",
                "heating-restored",
                "guard-attempt",
                "state-restore-attempt",
                "state-restored",
                "zero-writes"
            ),
            guardEvents
        )
    }

    @Test
    fun rollbackErrors_shouldRemainSeparateAndNeverReplaceOriginalCause() {
        val events = mutableListOf<String>()
        val rootError = IllegalStateException("root")
        val restoreError = IllegalStateException("restore")
        val zeroWriteError = IllegalStateException("zero-write")
        val store = FakeStateStore(events, restoreFaultError = restoreError)

        val result = coordinator(store).recover(
            targetOnlineStatus = 1,
            initialCondition = { true },
            commitGuard = { true },
            forceAlgorithmDisabled = { events += "algorithm-off" },
            restoreHeating = { throw rootError },
            stopHeating = { events += "zero-write-attempt"; throw zeroWriteError }
        ) as ManualFaultRecoveryResult.Failed

        assertEquals(ManualFaultRecoveryCode.HEATING_RESTORE_EXCEPTION, result.code)
        assertSame(rootError, result.cause)
        assertEquals(false, result.rollbackOutcome?.faultStateRestored)
        assertEquals(false, result.rollbackOutcome?.targetTemperatureZeroWritesSucceeded)
        assertSame(restoreError, result.rollbackOutcome?.faultStateRestoreError)
        assertSame(zeroWriteError, result.rollbackOutcome?.targetTemperatureZeroWriteError)
        assertEquals(listOf("algorithm-off", "state-restore-attempt", "zero-write-attempt"), events)
    }

    @Test
    fun zeroWriteFalse_shouldBeDistinguishableFromPortException() {
        val events = mutableListOf<String>()
        val result = coordinator(FakeStateStore(events)).recover(
            targetOnlineStatus = 1,
            initialCondition = { true },
            commitGuard = { false },
            forceAlgorithmDisabled = { events += "algorithm-off" },
            restoreHeating = { events += "heating-restored"; true },
            stopHeating = { events += "zero-writes-returned-false"; false }
        ) as ManualFaultRecoveryResult.Rejected

        assertEquals(ManualFaultRecoveryCode.COMMIT_GUARD_CHANGED, result.code)
        assertEquals(true, result.rollbackOutcome?.faultStateRestored)
        assertEquals(false, result.rollbackOutcome?.targetTemperatureZeroWritesSucceeded)
        assertEquals(null, result.rollbackOutcome?.targetTemperatureZeroWriteError)
        assertEquals(false, result.rollbackOutcome?.allRollbackOperationsSucceeded)
    }

    @Test
    fun rollbackOutcome_shouldRecordEveryPortResultCombination() {
        data class Case(
            val restoreThrows: Boolean,
            val zeroWriteMode: String,
            val expectedRestore: Boolean,
            val expectedZeroWrites: Boolean,
            val expectedRestoreError: Boolean,
            val expectedZeroWriteError: Boolean
        )
        val cases = listOf(
            Case(false, "true", true, true, false, false),
            Case(false, "false", true, false, false, false),
            Case(false, "throw", true, false, false, true),
            Case(true, "true", false, true, true, false),
            Case(true, "false", false, false, true, false),
            Case(true, "throw", false, false, true, true)
        )

        cases.forEach { case ->
            val events = mutableListOf<String>()
            val store = FakeStateStore(
                events,
                restoreFaultError = if (case.restoreThrows) IllegalStateException("restore") else null
            )
            val result = coordinator(store).recover(
                targetOnlineStatus = 1,
                initialCondition = { true },
                commitGuard = { true },
                forceAlgorithmDisabled = {},
                restoreHeating = { false },
                stopHeating = {
                    when (case.zeroWriteMode) {
                        "true" -> true
                        "false" -> false
                        else -> error("zero-write")
                    }
                }
            ) as ManualFaultRecoveryResult.Failed
            val rollback = requireNotNull(result.rollbackOutcome)

            assertEquals("case=$case", case.expectedRestore, rollback.faultStateRestored)
            assertEquals("case=$case", case.expectedZeroWrites, rollback.targetTemperatureZeroWritesSucceeded)
            assertEquals("case=$case", case.expectedRestoreError, rollback.faultStateRestoreError != null)
            assertEquals("case=$case", case.expectedZeroWriteError, rollback.targetTemperatureZeroWriteError != null)
        }
    }

    @Test
    fun twoCoordinatorInstances_shouldSerializeOnSharedTransitionLock() {
        val transitionLock = Any()
        val store = FakeStateStore(Collections.synchronizedList(mutableListOf()))
        val events = Collections.synchronizedList(mutableListOf<String>())
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        fun submit(label: String) = executor.submit(Callable<ManualFaultRecoveryResult> {
            start.await()
            ManualFaultRecoveryCoordinator(transitionLock, store).recover(
                targetOnlineStatus = 1,
                initialCondition = { events += "$label-initial"; true },
                commitGuard = { events += "$label-guard"; true },
                forceAlgorithmDisabled = { events += "$label-off" },
                restoreHeating = {
                    events += "$label-heat-start"
                    Thread.sleep(20)
                    events += "$label-heat-end"
                    true
                },
                stopHeating = { events += "$label-stop"; true }
            )
        })

        val first = submit("A")
        val second = submit("B")
        start.countDown()
        assertTrue(first.get(5, TimeUnit.SECONDS) is ManualFaultRecoveryResult.Success)
        assertTrue(second.get(5, TimeUnit.SECONDS) is ManualFaultRecoveryResult.Success)
        executor.shutdown()

        val labels = events.map { it.substringBefore('-') }
        val transitions = labels.zipWithNext().count { (left, right) -> left != right }
        assertEquals("events=$events", 1, transitions)
    }

    private fun coordinator(store: ManualFaultRecoveryStateStore) =
        ManualFaultRecoveryCoordinator(Any(), store)

    private data class FailureCase(
        val expectedCode: ManualFaultRecoveryCode,
        val expectedReason: String,
        val restoreHeating: () -> Boolean,
        val commitGuard: () -> Boolean,
        val failSave: Boolean
    )

    private class TestFatalError(message: String) : VirtualMachineError(message)

    private class FakeStateStore(
        private val events: MutableList<String>,
        private val failRecoveredSave: Boolean = false,
        private val snapshotError: Throwable? = null,
        private val restoreFaultError: Throwable? = null
    ) : ManualFaultRecoveryStateStore {
        private var state = ManualFaultRecoveryState(3, 1, "E", "fault", "test", 0)

        override fun snapshot(): ManualFaultRecoveryState {
            snapshotError?.let { throw it }
            return state.copy()
        }

        override fun saveRecovered(targetOnlineStatus: Int) {
            if (failRecoveredSave) {
                events += "state-recovered-failed"
                error("durable save failed")
            }
            events += "state-recovered"
            state = state.copy(onlineStatus = targetOnlineStatus, errorStatus = 0, isEnable = 0)
        }

        override fun restoreFault(snapshot: ManualFaultRecoveryState) {
            events += "state-restore-attempt"
            restoreFaultError?.let { throw it }
            events += "state-restored"
            state = snapshot.copy(isEnable = 0)
        }
    }
}
