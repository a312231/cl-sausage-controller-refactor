package cn.niuyannet.kaochang.android.services

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class PanPhysicalStateDeltaActionWiringSourceTest {

    @Test
    fun supplementReducer_shouldPublishPhysicalDeltaOnlyAfterLocalStateIsSaved() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val body = extractBetween(
            source,
            "private fun applySupplementPhysicalTelemetry(",
            "private fun applySuccessfulSupplementMove("
        )

        assertOrder(
            body,
            "KaoPanHelper.saveKaoPanBoxListSync(KaoPanHelper.getKaoPanBoxList())",
            "PanPhysicalStateDeltaReporter.reportStockChanged(",
            "in2 物理增量必须在本地箱库存保存后发布"
        )
        assertTrue("in2 必须用 action_physical_in2 source", body.contains("source = \"action_physical_in2\""))
        assertTrue("in2 必须继续保留 HTTP 快照兜底触发点", body.contains("action_physical:in2:picked_from_box"))

        assertOrder(
            body,
            "KaoPanHelper.saveKaoPanListSync(KaoPanHelper.getKaoPanList())",
            "PanPhysicalStateDeltaReporter.reportPanChanged(",
            "in7 物理增量必须在本地烤盘保存后发布"
        )
        assertTrue("in7 必须用 action_physical_in7 source", body.contains("source = \"action_physical_in7\""))
        assertTrue("in7 必须继续保留 HTTP 快照兜底触发点", body.contains("action_physical:in7:placed_on_pan"))
    }

    @Test
    fun outReducer_shouldPublishPhysicalDeltaOnlyAfterPanClearIsSaved() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val body = extractBetween(
            source,
            "private fun applyOutPhysicalTelemetry(",
            "private fun buildPipelineSupplementPlan("
        )

        assertOrder(
            body,
            "KaoPanHelper.saveKaoPanListSync(KaoPanHelper.getKaoPanList())",
            "PanPhysicalStateDeltaReporter.reportPanChanged(",
            "out1 物理增量必须在本地清盘保存后发布"
        )
        assertTrue("out1 必须用 action_physical_out1 source", body.contains("source = \"action_physical_out1\""))
        assertTrue("out1 必须继续保留 HTTP 快照兜底触发点", body.contains("action_physical:out1:removed_from_pan"))
    }

    @Test
    fun panMoveInternal_shouldPublishPhysicalDeltaAfterMoveStateWriteAndKeepSchedulingGate() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val body = extractFunctionBody(source, "private suspend fun moveKaoPanToKaoPanInternal(")

        assertTrue("搬盘核心必须继续保留自动调度门禁", body.contains("AutoMechanicalDispatchPolicy.evaluate("))
        assertTrue("搬盘核心必须继续保留原可售投影同步", body.contains("KaoPanCloudSyncReporter.uploadIfSellableStateChanged("))
        assertOrder(
            body,
            "KaoPanHelper.saveKaoPanListSync(KaoPanHelper.getKaoPanList())",
            "PanPhysicalStateDeltaReporter.reportPansChanged(",
            "搬盘物理增量必须在源/目标盘状态保存后发布"
        )
        assertTrue("搬盘必须同时上报源盘和目标盘", body.contains("changedPans = listOf(kaoPan, newKaoPan)"))
        assertTrue("搬盘必须用 pan_move_success source", body.contains("source = \"pan_move_success\""))
    }

    @Test
    fun physicalDeltaReporter_shouldUseAction2PublisherNotOrderAction() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/PanPhysicalStateDeltaReporter.kt")

        assertTrue("reporter 必须调用 SendServerHelper.publishPanPhysicalStateDelta", source.contains("SendServerHelper.publishPanPhysicalStateDelta("))
        assertTrue("reporter 不应出现 action=1 订单/动作指令常量", !source.contains("ACTION_ORDER_EVENT"))
    }

    private fun assertOrder(source: String, before: String, after: String, message: String) {
        val beforeIndex = source.indexOf(before)
        val afterIndex = source.indexOf(after)
        assertTrue("$message：找不到前置片段 $before", beforeIndex >= 0)
        assertTrue("$message：找不到后置片段 $after", afterIndex >= 0)
        assertTrue(message, beforeIndex < afterIndex)
    }

    private fun extractBetween(source: String, startText: String, endText: String): String {
        val start = source.indexOf(startText)
        assertTrue("找不到起点：$startText", start >= 0)
        val end = source.indexOf(endText, start).let { if (it >= 0) it else source.length }
        return source.substring(start, end)
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
