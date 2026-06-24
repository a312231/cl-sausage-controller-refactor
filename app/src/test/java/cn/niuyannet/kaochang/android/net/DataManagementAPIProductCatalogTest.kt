package cn.niuyannet.kaochang.android.net

import cn.niuyannet.kaochang.android.model.KaoPanHelper
import cn.niuyannet.kaochang.android.model.bean.Taste
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataManagementAPIProductCatalogTest {

    @After
    fun tearDown() {
        replaceTasteCatalog(mutableListOf())
        DataManagementAPI.resetProductCatalogRefreshStateForTest()
    }

    @Test
    fun parseProductCatalogResponse_shouldMapProductAndTasteMetadata() {
        val content = """
            {
              "code": 200,
              "data": [
                {
                  "id": 105,
                  "name": "123",
                  "tasteId": 3,
                  "tasteCode": "C",
                  "tasteName": "口味C"
                }
              ]
            }
        """.trimIndent()

        val tastes = DataManagementAPI.parseProductCatalogResponse(content)

        assertNotNull(tastes)
        val taste = tastes!!.single()
        assertEquals(105, taste.productId)
        assertEquals("123", taste.productName)
        assertEquals(3, taste.tasteId)
        assertEquals("C", taste.tasteCode)
        assertEquals("口味C", taste.tasteName)
    }

    @Test
    fun applyProductCatalogResponse_shouldKeepLocalCacheAndMarkRemoteEmptyWhenRemoteListEmpty() {
        val localCatalog = mutableListOf(Taste(1, "A", "原味", 101, "原味商品"))

        val result = DataManagementAPI.applyParsedProductCatalogForTest(
            refreshedTasteList = emptyList(),
            currentTasteList = localCatalog
        )

        assertTrue(result.success)
        assertTrue(result.remoteCatalogEmpty)
        assertEquals("remote_empty", result.reason)
        assertEquals(0, result.tasteCount)
        assertEquals(1, localCatalog.size)
        assertTrue(DataManagementAPI.wasLastRemoteProductCatalogEmpty())
    }

    @Test
    fun applyProductCatalogResponse_shouldClearRemoteEmptyMarkerWhenRemoteListNonEmpty() {
        DataManagementAPI.markLastRemoteProductCatalogEmptyForTest(true)
        val localCatalog = mutableListOf<Taste>()
        val remoteCatalog = listOf(Taste(1, "A", "原味", 101, "原味商品"))

        val result = DataManagementAPI.applyParsedProductCatalogForTest(
            refreshedTasteList = remoteCatalog,
            currentTasteList = localCatalog
        )

        assertTrue(result.success)
        assertFalse(result.remoteCatalogEmpty)
        assertEquals("success", result.reason)
        assertEquals(1, localCatalog.size)
        assertFalse(DataManagementAPI.wasLastRemoteProductCatalogEmpty())
    }

    @Test
    fun buildRecentlyRefreshedProductCatalogResult_shouldNotChangeRemoteEmptyMarker() {
        DataManagementAPI.markLastRemoteProductCatalogEmptyForTest(true)
        replaceTasteCatalog(mutableListOf(Taste(1, "A", "原味", 101, "原味商品")))

        val result = DataManagementAPI.buildRecentlyRefreshedProductCatalogResultForTest()

        assertTrue(result.success)
        assertFalse(result.remoteCatalogEmpty)
        assertEquals("recently_refreshed", result.reason)
        assertTrue(DataManagementAPI.wasLastRemoteProductCatalogEmpty())
    }

    private fun replaceTasteCatalog(tastes: MutableList<Taste>) {
        val field = KaoPanHelper::class.java.getDeclaredField("tasteList")
        field.isAccessible = true
        field.set(KaoPanHelper, tastes)
    }
}
