package cn.niuyannet.kaochang.android.services

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyResetStartupSyncSourceTest {

    @Test
    fun applicationStartup_shouldEstablishFenceBeforeAnyRemotePull() {
        val source = sourceText("app/src/main/java/cn/niuyannet/kaochang/android/MyApp.kt")
        val onCreate = functionBody(source, "override fun onCreate()")
        val resetCloseout = onCreate.indexOf("ResetStartupCloseoutManager.resumeIfNeeded")
        val enforce = onCreate.indexOf("KaoChangOperate.enforceEmergencyResetRecoveryOnStartup()")
        val dataSync = onCreate.indexOf("DataManagementAPI.dataSyncServer()")

        assertTrue("Application 必须先恢复统一复位收口", resetCloseout >= 0 && resetCloseout < enforce)
        assertTrue("Application 必须先恢复紧急复位 fence，再决定是否拉云端数据", enforce >= 0 && enforce < dataSync)
        assertTrue(
            "任一复位栅栏存在时必须跳过启动期云端配置和库存拉取",
            onCreate.contains("if (!lowerBoardResetPending && !emergencyResetPending)")
        )
    }

    @Test
    fun delayedRemoteCallbacks_shouldAtomicallyGuardFenceBeforeLocalMutation() {
        val source = sourceText(
            "app/src/main/java/cn/niuyannet/kaochang/android/net/DataManagementAPI.kt"
        )
        val stock = functionBlock(source, "fun refreshStockData(", "fun refreshProductCatalog(")
        val config = functionBlock(source, "fun getDeviceInfo()", "fun preflightRemoteDeviceState(")
        val home = functionBlock(source, "fun refreshRemoteDeviceState(", "fun refreshHomeDeviceState(")

        assertAtomicFenceGuard(stock, "KaoPanHelper.saveKaoPanBoxListDurable(")
        assertAtomicFenceGuard(config, "AppConfig.mergeRemoteConfig(")
        assertAtomicFenceGuard(home, "AppConfig.mergeRemoteConfig(")
    }

    @Test
    fun pendingFenceRuntime_shouldOnlyUploadDeviceStateNotPhysicalSnapshot() {
        val source = sourceText("app/src/main/java/cn/niuyannet/kaochang/android/ui/MainActivity.kt")
        val startup = functionBody(source, "private fun startRuntimeAfterConfigLoaded(")
        val emergencyStart = startup.indexOf("if (emergencyResetPending)")
        val emergencyEnd = startup.indexOf("if (shouldStartRuntime) {\n            PowerLossRecoveryGuard", emergencyStart)
        val emergencyBranch = startup.substring(emergencyStart, emergencyEnd)

        assertTrue(!emergencyBranch.contains("DataManagementAPI.getDeviceInfo()"))
        assertTrue(!emergencyBranch.contains("DataManagementAPI.uploadDataToServer()"))
        assertTrue(emergencyBranch.contains("DataManagementAPI.uploadDeviceInfo("))
    }

    @Test
    fun incomingOrderAdmission_shouldRejectPendingFenceBeforeClaimingOrder() {
        val source = sourceText(
            "app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt"
        )
        val admission = functionBody(source, "fun acceptIncomingOrderForMechanicalDispatch(")
        val fenceCheck = admission.indexOf("hasPendingEmergencyResetRecovery()")
        val orderClaim = admission.indexOf("OrderStateManager.checkAndAcceptOrder(orderNo)")

        assertTrue("订单最终准入必须显式检查紧急复位 fence", fenceCheck >= 0)
        assertTrue("fence 检查必须早于订单认领", fenceCheck < orderClaim)
    }

    @Test
    fun prepareEmergencyFence_shouldDisableAlgorithmInsideSharedTransitionLock() {
        val source = sourceText(
            "app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt"
        )
        val prepare = functionBody(source, "fun prepareEmergencyResetRecoveryFence(")
        val transitionLock = prepare.indexOf("withFaultHeatTransitionLock")
        val disableAlgorithm = prepare.indexOf("KaoChangAlgorithm.disableRoastAlgorithm(")
        val saveFence = prepare.indexOf("emergencyResetRecoveryFenceStore.save(")

        assertTrue("紧急复位必须先进入共享状态转换锁", transitionLock >= 0)
        assertTrue("关闭算法必须在共享锁内执行", disableAlgorithm > transitionLock)
        assertTrue("关闭算法必须早于 fence 落盘", disableAlgorithm < saveFence)
    }

    @Test
    fun mqttRuntimeControl_shouldAtomicallyRejectPendingEmergencyFence() {
        val source = sourceText(
            "app/src/main/java/cn/niuyannet/kaochang/android/net/DataManagementAPI.kt"
        )
        val apply = functionBody(source, "fun applyRemoteRuntimeControl(")
        val transitionLock = apply.indexOf("KaoChangOperate.withFaultHeatTransitionLock")
        val fenceCheck = apply.indexOf("hasPendingEmergencyResetRecovery()")
        val patchMutation = apply.indexOf("RemoteRuntimeConfigPatchApplier.apply(")

        assertTrue("MQTT 运行控制必须与紧急复位建 fence 共用状态转换锁", transitionLock >= 0)
        assertTrue("运行控制必须在锁内检查紧急复位 fence", fenceCheck > transitionLock)
        assertTrue("fence 检查必须早于任何运行配置写入", fenceCheck < patchMutation)
    }

    @Test
    fun mqttConcurrencyControl_shouldAtomicallyRejectPendingEmergencyFence() {
        val source = sourceText(
            "app/src/main/java/cn/niuyannet/kaochang/android/net/DataManagementAPI.kt"
        )
        val apply = functionBody(source, "fun applyRemoteConcurrencyControl(")
        assertAtomicFenceGuard(apply, "AppConfig.saveAppConfig(")
    }

    @Test
    fun loadNetStartup_shouldSkipRemoteMergeUnderEmergencyFence() {
        val source = sourceText(
            "app/src/main/java/cn/niuyannet/kaochang/android/ui/LoadNetActivity.kt"
        )
        val load = functionBody(source, "fun loadDeviceInfo(")
        assertAtomicFenceGuard(load, "AppConfig.mergeRemoteConfig(")
    }

    private fun assertAtomicFenceGuard(source: String, mutationMarker: String) {
        val transitionLock = source.indexOf("KaoChangOperate.withFaultHeatTransitionLock")
        assertTrue("远端落地必须与紧急复位建 fence 共用状态转换锁", transitionLock >= 0)
        val locked = source.substring(transitionLock)
        val fenceCheck = locked.indexOf("hasPendingEmergencyResetRecovery()")
        val mutation = locked.indexOf(mutationMarker)
        assertTrue("必须在共享锁内检查紧急复位 fence", fenceCheck >= 0)
        assertTrue("共享锁内 fence 检查必须早于本地写入：$mutationMarker", mutation > fenceCheck)
    }

    private fun occurrences(source: String, text: String): Int =
        Regex(Regex.escape(text)).findAll(source).count()

    private fun sourceText(path: String): String =
        listOf(File(path), File("..", path)).first { it.exists() }.readText().replace("\r\n", "\n")

    private fun functionBlock(source: String, startMarker: String, endMarker: String): String {
        val start = source.indexOf(startMarker)
        assertTrue("缺少函数：$startMarker", start >= 0)
        val end = source.indexOf(endMarker, start + startMarker.length)
        assertTrue("缺少函数结束标记：$endMarker", end > start)
        return source.substring(start, end)
    }

    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("缺少函数：$signature", start >= 0)
        val bodyStart = source.indexOf('{', start)
        var depth = 0
        for (index in bodyStart until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(bodyStart + 1, index)
                }
            }
        }
        throw AssertionError("函数体未闭合：$signature")
    }
}
