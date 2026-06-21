package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.bean.KaoPan

internal object KaoPanCacheInitPolicy {
    fun resolveInitialPans(
        currentPans: List<KaoPan>,
        storedPans: List<KaoPan>?,
        defaultPans: List<KaoPan>
    ): MutableList<KaoPan> {
        return when {
            currentPans.isNotEmpty() -> currentPans.toMutableList()
            !storedPans.isNullOrEmpty() -> storedPans.toMutableList()
            else -> defaultPans.toMutableList()
        }
    }

    fun resolveResetPansAfterManualClear(defaultPans: List<KaoPan>): MutableList<KaoPan> {
        return defaultPans.toMutableList()
    }
}
