package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.BusinessTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BusinessTimeWindowHelperTest {

    @Test
    fun isWithinBusinessHours_shouldSupportSecondPrecisionEndBoundary() {
        val businessTime = BusinessTime().apply {
            mode = "everyday"
            defaultTime = listOf("00:00:00", "23:59:59")
        }

        assertTrue(BusinessTimeWindowHelper.isWithinBusinessHours("23:59:59", businessTime))
    }

    @Test
    fun isWithinBusinessHours_shouldSupportLegacyHourMinuteFormat() {
        val businessTime = BusinessTime().apply {
            mode = "everyday"
            defaultTime = listOf("08:30", "20:00")
        }

        assertTrue(BusinessTimeWindowHelper.isWithinBusinessHours("08:30:00", businessTime))
        assertTrue(BusinessTimeWindowHelper.isWithinBusinessHours("19:59:59", businessTime))
        assertFalse(BusinessTimeWindowHelper.isWithinBusinessHours("20:00:01", businessTime))
    }

    @Test
    fun isWithinBusinessHours_shouldSupportCrossDaySecondPrecisionRange() {
        val businessTime = BusinessTime().apply {
            mode = "everyday"
            defaultTime = listOf("22:00:00", "02:00:00")
        }

        assertTrue(BusinessTimeWindowHelper.isWithinBusinessHours("23:30:00", businessTime))
        assertTrue(BusinessTimeWindowHelper.isWithinBusinessHours("01:30:00", businessTime))
        assertFalse(BusinessTimeWindowHelper.isWithinBusinessHours("03:00:00", businessTime))
    }

    @Test
    fun remainingMillisUntilClose_shouldPreserveSecondPrecisionEndBoundary() {
        val businessTime = BusinessTime().apply {
            mode = "everyday"
            defaultTime = listOf("00:00:00", "23:59:59")
        }

        assertEquals(1000L, BusinessTimeWindowHelper.remainingMillisUntilClose("23:59:58", businessTime))
        assertEquals(0L, BusinessTimeWindowHelper.remainingMillisUntilClose("23:59:59", businessTime))
    }

    @Test
    fun isBeforeCloseWindow_shouldUseSecondPrecisionBoundary() {
        val businessTime = BusinessTime().apply {
            mode = "everyday"
            defaultTime = listOf("00:00:00", "23:59:59")
        }

        assertFalse(BusinessTimeWindowHelper.isBeforeCloseWindow("23:58:58", businessTime, 1))
        assertTrue(BusinessTimeWindowHelper.isBeforeCloseWindow("23:58:59", businessTime, 1))
        assertTrue(BusinessTimeWindowHelper.isBeforeCloseWindow("23:59:59", businessTime, 1))
    }

    @Test
    fun isBeforeCloseWindow_shouldKeepLegacyHourMinuteCompatibility() {
        val businessTime = BusinessTime().apply {
            mode = "everyday"
            defaultTime = listOf("08:00", "20:00")
        }

        assertTrue(BusinessTimeWindowHelper.isBeforeCloseWindow("19:59:30", businessTime, 1))
        assertFalse(BusinessTimeWindowHelper.isBeforeCloseWindow("19:58:59", businessTime, 1))
    }
}
