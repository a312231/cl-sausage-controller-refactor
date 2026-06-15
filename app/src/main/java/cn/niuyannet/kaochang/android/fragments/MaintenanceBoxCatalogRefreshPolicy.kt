package cn.niuyannet.kaochang.android.fragments

internal enum class RefillCatalogRefreshAction {
    OPEN_DIALOG,
    OPEN_DIALOG_WITH_CACHE_TOAST,
    REJECT_WITH_NETWORK_TOAST
}

internal fun resolveRefillCatalogRefreshAction(
    refreshSuccess: Boolean,
    localCatalogNotEmpty: Boolean
): RefillCatalogRefreshAction {
    return when {
        refreshSuccess -> RefillCatalogRefreshAction.OPEN_DIALOG
        localCatalogNotEmpty -> RefillCatalogRefreshAction.OPEN_DIALOG_WITH_CACHE_TOAST
        else -> RefillCatalogRefreshAction.REJECT_WITH_NETWORK_TOAST
    }
}
