package cn.niuyannet.kaochang.android.services

internal class TakeSausageVoicePlaybackRegistry {
    private var nextToken = 0L
    private var activeToken: Long? = null

    fun begin(): Long {
        val token = ++nextToken
        activeToken = token
        return token
    }

    fun isActive(token: Long): Boolean {
        return activeToken == token
    }

    fun finishIfActive(token: Long): Boolean {
        if (activeToken != token) {
            return false
        }
        activeToken = null
        return true
    }

    fun hasActive(): Boolean {
        return activeToken != null
    }

    fun clearActive() {
        activeToken = null
    }
}
