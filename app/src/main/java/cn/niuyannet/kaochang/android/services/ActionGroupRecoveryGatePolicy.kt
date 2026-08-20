package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupRecoveryGateDecision
import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupSession
import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupSessionType
import cn.niuyannet.kaochang.android.services.actiongroup.MechanicalClaimState
import cn.niuyannet.kaochang.android.services.actiongroup.OrderDeliveryClaim

enum class ActionGroupRecoveryContext {
    SUPPLEMENT_ENTRY,
    FAIL_CLOSED
}

sealed class ClaimBeforeOutSessionGraceDecision {
    data object NoClaim : ClaimBeforeOutSessionGraceDecision()
    data class Defer(val reason: String) : ClaimBeforeOutSessionGraceDecision()
    data class AttemptExactCloseout(
        val expectedClaim: OrderDeliveryClaim,
        val reason: String
    ) : ClaimBeforeOutSessionGraceDecision()
    data class Maintenance(val reason: String) : ClaimBeforeOutSessionGraceDecision()
}

data class ActionGroupRecoveryGateEvidence(
    val expectedSession: ActionGroupSession?,
    val currentSession: ActionGroupSession?,
    val orderDeliveryClaim: OrderDeliveryClaim?,
    val livePauseOrderNo: String?,
    val nowMs: Long,
    val moveStatus: Int,
    val currentSessionOwnedMask: Int?,
    val resourceOwners: Set<String>,
    val hasGuardedSupplementResidue: Boolean,
    val evidenceReadable: Boolean
)

object ActionGroupRecoveryGatePolicy {
    const val CLAIM_TO_OUT_SESSION_GRACE_MS = 10_000L

    fun evaluate(
        context: ActionGroupRecoveryContext,
        evidence: ActionGroupRecoveryGateEvidence
    ): ActionGroupRecoveryGateDecision {
        if (!evidence.evidenceReadable) {
            return ActionGroupRecoveryGateDecision.Maintenance("动作组最终门禁证据读取失败或损坏")
        }
        if (evidence.hasGuardedSupplementResidue) {
            return ActionGroupRecoveryGateDecision.Maintenance("存在补肠 guarded 残留或串行失败证据")
        }

        val expected = evidence.expectedSession
        val current = evidence.currentSession
        ownerConflictReason(current, evidence)?.let { reason ->
            return ActionGroupRecoveryGateDecision.Maintenance(reason)
        }

        if (current?.type == ActionGroupSessionType.OUT_MANUAL) {
            return ActionGroupRecoveryGateDecision.Maintenance("OUT_MANUAL 缺少可核对的进程内 operation owner 身份")
        }
        if (current?.type == ActionGroupSessionType.OUT_SELL &&
            current.mechanicalClaimState == MechanicalClaimState.CLAIMED
        ) {
            val exactMechanicalOwner = evidence.moveStatus == 2 &&
                evidence.resourceOwners == setOf(current.sessionId) &&
                evidence.currentSessionOwnedMask == current.resourceMask
            if (!exactMechanicalOwner) {
                return ActionGroupRecoveryGateDecision.Maintenance("OUT_SELL 的忙态、owner 或资源 mask 不精确匹配")
            }
            val sessionOrderNo = sessionOrderNo(current)
            return if (sessionOrderNo != null &&
                sessionOrderNo == evidence.orderDeliveryClaim?.orderNo.nonBlankOrNull() &&
                sessionOrderNo == evidence.livePauseOrderNo.nonBlankOrNull()
            ) {
                ActionGroupRecoveryGateDecision.Defer("OUT_SELL 的 session、claim、pause 和机械 owner 身份精确匹配")
            } else {
                ActionGroupRecoveryGateDecision.Maintenance("OUT_SELL 的 session、claim 或 pause 订单身份不匹配")
            }
        }

        when (val graceDecision = evaluateClaimBeforeOutSessionGrace(evidence, context)) {
            ClaimBeforeOutSessionGraceDecision.NoClaim -> Unit
            is ClaimBeforeOutSessionGraceDecision.Defer ->
                return ActionGroupRecoveryGateDecision.Defer(graceDecision.reason)
            is ClaimBeforeOutSessionGraceDecision.AttemptExactCloseout ->
                return ActionGroupRecoveryGateDecision.Maintenance(graceDecision.reason)
            is ClaimBeforeOutSessionGraceDecision.Maintenance ->
                return ActionGroupRecoveryGateDecision.Maintenance(graceDecision.reason)
        }
        if (!sameRecoveryIdentity(expected, current)) {
            return ActionGroupRecoveryGateDecision.Maintenance("动作组会话身份或检查点发生未知变化")
        }
        return ActionGroupRecoveryGateDecision.Allow
    }

