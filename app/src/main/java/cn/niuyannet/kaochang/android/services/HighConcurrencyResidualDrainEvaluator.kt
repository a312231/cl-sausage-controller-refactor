package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.bean.KaoPan

/**
 * 高并发残留消化态判定。
 *
 * highConcurrencyResidualDrain（高并发残留消化态）：
 * - 0=无残留
 * - 1=低并发下高并发专属区域仍有烤肠、补肠占位、加热/保温时间或非空闲状态
 *
 * 低并发正常补热区是 13~18，它和高并发前区 10~21 有重叠。
 * 因此周期判定默认只看高并发专属位 10~12、19~21、25~33；
 * 只有刚从高并发/过渡态切低，或残留态已经处于 1 时，才把 13~18 纳入清残判断。
 */
object HighConcurrencyResidualDrainEvaluator {
    private const val MODE_LOW = 1

    fun evaluate(
        modeType: Int,
        kaoPanList: List<KaoPan>,
        includeLowHeatOverlapArea: Boolean = false
    ): Int {
        if (modeType != MODE_LOW) {
            return 0
        }
        return if (kaoPanList.any { pan -> hasHighConcurrencyResidual(pan, includeLowHeatOverlapArea) }) 1 else 0
    }

    private fun hasHighConcurrencyResidual(
        pan: KaoPan,
        includeLowHeatOverlapArea: Boolean
    ): Boolean {
        val inHighConcurrencyResidualArea =
            pan.positionSn in 10..12 ||
                pan.positionSn in 19..21 ||
                pan.positionSn in 25..33 ||
                (includeLowHeatOverlapArea && pan.positionSn in 13..18)
        return inHighConcurrencyResidualArea &&
            (pan.isHasSausage ||
                pan.isHasGrilling ||
                pan.startTime > 0L ||
                pan.holdingTime > 0L ||
                pan.status != 0)
    }
}
