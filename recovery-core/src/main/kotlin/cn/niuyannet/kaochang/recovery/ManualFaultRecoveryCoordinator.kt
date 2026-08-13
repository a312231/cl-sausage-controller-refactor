package cn.niuyannet.kaochang.recovery

data class ManualFaultRecoveryState(
    val onlineStatus: Int,
    val errorStatus: Int,
    val errorCode: String?,
    val errorCategory: String?,
    val errorReason: String?,
    val isEnable: Int
)

interface ManualFaultRecoveryStateStore {
    fun snapshot(): ManualFaultRecoveryState
    fun saveRecovered(targetOnlineStatus: Int)
    fun restoreFault(snapshot: ManualFaultRecoveryState)
}

enum class ManualFaultRecoveryCode {
    INITIAL_CONDITION_CHANGED,
    INITIAL_CONDITION_EXCEPTION,
    STATE_SNAPSHOT_EXCEPTION,
    ALGORITHM_DISABLE_EXCEPTION,
    HEATING_RESTORE_EXCEPTION,
    HEATING_RESTORE_FAILED,
    COMMIT_GUARD_CHANGED,
    COMMIT_GUARD_EXCEPTION,
    DURABLE_SAVE_FAILED
}

data class ManualFaultRecoveryRollbackOutcome(
    val faultStateRestored: Boolean,
    val targetTemperatureZeroWritesSucceeded: Boolean,
    val faultStateRestoreError: Throwable? = null,
    val targetTemperatureZeroWriteError: Throwable? = null
) {
    val allRollbackOperationsSucceeded: Boolean
        get() = faultStateRestored && targetTemperatureZeroWritesSucceeded
}

sealed class ManualFaultRecoveryResult {
    data class Success(
        val previous: ManualFaultRecoveryState,
        val recoveredOnlineStatus: Int
    ) : ManualFaultRecoveryResult()

    data class Rejected(
        val code: ManualFaultRecoveryCode,
        val reason: String,
        val rollbackOutcome: ManualFaultRecoveryRollbackOutcome? = null
    ) : ManualFaultRecoveryResult()

    data class Failed(
        val code: ManualFaultRecoveryCode,
        val reason: String,
        val cause: Throwable? = null,
        val rollbackOutcome: ManualFaultRecoveryRollbackOutcome? = null
    ) : ManualFaultRecoveryResult()
}

