package cn.niuyannet.kaochang.android.mqtt

import cn.niuyannet.kaochang.android.init.AppConfigBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SendServerHelperSignatureTest {

    @Test
    fun buildRuntimeStateSignature_shouldNormalizePendingDiscardOrder() {
        val first = baseConfig().apply {
            pendingDiscardPositions.addAll(listOf(26, 25, 26))
        }
        val second = baseConfig().apply {
            pendingDiscardPositions.addAll(listOf(25, 26))
        }

        assertEquals(RuntimeStateSignatureBuilder.build(first), RuntimeStateSignatureBuilder.build(second))
    }

    @Test
    fun buildRuntimeStateSignature_shouldChangeWhenFoodSafetyAlertChanges() {
        val normal = baseConfig()
        val alert = baseConfig().apply {
            foodSafetyAlertCode = FaultAlertCode
            foodSafetyAlertReason = "故障停热暂停超阈值"
        }

        assertNotEquals(RuntimeStateSignatureBuilder.build(normal), RuntimeStateSignatureBuilder.build(alert))
    }

    @Test
    fun buildRuntimeStateSignature_shouldIgnoreKeepWarmExpiredAlert() {
        val normal = baseConfig()
        val expiredAlert = baseConfig().apply {
            foodSafetyAlertCode = KeepWarmExpiredAlertCode
            foodSafetyAlertReason = "过保/待丢弃扫描触发：source=高并发轮询"
        }

        assertEquals(RuntimeStateSignatureBuilder.build(normal), RuntimeStateSignatureBuilder.build(expiredAlert))
    }

    @Test
    fun buildRuntimeStateSignature_shouldChangeWhenPendingDiscardContentChanges() {
        val original = baseConfig().apply {
            pendingDiscardPositions.addAll(listOf(25, 26))
        }
        val changed = baseConfig().apply {
            pendingDiscardPositions.addAll(listOf(25, 26, 30))
        }

        assertNotEquals(RuntimeStateSignatureBuilder.build(original), RuntimeStateSignatureBuilder.build(changed))
    }

    @Test
    fun buildRuntimeStateSignature_shouldIgnoreLocalBasicStatus() {
        val enabled = baseConfig().apply {
            status = 1
        }
        val disabled = baseConfig().apply {
            status = 0
        }

        assertEquals(RuntimeStateSignatureBuilder.build(enabled), RuntimeStateSignatureBuilder.build(disabled))
    }

    @Test
    fun buildRuntimeStateSignature_shouldChangeWhenOnlineStatusChanges() {
        val operating = baseConfig().apply {
            onlineStatus = 1
        }
        val resting = baseConfig().apply {
            onlineStatus = 2
        }

        assertNotEquals(RuntimeStateSignatureBuilder.build(operating), RuntimeStateSignatureBuilder.build(resting))
    }

    private fun baseConfig(): AppConfigBean =
        AppConfigBean().apply {
            status = 1
            onlineStatus = 1
            supplyStatus = AppConfigBean.SUPPLY_STATUS_NORMAL
            scanBlockStatus = AppConfigBean.SCAN_BLOCK_STATUS_NORMAL
            inspectionMode = AppConfigBean.INSPECTION_MODE_NORMAL
            isEnable = 1
            modeType = 2
            transitionMode = 0
            highConcurrencyResidualDrain = 0
            moveStatus = 0
            errorStatus = AppConfigBean.ERROR_STATUS_NORMAL
            restStatusSource = AppConfigBean.REST_SOURCE_NONE
        }

    private companion object {
        const val FaultAlertCode = "ANOMALY_TOO_LONG"
        const val KeepWarmExpiredAlertCode = "KEEP_WARM_EXPIRED"
    }
}
