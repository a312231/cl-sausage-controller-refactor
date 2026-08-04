package cn.niuyannet.kaochang.android.services

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async

internal class ManualTakeTaskCoordinator(
    private val scope: CoroutineScope
) {
    private val lock = Any()
    private var activeTask: Deferred<Boolean>? = null

    fun start(block: suspend () -> Boolean): Deferred<Boolean>? = synchronized(lock) {
        if (activeTask?.isActive == true) {
            return@synchronized null
        }

        val task = scope.async(Dispatchers.IO, start = CoroutineStart.LAZY) { block() }
        activeTask = task
        task.invokeOnCompletion {
            synchronized(lock) {
                if (activeTask === task) {
                    activeTask = null
                }
            }
        }
        task.start()
        task
    }
}
