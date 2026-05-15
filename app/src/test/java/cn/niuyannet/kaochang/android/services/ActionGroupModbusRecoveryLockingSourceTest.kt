package cn.niuyannet.kaochang.android.services

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ActionGroupModbusRecoveryLockingSourceTest {

    @Test
    fun recoverActionGroupState_shouldLockDisconnectedEarlyReturnInsideRecover() {
        val source = sourceText()

        assertTrue(
            "recover 入口 Modbus 未连接早退必须在 recover 内部锁存，避免调用方猜测 false 是否已锁",
            source.contains("动作组恢复早退：Modbus 未连接，reason=\$reason") &&
                source.contains("enterActionGroupMaintenance(detail, FaultCode.MODBUS_COMM_DROP)")
        )
    }

    @Test
    fun actionGroupEntrypoints_shouldLockOnlyEnsureModbusFailureAndOnlyReturnOnRecoverFailure() {
        val source = sourceText()

        assertEntry(
            source = source,
            ensureActionName = "补肠预取流水线恢复检查",
            recoverReason = "补肠预取流水线入口前恢复检查",
            returnText = "return 0"
        )
        assertEntry(
            source = source,
            ensureActionName = "补肠动作组恢复检查",
            recoverReason = "补肠入口前恢复检查",
            returnText = "return false"
        )
        assertEntry(
            source = source,
            ensureActionName = "维护手动取肠动作组恢复检查",
            recoverReason = "维护手动取肠入口前恢复检查",
            returnText = "return false"
        )
        assertEntry(
            source = source,
            ensureActionName = "出餐动作组恢复检查",
            recoverReason = "正式出餐入口前恢复检查",
            returnText = "return TakeSausageResult.ERROR"
        )
    }

    private fun assertEntry(
        source: String,
        ensureActionName: String,
        recoverReason: String,
        returnText: String
    ) {
        val ensureBlockStart = source.indexOf("if (!ensureModbusReady(\"$ensureActionName\")) {")
        assertTrue("入口必须单独判断 ensureModbusReady：$ensureActionName", ensureBlockStart >= 0)
        val recoverBlockStart = source.indexOf("if (!recoverActionGroupStateAfterModbusReady(\"$recoverReason\")) {", ensureBlockStart)
        assertTrue("入口必须单独判断 recoverActionGroupStateAfterModbusReady：$recoverReason", recoverBlockStart > ensureBlockStart)

        val ensureBlock = source.substring(ensureBlockStart, recoverBlockStart)
        assertTrue("ensure 失败分支必须锁存维护：$ensureActionName", ensureBlock.contains("enterActionGroupMaintenance("))
        assertTrue("ensure 失败分支必须按入口返回：$ensureActionName", ensureBlock.contains(returnText))
        assertTrue("ensure 失败分支必须使用 MODBUS_COMM_DROP：$ensureActionName", ensureBlock.contains("FaultCode.MODBUS_COMM_DROP"))

        val recoverBlockEnd = source.indexOf("\n        }\n", recoverBlockStart).let { if (it >= 0) it else source.length }
        val recoverBlock = source.substring(recoverBlockStart, recoverBlockEnd)
        assertTrue("recover 失败分支只能 return，不能二次锁存：$recoverReason", !recoverBlock.contains("enterActionGroupMaintenance("))
        assertTrue("recover 失败分支必须按入口返回：$recoverReason", recoverBlock.contains(returnText))
    }

    private fun sourceText(): String {
        val sourceFile = listOf(
            File("src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt"),
            File("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        ).first { it.exists() }
        return sourceFile.readText().replace("\r\n", "\n")
    }
}
