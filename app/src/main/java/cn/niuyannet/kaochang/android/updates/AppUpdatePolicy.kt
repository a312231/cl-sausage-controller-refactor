package cn.niuyannet.kaochang.android.updates

object AppUpdatePolicy {
    const val STARTUP_CHECK_DELAY_AFTER_FIRST_ACK_MS = 60_000L
    const val PERIODIC_CHECK_INTERVAL_MS = 6 * 60 * 60_000L
    const val MQTT_DRAIN_TIMEOUT_MS = 30_000L
    const val MQTT_DRAIN_RETRY_DELAY_MS = 5 * 60_000L
    const val CHECK_IN_PROGRESS_TIMEOUT_MS = 60_000L
    const val FORCE_UPDATE_MINIMAL_GATE_AFTER_MS = 24 * 60 * 60_000L
    const val ROOT_INSTALL_RECONCILE_TIMEOUT_MS = 15 * 60_000L
    val DOWNLOAD_RETRY_DELAYS_MS = listOf(30_000L, 2 * 60_000L, 10 * 60_000L)

    fun shouldUpdate(
        currentVersionCode: Int,
        remoteVersionCode: Int,
        hasUpdate: Boolean
    ): Boolean {
        return hasUpdate && remoteVersionCode > currentVersionCode
    }

    fun shouldForceUpdate(
        currentVersionCode: Int,
        forceUpdate: Boolean,
        minSupportedVersionCode: Int?
    ): Boolean {
        return forceUpdate || (minSupportedVersionCode != null && currentVersionCode < minSupportedVersionCode)
    }
}
