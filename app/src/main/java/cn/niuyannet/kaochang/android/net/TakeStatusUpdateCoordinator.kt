package cn.niuyannet.kaochang.android.net

import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.services.TakeStatusIntentRegistry
import java.util.concurrent.atomic.AtomicBoolean

fun interface TakeStatusUpdateTransport {
    fun send(box: KaoPanBox, callback: (Int, String?) -> Unit)
}

fun interface TakeStatusUpdateStore {
    fun applyAuthoritative(authoritative: KaoPanBox): KaoPanBox?
}

interface TakeStatusCriticalSection {
    fun <T> run(action: () -> T): T
}

class TakeStatusUpdateCoordinator(
    private val intentRegistry: TakeStatusIntentRegistry,
    private val transport: TakeStatusUpdateTransport,
    private val store: TakeStatusUpdateStore,
    private val criticalSection: TakeStatusCriticalSection = object : TakeStatusCriticalSection {
        override fun <T> run(action: () -> T): T = action()
    },
    private val admissionAllowed: () -> Boolean = { true }
) {
    private inner class Operation(
        val positionSn: Int,
        var token: Long,
        val explicit: Boolean,
        val onResult: ((confirmed: Boolean, authoritative: KaoPanBox?) -> Unit)?
    ) {
        val terminal = AtomicBoolean(false)
        val retryClaimed = AtomicBoolean(false)

        fun complete(confirmed: Boolean, authoritative: KaoPanBox?) {
            if (!terminal.compareAndSet(false, true)) return
            intentRegistry.finishExplicit(positionSn, token)
            onResult?.invoke(confirmed, authoritative)
        }
    }

    fun updateExplicitly(
        box: KaoPanBox,
        onResult: ((confirmed: Boolean, authoritative: KaoPanBox?) -> Unit)? = null
    ) {
        val prepared = runIfAdmitted {
            intentRegistry.beginExplicit(box.positionSn) to box.takeStatusSnapshot()
        } ?: run {
            onResult?.invoke(false, null)
            return
        }
        send(
            prepared.second,
            Operation(box.positionSn, prepared.first, explicit = true, onResult),
            retry = false
        )
    }

    fun <T> updateExplicitlyPrepared(
        positionSn: Int,
        prepare: () -> Pair<T, KaoPanBox>,
        onResult: ((confirmed: Boolean, authoritative: KaoPanBox?) -> Unit)? = null
    ): T {
        val tokenAndPrepared = try {
            runIfAdmitted {
                intentRegistry.beginExplicitAndRun(positionSn) {
                    val (result, preparedBox) = prepare()
                    result to preparedBox.takeStatusSnapshot()
                }
            }
        } catch (throwable: Throwable) {
            onResult?.invoke(false, null)
            throw throwable
        }
        if (tokenAndPrepared == null) {
            onResult?.invoke(false, null)
            error("take status update blocked by recovery fence")
        }
        val (token, prepared) = tokenAndPrepared
        send(
            prepared.second,
            Operation(positionSn, token, explicit = true, onResult),
            retry = false
        )
        return prepared.first
    }

    fun updateAutomaticallyIfUnchanged(box: KaoPanBox, observedGeneration: Long) {
        val prepared = runIfAdmitted {
            val token = intentRegistry.beginAutomaticIfUnchanged(
                positionSn = box.positionSn,
                observedGeneration = observedGeneration
            ) ?: return@runIfAdmitted null
            token to box.takeStatusSnapshot()
        } ?: return
        send(
            prepared.second,
            Operation(box.positionSn, prepared.first, explicit = false, onResult = null),
            retry = false
        )
    }

    private fun send(
        box: KaoPanBox,
        operation: Operation,
        retry: Boolean
    ) {
        val attemptConsumed = AtomicBoolean(false)
        try {
            val sent = runIfAdmitted {
                transport.send(box) callback@{ code, content ->
                if (!attemptConsumed.compareAndSet(false, true) || operation.terminal.get()) {
                    return@callback
                }
                var authoritative: KaoPanBox? = null
                try {
                    val parsed = TakeStatusUpdateResponseParser.parse(content)
                    authoritative = parsed.authoritativeStock
                    if (operation.explicit &&
                        !retry &&
                        parsed.reason == "CONFLICT" &&
                        authoritative?.positionSn == operation.positionSn &&
                        operation.retryClaimed.compareAndSet(false, true)
                    ) {
                        val retryBox = KaoPanBox().apply {
                            positionSn = operation.positionSn
                            cmdStatusTake = box.cmdStatusTake
                            takeStatusRevision = authoritative.takeStatusRevision
                        }
                        val retryToken = runIfAdmitted {
                            intentRegistry.claimRetryIfCurrent(
                                operation.positionSn,
                                operation.token
                            )
                        }
                        if (retryToken != null) {
                            operation.token = retryToken
                            send(retryBox, operation, retry = true)
                        } else {
                            operation.complete(false, authoritative)
                        }
                        return@callback
                    }
                    var current: KaoPanBox? = null
                    val applied = runIfAdmitted {
                        intentRegistry.runIfCurrent(operation.positionSn, operation.token) {
                            current = authoritative?.let(store::applyAuthoritative)
                        }
                    } ?: false
                    if (!applied) {
                        operation.complete(false, authoritative)
                        return@callback
                    }
                    val appliedCurrent = current
                    val confirmed = code == 0 && parsed.confirmed && authoritative != null &&
                        appliedCurrent?.cmdStatusTake == box.cmdStatusTake &&
                        appliedCurrent.takeStatusRevision >= authoritative.takeStatusRevision
                    operation.complete(confirmed, authoritative)
                } catch (_: Exception) {
                    operation.complete(false, authoritative)
                }
                }
                true
            } ?: false
            if (!sent && attemptConsumed.compareAndSet(false, true)) {
                operation.complete(false, null)
            }
        } catch (_: Exception) {
            if (attemptConsumed.compareAndSet(false, true)) {
                operation.complete(false, null)
            }
        }
    }

    private fun <T> runIfAdmitted(action: () -> T): T? = criticalSection.run {
        if (admissionAllowed()) action() else null
    }


    private fun KaoPanBox.takeStatusSnapshot(): KaoPanBox = KaoPanBox().also { snapshot ->
        snapshot.positionSn = positionSn
        snapshot.cmdStatusTake = cmdStatusTake
        snapshot.takeStatusRevision = takeStatusRevision
    }
}
