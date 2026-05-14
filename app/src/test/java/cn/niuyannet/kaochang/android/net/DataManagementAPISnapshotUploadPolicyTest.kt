package cn.niuyannet.kaochang.android.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataManagementAPISnapshotUploadPolicyTest {

    @Test
    fun shouldSkipSnapshotUploadForMissingStockCache_shouldRejectZeroStockItems() {
        assertTrue(DataManagementAPI.shouldSkipSnapshotUploadForMissingStockCache(0))
    }

    @Test
    fun shouldSkipSnapshotUploadForMissingStockCache_shouldAllowExistingStockItems() {
        assertFalse(DataManagementAPI.shouldSkipSnapshotUploadForMissingStockCache(1))
    }
}
