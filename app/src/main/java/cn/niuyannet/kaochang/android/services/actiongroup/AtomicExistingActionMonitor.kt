package cn.niuyannet.kaochang.android.services.actiongroup

import kotlinx.coroutines.delay

class AtomicExistingActionMonitor(
    private val reader: AtomicActionStateReader,
    private val pollDelayMs: Long = DEFAULT_POLL_DELAY_MS
) {
    constructor(gateway: AtomicModbusGateway, pollDelayMs: Long = DEFAULT_POLL_DELAY_MS) : this(
        reader = object : AtomicActionStateReader {
            override suspend fun readResult(spec: AtomicActionSpec): AtomicReadResult =
                gateway.readRegister(spec.resultRegister)
        },
        pollDelayMs = pollDelayMs
    )

    suspend fun awaitExisting(spec: AtomicActionSpec): ExistingActionObservation {
        val startedAtMs = System.currentTimeMillis()
        while (System.currentTimeMillis() - startedAtMs <= spec.timeoutMs) {
            when (val read = reader.readResult(spec)) {
                is AtomicReadResult.Failure -> return ExistingActionObservation.ReadFailure(read.message)
                is AtomicReadResult.Success -> when (val code = read.value) {
                    AtomicActionCodes.RUNNING -> delay(pollDelayMs.coerceAtLeast(0L))
                    AtomicActionCodes.COMPLETED -> return ExistingActionObservation.Completed
                    AtomicActionCodes.IDLE -> return ExistingActionObservation.Idle
                    in ERROR_CODE_MIN..ERROR_CODE_MAX ->
                        return ExistingActionObservation.Error(code, AtomicActionExecutor.messageOf(code))
                    else -> return ExistingActionObservation.Unknown(code)
                }
            }
        }
        return ExistingActionObservation.Timeout
    }

    private companion object {
        const val DEFAULT_POLL_DELAY_MS = 200L
        const val ERROR_CODE_MIN = AtomicActionCodes.PARAM_ERROR
        const val ERROR_CODE_MAX = AtomicActionCodes.MOVE_STATE_ERROR
    }
}

sealed class ExistingActionObservation {
    data object Completed : ExistingActionObservation()
    data object Idle : ExistingActionObservation()
    data class Error(val code: Int, val message: String) : ExistingActionObservation()
    data class Unknown(val code: Int) : ExistingActionObservation()
    data class ReadFailure(val message: String) : ExistingActionObservation()
    data object Timeout : ExistingActionObservation()
}
