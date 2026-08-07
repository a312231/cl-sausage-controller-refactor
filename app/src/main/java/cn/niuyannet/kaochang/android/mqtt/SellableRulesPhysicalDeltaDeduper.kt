package cn.niuyannet.kaochang.android.mqtt

import java.util.concurrent.atomic.AtomicReference

class SellableRulesPhysicalDeltaDeduper {
    private val lastPublishedSignature = AtomicReference<String?>(null)

    fun publishIfChanged(signature: String, publisher: () -> Boolean): Boolean {
        while (true) {
            val previousSignature = lastPublishedSignature.get()
            if (signature == previousSignature) {
                return false
            }
            if (!lastPublishedSignature.compareAndSet(previousSignature, signature)) {
                continue
            }
            var published = false
            try {
                published = publisher()
                return published
            } finally {
                if (!published) {
                    lastPublishedSignature.compareAndSet(signature, previousSignature)
                }
            }
        }
    }
}
