package cn.niuyannet.kaochang.android.init

import com.alibaba.fastjson.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class AppConfigParallelPrefetchTest {

    @Test
    fun parallelPrefetchFlag_shouldDefaultToEnabled() {
        val config = AppConfigBean()

        assertEquals(1, config.enableParallelSupplementPrefetch)
    }

    @Test
    fun remoteParallelPrefetchFlag_shouldEnableOnlyForExplicitIntegerOne() {
        val config = AppConfigBean()
        val remote = JSONObject().apply {
            put("enableParallelSupplementPrefetch", 1)
        }

        AppConfig.applyRemoteParallelSupplementPrefetchConfig(config, remote)

        assertEquals(1, config.enableParallelSupplementPrefetch)
    }

    @Test
    fun remoteParallelPrefetchFlag_shouldDisableOnlyForExplicitIntegerZero() {
        val config = AppConfigBean()
        val remote = JSONObject().apply {
            put("enableParallelSupplementPrefetch", 0)
        }

        AppConfig.applyRemoteParallelSupplementPrefetchConfig(config, remote)

        assertEquals(0, config.enableParallelSupplementPrefetch)
    }

    @Test
    fun remoteParallelPrefetchFlag_shouldKeepCurrentValueForMissingOrInvalidValue() {
        listOf<Any?>(null, "1", 1.0, true, -1, 2).forEach { rawValue ->
            val config = AppConfigBean().apply { enableParallelSupplementPrefetch = 1 }
            val remote = JSONObject().apply {
                if (rawValue != null) put("enableParallelSupplementPrefetch", rawValue)
            }

            AppConfig.applyRemoteParallelSupplementPrefetchConfig(config, remote)

            assertEquals("remote=$rawValue", 1, config.enableParallelSupplementPrefetch)
        }
    }

    @Test
    fun missingCompleteRemotePayload_shouldKeepPreviouslyDisabledValue() {
        val config = AppConfigBean().apply { enableParallelSupplementPrefetch = 0 }

        AppConfig.applyRemoteParallelSupplementPrefetchConfig(config, null)

        assertEquals(0, config.enableParallelSupplementPrefetch)
    }
}
