package cn.niuyannet.kaochang.android.services

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

sealed class ManualFaultRecoveryResult {
    data class Success(
        val previous: ManualFaultRecoveryState,
        val recoveredOnlineStatus: Int
    ) : ManualFaultRecoveryResult()

    data class Rejected(val reason: String) : ManualFaultRecoveryResult()
    data class Failed(val reason: String, val cause: Throwable? = null) : ManualFaultRecoveryResult()
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
        stopHeating: () -> Unit
    ): ManualFaultRecoveryResult = synchronized(transitionLock) {
        if (!initialCondition()) {
            return@synchronized ManualFaultRecoveryResult.Rejected("恢复提交前运行态已变化")
        }
        val previous = stateStore.snapshot()
        forceAlgorithmDisabled()

        val heatingRestored = runCatching { restoreHeating() }
            .getOrElse { error ->
                restoreFaultAndStop(previous, stopHeating)
                return@synchronized ManualFaultRecoveryResult.Failed("温控恢复异常", error)
            }
        if (!heatingRestored) {
            restoreFaultAndStop(previous, stopHeating)
            return@synchronized ManualFaultRecoveryResult.Failed("温控恢复未全部成功")
        }
        if (!commitGuard()) {
            restoreFaultAndStop(previous, stopHeating)
            return@synchronized ManualFaultRecoveryResult.Rejected("温控恢复后运行态已变化")
        }

        try {
            stateStore.saveRecovered(targetOnlineStatus)
        } catch (error: Throwable) {
            restoreFaultAndStop(previous, stopHeating)
            return@synchronized ManualFaultRecoveryResult.Failed("恢复状态耐久保存失败", error)
        }
        ManualFaultRecoveryResult.Success(previous, targetOnlineStatus)
    }

    private fun restoreFaultAndStop(
        previous: ManualFaultRecoveryState,
        stopHeating: () -> Unit
    ) {
        runCatching { stateStore.restoreFault(previous) }
        runCatching { stopHeating() }
    }
}
