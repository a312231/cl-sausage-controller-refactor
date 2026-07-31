package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfigBean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SelfCleanManagerIsolationGateTest {

    @Test
    fun shouldBlockAutoDispatchForLowerPlatformIsolation_shouldTreatIn4AsBlockedAndIn3AsAllowed() {
        assertTrue(
            AutoMechanicalDispatchPolicy.shouldBlockAutoDispatchForLowerPlatformIsolation(
                lowerPlatformStatus = AppConfigBean.LOWER_PLATFORM_STATUS_ISOLATED,
                lowerPlatformIsolationActionName = "in5"
            )
        )
        assertFalse(
            AutoMechanicalDispatchPolicy.shouldBlockAutoDispatchForLowerPlatformIsolation(
                lowerPlatformStatus = AppConfigBean.LOWER_PLATFORM_STATUS_ISOLATED,
                lowerPlatformIsolationActionName = "in3"
            )
        )
    }

    @Test
    fun runPanSelfCleanSequence_shouldGateLowerPlatformIsolationBeforeStartingSelfClean() {
        val source = kaoChangOperateSourceText()
        val start = source.indexOf("suspend fun runPanSelfCleanSequence(")
        assertTrue("必须存在 runPanSelfCleanSequence", start >= 0)
        val end = source.indexOf("val reports = mutableListOf<SelfCleanExecutionReport>()", start)
            .let { if (it >= 0) it else source.length }
        val preflight = source.substring(start, end)

        assertTrue(
            "自清洁入口必须先检查下平台 in4/in5 隔离，防止营业结束全量自清洁绕过半故障保护",
            preflight.contains("AutoMechanicalDispatchPolicy.shouldBlockAutoDispatchForLowerPlatformIsolation(") &&
                preflight.contains("lower_platform_isolated:")
        )
    }

    @Test
    fun runPanSelfCleanSequence_shouldReturnBeforeWaitingWhenFourFIsNoOp() {
        val source = kaoChangOperateSourceText()
        val start = source.indexOf("suspend fun runPanSelfCleanSequence(")
        val wait = source.indexOf("while (moveStatus != 0)", start)
        val writeBusy = source.indexOf("runtimeConfig.moveStatus = 5", start)
        val noOp = source.indexOf(
            "FourFSelfCleanNoOpPolicy.shouldSkipSequenceBeforeStateMutation()",
            start
        )

        assertTrue(noOp >= 0)
        assertTrue(noOp < wait)
        assertTrue(noOp < writeBusy)
    }

    private fun kaoChangOperateSourceText(): String {
        val sourceFile = listOf(
            File("src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt"),
            File("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        ).first { it.exists() }
        return sourceFile.readText().replace("\r\n", "\n")
    }
}
