package cn.niuyannet.kaochang.android.services

import java.util.concurrent.atomic.AtomicLong

internal class KaoPanAsyncSaveGeneration {
    private val generation = AtomicLong(0L)

    fun current(): Long = generation.get()

    fun advance(): Long = generation.incrementAndGet()

    fun isCurrent(capturedGeneration: Long): Boolean {
        return capturedGeneration == generation.get()
    }
}
