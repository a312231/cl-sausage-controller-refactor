package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoxRefillEligibilityPolicyTest {
    @Test
    fun `refill should reject disabled source boxes`() {
        val decision = BoxRefillEligibilityPolicy.evaluate(
            listOf(box(1), box(11), box(12), box(22))
        )

        assertFalse(decision.allowed)
        assertEquals(listOf(1, 11, 12, 22), decision.disabledBoxNos)
        assertEquals("1、11、12、22号箱位为禁用箱位，不允许加肠", decision.message)
    }

    @Test
    fun `refill should reject mixed selection when any disabled box is selected`() {
        val decision = BoxRefillEligibilityPolicy.evaluate(
            listOf(box(2), box(11), box(13))
        )

        assertFalse(decision.allowed)
        assertEquals(listOf(11), decision.disabledBoxNos)
        assertEquals("11号箱位为禁用箱位，不允许加肠", decision.message)
    }

    @Test
    fun `refill should allow valid source boxes`() {
        val decision = BoxRefillEligibilityPolicy.evaluate(
            listOf(box(2), box(10), box(13), box(21))
        )

        assertTrue(decision.allowed)
        assertEquals(emptyList<Int>(), decision.disabledBoxNos)
        assertEquals("", decision.message)
    }

    private fun box(positionSn: Int): KaoPanBox =
        KaoPanBox().apply {
            this.positionSn = positionSn
        }
}
