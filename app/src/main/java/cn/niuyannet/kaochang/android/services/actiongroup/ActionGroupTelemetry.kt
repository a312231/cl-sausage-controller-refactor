package cn.niuyannet.kaochang.android.services.actiongroup

data class ActionGroupTelemetryContext(
    val sessionId: String? = null,
    val boxNo: Int? = null,
    val panNo: Int? = null,
    val orderId: String? = null,
    val resourceLocks: String? = null
)

data class ActionGroupTelemetry(
    val actionProtocolVersion: String = "300_400",
    val sessionId: String?,
    val actionName: String,
    val registerAddress: Int,
    val resultRegister: Int,
    val errorCode: Int?,
    val errorMessage: String?,
    val boxNo: Int?,
    val panNo: Int?,
    val orderId: String?,
    val resourceLocks: String?,
    val elapsedMs: Long
)
