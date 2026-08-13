package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.recovery.ManualRecoveryCloudCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualRecoveryCloudHttpConfirmationTest {

    @Test
    fun httpVerification_shouldConfirmOnlyWhenBackendStateMatchesAndLocalStateStayedRecovered() {
        val confirmed = ManualRecoveryCloudHttpConfirmation.evaluate(
            updateTransportSucceeded = true,
            updateResponse = """{"msg":"更新成功","code":200}""",
            verifyTransportSucceeded = true,
            verifyResponse = """{"msg":"操作成功","code":200,"data":{"onlineStatus":"1","errorStatus":0,"moveStatus":0,"isEnable":0}}""",
            targetOnlineStatus = 1,
            localStateStillRecovered = true
        )
        assertTrue(confirmed.confirmed)
        assertEquals(ManualRecoveryCloudCode.CONFIRMED, confirmed.code)
        assertEquals("后台已通过HTTP回读确认恢复", confirmed.reason)

        val remoteStillFaulted = ManualRecoveryCloudHttpConfirmation.evaluate(
            updateTransportSucceeded = true,
            updateResponse = """{"msg":"更新成功","code":200}""",
            verifyTransportSucceeded = true,
            verifyResponse = """{"msg":"操作成功","code":200,"data":{"onlineStatus":"3","errorStatus":2,"moveStatus":0,"isEnable":0}}""",
            targetOnlineStatus = 1,
            localStateStillRecovered = true
        )
        assertFalse(remoteStillFaulted.confirmed)
        assertEquals(ManualRecoveryCloudCode.REMOTE_STATE_MISMATCH, remoteStillFaulted.code)
        assertTrue(remoteStillFaulted.reason.startsWith("后台仍未恢复："))

        val remoteAlgorithmStillEnabled = ManualRecoveryCloudHttpConfirmation.evaluate(
            updateTransportSucceeded = true,
            updateResponse = """{"msg":"更新成功","code":200}""",
            verifyTransportSucceeded = true,
            verifyResponse = """{"msg":"操作成功","code":200,"data":{"onlineStatus":"1","errorStatus":0,"isEnable":1}}""",
            targetOnlineStatus = 1,
            localStateStillRecovered = true
        )
        assertFalse(remoteAlgorithmStillEnabled.confirmed)
        assertEquals(ManualRecoveryCloudCode.REMOTE_STATE_MISMATCH, remoteAlgorithmStillEnabled.code)

        val localChanged = ManualRecoveryCloudHttpConfirmation.evaluate(
            updateTransportSucceeded = true,
            updateResponse = """{"msg":"更新成功","code":200}""",
            verifyTransportSucceeded = true,
            verifyResponse = """{"msg":"操作成功","code":200,"data":{"onlineStatus":"1","errorStatus":0,"moveStatus":0,"isEnable":0}}""",
            targetOnlineStatus = 1,
            localStateStillRecovered = false
        )
        assertFalse(localChanged.confirmed)
        assertEquals(ManualRecoveryCloudCode.LOCAL_STATE_CHANGED, localChanged.code)
        assertEquals("HTTP补偿期间本机状态再次变化", localChanged.reason)
    }

    @Test
    fun httpVerification_shouldTrustReadBackStateEvenWhenConcurrentUpdateRequestReportedFailure() {
        val result = ManualRecoveryCloudHttpConfirmation.evaluate(
            updateTransportSucceeded = false,
            updateResponse = """{"msg":"设备运行态处理中，请稍后重试","code":500}""",
            verifyTransportSucceeded = true,
            verifyResponse = """{"msg":"操作成功","code":200,"data":{"onlineStatus":1,"errorStatus":0,"moveStatus":0,"isEnable":0}}""",
            targetOnlineStatus = 1,
            localStateStillRecovered = true
        )

        assertTrue(result.confirmed)
        assertEquals(ManualRecoveryCloudCode.CONFIRMED, result.code)
        assertEquals("后台已通过HTTP回读确认恢复", result.reason)
    }

    @Test
    fun httpVerificationFailure_shouldUseStableFailureCodeAndKeepExistingReason() {
        val result = ManualRecoveryCloudHttpConfirmation.evaluate(
            updateTransportSucceeded = true,
            updateResponse = """{"msg":"更新成功","code":200}""",
            verifyTransportSucceeded = false,
            verifyResponse = null,
            targetOnlineStatus = 1,
            localStateStillRecovered = true
        )

        assertFalse(result.confirmed)
        assertEquals(ManualRecoveryCloudCode.HTTP_VERIFICATION_FAILED, result.code)
        assertEquals(
            "HTTP补偿后读取后台状态失败：网络请求失败；更新结果=code=200，message=更新成功",
            result.reason
        )
    }
}
