package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.KaoPanHelper
import org.junit.Assert.assertNotNull
import org.junit.Test

class KaoPanDurableApiTest {
    @Test
    fun helper_shouldExposeDurableBoxInventorySave() {
        assertNotNull(
            KaoPanHelper::class.java.getMethod("saveKaoPanBoxListDurable", List::class.java)
        )
    }
}
