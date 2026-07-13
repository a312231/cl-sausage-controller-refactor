package cn.niuyannet.kaochang.android.utils

import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

enum class OrderProcessingStage(val customerText: String) {
    WAITING("订单已收到，正在安排出餐，请稍等"),
    SUPPLEMENTING("正在完成当前补肠，随后优先为您出餐"),
    SUPPLEMENT_PAUSED("补肠动作已暂缓，优先为您出餐"),
    DELIVERING("正在为您取出烤肠，请稍等"),
    READY_TO_TAKE("烤肠已送达，请及时取走")
}

internal class OrderProcessingStageDispatcher(
    private val isDispatchThread: () -> Boolean,
    private val dispatch: (() -> Unit) -> Unit
) {
    private data class Registration(
        val generation: Long,
        val listener: (OrderProcessingStage) -> Unit
    )

    private val generation = AtomicLong(0L)
    private val registrationRef = AtomicReference<Registration?>(null)

    fun register(listener: (OrderProcessingStage) -> Unit) {
        registrationRef.set(Registration(generation.incrementAndGet(), listener))
    }

    fun unregister(listener: ((OrderProcessingStage) -> Unit)? = null) {
        while (true) {
            val current = registrationRef.get() ?: return
            if (listener != null && current.listener !== listener) return
            if (registrationRef.compareAndSet(current, null)) return
        }
    }

    fun publish(stage: OrderProcessingStage) {
        val registration = registrationRef.get() ?: return
        val deliver = {
            if (registrationRef.get()?.generation == registration.generation) {
                registration.listener(stage)
            }
        }
        if (isDispatchThread()) deliver() else dispatch(deliver)
    }
}

object OrderProcessingStageBridge {
    private val dispatcher by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val mainLooper = Looper.getMainLooper()
        val mainHandler = Handler(mainLooper)
        OrderProcessingStageDispatcher(
            isDispatchThread = { Looper.myLooper() == mainLooper },
            dispatch = { task -> mainHandler.post(task) }
        )
    }

    fun register(listener: (OrderProcessingStage) -> Unit) = dispatcher.register(listener)

    fun unregister(listener: ((OrderProcessingStage) -> Unit)? = null) = dispatcher.unregister(listener)

    fun publish(stage: OrderProcessingStage) = dispatcher.publish(stage)
}
