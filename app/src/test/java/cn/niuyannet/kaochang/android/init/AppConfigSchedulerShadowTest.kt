package cn.niuyannet.kaochang.android.init

import com.alibaba.fastjson.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class AppConfigSchedulerShadowTest {

    @Test
    fun schedulerShadowFlag_shouldDefaultToDisabled() {
        assertEquals(0, AppConfigBean().enableSchedulerShadowMode)
    }

    @Test
    fun schedulerShadowFlag_shouldAcceptOnlyExplicitIntegerZeroOrOne() {
        val config = AppConfigBean()

        AppConfig.applyRemoteSchedulerShadowModeConfig(config, JSONObject().apply {
            put("enableSchedulerShadowMode", 1)
        })
        assertEquals(1, config.enableSchedulerShadowMode)

        AppConfig.applyRemoteSchedulerShadowModeConfig(config, JSONObject().apply {
            put("enableSchedulerShadowMode", 0)
        })
        assertEquals(0, config.enableSchedulerShadowMode)
    }

    @Test
    fun schedulerShadowFlag_shouldPreserveCurrentValueWhenMissingNullOrInvalid() {
        listOf<Any?>(MISSING, null, "1", 1.0, true, -1, 2).forEach { raw ->
            listOf(0, 1).forEach { current ->
                val config = AppConfigBean().apply { enableSchedulerShadowMode = current }
                val remote = JSONObject().apply {
                    if (raw !== MISSING) put("enableSchedulerShadowMode", raw)
                }

                AppConfig.applyRemoteSchedulerShadowModeConfig(config, remote)

                assertEquals("raw=$raw current=$current", current, config.enableSchedulerShadowMode)
            }
        }
    }

    private companion object {
        val MISSING = Any()
    }
}
