package cn.niuyannet.kaochang.android.init

import com.alibaba.fastjson.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class AppConfigHomeQrHiddenMergeTest {

    @Test
    fun applyHomeQrHiddenRemoteConfigInto_shouldPreferRemoteHomeQrHiddenOverLocalLegacyScanBlockStatus() {
        val config = AppConfigBean().apply {
            homeQrHidden = AppConfigBean.HOME_QR_HIDDEN_HIDDEN
            scanBlockStatus = AppConfigBean.SCAN_BLOCK_STATUS_BLOCKED
        }
        val remote = JSONObject().apply {
            put("homeQrHidden", AppConfigBean.HOME_QR_HIDDEN_SHOW)
        }

        AppConfig.applyHomeQrHiddenRemoteConfigInto(config, remote)

        assertEquals(AppConfigBean.HOME_QR_HIDDEN_SHOW, config.homeQrHidden)
        assertEquals(AppConfigBean.SCAN_BLOCK_STATUS_NORMAL, config.scanBlockStatus)
    }

    @Test
    fun applyHomeQrHiddenRemoteConfigInto_shouldUseRemoteLegacyScanBlockStatusWhenHomeQrHiddenIsMissing() {
        val config = AppConfigBean().apply {
            homeQrHidden = AppConfigBean.HOME_QR_HIDDEN_HIDDEN
            scanBlockStatus = AppConfigBean.SCAN_BLOCK_STATUS_BLOCKED
        }
        val remote = JSONObject().apply {
            put("scanBlockStatus", AppConfigBean.SCAN_BLOCK_STATUS_NORMAL)
        }

        AppConfig.applyHomeQrHiddenRemoteConfigInto(config, remote)

        assertEquals(AppConfigBean.HOME_QR_HIDDEN_SHOW, config.homeQrHidden)
        assertEquals(AppConfigBean.SCAN_BLOCK_STATUS_NORMAL, config.scanBlockStatus)
    }

    @Test
    fun applyHomeQrHiddenRemoteConfigInto_shouldPreserveLocalCompatibilityWhenRemoteFieldsAreMissing() {
        val config = AppConfigBean().apply {
            homeQrHidden = AppConfigBean.HOME_QR_HIDDEN_SHOW
            scanBlockStatus = AppConfigBean.SCAN_BLOCK_STATUS_BLOCKED
        }
        val remote = JSONObject()

        val preservedFields = AppConfig.applyHomeQrHiddenRemoteConfigInto(config, remote)

        assertEquals(listOf("homeQrHidden"), preservedFields)
        assertEquals(AppConfigBean.HOME_QR_HIDDEN_HIDDEN, config.homeQrHidden)
        assertEquals(AppConfigBean.SCAN_BLOCK_STATUS_BLOCKED, config.scanBlockStatus)
    }
}
