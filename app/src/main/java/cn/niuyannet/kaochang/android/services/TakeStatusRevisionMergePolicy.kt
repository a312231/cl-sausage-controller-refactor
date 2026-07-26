package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionCodes
import java.util.concurrent.ConcurrentHashMap

data class TakeStatusRevisionMergeResult(
    val useCloud: Boolean,
    val requiresPush: Boolean
)

class TakeStatusIntentRegistry {
    private data class State(
        var generation: Long = 0L,
        var currentToken: Long = 0L,
        var explicitToken: Long? = null
    )

    private val states = ConcurrentHashMap<Int, State>()

    fun observeAutomaticGeneration(positionSn: Int): Long? {
        val state = states.computeIfAbsent(positionSn) { State() }
        return synchronized(state) {
            if (state.explicitToken == null) state.generation else null
        }
    }

    fun beginExplicit(positionSn: Int): Long {
        val state = states.computeIfAbsent(positionSn) { State() }
        return synchronized(state) {
            beginExplicitLocked(state)
        }
    }

    fun <T> beginExplicitAndRun(positionSn: Int, action: () -> T): Pair<Long, T> {
        val state = states.computeIfAbsent(positionSn) { State() }
        return synchronized(state) {
            val token = beginExplicitLocked(state)
            try {
                token to action()
            } catch (throwable: Throwable) {
                if (state.explicitToken == token) {
                    state.explicitToken = null
                }
                throw throwable
            }
        }
    }

    fun beginAutomaticIfUnchanged(positionSn: Int, observedGeneration: Long): Long? {
        val state = states.computeIfAbsent(positionSn) { State() }
        return synchronized(state) {
            if (state.generation != observedGeneration || state.explicitToken != null) {
                return@synchronized null
            }
            val token = ++state.generation
            state.currentToken = token
            token
        }
    }

    fun runIfCurrent(positionSn: Int, token: Long, action: () -> Unit): Boolean {
        val state = states[positionSn] ?: return false
        return synchronized(state) {
            if (state.currentToken != token) {
                return@synchronized false
            }
            action()
            true
        }
    }

    fun claimRetryIfCurrent(positionSn: Int, token: Long): Long? {
        val state = states[positionSn] ?: return null
        return synchronized(state) {
            if (state.currentToken != token) {
                return@synchronized null
            }
            val retryToken = ++state.generation
            state.currentToken = retryToken
            if (state.explicitToken == token) {
                state.explicitToken = retryToken
            }
            retryToken
        }
    }

    fun finishExplicit(positionSn: Int, token: Long) {
        val state = states[positionSn] ?: return
        synchronized(state) {
            if (state.explicitToken == token) {
                state.explicitToken = null
            }
        }
    }

    fun begin(positionSn: Int): Long = beginExplicit(positionSn)

    fun isCurrent(positionSn: Int, token: Long): Boolean =
        states[positionSn]?.let { state -> synchronized(state) { state.currentToken == token } } == true

    private fun beginExplicitLocked(state: State): Long {
        val token = ++state.generation
        state.currentToken = token
        state.explicitToken = token
        return token
    }
}

object TakeStatusRevisionMergePolicy {
    fun merge(local: KaoPanBox?, cloud: KaoPanBox): TakeStatusRevisionMergeResult {
        if (local == null || cloud.takeStatusRevision > local.takeStatusRevision) {
            return TakeStatusRevisionMergeResult(useCloud = true, requiresPush = false)
        }
        if (cloud.takeStatusRevision == local.takeStatusRevision &&
            cloud.cmdStatusTake == AtomicActionCodes.IR_PICK_FAILED
        ) {
            return TakeStatusRevisionMergeResult(useCloud = true, requiresPush = false)
        }
        val requiresPush = cloud.takeStatusRevision < local.takeStatusRevision ||
            cloud.cmdStatusTake != local.cmdStatusTake
        cloud.cmdStatusTake = local.cmdStatusTake
        cloud.takeStatusRevision = local.takeStatusRevision
        return TakeStatusRevisionMergeResult(useCloud = false, requiresPush = requiresPush)
    }

    fun applyAuthoritative(current: KaoPanBox, authoritative: KaoPanBox): Boolean {
        if (authoritative.takeStatusRevision < current.takeStatusRevision ||
            authoritative.takeStatusRevision == current.takeStatusRevision &&
            current.cmdStatusTake == AtomicActionCodes.IR_PICK_FAILED &&
            authoritative.cmdStatusTake != AtomicActionCodes.IR_PICK_FAILED
        ) {
            return false
        }
        current.cmdStatusTake = authoritative.cmdStatusTake
        current.takeStatusRevision = authoritative.takeStatusRevision
        return true
    }

    fun isRequestedStateCurrent(
        current: KaoPanBox?,
        requestedStatus: Int,
        authoritativeRevision: Long
    ): Boolean =
        current != null &&
            current.cmdStatusTake == requestedStatus &&
            current.takeStatusRevision >= authoritativeRevision
}
