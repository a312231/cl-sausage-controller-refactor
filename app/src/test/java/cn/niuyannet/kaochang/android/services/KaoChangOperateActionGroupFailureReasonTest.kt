package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupResult
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KaoChangOperateActionGroupFailureReasonTest {

    @Test
    fun actionGroupFailureReason_shouldPutDiagnosisBeforeLegacyResultShell() {
        val reason = formatActionGroupFailureReasonForUpload(
            actionName = "正式出餐",
            result = ActionGroupResult.Failed(
                actionName = "out3",
                result = AtomicActionResult.Timeout("初判责任=下位机执行状态异常回退，失败阶段=执行中后结果回退，动作=out3"),
                message = "初判责任=下位机执行状态异常回退，失败阶段=执行中后结果回退，动作=out3"
            ),
            resourceLocks = "BOTTOM|LIFT"
        )

        assertTrue(reason.startsWith("正式出餐 动作组失败：out3，初判责任=下位机执行状态异常回退"))
        assertFalse(reason.contains("FAILED（out3："))
        assertTrue(reason.contains("resourceLocks=BOTTOM|LIFT"))
    }

    @Test
    fun actionGroupFailureReason_shouldTruncateFinalReasonTo300Characters() {
        val reason = formatActionGroupFailureReasonForUpload(
            actionName = "正式出餐",
            result = ActionGroupResult.Failed(
                actionName = "out3",
                result = AtomicActionResult.Timeout("初判责任=下位机执行状态异常回退，${"诊断".repeat(200)}"),
                message = "初判责任=下位机执行状态异常回退，${"诊断".repeat(200)}"
            ),
            resourceLocks = "BOTTOM|LIFT"
        )

        assertTrue(reason.startsWith("正式出餐 动作组失败：out3，初判责任=下位机执行状态异常回退"))
        assertTrue(reason.endsWith("..."))
        assertTrue(reason.length <= 300)
    }
}
