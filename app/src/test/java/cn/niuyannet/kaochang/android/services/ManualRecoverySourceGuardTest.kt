package cn.niuyannet.kaochang.android.services

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualRecoverySourceGuardTest {

    @Test
    fun confirmClearActionGroupRecoveryResidue_shouldNotClearDeviceStatusFields() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val body = extractFunctionBody(source, "fun confirmClearActionGroupRecoveryResidue")

        assertTrue("confirmClearActionGroupRecoveryResidue 不允许写 errorStatus（设备故障状态）", !body.contains("errorStatus"))
        assertTrue("confirmClearActionGroupRecoveryResidue 不允许写 onlineStatus（设备营业状态）", !body.contains("onlineStatus"))
        assertTrue("confirmClearActionGroupRecoveryResidue 不允许写 isEnable（烤肠算法开关）", !body.contains("isEnable"))
    }

    @Test
    fun maintenanceRecoveryClick_shouldGoThroughManualRecoveryGateBeforeApplyingRecoveredState() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/fragments/MaintenanceFragment.kt")
        val clickBody = extractFunctionBody(source, "binding.btnErroStatus.setOnClickListener")
        val gateIndex = clickBody.indexOf("evaluateManualRecoveryHeating(")
        val applyIndex = clickBody.indexOf("applyManualRecoveredState(")

        assertTrue("恢复正常状态路径必须先调用 evaluateManualRecoveryHeating 恢复闸门", gateIndex >= 0)
        assertTrue("恢复正常状态路径必须通过 applyManualRecoveredState 统一写状态", applyIndex >= 0)
        assertTrue("恢复闸门必须先于状态写入方法调用", gateIndex < applyIndex)
    }

    @Test
    fun applyManualRecoveredState_shouldRestoreHeatingBeforePublishingRecoveredState() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/fragments/MaintenanceFragment.kt")
        val body = extractFunctionBody(source, "fun applyManualRecoveredState")

        assertTrue(
            "applyManualRecoveredState 必须通过原子恢复入口先恢复温控再清故障",
            body.contains("recoverFaultHeatingAndRuntimeState")
        )
        assertTrue(
            "applyManualRecoveredState 不允许在恢复成功后再次停热",
            !body.contains("stopHeatingForFault")
        )
    }

    @Test
    fun algorithmPreflight_shouldBeReadOnlyAndCallbacksShouldBeGenerationFenced() {
        val apiSource = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/net/DataManagementAPI.kt")
        val preflightBody = extractFunctionBody(apiSource, "fun preflightRemoteDeviceState")
        val fragmentSource = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/fragments/MaintenanceFragment.kt")
        val setupBody = extractFunctionBody(fragmentSource, "fun setupButtons")

        assertTrue("算法开启预检不得合并远端配置", !preflightBody.contains("mergeRemoteConfig"))
        assertTrue("算法开启预检不得触发运行态迁移", !preflightBody.contains("handleRemoteOnlineStatusTransition"))
        assertTrue("维护页必须使用只读预检", setupBody.contains("preflightRemoteDeviceState"))
        assertTrue("迟到回调必须校验操作代次", setupBody.contains("operationGate.isCurrent(operationToken)"))
    }

    @Test
    fun algorithmDisable_shouldShareFaultHeatTransitionLock() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangAlgorithm.kt")
        val body = extractFunctionBody(source, "fun disableRoastAlgorithm")

        assertTrue("关闭算法必须与开启/故障恢复共享 transition lock", body.contains("withFaultHeatTransitionLock"))
    }

    @Test
    fun staleFaultRecoveryFinally_shouldNotResetNewerOperationState() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/fragments/MaintenanceFragment.kt")
        val body = extractFunctionBody(source, "private fun finishFaultRecoveryOperation")
        val finishToken = body.indexOf("if (!operationGate.finish(token)) return")
        val clearRecovery = body.indexOf("manualRecoveryInProgress.set(false)")
        val releaseBusy = body.indexOf("status.set(true)")

        assertTrue("迟到 finally 必须先通过操作代次校验", finishToken >= 0)
        assertTrue("只有当前恢复操作才能清除恢复中标记", clearRecovery > finishToken)
        assertTrue("只有当前恢复操作才能释放维护忙标记", releaseBusy > finishToken)
    }

    @Test
    fun algorithmCooldown_shouldRestoreAllPreflightDisabledControls() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/fragments/MaintenanceFragment.kt")
        val body = extractFunctionBody(source, "fun startAlgorithmSwitchCooldown()")
        val restoreCount = Regex("setFaultRecoveryConflictingControlsEnabled\\(true\\)")
            .findAll(body)
            .count()

        assertTrue("冷却超时和正常结束都必须恢复预检阶段禁用的全部冲突控件", restoreCount == 2)
    }

    private fun sourceFile(path: String): String {
        val direct = File(path)
        if (direct.exists()) {
            return direct.readText()
        }
        val fromParent = File("..", path)
        if (fromParent.exists()) {
            return fromParent.readText()
        }
        throw AssertionError("找不到源码文件：$path，cwd=${File(".").absolutePath}")
    }

    private fun extractFunctionBody(source: String, functionSignature: String): String {
        val start = source.indexOf(functionSignature)
        assertTrue("找不到函数：$functionSignature", start >= 0)
        val bodyStart = source.indexOf('{', start)
        assertTrue("找不到函数体：$functionSignature", bodyStart >= 0)
        var depth = 0
        for (index in bodyStart until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return source.substring(bodyStart + 1, index)
                    }
                }
            }
        }
        throw AssertionError("函数体未闭合：$functionSignature")
    }
}
