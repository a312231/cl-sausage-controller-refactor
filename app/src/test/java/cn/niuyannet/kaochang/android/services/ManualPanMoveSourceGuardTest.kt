package cn.niuyannet.kaochang.android.services

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualPanMoveSourceGuardTest {

    @Test
    fun automaticPanMove_shouldKeepAutoDispatchGateAndLegacyOwner() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val body = extractFunctionBody(source, "suspend fun moveKaoPanToKaoPan(")

        assertTrue("自动搬盘入口必须继续走自动机械调度门禁", body.contains("requireAutoDispatchGate = true"))
        assertTrue("自动搬盘入口 owner 前缀必须保持 legacy_move", body.contains("ownerPrefix = \"legacy_move\""))
        assertTrue("自动搬盘日志原因必须保持自动调度", body.contains("operationReason = \"自动调度\""))
    }

    @Test
    fun manualPanMove_shouldBypassOnlyAutoDispatchGateAndUseManualOwner() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val body = extractFunctionBody(source, "suspend fun moveKaoPanToKaoPanManual(")

        assertTrue("手动搬盘入口不能走自动机械调度门禁", body.contains("requireAutoDispatchGate = false"))
        assertTrue("手动搬盘入口 owner 前缀必须是 manual_move", body.contains("ownerPrefix = \"manual_move\""))
        assertTrue("手动搬盘日志原因必须区分维护页人工操作", body.contains("operationReason = \"维护页手动搬盘\""))
    }

    @Test
    fun panMoveInternal_shouldKeepResourceMaskAndAutoGateCondition() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val body = extractFunctionBody(source, "private suspend fun moveKaoPanToKaoPanInternal(")

        assertTrue("搬盘核心必须只在 requireAutoDispatchGate=true 时调用 AutoMechanicalDispatchPolicy", body.contains("if (requireAutoDispatchGate)"))
        assertTrue("搬盘核心必须保留 AutoMechanicalDispatchPolicy.evaluate", body.contains("AutoMechanicalDispatchPolicy.evaluate("))
        assertTrue("搬盘核心必须保留 lift|above 资源锁", body.contains("val lockMask = ResourceMask.lift or ResourceMask.above"))
        assertTrue("搬盘核心必须保留 moveStatus=3", body.contains("moveStatus = 3"))
        assertTrue("搬盘核心必须保留成功后 moveStatus=0", body.contains("moveStatus = 0"))
    }

    private fun sourceFile(path: String): String {
        val direct = File(path)
        if (direct.exists()) {
            return direct.readText().replace("\r\n", "\n")
        }
        val fromParent = File("..", path)
        if (fromParent.exists()) {
            return fromParent.readText().replace("\r\n", "\n")
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
