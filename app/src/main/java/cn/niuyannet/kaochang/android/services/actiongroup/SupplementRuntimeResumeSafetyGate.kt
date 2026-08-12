package cn.niuyannet.kaochang.android.services.actiongroup

import kotlin.coroutines.cancellation.CancellationException

sealed class SupplementRuntimeResumeSafetyResult {
    data object Safe : SupplementRuntimeResumeSafetyResult()
    data class Unsafe(val reason: String) : SupplementRuntimeResumeSafetyResult()
}

class SupplementRuntimeResumeSafetyGate(
    private val reader: AtomicActionStateReader,
    private val betweenSnapshots: suspend () -> Unit
) {
    suspend fun verify(resumeAfterAction: String): SupplementRuntimeResumeSafetyResult {
        return try {
            val checkpointIndex = IN_ACTIONS.indexOf(resumeAfterAction)
            if (checkpointIndex < 0) {
                return SupplementRuntimeResumeSafetyResult.Unsafe(
                    "持久化断点非法：resumeAfterAction=$resumeAfterAction"
                )
            }
            val first = readSnapshot()
                ?: return SupplementRuntimeResumeSafetyResult.Unsafe("第一次动作结果寄存器读取失败")
            betweenSnapshots()
            val second = readSnapshot()
                ?: return SupplementRuntimeResumeSafetyResult.Unsafe("第二次动作结果寄存器读取失败")
            when {
                first != second -> SupplementRuntimeResumeSafetyResult.Unsafe("两次动作结果寄存器快照不稳定")
                second.any { (_, value) -> value !in SAFE_STATES } -> {
                    val unsafe = second.filterValues { it !in SAFE_STATES }
                    SupplementRuntimeResumeSafetyResult.Unsafe("动作结果寄存器仍在运行或包含错误：$unsafe")
                }
                IN_ACTIONS.take(checkpointIndex + 1).any {
                    second[it] != AtomicActionCodes.COMPLETED
                } -> {
                    val incomplete = IN_ACTIONS.take(checkpointIndex + 1)
                        .associateWith { second[it] }
                        .filterValues { it != AtomicActionCodes.COMPLETED }
                    SupplementRuntimeResumeSafetyResult.Unsafe(
                        "持久化断点缺少连续完成前缀：checkpoint=$resumeAfterAction，states=$incomplete"
                    )
                }
                IN_ACTIONS.drop(checkpointIndex + 1).any {
                    second[it] == AtomicActionCodes.COMPLETED
                } -> {
                    val advanced = IN_ACTIONS.drop(checkpointIndex + 1)
                        .filter { second[it] == AtomicActionCodes.COMPLETED }
                    SupplementRuntimeResumeSafetyResult.Unsafe(
                        "动作结果已超过持久化断点：checkpoint=$resumeAfterAction，completed=$advanced"
                    )
                }
                else -> SupplementRuntimeResumeSafetyResult.Safe
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            SupplementRuntimeResumeSafetyResult.Unsafe(
                "动作结果寄存器复核异常：${error.message ?: error::class.java.simpleName}"
            )
        }
    }

    private suspend fun readSnapshot(): Map<String, Int>? {
        val snapshot = linkedMapOf<String, Int>()
        for (spec in ActionGroupRecoveryBootstrap.allSpecs) {
            when (val result = reader.readResult(spec)) {
                is AtomicReadResult.Success -> snapshot[spec.name] = result.value
                is AtomicReadResult.Failure -> return null
            }
        }
        return snapshot
    }

    private companion object {
        val IN_ACTIONS = (1..7).map { "in$it" }
        val SAFE_STATES = setOf(AtomicActionCodes.IDLE, AtomicActionCodes.COMPLETED)
    }
}
