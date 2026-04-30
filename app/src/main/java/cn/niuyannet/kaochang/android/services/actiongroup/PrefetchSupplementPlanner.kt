package cn.niuyannet.kaochang.android.services.actiongroup

object PrefetchSupplementPlanner {

    fun selectCandidateBoxNos(
        candidateBoxNos: List<Int>,
        reservedStockByBoxNo: MutableMap<Int, Int>
    ): List<Int> {
        val primary = candidateBoxNos.firstOrNull { boxNo ->
            (reservedStockByBoxNo[boxNo] ?: 0) > 0
        } ?: return emptyList()

        reservedStockByBoxNo[primary] = ((reservedStockByBoxNo[primary] ?: 0) - 1).coerceAtLeast(0)

        val backupCandidates = candidateBoxNos.filter { boxNo ->
            boxNo != primary && (reservedStockByBoxNo[boxNo] ?: 0) > 0
        }
        return listOf(primary) + backupCandidates
    }
}
