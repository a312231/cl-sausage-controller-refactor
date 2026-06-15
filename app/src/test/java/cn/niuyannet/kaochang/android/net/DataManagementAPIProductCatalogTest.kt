package cn.niuyannet.kaochang.android.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class DataManagementAPIProductCatalogTest {

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
}
