package cn.niuyannet.kaochang.android.services.reset

class ResetStartupCloseoutCoordinator(
    private val store: ResetCloseoutStore,
    private val nowMs: () -> Long,
    private val processBoundaryCrossed: (ResetCloseoutRecord) -> Boolean,
    private val recoverPrepared: (ResetCloseoutRecord) -> Boolean,
    private val closeLocalResources: (ResetCloseoutRecord) -> Boolean,
    private val closeServerOrder: (ResetCloseoutRecord, (Boolean) -> Unit) -> Unit,
    private val persistOrderTerminal: (String, Boolean) -> Boolean,
    private val scheduleFinalize: (Long, () -> Unit) -> Unit,
    private val finalizeAndUnblock: (ResetCloseoutRecord) -> Boolean
) {
    @Volatile
    private var inFlight = false

    @Synchronized
    fun resume(): Boolean {
        if (inFlight) return true
        val record = store.load() ?: return false
        return when (record.phase) {
            ResetCloseoutPhase.PREPARED -> recoverPrepared(record)
            ResetCloseoutPhase.RESET_WRITTEN -> startServerCloseout(record)
            ResetCloseoutPhase.SERVER_CLOSED -> scheduleFinalization(record)
            else -> false
        }
    }

    private fun startServerCloseout(record: ResetCloseoutRecord): Boolean {
        if (!processBoundaryCrossed(record)) return false
        if (!closeLocalResources(record)) return false
        inFlight = true
        closeServerOrder(record) { success ->
            if (!success) {
                inFlight = false
                return@closeServerOrder
            }
            val allDelivered = record.items.all { ResetCloseoutPolicy.remainingCount(it) == 0 }
            val terminalSaved = record.orderNo.isNullOrBlank() ||
                persistOrderTerminal(record.orderNo, allDelivered)
            val closed = record.copy(phase = ResetCloseoutPhase.SERVER_CLOSED).requireValid()
            if (!terminalSaved || !store.saveDurable(closed)) {
                inFlight = false
                return@closeServerOrder
            }
            scheduleFinalization(closed)
        }
        return true
    }

    private fun scheduleFinalization(record: ResetCloseoutRecord): Boolean {
        inFlight = true
        val delayMs = ResetCloseoutPolicy.remainingResetGateMs(record, nowMs())
        scheduleFinalize(delayMs) finalize@{
            val current = runCatching { store.load() }.getOrNull()
            if (current == null || current.resetId != record.resetId ||
                current.phase != ResetCloseoutPhase.SERVER_CLOSED
            ) {
                inFlight = false
                return@finalize
            }
            if (current.orderNo.isNullOrBlank() ||
                persistOrderTerminal(
                    current.orderNo,
                    current.items.all { ResetCloseoutPolicy.remainingCount(it) == 0 }
                )
            ) {
                if (finalizeAndUnblock(current)) {
                    inFlight = false
                    return@finalize
                }
            }
            inFlight = false
        }
        return true
    }
}
