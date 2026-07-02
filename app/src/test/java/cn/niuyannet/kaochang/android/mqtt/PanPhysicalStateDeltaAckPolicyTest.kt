package cn.niuyannet.kaochang.android.mqtt

import org.junit.Assert.assertEquals
import org.junit.Test

class PanPhysicalStateDeltaAckPolicyTest {

    @Test
    fun classify_shouldVerifyAcceptedPhysicalAckOnlyWhenServerHashExists() {
        assertEquals(
            PanPhysicalStateDeltaAckPolicy.Decision.VerifiedAccepted,
            PanPhysicalStateDeltaAckPolicy.classify(
                accepted = true,
                duplicate = false,
                stale = false,
                serverPhysicalStateHash = "hash-1"
            )
        )
    }

    @Test
    fun classify_shouldVerifyDuplicatePhysicalAckOnlyWhenServerHashExists() {
        assertEquals(
            PanPhysicalStateDeltaAckPolicy.Decision.VerifiedAccepted,
            PanPhysicalStateDeltaAckPolicy.classify(
                accepted = false,
                duplicate = true,
                stale = false,
                serverPhysicalStateHash = "hash-1"
            )
        )
    }

    @Test
    fun classify_shouldRequireFallbackWhenAcceptedAckLacksServerPhysicalHash() {
        assertEquals(
            PanPhysicalStateDeltaAckPolicy.Decision.UnverifiedAccepted,
            PanPhysicalStateDeltaAckPolicy.classify(
                accepted = true,
                duplicate = false,
                stale = false,
                serverPhysicalStateHash = ""
            )
        )
    }

    @Test
    fun classify_shouldKeepStaleRejectedSeparateFromFallbackRejected() {
        assertEquals(
            PanPhysicalStateDeltaAckPolicy.Decision.StaleRejected,
            PanPhysicalStateDeltaAckPolicy.classify(
                accepted = false,
                duplicate = false,
                stale = true,
                serverPhysicalStateHash = ""
            )
        )
    }

    @Test
    fun classify_shouldTreatNonStaleRejectedAsRejected() {
        assertEquals(
            PanPhysicalStateDeltaAckPolicy.Decision.Rejected,
            PanPhysicalStateDeltaAckPolicy.classify(
                accepted = false,
                duplicate = false,
                stale = false,
                serverPhysicalStateHash = ""
            )
        )
    }
}
