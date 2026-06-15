package cn.niuyannet.kaochang.android.fragments

import org.junit.Assert.assertEquals
import org.junit.Test

class MaintenanceBoxCatalogRefreshPolicyTest {

    @Test
    fun resolveRefillCatalogRefreshAction_shouldOpenDialogAfterSuccessfulRefresh() {
        assertEquals(
            RefillCatalogRefreshAction.OPEN_DIALOG,
            resolveRefillCatalogRefreshAction(refreshSuccess = true, localCatalogNotEmpty = false)
        )
    }

    @Test
    fun resolveRefillCatalogRefreshAction_shouldUseLocalCacheWhenRefreshFailsButCatalogExists() {
        assertEquals(
            RefillCatalogRefreshAction.OPEN_DIALOG_WITH_CACHE_TOAST,
            resolveRefillCatalogRefreshAction(refreshSuccess = false, localCatalogNotEmpty = true)
        )
    }

    @Test
    fun resolveRefillCatalogRefreshAction_shouldRejectWhenRefreshFailsAndCatalogIsEmpty() {
        assertEquals(
            RefillCatalogRefreshAction.REJECT_WITH_NETWORK_TOAST,
            resolveRefillCatalogRefreshAction(refreshSuccess = false, localCatalogNotEmpty = false)
        )
    }
}
