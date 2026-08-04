package cn.niuyannet.kaochang.android.init

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteConfigMergeLogPolicyTest {

    @Test
    fun routineLocalPreservation_shouldBeDebugOnly() {
        assertTrue(
            RemoteConfigMergeLogPolicy.isRoutinePreservation(
                fields = listOf(
                    "lowerPlatformStatus(runtime_local)",
                    "pendingDiscardPositions(local_runtime_preserved)",
                    "faultBakingPauseAbortMs",
                    "status(normalized_from_onlineStatus)"
                ),
                parseIssues = emptyList()
            )
        )
    }

    @Test
    fun missingRequiredFieldOrFaultConflict_shouldRemainWarning() {
        assertFalse(
            RemoteConfigMergeLogPolicy.isRoutinePreservation(
                fields = listOf("deviceCode"),
                parseIssues = emptyList()
            )
        )
        assertFalse(
            RemoteConfigMergeLogPolicy.isRoutinePreservation(
                fields = listOf("onlineStatus(fault_latched_local)"),
                parseIssues = emptyList()
            )
        )
    }

    @Test
    fun parseFailure_shouldRemainWarningEvenWithLocalRuntimeFields() {
        assertFalse(
            RemoteConfigMergeLogPolicy.isRoutinePreservation(
                fields = listOf("lowerPlatformStatus(runtime_local)"),
                parseIssues = listOf("businessHours")
            )
        )
    }
}
