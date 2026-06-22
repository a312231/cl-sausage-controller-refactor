package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.bean.KaoPanBox

data class BoxRefillEligibilityDecision(
    val allowed: Boolean,
    val disabledBoxNos: List<Int>,
    val message: String
)

object BoxRefillEligibilityPolicy {
    fun evaluate(selectedBoxes: List<KaoPanBox>): BoxRefillEligibilityDecision {
        val disabledBoxNos = selectedBoxes
            .map { it.positionSn }
            .filter { !SourceBoxPositionPolicy.isValidSourceBox(it) }
            .distinct()
            .sorted()
        if (disabledBoxNos.isEmpty()) {
            return BoxRefillEligibilityDecision(
                allowed = true,
                disabledBoxNos = emptyList(),
                message = ""
            )
        }
        return BoxRefillEligibilityDecision(
            allowed = false,
            disabledBoxNos = disabledBoxNos,
            message = "${disabledBoxNos.joinToString("、")}号箱位为禁用箱位，不允许加肠"
        )
    }
}
