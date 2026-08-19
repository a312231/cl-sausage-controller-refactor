package cn.niuyannet.kaochang.android.services.reset

enum class ResetCloseoutPhase {
    ORDER_ACTIVE,
    PREPARED,
    RESET_WRITTEN,
    SERVER_CLOSED
}

enum class ResetKind {
    NORMAL,
    EMERGENCY
}

data class ResetOrderItem(
    val itemId: Long = 0L,
    val quantity: Int = 0,
    val confirmedOut4: Int = 0
)

data class ResetCloseoutRecord(
    val resetId: String? = null,
    val originProcessId: String? = null,
    val kind: ResetKind? = null,
    val phase: ResetCloseoutPhase = ResetCloseoutPhase.ORDER_ACTIVE,
    val orderNo: String? = null,
    val items: List<ResetOrderItem> = emptyList(),
    val previousOnlineStatus: Int = 0,
    val previousOrderBlockReason: String = "",
    val previousHomeQrHidden: Int = 0,
    val previousScanBlockStatus: Int = 0,
    val resetWrittenAtMs: Long? = null
) {
    fun requireValid(): ResetCloseoutRecord {
        check(previousOnlineStatus in 0..4) { "复位前营业状态非法：$previousOnlineStatus" }
        check(previousHomeQrHidden in 0..1) { "复位前首页二维码状态非法：$previousHomeQrHidden" }
        check(previousScanBlockStatus in 0..1) { "复位前扫码阻断状态非法：$previousScanBlockStatus" }
        check(items.map { it.itemId }.distinct().size == items.size) { "复位订单项重复" }
        items.forEach { item ->
            check(item.itemId > 0L) { "复位订单项ID非法：${item.itemId}" }
            check(item.quantity > 0) { "复位订单项数量非法：${item.quantity}" }
            check(item.confirmedOut4 in 0..item.quantity) {
                "复位订单项完成数量非法：${item.confirmedOut4}/${item.quantity}"
            }
        }
        if (items.isNotEmpty()) {
            check(!orderNo.isNullOrBlank()) { "复位订单证据缺少订单号" }
        }
        when (phase) {
            ResetCloseoutPhase.ORDER_ACTIVE -> {
                check(resetId == null && originProcessId == null && kind == null && resetWrittenAtMs == null) {
                    "活动订单证据不能包含复位阶段字段"
                }
            }

            ResetCloseoutPhase.PREPARED -> {
                check(!resetId.isNullOrBlank() && !originProcessId.isNullOrBlank() && kind != null) {
                    "复位准备证据不完整"
                }
                check(resetWrittenAtMs == null) { "复位准备阶段不能已有写入时间" }
            }

            ResetCloseoutPhase.RESET_WRITTEN,
            ResetCloseoutPhase.SERVER_CLOSED -> {
                check(!resetId.isNullOrBlank() && !originProcessId.isNullOrBlank() && kind != null) {
                    "复位写入证据不完整"
                }
                check(resetWrittenAtMs != null && resetWrittenAtMs > 0L) { "复位写入时间无效" }
            }
        }
        return this
    }
}
