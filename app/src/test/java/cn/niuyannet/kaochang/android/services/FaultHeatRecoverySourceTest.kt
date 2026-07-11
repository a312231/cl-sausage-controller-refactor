package cn.niuyannet.kaochang.android.services

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class FaultHeatRecoverySourceTest {

    @Test
    fun faultStop_shouldUsePersistedPauseStateInsteadOfCrossEpisodeMemoryGuard() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangAlgorithm.kt")
        val body = extractFunctionBody(source, "private fun stopHeatingAndDispatchForFault")

        assertTrue(
            "故障停热幂等必须使用持久化 faultHeatPauseActive，不能被跨故障周期残留的 faultHeatingStopped 跳过",
            body.contains("FaultHeatPauseManager.shouldSkipBakingCompletion(") &&
                !body.contains("faultHeatingStopped") &&
                !source.contains("private var faultHeatingStopped")
        )
    }

    @Test
    fun modbusAutoRecovery_shouldConfirmTemperatureRestoreBeforeClearingFault() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangAlgorithm.kt")
        val recoveryStart = source.indexOf("now - modbusReconnectSinceMs!! >= MODBUS_RECONNECT_STABLE_DELAY_MS")
        assertTrue("必须存在 Modbus 稳定恢复分支", recoveryStart >= 0)
        val recoveryEnd = (recoveryStart + 5_000).coerceAtMost(source.length)
        val recovery = source.substring(recoveryStart, recoveryEnd)

        val restoreResult = recovery.indexOf("restoreHeatingStateFromCurrentPans(")
        val restoreGuard = recovery.indexOf("if (restored)")
        val clearFault = recovery.indexOf("config.errorStatus = 0")
        assertTrue("自动恢复必须先尝试恢复温控", restoreResult >= 0)
        assertTrue("自动恢复必须检查温控恢复结果", restoreGuard > restoreResult)
        assertTrue("温控恢复确认必须早于清除故障", clearFault > restoreGuard)
    }

    @Test
    fun configuredTemperatureRestore_shouldEndPauseOnlyAfterAllWritesSucceed() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val body = extractFunctionBody(source, "private fun applyConfiguredTemperatureByPanStateInternal")
        val writes = body.indexOf("writeResults")
        val successCheck = body.indexOf("all { it.isSuccess }")
        val endPause = body.indexOf("endFaultHeatPauseAndSyncIfNeeded")

        assertTrue("恢复温控必须收集三个温区写入结果", writes >= 0)
        assertTrue("恢复温控必须确认所有写入成功", successCheck > writes)
        assertTrue("只有全部写入成功后才能结束故障暂停", endPause > successCheck)
    }

    @Test
    fun configuredTemperatureRestore_shouldReapplyPolicyAfterRecoveryMarksDiscardPans() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val body = extractFunctionBody(source, "private fun applyConfiguredTemperatureByPanStateInternal")

        assertTrue(
            "故障恢复必须在写温前预判待丢弃盘，避免先恢复错误温度再二次重刷",
            body.contains("previewFaultHeatPauseEnd") &&
                body.indexOf("previewFaultHeatPauseEnd") < body.indexOf("TemperatureControlPolicy.decideZoneTemperatures")
        )
    }

    @Test
    fun configuredTemperatureRestore_shouldReestablishStopAndPauseWhenAnyWriteFails() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val body = extractFunctionBody(source, "private fun applyConfiguredTemperatureByPanStateInternal")
        val failedBranch = body.indexOf("if (!allWritesSucceeded)")
        val stopAgain = body.indexOf("stopHeatingForFault(", failedBranch)

        assertTrue("任一温区恢复失败后必须重新停热并保持计时暂停", failedBranch >= 0 && stopAgain > failedBranch)
    }

    @Test
    fun normalTemperatureRefresh_shouldNotEndActiveFaultPause() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val normalApply = extractFunctionBody(source, "fun applyConfiguredTemperatureByPanState(")
        val restore = extractFunctionBody(source, "fun restoreHeatingStateFromCurrentPans(")

        assertTrue("普通温度刷新遇到故障暂停必须拒绝写温", normalApply.contains("shouldSkipBakingCompletion"))
        assertTrue("普通温度刷新不能结束故障暂停", !normalApply.contains("finishFaultPause = true"))
        assertTrue("只有显式故障恢复入口可以结束暂停", restore.contains("finishFaultPause = true"))
    }

    @Test
    fun faultStopAndRecovery_shouldShareTransitionLock() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val stop = extractFunctionBody(source, "fun stopHeatingForFault(")
        val restore = extractFunctionBody(source, "fun restoreHeatingStateFromCurrentPans(")

        assertTrue("故障停热必须持有统一状态锁", stop.contains("withFaultHeatTransitionLock"))
        assertTrue("故障恢复必须持有统一状态锁", restore.contains("withFaultHeatTransitionLock"))
    }

    @Test
    fun failedFaultStop_shouldPersistUnconfirmedStateAndRetryWhileMaintaining() {
        val operate = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val algorithm = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangAlgorithm.kt")
        val stop = extractFunctionBody(operate, "fun stopHeatingForFault(")
        val faultClose = extractFunctionBody(algorithm, "private fun stopHeatingAndDispatchForFault(")
        val poll = extractFunctionBody(algorithm, "private suspend fun pollKaoPanStatus(")

        assertTrue("三区写0结果必须持久化为独立确认状态", stop.contains("faultHeatStopConfirmed"))
        assertTrue("故障收口不能把计时暂停误当作已停热", faultClose.contains("faultHeatStopConfirmed != 1"))
        assertTrue("维护态且停热未确认时必须继续重试", poll.contains("faultHeatStopConfirmed != 1") && poll.contains("stopHeatingForFault("))
    }

    @Test
    fun faultAndMaintenanceStop_shouldRunBeforeNonBusinessHoursReturn() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangAlgorithm.kt")
        val poll = extractFunctionBody(source, "private suspend fun pollKaoPanStatus(")
        val nonBusinessReturn = poll.indexOf("if (!isWithinBusinessHours)")
        val faultStop = poll.indexOf("if (config.errorStatus != AppConfigBean.ERROR_STATUS_NORMAL)")
        val maintenanceStop = poll.indexOf("onlineStatus == 3")

        assertTrue("非营业时间也必须先执行错误停热", faultStop >= 0 && faultStop < nonBusinessReturn)
        assertTrue("非营业时间也必须建立或重试维护态停热", maintenanceStop >= 0 && maintenanceStop < nonBusinessReturn)
        assertTrue("维护态无暂停时也必须建立暂停并写0", poll.contains("!FaultHeatPauseManager.shouldSkipBakingCompletion(config)"))
    }

    @Test
    fun modbusDisconnect_shouldInvalidatePersistedHeatStopConfirmation() {
        val algorithm = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangAlgorithm.kt")
        val operate = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val invalidate = extractFunctionBody(operate, "fun invalidateFaultHeatStopConfirmation(")

        assertTrue(
            "Modbus断连必须使当前连接周期的停热确认失效",
            algorithm.contains("invalidateFaultHeatStopConfirmation(\"Modbus断开\")")
        )
        val monitorStart = algorithm.indexOf("private fun startModbusStateMonitor()")
        val monitorEnd = algorithm.indexOf("private fun", monitorStart + 20).let { if (it >= 0) it else algorithm.length }
        val monitor = algorithm.substring(monitorStart, monitorEnd)
        assertTrue(
            "停热确认不能跨App/设备启动周期继续可信",
            monitor.indexOf("invalidateFaultHeatStopConfirmation(\"Modbus监控启动\")") <
                monitor.indexOf("algorithmScope.launch")
        )
        assertTrue(
            "检修模式早退前也必须使断连确认失效",
            monitor.indexOf("invalidateFaultHeatStopConfirmation(\"Modbus断开\")") <
                monitor.indexOf("if (shouldFreezeByInspection(config))")
        )
        assertTrue("仅故障暂停中的已确认状态需要失效", invalidate.contains("shouldSkipBakingCompletion") && invalidate.contains("faultHeatStopConfirmed == 1"))
        assertTrue("失效状态必须持久化为0", invalidate.contains("faultHeatStopConfirmed = 0") && invalidate.contains("AppConfig.saveAppConfig"))
    }

    @Test
    fun manualEnable_shouldRestoreTemperatureBeforeOpeningDispatch() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangAlgorithm.kt")
        val body = extractFunctionBody(source, "fun enableRoastAlgorithm")
        val restore = body.indexOf("restoreHeatingStateFromCurrentPans(")
        val enable = body.indexOf("isEnabled = true")
        val discardScan = body.indexOf("OverKeepWarmDiscardScanner.scanAndDiscard")

        assertTrue("手动开启必须先恢复并确认温控", restore >= 0)
        assertTrue("温控确认必须早于开放算法调度", enable > restore)
        assertTrue("温控确认必须早于启动过保机械扫描", discardScan > restore)
    }

    @Test
    fun modbusAutoRecovery_shouldHoldTransitionLockUntilFaultStateIsCleared() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangAlgorithm.kt")
        val recoveryStart = source.indexOf("now - modbusReconnectSinceMs!! >= MODBUS_RECONNECT_STABLE_DELAY_MS")
        val recoveryEnd = (recoveryStart + 6_000).coerceAtMost(source.length)
        val recovery = source.substring(recoveryStart, recoveryEnd)
        val lock = recovery.indexOf("withFaultHeatTransitionLock")
        val restore = recovery.indexOf("restoreHeatingStateFromCurrentPans(")
        val clearFault = recovery.indexOf("config.errorStatus = 0")

        assertTrue("自动恢复必须在统一锁内恢复温控", lock >= 0 && restore > lock)
        assertTrue("自动恢复必须持锁到故障状态清除完成", clearFault > restore)
    }

    @Test
    fun startupHeatingRestore_shouldRequireEnabledAlgorithmAndRetryAfterFailure() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangAlgorithm.kt")
        val startupCall = source.indexOf("TemperatureControlPolicy.shouldRestoreHeatingAfterStartup")
        val startup = (startupCall - 300).coerceAtLeast(0)
        val end = (startup + 1_200).coerceAtMost(source.length)
        val block = source.substring(startup, end)

        assertTrue("启动恢复温控必须要求算法已开启", block.contains("config.isEnable == 1") && block.contains("isEnabled"))
        assertTrue("启动恢复失败后必须保留重试机会", block.contains("startupHeatingStateRestored = restoreSucceeded"))
    }

    private fun sourceFile(path: String): String {
        val direct = File(path)
        if (direct.exists()) return direct.readText().replace("\r\n", "\n")
        val fromParent = File("..", path)
        if (fromParent.exists()) return fromParent.readText().replace("\r\n", "\n")
        throw AssertionError("找不到源码文件：$path")
    }

    private fun extractFunctionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("找不到函数：$signature", start >= 0)
        val bodyStart = source.indexOf('{', start)
        var depth = 0
        for (index in bodyStart until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(bodyStart + 1, index)
            }
        }
        throw AssertionError("函数体未闭合：$signature")
    }
}
