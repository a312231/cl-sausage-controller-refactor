package cn.niuyannet.kaochang.android.mqtt

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

enum class MqttPublishPriority {
    HIGH,
    NORMAL,
    LOW
}

data class QueuedMqttPublish(
    val topic: String,
    val payload: String,
    val qos: Int,
    val retained: Boolean,
    val description: String,
    val priority: MqttPublishPriority = MqttPublishPriority.NORMAL,
    val coalesceKey: String? = null,
    val logLifecycle: Boolean = true,
    val onDroppedFromQueue: (() -> Unit)? = null
)

class MqttPublishBackpressureQueue(
    private val scope: CoroutineScope,
    private val maxPendingCount: Int,
    private val sender: suspend (QueuedMqttPublish) -> Boolean
) {
    private val lock = Any()
    private val highPriorityQueue = mutableListOf<QueuedMqttPublish>()
    private val normalPriorityQueue = mutableListOf<QueuedMqttPublish>()
    private val lowPriorityQueue = mutableListOf<QueuedMqttPublish>()
    private var pendingCount = 0
    private var drainJob: Job? = null
    private var connected = true
    private var closed = false

    fun offer(request: QueuedMqttPublish): Boolean {
        var droppedRequest: QueuedMqttPublish? = null
        val accepted = synchronized(lock) {
            if (closed) {
                false
            } else {
                droppedRequest = replaceCoalescedRequest(request)
                if (droppedRequest != null) {
                    startDrainIfNeededLocked()
                    true
                } else if (pendingCount >= maxPendingCount) {
                    false
                } else {
                    queueFor(request.priority).add(request)
                    pendingCount += 1
                    startDrainIfNeededLocked()
                    true
                }
            }
        }
        if (accepted) {
            try {
                droppedRequest?.onDroppedFromQueue?.invoke()
            } catch (_: Throwable) {
                // 队列本身不能因为清理回调失败而拒绝新的发送请求。
            }
        }
        return accepted
    }

    fun markConnected() {
        synchronized(lock) {
            connected = true
            startDrainIfNeededLocked()
        }
    }

    fun pause() {
        synchronized(lock) {
            connected = false
        }
    }

    fun shutdown() {
        synchronized(lock) {
            closed = true
            highPriorityQueue.clear()
            normalPriorityQueue.clear()
            lowPriorityQueue.clear()
            pendingCount = 0
            drainJob?.cancel()
            drainJob = null
        }
    }

    fun pendingCount(): Int {
        return synchronized(lock) {
            pendingCount
        }
    }

    private fun startDrainIfNeededLocked() {
        if (!connected || closed || pendingCount == 0 || drainJob?.isActive == true) {
            return
        }
        drainJob = scope.launch {
            drainLoop()
        }
    }

    private suspend fun drainLoop() {
        while (true) {
            val request = synchronized(lock) {
                if (!connected || closed || pendingCount == 0) {
                    drainJob = null
                    return
                }
                removeNextLocked()
            } ?: run {
                synchronized(lock) {
                    drainJob = null
                }
                return
            }

            val sent = try {
                sender(request)
            } catch (_: Throwable) {
                false
            }

            synchronized(lock) {
                if (sent) {
                    pendingCount -= 1
                } else {
                    addFirstLocked(request)
                    connected = false
                    drainJob = null
                    return
                }
            }
        }
    }

    private fun replaceCoalescedRequest(request: QueuedMqttPublish): QueuedMqttPublish? {
        val key = request.coalesceKey ?: return null
        val queue = queueFor(request.priority)
        val index = queue.indexOfFirst { it.coalesceKey == key }
        if (index < 0) {
            return null
        }
        return queue.set(index, request)
    }

    private fun queueFor(priority: MqttPublishPriority): MutableList<QueuedMqttPublish> =
        when (priority) {
            MqttPublishPriority.HIGH -> highPriorityQueue
            MqttPublishPriority.NORMAL -> normalPriorityQueue
            MqttPublishPriority.LOW -> lowPriorityQueue
        }

    private fun removeNextLocked(): QueuedMqttPublish? =
        when {
            highPriorityQueue.isNotEmpty() -> highPriorityQueue.removeAt(0)
            normalPriorityQueue.isNotEmpty() -> normalPriorityQueue.removeAt(0)
            lowPriorityQueue.isNotEmpty() -> lowPriorityQueue.removeAt(0)
            else -> null
        }

    private fun addFirstLocked(request: QueuedMqttPublish) {
        queueFor(request.priority).add(0, request)
    }
}
