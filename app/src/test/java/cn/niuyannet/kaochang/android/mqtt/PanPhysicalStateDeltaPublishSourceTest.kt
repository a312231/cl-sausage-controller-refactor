package cn.niuyannet.kaochang.android.mqtt

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanPhysicalStateDeltaPublishSourceTest {

    @Test
    fun publishPhysicalDelta_shouldUseAction2AndIndependentPendingQueue() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/mqtt/SendServerHelper.kt")
        val body = extractFunctionBody(source, "fun publishPanPhysicalStateDelta")

        assertTrue("完整物理烤盘增量必须有独立 pending map", source.contains("pendingPanPhysicalStateDeltaAcks"))
        assertTrue("完整物理烤盘增量必须有独立 ACK 结果类型", source.contains("PanPhysicalStateDeltaAckResult"))
        assertTrue("完整物理烤盘增量必须只读取最后一次云端确认的 HTTP 快照基线", body.contains("val baseline = PanPhysicalStateBaselineStore.currentBaseline()"))
        assertTrue("缺少已确认 HTTP 快照基线时不能发布 physical delta", body.contains("if (baseline == null)"))
        assertTrue("缺少已确认 HTTP 快照基线时必须触发 HTTP 全量快照兜底", body.contains("triggerPanPhysicalStateDeltaSnapshotFallback(\"pan_physical_delta_missing_base:${'$'}reason\")"))
        assertTrue("完整物理烤盘增量必须走 action=2 服务状态通道", body.contains("action = MqttProtocol.ACTION_SERVICE_STATE"))
        assertFalse("完整物理烤盘增量绝不能走 action=1 订单/动作指令通道", body.contains("ACTION_ORDER_EVENT"))
        assertTrue("完整物理烤盘增量必须由 PanPhysicalStateDeltaPayloadBuilder 构建", body.contains("PanPhysicalStateDeltaPayloadBuilder.build"))
        assertFalse("完整物理增量不能复用可售投影 coalesceKey，否则会丢中间物理事件", body.contains("coalesceKey = \"pan_state_delta\""))
        assertFalse("完整物理增量不能清空旧 physical pending，否则连续动作会丢 ACK 追踪", body.contains("pendingPanPhysicalStateDeltaAcks.clear()"))
        assertTrue("完整物理增量 MQTT 入队成功后必须保留 pending ACK", body.contains("pendingPanPhysicalStateDeltaAcks[seq] = snapshot"))
        assertTrue("完整物理增量 MQTT 入队成功后必须安排 ACK 超时兜底", body.contains("schedulePanPhysicalStateDeltaAckTimeout(snapshot)"))
        assertTrue("完整物理增量 MQTT 入队失败必须触发 HTTP 全量快照兜底", body.contains("triggerPanPhysicalStateDeltaSnapshotFallback(\"pan_physical_delta_publish_failed:${'$'}reason\")"))
    }

    @Test
    fun physicalDeltaAck_shouldTriggerSnapshotFallbackOnlyWhenBackendNeedsFullSnapshot() {
        val sender = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/mqtt/SendServerHelper.kt")
        val receiver = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/mqtt/VMMqttHelper.kt")
        val consumeBody = extractFunctionBody(receiver, "private fun consumePanStateDeltaAckIfNeeded")

        assertTrue("ACK 处理必须读取 needFullSnapshot，区分普通 stale 和需要 HTTP 全量补偿的拒绝", consumeBody.contains("needFullSnapshot"))
        assertTrue("physical ACK 成功必须读取 serverPhysicalStateHash，防止新 APK 误信旧后端普通 pan_state_delta ACK", consumeBody.contains("serverPhysicalStateHash"))
        assertTrue("accepted/duplicate 但缺少 physical 专属哈希时必须触发 HTTP 快照兜底", consumeBody.contains("handleUnverifiedPanPhysicalStateDeltaAck"))
        assertTrue("空 eventId 且本地存在 physical pending 时必须直接忽略，不能仅凭 seq 清 pending", consumeBody.contains("eventId.isBlank() && SendServerHelper.hasPendingPanPhysicalStateDelta(panDeltaSeq)"))
        assertTrue("ACK 处理必须优先用 seq + eventId 尝试标记完整物理状态 ACK", consumeBody.contains("SendServerHelper.markPanPhysicalStateDeltaAcked(panDeltaSeq, eventId)"))
        assertTrue("物理状态 eventId 即使本地 pending 已不存在，也不能落回旧可售投影 ACK 路径", consumeBody.contains("eventId.contains(\":pan_physical_delta:\")") && consumeBody.contains("return true"))
        assertTrue("ACK 处理必须保留旧可售投影 ACK 路径", consumeBody.contains("SendServerHelper.markPanStateDeltaAcked(panDeltaSeq)"))
        assertTrue("完整物理状态被服务端要求全量时必须触发专用 rejected 兜底入口", consumeBody.contains("SendServerHelper.handleRejectedPanPhysicalStateDeltaAck(panDeltaSeq, reason, needFullSnapshot)"))
        assertTrue("完整物理状态 rejected 兜底入口必须存在", sender.contains("fun handleRejectedPanPhysicalStateDeltaAck(panDeltaSeq: Long, reason: String, needFullSnapshot: Boolean)"))
        assertTrue("完整物理状态 unverified ACK 兜底入口必须存在", sender.contains("fun handleUnverifiedPanPhysicalStateDeltaAck(panDeltaSeq: Long, reason: String)"))
        assertTrue("SendServerHelper 必须提供 physical pending 查询，供空 eventId ACK 防误清", sender.contains("fun hasPendingPanPhysicalStateDelta(panDeltaSeq: Long): Boolean"))
        assertTrue("needFullSnapshot=true 时必须调用 HTTP 快照兜底", sender.contains("pan_physical_delta_rejected"))
        assertTrue("physical ACK 缺少服务端物理哈希时必须调用 HTTP 快照兜底", sender.contains("pan_physical_delta_ack_unverified"))
        assertTrue("完整物理状态 HTTP 兜底必须复用 SnapshotSyncPolicy.maybeUploadSnapshot", sender.contains("SnapshotSyncPolicy.maybeUploadSnapshot(reason)"))
    }

    @Test
    fun physicalDeltaSeq_shouldUsePersistentStoreInsteadOfVolatileAtomicLong() {
        val sender = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/mqtt/SendServerHelper.kt")
        val store = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/mqtt/PanPhysicalStateDeltaSequenceStore.kt")
        val body = extractFunctionBody(sender, "fun publishPanPhysicalStateDelta")

        assertTrue("必须新增完整物理状态独立持久序列存储", store.contains("object PanPhysicalStateDeltaSequenceStore"))
        assertTrue("完整物理状态发布必须从持久 store 取下一个 seq", body.contains("PanPhysicalStateDeltaSequenceStore.nextSequence()"))
        assertFalse("完整物理状态不能复用可售投影的易失 AtomicLong 序列", body.contains("panStateDeltaSeq.incrementAndGet()"))
        assertFalse("完整物理状态发布接口不应暴露 baseSnapshotSequence 参数，避免调用者传入未确认基线", body.contains("baseSnapshotSequence: Long?"))
        assertFalse("完整物理状态发布接口不应暴露 baseSnapshotHash 参数，避免调用者传入未确认基线", body.contains("baseSnapshotHash: String?"))
        assertTrue("持久序列必须使用独立 preference 文件，避免和 HTTP snapshot sequence 混用", store.contains("file_pan_physical_state_delta_sequence"))
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
