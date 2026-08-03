package cn.niuyannet.kaochang.android.services.actiongroup

sealed class AutomaticMechanicalCommandAdmissionResult {
    data object Admitted : AutomaticMechanicalCommandAdmissionResult()
    data class Rejected(val reason: String) : AutomaticMechanicalCommandAdmissionResult()
    data class Failed(val reason: String) : AutomaticMechanicalCommandAdmissionResult()
}

class AutomaticMechanicalCommandAdmissionCoordinator(
    private val transitionLock: Any,
    private val orderPending: () -> Boolean,
    private val currentOwner: () -> String?,
    private val currentMoveStatus: () -> Int,
    private val automaticCommandAllowed: () -> Boolean,
    private val saveCommandIntent: () -> Boolean
) {
    fun admit(
        owner: String,
        expectedMoveStatus: Int,
        automatic: Boolean
    ): AutomaticMechanicalCommandAdmissionResult = synchronized(transitionLock) {
        if (automatic && orderPending()) {
            return@synchronized AutomaticMechanicalCommandAdmissionResult.Rejected("订单已进入等待")
        }
        if (automatic && !automaticCommandAllowed()) {
            return@synchronized AutomaticMechanicalCommandAdmissionResult.Rejected("自动机械运行态门禁已关闭")
        }
        val actualOwner = currentOwner()
        if (actualOwner != owner) {
            return@synchronized AutomaticMechanicalCommandAdmissionResult.Rejected(
                "owner 不匹配：expected=$owner，actual=$actualOwner"
            )
        }
        val actualMoveStatus = currentMoveStatus()
        if (actualMoveStatus != expectedMoveStatus) {
            return@synchronized AutomaticMechanicalCommandAdmissionResult.Rejected(
                "moveStatus 不匹配：expected=$expectedMoveStatus，actual=$actualMoveStatus"
            )
        }
        val intentSaved = try {
            saveCommandIntent()
        } catch (error: Exception) {
            return@synchronized AutomaticMechanicalCommandAdmissionResult.Failed(
                "COMMAND_INTENT 落盘异常：${error.message ?: error.javaClass.simpleName}"
            )
        }
        if (!intentSaved) {
            return@synchronized AutomaticMechanicalCommandAdmissionResult.Failed("COMMAND_INTENT 落盘失败")
        }
        AutomaticMechanicalCommandAdmissionResult.Admitted
    }
}
