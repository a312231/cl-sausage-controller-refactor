package cn.niuyannet.kaochang.android.mqtt

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class RuntimeStateAckCallbackRegistry {
    class Listener internal constructor(
        private val callback: (SendServerHelper.RuntimeStateServerAck) -> Unit
    ) {
        private val active = AtomicBoolean(true)

        internal fun isActive(): Boolean = active.get()

        internal fun complete(ack: SendServerHelper.RuntimeStateServerAck): Boolean {
            if (!active.compareAndSet(true, false)) {
                return false
            }
            callback(ack)
            return true
        }

        internal fun cancel(): Boolean = active.getAndSet(false)
    }

    private data class Pending(
        val listener: Listener,
        val onlineStatus: Int,
        val errorStatus: Int
    )

    private val pending = ConcurrentHashMap<Long, Pending>()

    fun createListener(
        callback: (SendServerHelper.RuntimeStateServerAck) -> Unit
    ): Listener = Listener(callback)

    fun register(
        runtimeSeq: Long,
        onlineStatus: Int,
        errorStatus: Int,
        listener: Listener
    ): Boolean {
        if (!listener.isActive()) {
            return false
        }
        val registration = Pending(listener, onlineStatus, errorStatus)
        if (pending.putIfAbsent(runtimeSeq, registration) != null) {
            return false
        }
        if (!listener.isActive()) {
            pending.remove(runtimeSeq, registration)
            return false
        }
        return true
    }

    fun complete(runtimeSeq: Long, ack: SendServerHelper.RuntimeStateServerAck): Boolean {
        val registration = pending.remove(runtimeSeq) ?: return false
        return registration.listener.complete(ack)
    }

    fun timeout(runtimeSeq: Long): Boolean {
        val registration = pending.remove(runtimeSeq) ?: return false
        return registration.listener.complete(
            SendServerHelper.RuntimeStateServerAck(
                runtimeSeq = runtimeSeq,
                received = false,
                applied = false,
                reason = "ack_timeout",
                onlineStatus = registration.onlineStatus,
                errorStatus = registration.errorStatus
            )
        )
    }

    fun cancel(listener: Listener): Boolean {
        val cancelled = listener.cancel()
        pending.entries.forEach { entry ->
            if (entry.value.listener === listener) {
                pending.remove(entry.key, entry.value)
            }
        }
        return cancelled
    }
}
