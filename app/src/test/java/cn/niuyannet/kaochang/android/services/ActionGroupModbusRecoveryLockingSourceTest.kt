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
            returnText = "return SingleSupplementMoveResult(false)"
        )
        assertEntry(
            source = source,
            ensureActionName = "维护手动取肠动作组恢复检查",
            recoverReason = "维护手动取肠入口前恢复检查",
            returnText = "return false"
        )
        assertOrderDispatchEntry(
            source = source,
            ensureActionName = "出餐动作组恢复检查",
            recoverReason = "正式出餐入口前恢复检查",
            returnText = "return TakeSausageResult.ERROR"
        )
    }

    @Test
    fun orderDeliveryHotPath_shouldUseOrderDispatchGateInsteadOfFullRecovery() {
        val source = orderActivitySourceText()
        val functionStart = source.indexOf("private suspend fun ensureModbusReadyForDelivery(")
        assertTrue("必须存在订单出餐前 Modbus 预检函数", functionStart >= 0)
        val functionEnd = source.indexOf("private suspend fun syncDeviceStatusToServer", functionStart)
            .let { if (it >= 0) it else source.length }
        val functionSource = source.substring(functionStart, functionEnd)

        assertTrue(
            "订单热路径必须改用 ensureActionGroupReadyForOrderDispatch，避免重复读取 12 个 result 寄存器",
            functionSource.contains("KaoChangOperate.ensureActionGroupReadyForOrderDispatch(")
        )
        assertTrue(
            "订单热路径不应直接调用 recoverActionGroupStateAfterModbusReady",
            !functionSource.contains("KaoChangOperate.recoverActionGroupStateAfterModbusReady(")
        )
    }

    @Test
    fun modbusDisconnect_shouldInvalidateOrderRecoveryVerification() {
        val source = algorithmSourceText()
        val disconnectStart = source.indexOf("actionGroupRecoveredAfterModbusConnect = false")
        assertTrue("Modbus 断开分支必须重置后台恢复标志", disconnectStart >= 0)
        val disconnectBlockEnd = source.indexOf("if (modbusDisconnectSinceMs == null)", disconnectStart)
            .let { if (it >= 0) it else source.length }
        val disconnectBlock = source.substring(disconnectStart, disconnectBlockEnd)

        assertTrue(
            "Modbus 断开时必须清除订单动作组恢复验证标志",
            disconnectBlock.contains("KaoChangOperate.invalidateActionGroupRecoveryVerification(")
        )
    }

    @Test
    fun runServer_shouldOnlyRearmModbusAutoRecoveryForModbusDisconnectFault() {
        val source = algorithmSourceText()
        val runServerStart = source.indexOf("fun runServer()")
        assertTrue("必须存在 runServer", runServerStart >= 0)
        val runServerEnd = source.indexOf("private fun logBusinessStartSkipOnce", runServerStart)
            .let { if (it >= 0) it else source.length }
        val runServerBlock = source.substring(runServerStart, runServerEnd)

        assertTrue(
            "启动时重新武装 modbusReportedUnavailable 必须校验 errorCode 是 MODBUS_DISCONNECT",
            runServerBlock.contains("isModbusAutoRecoverableFault(config.errorCode)")
        )
    }

    @Test
    fun modbusStableRecovery_shouldGuardAutoRecoveryByFaultCode() {
        val source = algorithmSourceText()
        val recoveryStart = source.indexOf("if (modbusReportedUnavailable)")
        assertTrue("必须存在 Modbus 自动恢复分支", recoveryStart >= 0)
        val recoveryEnd = source.indexOf("} else {", recoveryStart)
            .let { if (it >= 0) it else source.length }
        val recoveryBlock = source.substring(recoveryStart, recoveryEnd)

        assertTrue(
            "Modbus 稳定恢复写 errorStatus=0 前必须校验 errorCode 是 MODBUS_DISCONNECT",
            recoveryBlock.contains("isModbusAutoRecoverableFault(config.errorCode)")
        )
    }

    @Test
    fun actionGroupFaultLock_shouldInvalidateOrderRecoveryVerification() {
        val source = sourceText()
        val faultStart = source.indexOf("private fun markDeviceErrorAndUpload(")
        assertTrue("必须存在动作组故障锁存入口", faultStart >= 0)
        val faultEnd = source.indexOf("private fun armPostSellSupplementGuard", faultStart)
            .let { if (it >= 0) it else source.length }
        val faultBlock = source.substring(faultStart, faultEnd)

        assertTrue(
            "动作组/硬件故障锁存时必须清除订单动作组恢复验证标志",
            faultBlock.contains("invalidateActionGroupRecoveryVerification(")
        )
    }

    @Test
    fun actionGroupFaultLock_shouldRefreshHomeUiAfterMaintenanceStatusWritten() {
        val source = sourceText()
        val faultStart = source.indexOf("private fun markDeviceErrorAndUpload(")
        assertTrue("必须存在动作组故障锁存入口", faultStart >= 0)
        val faultEnd = source.indexOf("private fun armPostSellSupplementGuard", faultStart)
            .let { if (it >= 0) it else source.length }
        val faultBlock = source.substring(faultStart, faultEnd)

        assertTrue(
            "动作组/硬件故障锁存进入维护后必须刷新首页，否则二维码会等到 onResume/周期刷新才隐藏",
            faultBlock.contains("HomeUiRefreshBridge.requestRefresh(")
        )
    }

    @Test
    fun algorithmFaultStop_shouldRefreshHomeUiAfterMaintenanceStatusWritten() {
        val source = algorithmSourceText()
        val faultStart = source.indexOf("private fun stopHeatingAndDispatchForFault(")
        assertTrue("必须存在算法故障停热收口入口", faultStart >= 0)
        val faultEnd = source.indexOf("private fun evaluateModbusAutoRecovery", faultStart)
            .let { if (it >= 0) it else source.length }
        val faultBlock = source.substring(faultStart, faultEnd)

        assertTrue(
            "算法监控检测到下位机故障并进入维护后必须刷新首页，否则二维码会等到 onResume/周期刷新才隐藏",
            faultBlock.contains("HomeUiRefreshBridge.requestRefresh(")
        )
    }

    @Test
    fun modbusAutoRecovery_shouldRefreshHomeUiAfterOnlineStatusRestored() {
        val source = algorithmSourceText()
        val restoreIndex = source.indexOf("config.onlineStatus = restoredOnlineStatus")
        assertTrue("必须存在 Modbus 自动恢复 onlineStatus 写入", restoreIndex >= 0)
        val restoreBlockEnd = source.indexOf("uploadDeviceStatus(", restoreIndex)
            .let { if (it >= 0) it else source.length }
        val restoreBlock = source.substring(restoreIndex, restoreBlockEnd)

        assertTrue(
            "Modbus 自动恢复 onlineStatus 后必须刷新首页，否则二维码/维护文案会延迟更新",
            restoreBlock.contains("HomeUiRefreshBridge.requestRefresh(")
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
        val recoverCall = Regex(
            """if\s*\(\s*!recoverActionGroupStateAfterModbusReady\s*\(\s*"${Regex.escape(recoverReason)}"(?:\s*,[^{}]*?)?\s*\)\s*\)\s*\{"""
        )
        val recoverBlockStart = recoverCall.find(source, ensureBlockStart)?.range?.first ?: -1
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

    private fun assertOrderDispatchEntry(
        source: String,
        ensureActionName: String,
        recoverReason: String,
        returnText: String
    ) {
        val ensureBlockStart = source.indexOf("if (!ensureModbusReady(\"$ensureActionName\")) {")
        assertTrue("入口必须单独判断 ensureModbusReady：$ensureActionName", ensureBlockStart >= 0)
        val recoveryGateStart = source.indexOf("if (!ensureActionGroupReadyForOrderDispatch(\"$recoverReason\")) {", ensureBlockStart)
        assertTrue("出餐入口必须走订单动作组恢复门控：$recoverReason", recoveryGateStart > ensureBlockStart)

        val ensureBlock = source.substring(ensureBlockStart, recoveryGateStart)
        assertTrue("ensure 失败分支必须锁存维护：$ensureActionName", ensureBlock.contains("enterActionGroupMaintenance("))
        assertTrue("ensure 失败分支必须按入口返回：$ensureActionName", ensureBlock.contains(returnText))
        assertTrue("ensure 失败分支必须使用 MODBUS_COMM_DROP：$ensureActionName", ensureBlock.contains("FaultCode.MODBUS_COMM_DROP"))

        val recoveryBlockEnd = source.indexOf("\n        }\n", recoveryGateStart).let { if (it >= 0) it else source.length }
        val recoveryBlock = source.substring(recoveryGateStart, recoveryBlockEnd)
        assertTrue("订单动作组恢复门控失败分支只能 return，不能二次锁存：$recoverReason", !recoveryBlock.contains("enterActionGroupMaintenance("))
        assertTrue("订单动作组恢复门控失败分支必须按入口返回：$recoverReason", recoveryBlock.contains(returnText))
    }

    private fun sourceText(): String {
        val sourceFile = listOf(
            File("src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt"),
            File("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        ).first { it.exists() }
        return sourceFile.readText().replace("\r\n", "\n")
    }

    private fun orderActivitySourceText(): String {
        val sourceFile = listOf(
            File("src/main/java/cn/niuyannet/kaochang/android/ui/OrderActivity.kt"),
            File("app/src/main/java/cn/niuyannet/kaochang/android/ui/OrderActivity.kt")
        ).first { it.exists() }
        return sourceFile.readText().replace("\r\n", "\n")
    }

    private fun algorithmSourceText(): String {
        val sourceFile = listOf(
            File("src/main/java/cn/niuyannet/kaochang/android/services/KaoChangAlgorithm.kt"),
            File("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangAlgorithm.kt")
        ).first { it.exists() }
        return sourceFile.readText().replace("\r\n", "\n")
    }
}
