package cn.niuyannet.kaochang.android.services

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

        val remoteStillFaulted = ManualRecoveryCloudHttpConfirmation.evaluate(
            updateTransportSucceeded = true,
            updateResponse = """{"msg":"更新成功","code":200}""",
            verifyTransportSucceeded = true,
            verifyResponse = """{"msg":"操作成功","code":200,"data":{"onlineStatus":"3","errorStatus":2,"moveStatus":0,"isEnable":0}}""",
            targetOnlineStatus = 1,
            localStateStillRecovered = true
        )
        assertFalse(remoteStillFaulted.confirmed)

        val remoteAlgorithmStillEnabled = ManualRecoveryCloudHttpConfirmation.evaluate(
            updateTransportSucceeded = true,
            updateResponse = """{"msg":"更新成功","code":200}""",
            verifyTransportSucceeded = true,
            verifyResponse = """{"msg":"操作成功","code":200,"data":{"onlineStatus":"1","errorStatus":0,"isEnable":1}}""",
            targetOnlineStatus = 1,
            localStateStillRecovered = true
        )
        assertFalse(remoteAlgorithmStillEnabled.confirmed)

        val localChanged = ManualRecoveryCloudHttpConfirmation.evaluate(
            updateTransportSucceeded = true,
            updateResponse = """{"msg":"更新成功","code":200}""",
            verifyTransportSucceeded = true,
            verifyResponse = """{"msg":"操作成功","code":200,"data":{"onlineStatus":"1","errorStatus":0,"moveStatus":0,"isEnable":0}}""",
            targetOnlineStatus = 1,
            localStateStillRecovered = false
        )
        assertFalse(localChanged.confirmed)
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
    }
}
