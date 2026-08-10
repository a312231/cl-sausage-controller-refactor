package cn.niuyannet.kaochang.android.services.actiongroup

data class GuardedSupplementCloseoutKey(
    val sessionId: String,
    val claimRevision: Long,
    val actionName: String
)

sealed class GuardedSupplementCloseoutResult {
    data object Closed : GuardedSupplementCloseoutResult()
    data class Rejected(val reason: String) : GuardedSupplementCloseoutResult()
}

class GuardedSupplementCloseoutCoordinator(
    private val transitionLock: Any,
    private val sessionStore: ActionGroupSessionStore,
    private val resourceLocks: ResourceLocks,
    private val moveStatusProvider: () -> Int,
    private val guardedEpisodeExists: (GuardedSupplementCloseoutKey) -> Boolean,
    private val closeEpisode: (GuardedSupplementCloseoutKey) -> Boolean
) {
    fun close(
        key: GuardedSupplementCloseoutKey,
        releaseClaimAndSession: (ActionGroupSession) -> MechanicalMoveReleaseResult
    ): GuardedSupplementCloseoutResult = synchronized(transitionLock) {
        if (key.sessionId.isBlank() || key.claimRevision <= 0L || key.actionName !in setOf("in1", "in2", "in3")) {
            return@synchronized GuardedSupplementCloseoutResult.Rejected("guarded episode 精确 key 无效")
        }
        if (!guardedEpisodeExists(key)) {
            return@synchronized GuardedSupplementCloseoutResult.Rejected("guarded episode 已变化或不存在")
        }

        val session = sessionStore.load()
        if (session == null) {
            if (moveStatusProvider() == 1) {
                return@synchronized GuardedSupplementCloseoutResult.Rejected(
                    "session 已不存在但 moveStatus 仍为补肠中，禁止只清 episode"
                )
            }
            if (resourceLocks.ownerIds().isNotEmpty()) {
                return@synchronized GuardedSupplementCloseoutResult.Rejected(
                    "session 已不存在但仍有资源 owner，拒绝猜测归属"
                )
            }
            return@synchronized if (runCatching { closeEpisode(key) }.getOrDefault(false)) {
                GuardedSupplementCloseoutResult.Closed
            } else {
                GuardedSupplementCloseoutResult.Rejected("孤立 guarded episode 精确关闭失败")
            }
        }

        if (session.sessionId != key.sessionId ||
            session.mechanicalClaimRevision != key.claimRevision ||
            session.currentAction != key.actionName ||
            session.type != ActionGroupSessionType.IN_SUPPLEMENT ||
            session.prefetch ||
            session.mechanicalClaimState != MechanicalClaimState.CLAIMED
        ) {
            return@synchronized GuardedSupplementCloseoutResult.Rejected(
                "sessionId/revision/action 与 guarded episode 不匹配"
            )
        }
        val otherOwners = resourceLocks.ownerIds() - key.sessionId
        if (otherOwners.isNotEmpty()) {
            return@synchronized GuardedSupplementCloseoutResult.Rejected("存在其他资源 owner=$otherOwners")
        }
        val ownedMask = resourceLocks.ownedMask(key.sessionId)
        if (ownedMask != null && ownedMask != session.resourceMask) {
            return@synchronized GuardedSupplementCloseoutResult.Rejected("guarded session 资源 mask 不匹配")
        }

        val claimRelease = releaseClaimAndSession(session)
        if (claimRelease !is MechanicalMoveReleaseResult.Released) {
            return@synchronized GuardedSupplementCloseoutResult.Rejected(
                "claim/session 精确收口失败：$claimRelease"
            )
        }
        if (sessionStore.load() != null) {
            return@synchronized GuardedSupplementCloseoutResult.Rejected("claim/session 收口后仍有会话残留")
        }
        when (resourceLocks.releaseIfOwned(key.sessionId, session.resourceMask)) {
            ResourceReleaseResult.RELEASED,
            ResourceReleaseResult.ALREADY_ABSENT -> Unit
            ResourceReleaseResult.MASK_MISMATCH ->
                return@synchronized GuardedSupplementCloseoutResult.Rejected("匹配资源释放失败")
        }
        if (!runCatching { closeEpisode(key) }.getOrDefault(false)) {
            return@synchronized GuardedSupplementCloseoutResult.Rejected("guarded episode 精确关闭失败")
        }
        GuardedSupplementCloseoutResult.Closed
    }
}
