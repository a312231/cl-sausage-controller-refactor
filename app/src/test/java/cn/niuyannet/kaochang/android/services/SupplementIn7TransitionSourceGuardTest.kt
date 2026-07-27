package cn.niuyannet.kaochang.android.services

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupplementIn7TransitionSourceGuardTest {
    private val operateSource = File(
        "src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt"
    ).readText()
    private val pauseSource = File(
        "src/main/java/cn/niuyannet/kaochang/android/services/FaultHeatPauseManager.kt"
    ).readText()

    @Test
    fun in7Commit_shouldPersistPanAndClearPendingInsideTransitionLock() {
        val body = functionBody(operateSource, "commitSupplementPlacedOnPan")
        assertTrue(body.contains("withFaultHeatTransitionLock"))
        assertTrue(body.contains("applySupplementPlacedOnPan"))
        assertTrue(body.contains("clearDiscardPositionStateAndAlertIfFinished"))
        assertTrue(body.contains("saveKaoPanListDurable"))
        assertFalse(body.contains("reportPanChanged"))
        assertFalse(body.contains("publishServiceUpdateStatus"))
    }

    @Test
    fun pendingDiscardMutation_shouldPublishOnlyAfterMutexProtectedStateMutation() {
        val stateBody = functionBody(pauseSource, "clearDiscardPositionStateAndAlertIfFinished")
        assertTrue(stateBody.contains("synchronized(mutex)"))
        assertFalse(stateBody.contains("publishServiceUpdateStatus"))

        val publishBody = functionBody(pauseSource, "publishDiscardPositionCleared")
        assertTrue(publishBody.contains("publishServiceUpdateStatus"))
        assertFalse(publishBody.contains("synchronized(mutex)"))
    }

    private fun functionBody(source: String, name: String): String {
        val start = source.indexOf("fun $name")
        assertTrue("未找到函数 $name", start >= 0)
        val next = Regex("\\n    (?:private |internal )?fun ")
            .find(source, start + 1)?.range?.first ?: source.length
        return source.substring(start, next)
    }
}
