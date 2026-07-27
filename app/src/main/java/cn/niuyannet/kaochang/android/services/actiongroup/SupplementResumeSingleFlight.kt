package cn.niuyannet.kaochang.android.services.actiongroup

import java.util.concurrent.atomic.AtomicBoolean

class SupplementResumeSingleFlight {
    private val running = AtomicBoolean(false)

    fun tryEnter(): Boolean = running.compareAndSet(false, true)

    fun exit() {
        running.set(false)
    }
}
