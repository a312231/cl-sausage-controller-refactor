package cn.niuyannet.kaochang.android.services

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupplementClaimSourceGuardTest {
    private val source = File(
        "src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt"
    ).readText()

    @Test
    fun preparedClaim_shouldBePersistedBeforeMoveStatusClaimAndPublishOutsideLock() {
        val claimFunction = functionBody("claimPreparedSupplementMove")
        assertTrue(claimFunction.contains("claimMechanicalMove"))
        val sharedClaimFunction = functionBody("claimMechanicalMove")
        assertTrue(sharedClaimFunction.contains("MechanicalMoveClaimCoordinator"))
        assertTrue(sharedClaimFunction.contains("SupplementStartGate.evaluate"))
        assertFalse(claimFunction.contains("publishRuntimeState"))
        assertFalse(claimFunction.contains("delay("))
    }

    @Test
    fun release_shouldRequireCurrentOwnerAndNeverPublishWhileHoldingTransitionLock() {
        val releaseFunction = functionBody("releaseSupplementMoveClaimIfOwned")
        assertTrue(releaseFunction.contains("MechanicalMoveClaimCoordinator"))
        assertFalse(releaseFunction.contains("publishRuntimeState"))
    }

    @Test
    fun allGenericMechanicalEntrypoints_shouldUseSharedOwnerFencedClaim() {
        listOf(
            "resumePreparedParallelSupplementOnce",
            "resumePausedSupplementAfterOrder",
            "takeSausage",
            "takeSausageResult"
        ).forEach { functionName ->
            assertTrue(
                "$functionName 未复用共享机械认领",
                functionBody(functionName).contains("claimMechanicalMove")
            )
        }
    }

    @Test
    fun promotedPrefetchContinuation_shouldStayInsideExistingSingleFlightAttempt() {
        val entry = functionBody("resumePreparedParallelSupplement")
        val finish = functionBody("finishParallelSupplementResume")

        assertTrue(entry.contains("supplementResumeSingleFlight.tryEnter"))
        assertTrue(entry.contains("resumePreparedParallelSupplementOnce"))
        assertTrue(finish.contains("resumePreparedParallelSupplementOnce"))
        assertFalse(finish.contains("return resumePreparedParallelSupplement("))
    }

    @Test
    fun nextPrefetchGate_shouldRunThroughTransitionLockBackedGate() {
        val gateFunction = functionBody("ensureSupplementBatchStartAllowed")
        assertTrue(gateFunction.contains("withFaultHeatTransitionLock"))
        assertTrue(source.contains("补肠预取流水线启动下一根预取前"))
    }

    private fun functionBody(name: String): String {
        val start = source.indexOf("fun $name")
        assertTrue("未找到函数 $name", start >= 0)
        val next = Regex("\\n    (?:private |internal )?fun ")
            .find(source, start + 1)?.range?.first ?: source.length
        return source.substring(start, next)
    }
}
