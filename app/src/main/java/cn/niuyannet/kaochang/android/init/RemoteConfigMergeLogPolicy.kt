package cn.niuyannet.kaochang.android.init

/** 仅用于区分正常本地运行态保留与需要人工关注的配置异常。 */
internal object RemoteConfigMergeLogPolicy {
    private val routineBaseFields = setOf(
        "faultBakingPauseAbortMs",
        "faultPauseAnomalyMs",
        "httpFallbackMinIntervalMs"
    )

    fun isRoutinePreservation(fields: Collection<String>, parseIssues: Collection<String>): Boolean {
        if (fields.isEmpty() || parseIssues.isNotEmpty()) return false
        return fields.all { field ->
            val baseField = field.substringBefore('(')
            field.endsWith("(runtime_local)") ||
                field.endsWith("(local_runtime_preserved)") ||
                baseField in routineBaseFields ||
                field == "status(normalized_from_onlineStatus)"
        }
    }
}
