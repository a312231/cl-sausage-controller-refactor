package cn.niuyannet.kaochang.android.services.actiongroup

import org.junit.Assert.assertEquals
import org.junit.Test

class SupplementPipelineOrderCloseoutPolicyTest {

    @Test
    fun serialIn7Pause_withCompletedRootAndDurableState_shouldReleaseCompletedRoot() {
        assertEquals(
            SupplementPipelineOrderCloseout.COMPLETED_ROOT,
            SupplementPipelineOrderCloseoutPolicy.classify(
                parallelEnabled = false,
                pausedAfterAction = "in7",
                actionGroupCompleted = true,
                durableStateSaved = true
            )
        )
    }

    @Test
    fun serialIn1ToIn3Pause_shouldKeepResumableRoot() {
        listOf("in1", "in2", "in3").forEach { pausedAfterAction ->
            assertEquals(
                pausedAfterAction,
                SupplementPipelineOrderCloseout.PAUSED_ROOT,
                SupplementPipelineOrderCloseoutPolicy.classify(
                    parallelEnabled = false,
                    pausedAfterAction = pausedAfterAction,
                    actionGroupCompleted = true,
                    durableStateSaved = true
                )
            )
        }
    }

    @Test
    fun unsafeIn7Pause_shouldRetainEvidence() {
        listOf(
            false to true,
            true to false,
            false to false
        ).forEach { (actionGroupCompleted, durableStateSaved) ->
            assertEquals(
                SupplementPipelineOrderCloseout.RETAIN,
                SupplementPipelineOrderCloseoutPolicy.classify(
                    parallelEnabled = false,
                    pausedAfterAction = "in7",
                    actionGroupCompleted = actionGroupCompleted,
                    durableStateSaved = durableStateSaved
                )
            )
        }
    }

    @Test
    fun parallelPause_shouldRemainOwnedByDurableParallelSession() {
        assertEquals(
            SupplementPipelineOrderCloseout.RETAIN,
            SupplementPipelineOrderCloseoutPolicy.classify(
                parallelEnabled = true,
                pausedAfterAction = "in7",
                actionGroupCompleted = true,
                durableStateSaved = true
            )
        )
    }
}
