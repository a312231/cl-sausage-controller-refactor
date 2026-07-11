package cn.niuyannet.kaochang.android.init

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppConfigLowerPlatformGenerationTest {
    @Test
    fun ensureLowerPlatformGeneration_shouldInitializeBlankGenerationOnce() {
        val config = AppConfigBean().apply { lowerPlatformGeneration = "" }

        val changed = AppConfig.ensureLowerPlatformGeneration(config) { "generated-uuid" }
        val changedAgain = AppConfig.ensureLowerPlatformGeneration(config) { "must-not-replace" }

        assertTrue(changed)
        assertFalse(changedAgain)
        assertEquals("generated-uuid", config.lowerPlatformGeneration)
    }
}