    fun evaluateClaimBeforeOutSessionGrace(
        evidence: ActionGroupRecoveryGateEvidence,
        context: ActionGroupRecoveryContext = ActionGroupRecoveryContext.SUPPLEMENT_ENTRY
    ): ClaimBeforeOutSessionGraceDecision {
        if (!evidence.evidenceReadable) {
            return ClaimBeforeOutSessionGraceDecision.Maintenance("订单 claim 宽限证据读取失败或损坏")
        }
        if (evidence.hasGuardedSupplementResidue) {
            return ClaimBeforeOutSessionGraceDecision.Maintenance("存在补肠 guarded 残留或串行失败证据")
        }

        val claim = evidence.orderDeliveryClaim
        val claimOrderNo = claim?.orderNo.nonBlankOrNull()
        val livePauseOrderNo = evidence.livePauseOrderNo.nonBlankOrNull()
        if (claimOrderNo == null && livePauseOrderNo == null) {
            return ClaimBeforeOutSessionGraceDecision.NoClaim
        }
        if (claimOrderNo == null || livePauseOrderNo == null || claimOrderNo != livePauseOrderNo) {
            return ClaimBeforeOutSessionGraceDecision.Maintenance("订单 claim 与进程内 pause 身份缺失或不匹配")
        }
        val expectedClaim = claim
            ?: return ClaimBeforeOutSessionGraceDecision.Maintenance("订单 claim 完整指纹缺失")
        if (context != ActionGroupRecoveryContext.SUPPLEMENT_ENTRY) {
            return ClaimBeforeOutSessionGraceDecision.Maintenance("当前恢复上下文不允许 claim-before-session 延期")
        }
        if (evidence.currentSession != null) {
            return ClaimBeforeOutSessionGraceDecision.Maintenance("订单 claim 宽限期内已存在动作组 session")
        }
        ownerConflictReason(null, evidence)?.let { reason ->
            return ClaimBeforeOutSessionGraceDecision.Maintenance(reason)
        }

        val createdAtMs = expectedClaim.createdAtMs
        if (createdAtMs <= 0L || evidence.nowMs < createdAtMs) {
            return ClaimBeforeOutSessionGraceDecision.Maintenance("订单 claim 创建时间非法或来自未来")
        }
        val ageMs = evidence.nowMs - createdAtMs
        return if (ageMs <= CLAIM_TO_OUT_SESSION_GRACE_MS) {
            ClaimBeforeOutSessionGraceDecision.Defer(
                "订单 claim 正处于 OUT session 建立宽限期：ageMs=$ageMs"
            )
        } else {
            ClaimBeforeOutSessionGraceDecision.AttemptExactCloseout(
                expectedClaim = expectedClaim,
                reason = "订单 claim 已超过 OUT session 建立宽限期，必须执行精确收口：ageMs=$ageMs"
            )
        }
    }

    fun evaluateExpectedClaimCloseoutGate(
        expectedClaim: OrderDeliveryClaim,
        context: ActionGroupRecoveryContext,
        evidence: ActionGroupRecoveryGateEvidence
    ): ActionGroupRecoveryGateDecision {
        val gateDecision = evaluate(context, evidence)
        if (gateDecision is ActionGroupRecoveryGateDecision.Defer) return gateDecision
        if (evidence.orderDeliveryClaim != expectedClaim) {
            return ActionGroupRecoveryGateDecision.Maintenance("订单 claim 完整指纹已变化")
        }
        return when (val graceDecision = evaluateClaimBeforeOutSessionGrace(evidence, context)) {
            is ClaimBeforeOutSessionGraceDecision.AttemptExactCloseout -> {
                if (graceDecision.expectedClaim == expectedClaim) {
                    ActionGroupRecoveryGateDecision.Allow
                } else {
                    ActionGroupRecoveryGateDecision.Maintenance("超窗 claim 与 expected 指纹不一致")
                }
            }
            is ClaimBeforeOutSessionGraceDecision.Defer ->
                ActionGroupRecoveryGateDecision.Defer(graceDecision.reason)
            is ClaimBeforeOutSessionGraceDecision.Maintenance ->
                ActionGroupRecoveryGateDecision.Maintenance(graceDecision.reason)
            ClaimBeforeOutSessionGraceDecision.NoClaim ->
                ActionGroupRecoveryGateDecision.Maintenance("expected claim 已不存在")
        }
    }

    private fun ownerConflictReason(
        session: ActionGroupSession?,
        evidence: ActionGroupRecoveryGateEvidence
    ): String? {
        if (session == null) {
            return if (evidence.resourceOwners.isNotEmpty() || evidence.currentSessionOwnedMask != null) {
                "无 session 时存在机械资源 owner 或 mask"
            } else {
                null
            }
        }
        if (evidence.resourceOwners.any { it != session.sessionId }) {
            return "存在不属于当前 session 的机械资源 owner=${evidence.resourceOwners}"
        }
        if (evidence.currentSessionOwnedMask != null &&
            evidence.currentSessionOwnedMask != session.resourceMask
        ) {
            return "当前 session 的资源 mask 不匹配"
        }
        return null
    }

    private fun sessionOrderNo(session: ActionGroupSession): String? {
        val identities = listOf(session.orderId, session.orderContext?.orderNo)
            .mapNotNull { it.nonBlankOrNull() }
            .distinct()
        return identities.singleOrNull()
    }

    private fun String?.nonBlankOrNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    private fun sameRecoveryIdentity(
        expected: ActionGroupSession?,
        current: ActionGroupSession?
    ): Boolean {
        if (expected == null || current == null) return expected == current
        return expected.sessionId == current.sessionId &&
            expected.type == current.type &&
            expected.currentAction == current.currentAction &&
            expected.resourceMask == current.resourceMask &&
            expected.checkpointVersion == current.checkpointVersion &&
            expected.mechanicalClaimRevision == current.mechanicalClaimRevision
    }
}
