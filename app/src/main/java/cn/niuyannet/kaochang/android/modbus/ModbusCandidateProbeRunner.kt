package cn.niuyannet.kaochang.android.modbus

internal object ModbusCandidateProbeRunner {
    const val OPEN_SUCCESS = "SUCCESS"
    const val OPEN_FAILED = "FAILED"
    const val PROBE_SUCCESS = "SUCCESS"
    const val PROBE_NO_RESPONSE = "NO_RESPONSE"
    const val PROBE_FAILED = "FAILED"
    const val PROBE_SKIPPED = "SKIPPED"

    data class Result<T>(
        val devicePath: String,
        val master: T?,
        val openResult: String,
        val probeResult: String,
        val error: String?
    ) {
        val isSuccess: Boolean
            get() = master != null

        val isPortBusy: Boolean
            get() = error?.contains("Port busy", ignoreCase = true) == true

        fun summary(): String {
            val busyHint = if (isPortBusy) "，portBusy（串口占用）=true" else ""
            return "device（串口设备）=$devicePath，" +
                "openResult（打开结果）=$openResult，" +
                "probeResult（Modbus响应结果）=$probeResult，" +
                "error（异常原因）=${error ?: "无"}$busyHint"
        }
    }

    fun <T> probe(
        devicePath: String,
        createMaster: () -> T,
        connect: (T) -> Unit,
        hasResponse: (T) -> Boolean,
        disconnect: (T) -> Unit
    ): Result<T> {
        var candidateMaster: T? = null
        var openResult = OPEN_FAILED
        var probeResult = PROBE_SKIPPED
        var error: String? = null

        fun disconnectCandidate() {
            candidateMaster?.let { master ->
                runCatching { disconnect(master) }
            }
        }

        return try {
            candidateMaster = createMaster()
            val master = candidateMaster!!
            try {
                connect(master)
                openResult = OPEN_SUCCESS
            } catch (e: Exception) {
                error = e.message ?: e.toString()
                disconnectCandidate()
                return Result(devicePath, null, openResult, probeResult, error)
            }

            val responded = try {
                hasResponse(master)
            } catch (e: Exception) {
                error = e.message ?: e.toString()
                probeResult = PROBE_FAILED
                false
            }

            if (responded) {
                Result(devicePath, master, openResult, PROBE_SUCCESS, null)
            } else {
                if (probeResult == PROBE_SKIPPED) {
                    probeResult = PROBE_NO_RESPONSE
                }
                disconnectCandidate()
                Result(devicePath, null, openResult, probeResult, error)
            }
        } catch (e: Exception) {
            error = e.message ?: e.toString()
            disconnectCandidate()
            Result(devicePath, null, openResult, probeResult, error)
        }
    }
}
