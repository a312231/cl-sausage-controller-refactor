package cn.niuyannet.kaochang.android.services.actiongroup

enum class SupplementPipelineOrderCloseout {
    PAUSED_ROOT,
    COMPLETED_ROOT,
    RETAIN
}

object SupplementPipelineOrderCloseoutPolicy {
    fun classify(
        parallelEnabled: Boolean,
        pausedAfterAction: String?,
        actionGroupCompleted: Boolean,
        durableStateSaved: Boolean
    ): SupplementPipelineOrderCloseout {
        if (parallelEnabled || !actionGroupCompleted || !durableStateSaved) {
            return SupplementPipelineOrderCloseout.RETAIN
        }
        return when (pausedAfterAction) {
            "in1", "in2", "in3" -> SupplementPipelineOrderCloseout.PAUSED_ROOT
            "in7" -> SupplementPipelineOrderCloseout.COMPLETED_ROOT
            else -> SupplementPipelineOrderCloseout.RETAIN
        }
    }
}
