package cn.niuyannet.kaochang.android.services

internal data class SupplementWriteRecord(
    val targetPanNo: Int,
    val sourceBoxNo: Int,
    val panApplied: Boolean,
    val boxDeducted: Boolean
)

internal data class SupplementConsistencyResult(
    val requiresMaintenance: Boolean,
    val message: String
)

internal object SupplementConsistencyChecker {
    fun evaluate(
        expectedSuccessCount: Int,
        records: List<SupplementWriteRecord>
    ): SupplementConsistencyResult {
        val panAppliedCount = records.count { it.panApplied }
        val boxDeductedCount = records.count { it.boxDeducted }
        val consistent = panAppliedCount == expectedSuccessCount && boxDeductedCount == expectedSuccessCount
        if (consistent) {
            return SupplementConsistencyResult(
                requiresMaintenance = false,
                message = "补肠盘箱一致：预期成功数=$expectedSuccessCount，落盘成功数=$panAppliedCount，扣箱成功数=$boxDeductedCount"
            )
        }
        return SupplementConsistencyResult(
            requiresMaintenance = true,
            message = "补肠盘箱背离：预期成功数=$expectedSuccessCount，落盘成功数=$panAppliedCount，扣箱成功数=$boxDeductedCount，" +
                "records=${records.joinToString(";") { "pan=${it.targetPanNo},box=${it.sourceBoxNo},panApplied=${it.panApplied},boxDeducted=${it.boxDeducted}" }}"
        )
    }
}
