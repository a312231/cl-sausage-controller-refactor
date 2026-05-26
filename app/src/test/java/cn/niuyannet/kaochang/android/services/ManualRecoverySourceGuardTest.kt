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
    fun applyManualRecoveredState_shouldKeepHeatingStoppedUntilManualAlgorithmEnable() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/fragments/MaintenanceFragment.kt")
        val body = extractFunctionBody(source, "fun applyManualRecoveredState")

        assertTrue(
            "applyManualRecoveredState 不允许调用 restoreHeatingStateFromCurrentPans 自动恢复温控",
            !body.contains("restoreHeatingStateFromCurrentPans")
        )
        assertTrue(
            "applyManualRecoveredState 必须调用 stopHeatingForFault 保持温控关闭",
            body.contains("stopHeatingForFault")
        )
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
