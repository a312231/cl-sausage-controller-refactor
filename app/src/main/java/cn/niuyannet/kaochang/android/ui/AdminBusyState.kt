package cn.niuyannet.kaochang.android.ui

import java.util.concurrent.atomic.AtomicLong

class AdminBusyState(
    private val staleMs: Long,
    private val nowProvider: () -> Long
) {
    data class Token(
        val id: Long,
        val reason: String,
        val startedAtMs: Long
    )

    private val nextId = AtomicLong(1L)
    private val tokens = linkedMapOf<Long, Token>()

    @Synchronized
    fun begin(reason: String): Token {
        val token = Token(
            id = nextId.getAndIncrement(),
            reason = reason,
            startedAtMs = nowProvider()
        )
        tokens[token.id] = token
        return token
    }

    @Synchronized
    fun finish(token: Token?) {
        if (token == null) {
            return
        }
        tokens.remove(token.id)
    }

    @Synchronized
    fun isBusy(onStale: (Token, Long) -> Unit = { _, _ -> }): Boolean {
        pruneStaleTokensLocked(onStale)
        return tokens.isNotEmpty()
    }

    @Synchronized
    fun activeCount(): Int {
        pruneStaleTokensLocked()
        return tokens.size
    }

    @Synchronized
    fun activeReasons(): List<String> {
        pruneStaleTokensLocked()
        return tokens.values.map { it.reason }
    }

    private fun pruneStaleTokensLocked(onStale: (Token, Long) -> Unit = { _, _ -> }) {
        val now = nowProvider()
        val staleTokens = tokens.values.filter { token ->
            now - token.startedAtMs > staleMs
        }
        staleTokens.forEach { token ->
            tokens.remove(token.id)
            onStale(token, now - token.startedAtMs)
        }
    }
}
