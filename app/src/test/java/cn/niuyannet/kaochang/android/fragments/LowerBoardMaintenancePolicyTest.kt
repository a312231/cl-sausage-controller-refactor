package cn.niuyannet.kaochang.android.fragments

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LowerBoardMaintenancePolicyTest {

    @Test
    fun actionResultRegisters_shouldMatch300And400ProtocolResultRegisters() {
        assertEquals(
            listOf(311, 321, 331, 341, 351, 361, 371, 411, 421, 431, 441, 451),
            LowerBoardMaintenancePolicy.actionResultRegisters
        )
    }

    @Test
    fun canRunMaintenance_shouldRejectWhenLocalMoveStatusIsNotIdle() {
        val decision = LowerBoardMaintenancePolicy.canRunMaintenance(
            moveStatus = 2,
            status201 = 0,
            moveResult203 = 0,
            atomicResultValues = mapOf(311 to 0, 411 to 0)
        )

        assertFalse(decision.allowed)
        assertEquals("上位机机械动作状态 moveStatus=2，非空闲，不能执行下位机维护操作", decision.reason)
    }

    @Test
    fun canRunMaintenance_shouldRejectWhenLegacyStatus201IsRunning() {
        val decision = LowerBoardMaintenancePolicy.canRunMaintenance(
            moveStatus = 0,
            status201 = 1,
            moveResult203 = 0,
            atomicResultValues = emptyMap()
        )

        assertFalse(decision.allowed)
        assertEquals("下位机动作执行中：201=1", decision.reason)
    }

    @Test
    fun canRunMaintenance_shouldRejectWhenPanMoveResult203IsRunning() {
        val decision = LowerBoardMaintenancePolicy.canRunMaintenance(
            moveStatus = 0,
            status201 = 0,
            moveResult203 = 1,
            atomicResultValues = emptyMap()
        )

        assertFalse(decision.allowed)
        assertEquals("下位机搬盘执行中：203=1", decision.reason)
    }

    @Test
    fun canRunMaintenance_shouldRejectWhenAnyAtomicResultIsRunning() {
        val decision = LowerBoardMaintenancePolicy.canRunMaintenance(
            moveStatus = 0,
            status201 = 0,
            moveResult203 = 0,
            atomicResultValues = mapOf(311 to 0, 421 to 1, 451 to 0)
        )

        assertFalse(decision.allowed)
        assertEquals("下位机原子动作执行中：421=1", decision.reason)
    }

    @Test
    fun canRunMaintenance_shouldAllowWhenAllStatusesAreIdleOrFinished() {
        val decision = LowerBoardMaintenancePolicy.canRunMaintenance(
            moveStatus = 0,
            status201 = 0,
            moveResult203 = 2,
            atomicResultValues = mapOf(311 to 0, 421 to 2, 451 to 0x00E1)
        )

        assertTrue(decision.allowed)
        assertEquals("允许执行", decision.reason)
    }

    @Test
    fun canRunMaintenance_shouldAllowTerminalActionErrorResidueWhenLocalMoveStatusIsIdle() {
        val decision = LowerBoardMaintenancePolicy.canRunMaintenance(
            moveStatus = 0,
            status201 = 0,
            moveResult203 = 0,
            atomicResultValues = mapOf(311 to 0x00EF, 421 to 0)
        )

        assertTrue(decision.allowed)
        assertEquals("允许执行", decision.reason)
    }

    @Test
    fun canRunEmergencyReset_shouldBypassIdleGate() {
        val decision = LowerBoardMaintenancePolicy.canRunEmergencyReset()

        assertTrue(decision.allowed)
        assertEquals("允许紧急复位", decision.reason)
    }

    @Test
    fun emergencyReset_shouldRequireAdminConfirmationAndFenceThe999Write() {
        val fragment = sourceFile(
            "app/src/main/java/cn/niuyannet/kaochang/android/fragments/TestFragment.kt"
        )
        val confirmBody = extractFunctionBody(fragment, "private fun emergencyResetLowerBoardSystem()")
        val executeBody = extractFunctionBody(fragment, "private fun executeEmergencyResetLowerBoardSystem()")
        val prepareFence = executeBody.indexOf("KaoChangOperate.prepareEmergencyResetRecoveryFence(")
        val write999 = executeBody.indexOf("KaoChangOperate.writeEmergencyResetAndBlockMechanicalCommands()")
        val completeFence = executeBody.indexOf("KaoChangOperate.completeEmergencyResetWrite(")
        val coldRestart = executeBody.indexOf("EmergencyResetAppRestarter.scheduleAndTerminate(")

        assertTrue("紧急复位必须先弹出管理员确认", confirmBody.contains("确认紧急复位"))
        assertTrue("确认后才允许进入实际写入流程", confirmBody.contains("executeEmergencyResetLowerBoardSystem()"))
        assertTrue("必须在 999 写入前建立 fence", prepareFence >= 0 && prepareFence < write999)
        assertTrue("必须在 999 返回后记录写入结果", completeFence > write999)
        assertTrue("999 成功后必须安排冷重启", coldRestart > completeFence)

        val operate = sourceFile(
            "app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt"
        )
        val prepareBody = extractFunctionBody(operate, "fun prepareEmergencyResetRecoveryFence(")
        val completeBody = extractFunctionBody(operate, "fun completeEmergencyResetWrite(")
        assertTrue(prepareBody.contains("withFaultHeatTransitionLock"))
        assertTrue(prepareBody.contains("onlineStatus = ORDER_CLAIM_MAINTENANCE_STATUS"))
        assertTrue(prepareBody.contains("isEnable = 0"))
        assertTrue(prepareBody.contains("publishRuntimeStateWithHttpFallback"))
        assertTrue(prepareBody.contains("EmergencyResetRecoveryFence.prepared("))
        assertTrue("重复紧急复位必须覆盖旧 fence", !prepareBody.contains("emergencyResetRecoveryFenceStore.load()"))
        assertTrue(!prepareBody.contains("orderDeliveryClaimStore.load()"))
        assertTrue(!prepareBody.contains("readSupplementPrefetchEvidence()"))
        assertTrue("999 失败只能保留 PREPARED 待重试，不能误清未知结果栅栏", !completeBody.contains("emergencyResetRecoveryFenceStore.clear()"))
    }

    @Test
    fun formatMaintenanceRegisterValue_shouldDescribe998And999() {
        assertEquals(
            "0（0x0000，清空 300/400 原子动作寄存器触发寄存器）",
            LowerBoardMaintenancePolicy.formatMaintenanceRegisterValue(998, 0)
        )
        assertEquals(
            "1（0x0001，下位机系统软重启触发寄存器）",
            LowerBoardMaintenancePolicy.formatMaintenanceRegisterValue(999, 1)
        )
    }

    private fun sourceFile(path: String): String {
        return listOf(File(path), File("..", path)).first { it.exists() }.readText()
    }

    private fun extractFunctionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("找不到函数：$signature", start >= 0)
        val bodyStart = source.indexOf('{', start)
        assertTrue("找不到函数体：$signature", bodyStart >= 0)
        var depth = 0
        for (index in bodyStart until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(bodyStart + 1, index)
                }
            }
        }
        throw AssertionError("函数体未闭合：$signature")
    }
}