class ManualFaultRecoveryCoordinator(
    private val transitionLock: Any,
    private val stateStore: ManualFaultRecoveryStateStore
) {
    fun recover(
        targetOnlineStatus: Int,
        initialCondition: () -> Boolean,
        commitGuard: () -> Boolean,
        forceAlgorithmDisabled: () -> Unit,
        restoreHeating: () -> Boolean,
        stopHeating: () -> Boolean
    ): ManualFaultRecoveryResult = synchronized(transitionLock) {
        val initialConditionMet = try {
            initialCondition()
        } catch (error: Exception) {
            return@synchronized ManualFaultRecoveryResult.Failed(
                ManualFaultRecoveryCode.INITIAL_CONDITION_EXCEPTION,
                "恢复前运行态检查异常",
                error
            )
        }
        if (!initialConditionMet) {
            return@synchronized ManualFaultRecoveryResult.Rejected(
                ManualFaultRecoveryCode.INITIAL_CONDITION_CHANGED,
                "恢复提交前运行态已变化"
            )
        }
        val previous = try {
            stateStore.snapshot()
        } catch (error: Exception) {
            return@synchronized ManualFaultRecoveryResult.Failed(
                ManualFaultRecoveryCode.STATE_SNAPSHOT_EXCEPTION,
                "故障状态快照读取异常",
                error
            )
        }
        try {
            forceAlgorithmDisabled()
        } catch (fatal: Error) {
            rollbackThenRethrow(previous, stopHeating, fatal)
        } catch (error: Exception) {
            return@synchronized ManualFaultRecoveryResult.Failed(
                ManualFaultRecoveryCode.ALGORITHM_DISABLE_EXCEPTION,
                "关闭烤制算法异常",
                error,
                restoreFaultAndStop(previous, stopHeating)
            )
        }

        val heatingRestored = try {
            restoreHeating()
        } catch (fatal: Error) {
            rollbackThenRethrow(previous, stopHeating, fatal)
        } catch (error: Exception) {
            return@synchronized ManualFaultRecoveryResult.Failed(
                ManualFaultRecoveryCode.HEATING_RESTORE_EXCEPTION,
                "温控恢复异常",
                error,
                restoreFaultAndStop(previous, stopHeating)
            )
        }
        if (!heatingRestored) {
            return@synchronized ManualFaultRecoveryResult.Failed(
                ManualFaultRecoveryCode.HEATING_RESTORE_FAILED,
                "温控恢复未全部成功",
                rollbackOutcome = restoreFaultAndStop(previous, stopHeating)
            )
        }
        val commitAllowed = try {
            commitGuard()
        } catch (fatal: Error) {
            rollbackThenRethrow(previous, stopHeating, fatal)
        } catch (error: Exception) {
            return@synchronized ManualFaultRecoveryResult.Failed(
                ManualFaultRecoveryCode.COMMIT_GUARD_EXCEPTION,
                "温控恢复后运行态复查异常",
                error,
                restoreFaultAndStop(previous, stopHeating)
            )
        }
        if (!commitAllowed) {
            return@synchronized ManualFaultRecoveryResult.Rejected(
                ManualFaultRecoveryCode.COMMIT_GUARD_CHANGED,
                "温控恢复后运行态已变化",
                restoreFaultAndStop(previous, stopHeating)
            )
        }

        try {
            stateStore.saveRecovered(targetOnlineStatus)
        } catch (fatal: Error) {
            rollbackThenRethrow(previous, stopHeating, fatal)
        } catch (error: Exception) {
            return@synchronized ManualFaultRecoveryResult.Failed(
                ManualFaultRecoveryCode.DURABLE_SAVE_FAILED,
                "恢复状态耐久保存失败",
                error,
                restoreFaultAndStop(previous, stopHeating)
            )
        }
        ManualFaultRecoveryResult.Success(previous, targetOnlineStatus)
    }

    private fun restoreFaultAndStop(
        previous: ManualFaultRecoveryState,
        stopHeating: () -> Boolean
    ): ManualFaultRecoveryRollbackOutcome {
        var faultStateRestored = false
        var targetTemperatureZeroWritesSucceeded = false
        var faultStateRestoreError: Exception? = null
        var targetTemperatureZeroWriteError: Exception? = null
        var fatalError: Error? = null

        try {
            stateStore.restoreFault(previous)
            faultStateRestored = true
        } catch (error: Error) {
            fatalError = error
        } catch (error: Exception) {
            faultStateRestoreError = error
        }
        try {
            targetTemperatureZeroWritesSucceeded = stopHeating()
        } catch (error: Error) {
            if (fatalError == null) {
                fatalError = error
            } else {
                fatalError.addSuppressed(error)
            }
        } catch (error: Exception) {
            targetTemperatureZeroWriteError = error
        }
        fatalError?.let { fatal ->
            faultStateRestoreError?.let(fatal::addSuppressed)
            targetTemperatureZeroWriteError?.let(fatal::addSuppressed)
            throw fatal
        }
        return ManualFaultRecoveryRollbackOutcome(
            faultStateRestored = faultStateRestored,
            targetTemperatureZeroWritesSucceeded = targetTemperatureZeroWritesSucceeded,
            faultStateRestoreError = faultStateRestoreError,
            targetTemperatureZeroWriteError = targetTemperatureZeroWriteError
        )
    }

    private fun rollbackThenRethrow(
        previous: ManualFaultRecoveryState,
        stopHeating: () -> Boolean,
        fatal: Error
    ): Nothing {
        try {
            val rollback = restoreFaultAndStop(previous, stopHeating)
            rollback.faultStateRestoreError?.let(fatal::addSuppressed)
            rollback.targetTemperatureZeroWriteError?.let(fatal::addSuppressed)
        } catch (rollbackFatal: Error) {
            if (rollbackFatal !== fatal) {
                fatal.addSuppressed(rollbackFatal)
            }
        }
        throw fatal
    }
}
