package cn.niuyannet.kaochang.android.services

enum class SupplementPipelineOutcome {
    SUCCESS,
    PAUSED_FOR_ORDER,
    SUPPLY_DEGRADED,
    FAILED,
    CANCELLED,
    RECOVERY_FAILED
}

object SupplementPipelineFailureRetentionPolicy {
    fun shouldRetain(
        evidenceActive: Boolean,
        outcome: SupplementPipelineOutcome,
        safeReleaseConfirmed: Boolean
    ): Boolean {
        if (!evidenceActive) return false
        return when (outcome) {
            SupplementPipelineOutcome.SUCCESS,
            SupplementPipelineOutcome.PAUSED_FOR_ORDER,
            SupplementPipelineOutcome.SUPPLY_DEGRADED -> !safeReleaseConfirmed
            SupplementPipelineOutcome.FAILED,
            SupplementPipelineOutcome.CANCELLED,
            SupplementPipelineOutcome.RECOVERY_FAILED -> true
        }
    }
}

sealed class SupplementPipelineCompletionResult {
    data object Success : SupplementPipelineCompletionResult()
    data class Failed(
        val reason: String,
        val cause: Throwable,
        val restoreFailure: Throwable? = null
    ) : SupplementPipelineCompletionResult()
}

object SupplementPipelineCompletionCoordinator {
    fun complete(
        clearSession: () -> Boolean,
        persistIdle: () -> Unit,
        releaseLock: () -> Unit,
        publish: () -> Unit,
        restoreBusy: () -> Unit
    ): SupplementPipelineCompletionResult {
        return try {
            persistIdle()
            publish()
            check(clearSession()) { "双 lane 会话版本变化，禁止误清" }
            releaseLock()
            SupplementPipelineCompletionResult.Success
        } catch (error: Throwable) {
            val restoreFailure = runCatching(restoreBusy).exceptionOrNull()
            SupplementPipelineCompletionResult.Failed(
                reason = error.message ?: error::class.java.simpleName,
                cause = error,
                restoreFailure = restoreFailure
            )
        }
    }
}
