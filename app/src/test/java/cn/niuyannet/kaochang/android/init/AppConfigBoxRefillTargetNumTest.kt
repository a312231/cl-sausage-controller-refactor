package cn.niuyannet.kaochang.android.init

import org.junit.Assert.assertEquals
import org.junit.Test

class AppConfigBoxRefillTargetNumTest {
    @Test
    fun normalizeBoxRefillTargetNum_shouldKeepValidValues() {
        assertEquals(1, AppConfig.normalizeBoxRefillTargetNum(1))
        assertEquals(10, AppConfig.normalizeBoxRefillTargetNum(10))
        assertEquals(15, AppConfig.normalizeBoxRefillTargetNum(15))
    }

    @Test
    fun normalizeBoxRefillTargetNum_shouldFallbackToDefaultWhenInvalid() {
        assertEquals(10, AppConfig.normalizeBoxRefillTargetNum(0))
        assertEquals(10, AppConfig.normalizeBoxRefillTargetNum(-1))
        assertEquals(10, AppConfig.normalizeBoxRefillTargetNum(16))
    }
}
