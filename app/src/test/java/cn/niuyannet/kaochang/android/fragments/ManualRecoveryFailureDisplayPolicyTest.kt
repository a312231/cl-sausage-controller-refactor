package cn.niuyannet.kaochang.android.fragments

import cn.niuyannet.kaochang.recovery.ManualFaultRecoveryCode
import cn.niuyannet.kaochang.recovery.ManualFaultRecoveryResult
import cn.niuyannet.kaochang.recovery.ManualFaultRecoveryRollbackOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualRecoveryFailureDisplayPolicyTest {

    @Test
    fun errorText_shouldKeepTypeAndMessageAndDistinguishNoError() {
        assertEquals("无", ManualRecoveryFailureDisplayPolicy.errorText(null))
        assertEquals(
            "java.lang.IllegalStateException:write failed",
            ManualRecoveryFailureDisplayPolicy.errorText(IllegalStateException("write failed"))
        )
        assertEquals(
            "java.lang.IllegalStateException:无消息",
            ManualRecoveryFailureDisplayPolicy.errorText(IllegalStateException())
        )
    }

    @Test
    fun initialConditionChanged_shouldKeepExistingNoMutationMessage() {
        val detail = ManualRecoveryFailureDisplayPolicy.detail(
            ManualFaultRecoveryResult.Rejected(
                ManualFaultRecoveryCode.INITIAL_CONDITION_CHANGED,
                "恢复提交前运行态已变化"
            )
        )

        assertEquals("未重复执行温控恢复，请重新检查当前状态", detail)
    }

    @Test
    fun preMutationException_shouldSayNoHeatingRecoveryWasExecuted() {
        listOf(
            ManualFaultRecoveryCode.INITIAL_CONDITION_EXCEPTION,
            ManualFaultRecoveryCode.STATE_SNAPSHOT_EXCEPTION
        ).forEach { code ->
            val detail = ManualRecoveryFailureDisplayPolicy.detail(
                ManualFaultRecoveryResult.Failed(code, "precheck")
            )
            assertEquals("恢复前检查未完成，未执行温控恢复；设备仍未恢复正常", detail)
        }
    }

    @Test
    fun missingRollbackOutcome_shouldNotClaimMaintenanceState() {
        val detail = ManualRecoveryFailureDisplayPolicy.detail(
            ManualFaultRecoveryResult.Failed(
                ManualFaultRecoveryCode.ALGORITHM_DISABLE_EXCEPTION,
                "关闭烤制算法异常"
            )
        )

        assertEquals("恢复安全收口结果缺失；设备仍未恢复正常，请检查日志", detail)
        assertFalse(detail.contains("设备保持维护"))
    }

    @Test
    fun rollbackOutcome_shouldRenderEveryEvidenceCombinationWithoutPhysicalStopClaim() {
        val cases = listOf(
            ManualFaultRecoveryRollbackOutcome(true, true) to
                "故障状态已回退，三区目标温度写0调用均返回成功；设备仍未恢复正常，实际温控状态仍需现场确认",
            ManualFaultRecoveryRollbackOutcome(false, true) to
                "三区目标温度写0调用均返回成功，但故障状态耐久回退未确认；设备仍未恢复正常，请检查日志；实际温控状态仍需现场确认",
            ManualFaultRecoveryRollbackOutcome(true, false) to
                "故障状态已回退，但三区目标温度写0调用未全部成功；设备仍未恢复正常，请检查下位机",
            ManualFaultRecoveryRollbackOutcome(false, false) to
                "故障状态回退与三区目标温度写0调用均未确认；设备仍未恢复正常，请立即检查下位机"
        )

        cases.forEach { (rollback, expected) ->
            val detail = ManualRecoveryFailureDisplayPolicy.detail(
                ManualFaultRecoveryResult.Failed(
                    ManualFaultRecoveryCode.HEATING_RESTORE_FAILED,
                    "温控恢复未全部成功",
                    rollbackOutcome = rollback
                )
            )
            assertEquals(expected, detail)
            assertFalse(detail.contains("已停止加热"))
            assertFalse(detail.contains("已重新停热"))
            assertFalse(detail.contains("物理确认"))
            if (rollback.targetTemperatureZeroWritesSucceeded) {
                assertTrue(detail.contains("实际温控状态仍需现场确认"))
            }
        }
    }
}
