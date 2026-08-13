package cn.niuyannet.kaochang.android.services

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualRecoveryCoreWiringSourceTest {

    @Test
    fun recoveryGate_shouldKeepRealModbusEvidenceInApp() {
        val source = sourceFile(
            "app/src/main/java/cn/niuyannet/kaochang/android/fragments/MaintenanceFragment.kt"
        )
        val body = extractFunctionBody(source, "suspend fun evaluateManualRecoveryHeating")
        val moveResultBranch = body.indexOf("if (currentConfig.moveStatus == 3)")
        val result203 = body.indexOf("KaoChangOperate.action_address_move_result", moveResultBranch)

        assertTrue("未连接分支必须仍由 App 拒绝", body.contains("if (!VMModbusHelper.connectStatus())"))
        assertTrue("201 必须仍由 App 读取", body.contains("KaoChangOperate.action_address_status"))
        assertTrue("只有 moveStatus=3 才允许读取 203", moveResultBranch >= 0 && result203 > moveResultBranch)
        assertTrue("203 读取必须位于条件分支内", result203 < body.indexOf("} else {", moveResultBranch))
    }

    @Test
    fun recoveryCoordinator_shouldKeepAppOwnedStateHardwareAndLockWiring() {
        val source = sourceFile(
            "app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt"
        )
        val body = extractFunctionBody(source, "fun recoverFaultHeatingAndRuntimeState")
        val coordinator = body.indexOf("ManualFaultRecoveryCoordinator(faultHeatTransitionLock, stateStore).recover(")
        val sync = body.indexOf("syncRecoveredFaultHeatPhysicalState(pauseEndResult, reason)")

        assertTrue("真实 transition lock 必须传给纯协调器", coordinator >= 0)
        assertTrue("恢复后的 HTTP/MQTT 物理同步必须在协调器事务结束后执行", sync > coordinator)
        assertTrue("安全门必须限制目标营业态", body.contains("targetOnlineStatus in 0..2"))
        assertTrue("安全门必须复查真实 Modbus 连接", body.contains("VMModbusHelper.connectStatus()"))
        assertTrue("安全门必须要求 moveStatus=0", body.contains("config.moveStatus == 0"))
        assertTrue("安全门必须保留并行补货暂停例外", body.contains("isParallelSupplementPausedForRuntimeRecovery()"))
        assertTrue("恢复必须关闭算法内存态", body.contains("KaoChangAlgorithm.isEnabled = false"))
        assertTrue("恢复必须关闭算法耐久配置态", body.contains("AppConfig.getAppConfig().isEnable = 0"))
        assertTrue("温控恢复仍由 App 硬件端口执行", body.contains("restoreHeatingStateFromCurrentPansLocked(reason)"))
        assertTrue("失败停热仍由 App 硬件端口执行", body.contains("stopHeatingForFault("))
        assertTrue(
            "stopHeating lambda 必须直接返回三区写0调用结果",
            body.contains("stopHeating = {\n                stopHeatingForFault(")
        )
    }

    @Test
    fun recoveryFailure_shouldStillSyncPreparedJournalOutsideCoordinatorLock() {
        val source = sourceFile(
            "app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt"
        )
        val body = extractFunctionBody(source, "fun recoverFaultHeatingAndRuntimeState")
        val coordinator = body.indexOf("val result = ManualFaultRecoveryCoordinator(")
        val sync = body.indexOf("syncRecoveredFaultHeatPhysicalState(pauseEndResult, reason)")
        val resultReturn = body.indexOf("return result")

        assertTrue("必须先取得协调器失败/成功结果", coordinator >= 0)
        assertTrue("无论结果类型都必须先同步恢复journal增量", sync > coordinator)
        assertTrue("同步完成后才能返回结果", resultReturn > sync)
        assertTrue("协调器后不得新增提前返回跳过同步", !body.substring(coordinator, sync).contains("return "))
    }

    @Test
    fun maintenanceFailureLog_shouldKeepRootAndBothRollbackErrorsSeparate() {
        val source = sourceFile(
            "app/src/main/java/cn/niuyannet/kaochang/android/fragments/MaintenanceFragment.kt"
        )
        val body = extractFunctionBody(source, "private suspend fun applyManualRecoveredState")

        listOf(
            "failureCode=",
            "rootCause=",
            "faultStateRestored=",
            "faultStateRestoreError=",
            "targetTemperatureZeroWritesSucceeded=",
            "targetTemperatureZeroWriteError="
        ).forEach { field ->
            assertTrue("管理日志缺少失败诊断字段：$field", body.contains(field))
        }
        assertTrue("UI 必须通过证据策略生成详情", body.contains("ManualRecoveryFailureDisplayPolicy.detail("))
        assertTrue("UI 不得继续固定宣称停止加热", !body.contains("已回退故障状态并停止加热"))
    }

    @Test
    fun recoveryStateStore_shouldKeepExactDurableFields() {
        val source = sourceFile(
            "app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt"
        )
        val body = extractFunctionBody(source, "fun recoverFaultHeatingAndRuntimeState")
        val saveRecovered = extractFunctionBody(body, "override fun saveRecovered")
        val restoreFault = extractFunctionBody(body, "override fun restoreFault")

        listOf(
            "onlineStatus = config.onlineStatus",
            "errorStatus = config.errorStatus",
            "errorCode = config.errorCode",
            "errorCategory = config.errorCategory",
            "errorReason = config.errorReason",
            "isEnable = config.isEnable",
            "config.errorStatus = AppConfigBean.ERROR_STATUS_NORMAL",
            "config.errorCode = null",
            "config.errorCategory = null",
            "config.errorReason = null",
            "config.onlineStatus = targetOnlineStatus",
            "config.onlineStatus = snapshot.onlineStatus",
            "config.errorStatus = snapshot.errorStatus",
            "config.errorCode = snapshot.errorCode",
            "config.errorCategory = snapshot.errorCategory",
            "config.errorReason = snapshot.errorReason",
            "AppConfig.saveAppConfigDurable(config)"
        ).forEach { expected ->
            assertTrue("恢复状态存储缺少接线：$expected", body.contains(expected))
        }
        assertTrue("成功保存必须保持算法关闭", saveRecovered.contains("config.isEnable = 0"))
        assertTrue("故障回滚必须保持算法关闭", restoreFault.contains("config.isEnable = 0"))
    }

    @Test
    fun maintenanceUi_shouldBranchOnStableCodeInsteadOfChineseReason() {
        val source = sourceFile(
            "app/src/main/java/cn/niuyannet/kaochang/android/fragments/MaintenanceFragment.kt"
        )
        val body = extractFunctionBody(source, "private suspend fun applyManualRecoveredState")
        val policy = sourceFile(
            "app/src/main/java/cn/niuyannet/kaochang/android/fragments/ManualRecoveryFailureDisplayPolicy.kt"
        )

        assertTrue(
            "初始条件变化必须使用稳定 code",
            policy.contains("result.code == ManualFaultRecoveryCode.INITIAL_CONDITION_CHANGED")
        )
        assertTrue("Fragment 必须委托稳定证据策略", body.contains("ManualRecoveryFailureDisplayPolicy.detail(recoveryResult)"))
        assertTrue("中文 reason 不得再充当控制标识", !body.contains("failureReason =="))
    }

    @Test
    fun appConstructedDecisions_shouldUseDedicatedCodeAndExistingReason() {
        val maintenance = sourceFile(
            "app/src/main/java/cn/niuyannet/kaochang/android/fragments/MaintenanceFragment.kt"
        )
        val gate = extractFunctionBody(maintenance, "suspend fun evaluateManualRecoveryHeating")
        assertTrue(gate.contains("code = ManualRecoveryGateCode.ACTION_GROUP_RESIDUE_CLOSEOUT_FAILED"))
        assertTrue(gate.contains("reason = closeout.reason"))

        val http = sourceFile(
            "app/src/main/java/cn/niuyannet/kaochang/android/services/ManualRecoveryCloudHttpConfirmation.kt"
        )
        listOf(
            "ManualRecoveryCloudCode.LOCAL_STATE_CHANGED",
            "ManualRecoveryCloudCode.HTTP_VERIFICATION_FAILED",
            "ManualRecoveryCloudCode.CONFIRMED",
            "ManualRecoveryCloudCode.REMOTE_STATE_MISMATCH"
        ).forEach { code ->
            assertTrue("HTTP 适配器缺少稳定结果码：$code", http.contains(code))
        }
        listOf(
            "HTTP补偿期间本机状态再次变化",
            "HTTP补偿后读取后台状态失败：",
            "后台已通过HTTP回读确认恢复",
            "后台仍未恢复："
        ).forEach { reason ->
            assertTrue("HTTP 适配器丢失既有文案：$reason", http.contains(reason))
        }
    }

    @Test
    fun recoveryCore_shouldNotImportAndroidNetworkOrHardwareDependencies() {
        val coreRoot = sourceFilePath("recovery-core/src/main/kotlin")
        val forbidden = listOf(
            "android.",
            "VMModbusHelper",
            "AppConfig",
            "KaoChangOperate",
            "MaintenanceFragment",
            "mqtt",
            "okhttp",
            "retrofit",
            "fastjson",
            "java.io",
            "java.nio",
            "Thread(",
            "Executors",
            "kotlinx.coroutines",
            "Timer(",
            "Handler(",
            "System.currentTimeMillis",
            "System.nanoTime",
            "Clock.system",
            "File("
        )
        val source = coreRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }

        forbidden.forEach { token ->
            assertTrue("recovery-core 不得依赖：$token", !source.contains(token, ignoreCase = true))
        }
    }

    private fun sourceFile(path: String): String =
        sourceFilePath(path).readText().replace("\r\n", "\n")

    private fun sourceFilePath(path: String): File {
        val direct = File(path)
        if (direct.exists()) return direct
        val fromParent = File("..", path)
        if (fromParent.exists()) return fromParent
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
