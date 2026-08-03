package cn.niuyannet.kaochang.android.services

import java.io.File
import org.junit.Assert.assertFalse
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
        assertTrue("搬盘认领后必须重新读取 journal，避免 checkpoint 覆盖 CLAIMED 证据", body.contains("journal = panMoveJournalStore.load() ?: journal"))
        assertTrue("搬盘核心必须保留 moveStatus=3", body.contains("claimedMoveStatus = 3"))
        assertTrue("搬盘核心必须保留成功后 moveStatus=0", body.contains("idleMoveStatus = 0"))
        assertTrue("搬盘 PREPARED 与机械认领必须复用短 transitionLock", body.contains("synchronized(faultHeatTransitionLock)"))
        assertTrue("搬盘拒绝时只能清理自己的 PREPARED journal", body.contains("panMoveJournalStore.load()?.moveId == owner"))
        assertFalse("搬盘认领失败不得绕过协调器裸清 journal", body.contains("if (panMoveJournalStore.load()?.moveId == owner) {\n                panMoveJournalStore.clearDurable()"))
        assertTrue("搬盘认领持久化失败必须保留忙态并进入维护", body.contains("保留 journal 与 moveStatus=3"))
    }

    @Test
    fun panMoveInternal_shouldNotHoldResourceLockWhileWaitingForMoveStatus() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val body = extractFunctionBody(source, "private suspend fun moveKaoPanToKaoPanInternal(")
        val acquire = body.indexOf("actionGroupLocks.tryAcquire(lockMask, owner)")
        val wait = body.indexOf("while (appState != 0)")
        val release = body.indexOf("actionGroupLocks.release(owner)")

        assertTrue("搬盘必须存在资源认领", acquire >= 0)
        assertTrue("搬盘不得在资源认领后等待其他动作", wait < 0 || wait < acquire)
        assertTrue("搬盘释放必须保留", release > acquire)
        assertTrue("搬盘资源忙时必须检查 owner/moveStatus 回滚结果", body.contains("val released = releaseMechanicalMove("))
        assertTrue("搬盘回滚失败必须保留忙态并进入维护", body.contains("preserveMoveStatus = true"))
    }

    @Test
    fun discard_shouldUseAtomicMechanicalClaimBeforeHardwareCommand() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val body = extractFunctionBody(source, "suspend fun discardSausage(")

        assertTrue("丢弃必须通过统一机械认领协调器", body.contains("claimMechanicalMove("))
        assertTrue("丢弃必须保留 0 -> 4 的认领语义", body.contains("claimedMoveStatus = 4"))
        val hardwareWrite = body.indexOf("writeSingleRegister(action_address")
        assertTrue("丢弃必须保留下位机要求的 150ms 写入延迟", body.contains("delay(150L)"))
        assertTrue("丢弃认领必须早于硬件指令", body.indexOf("claimedMoveStatus = 4") < hardwareWrite)
        assertTrue("丢弃不得再无界等待订单状态", !body.contains("while (appState!=0)"))
        assertTrue("丢弃下发前必须保留耐久 journal", body.contains("DiscardJournal("))
        assertTrue("丢弃结果未知不得清理 moveStatus", body.contains("保留 owner/journal 与 moveStatus=4"))
        assertTrue("丢弃未知结果进入维护时必须保留 moveStatus", body.contains("保留 owner/journal 与 moveStatus=4") && body.contains("preserveMoveStatus = true"))
        assertTrue("丢弃命令已尝试后取消必须保留恢复证据", body.contains("commandAttempted") && body.contains("CancellationException"))
    }

    @Test
    fun idleRecovery_shouldTreatDiscardJournalAsBlockingEvidence() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val start = source.indexOf("fun isActionGroupIdleForMoveStatusRecovery()")
        val end = source.indexOf("fun abandonSupplementPrefetchForManualPanClear", start)
        val body = source.substring(start, end)
        assertTrue("残留丢弃 journal 必须阻止自动清零 moveStatus", body.contains("discardJournalStore.load() == null"))
    }

    @Test
    fun journals_shouldFailClosedWhenPersistedJsonIsNullOrMissingIdentity() {
        val discard = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/DiscardJournal.kt")
        val panMove = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/PanMoveJournal.kt")

        assertTrue("丢弃 journal 解析 null 必须转为损坏证据", discard.contains("?: corruptJournal()"))
        assertTrue("丢弃 journal owner/位置非法必须转为损坏证据", discard.contains("owner.isNotBlank()") && discard.contains("positionSn > 0"))
        assertTrue("搬盘 journal 解析 null 必须转为损坏证据", panMove.contains("?: corruptJournal()"))
        assertTrue("搬盘 journal identity 非法必须转为损坏证据", panMove.contains("moveId.isNotBlank()") && panMove.contains("sourcePositionSn > 0"))
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
