package cn.niuyannet.kaochang.scheduler

enum class PanLifecycle {
    EMPTY,
    BAKING,
    SELLABLE_KEEP_WARM,
    WAITING_RETURN_KEEP_WARM,
    UNKNOWN
}

data class PanSnapshot(
    val positionSn: Int,
    val hasSausage: Boolean,
    val lifecycle: PanLifecycle,
    val holdingTime: Long,
    val closeTime: Long,
    val tasteCode: String?,
    val productId: Int?
)

class SellCandidateInput(
    val nowMs: Long,
    val tasteCode: String,
    val productId: Int,
    val sellRange: IntRange,
    pendingDiscardPositions: Set<Int>,
    pans: List<PanSnapshot>
) {
    val pendingDiscardPositions: Set<Int> = pendingDiscardPositions.toSet()
    val pans: List<PanSnapshot> = pans.toList()
}
