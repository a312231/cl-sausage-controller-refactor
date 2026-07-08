package cn.niuyannet.kaochang.android.init

import com.alibaba.fastjson.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class AppConfigAvailableMergeTest {

    @Test
    fun applyRemoteAvailableState_shouldPreserveLocalRealtimeStateWhenRuntimePreserveEnabled() {
        val config = AppConfigBean().apply {
            available = 17L * 60L * 1000L
            availableCountdownLatched = 0
        }
        val remote = JSONObject().apply {
            put("available", 30 * 60 * 1000)
            put("availableCountdownLatched", 1)
        }

        val preservedFields = AppConfig.applyRemoteAvailableState(
            config = config,
            jsonData = remote,
            preserveRuntimeAvailableState = true,
            emitLogs = false
        )

        assertEquals(17L * 60L * 1000L, config.available)
        assertEquals(0, config.availableCountdownLatched)
        assertEquals(
            listOf("available(runtime_local)", "availableCountdownLatched(runtime_local)"),
            preservedFields
        )
    }

    @Test
    fun applyRemoteAvailableState_shouldApplyRemoteStateWhenRuntimePreserveDisabled() {
        val config = AppConfigBean().apply {
            available = 0L
            availableCountdownLatched = 0
        }
        val remote = JSONObject().apply {
            put("available", 30 * 60 * 1000)
            put("availableCountdownLatched", 1)
        }

        val preservedFields = AppConfig.applyRemoteAvailableState(
            config = config,
            jsonData = remote,
            preserveRuntimeAvailableState = false,
            emitLogs = false
        )

        assertEquals(30L * 60L * 1000L, config.available)
        assertEquals(1, config.availableCountdownLatched)
        assertEquals(emptyList<String>(), preservedFields)
    }
}
