package cn.niuyannet.kaochang.android.services.actiongroup

import org.junit.Assert.assertEquals
import org.junit.Test

class PrefetchSupplementPlannerTest {

    @Test
    fun selectCandidateBoxNos_shouldReservePrimaryOnlyAndKeepBackupCandidates() {
        val reservedStock = mutableMapOf(20 to 1, 19 to 1, 18 to 2)

        val first = PrefetchSupplementPlanner.selectCandidateBoxNos(
            candidateBoxNos = listOf(20, 19, 18),
            reservedStockByBoxNo = reservedStock
        )
        val second = PrefetchSupplementPlanner.selectCandidateBoxNos(
            candidateBoxNos = listOf(20, 19, 18),
            reservedStockByBoxNo = reservedStock
        )

        assertEquals(listOf(20, 19, 18), first)
        assertEquals(listOf(19, 18), second)
        assertEquals(0, reservedStock[20])
        assertEquals(0, reservedStock[19])
        assertEquals(2, reservedStock[18])
    }

    @Test
    fun selectCandidateBoxNos_shouldSkipBoxesAlreadyReservedAsPrimary() {
        val reservedStock = mutableMapOf(20 to 0, 19 to 1)

        val result = PrefetchSupplementPlanner.selectCandidateBoxNos(
            candidateBoxNos = listOf(20, 19),
            reservedStockByBoxNo = reservedStock
        )

        assertEquals(listOf(19), result)
        assertEquals(0, reservedStock[19])
    }
}
