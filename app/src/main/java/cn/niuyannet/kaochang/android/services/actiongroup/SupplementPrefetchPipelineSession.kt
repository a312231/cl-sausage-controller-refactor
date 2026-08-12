package cn.niuyannet.kaochang.android.services.actiongroup

enum class SupplementLaneRole {
    DELIVERY,
    PREFETCH
}

enum class SupplementActionLifecycle {
    PREPARED,
    COMMAND_WRITTEN,
    RUNNING_CONFIRMED,
    TERMINAL_CONFIRMED,
    PHYSICAL_APPLIED
}

enum class SupplementBarrierState {
    OPEN,
    ORDER_WON,
    DELIVERY_COMMITTED
}

enum class SupplementPipelinePhase {
    RUNNING,
    PAUSED_FOR_ORDER,
    PAUSED_FOR_RUNTIME,
    RECOVERING,
    MAINTENANCE_REQUIRED,
    COMPLETED
}

data class SupplementPrefetchResumePlan(
    val sessionId: String,
    val batchId: String?,
    val resourceMask: Int,
    val sourceBoxNo: Int,
    val targetPanNo: Int,
    val resumeAfterAction: String
)

sealed class SupplementPrefetchResumePreparation {
    data class Ready(val plan: SupplementPrefetchResumePlan) : SupplementPrefetchResumePreparation()
    data class ReplanRemaining(
        val sessionId: String,
        val revision: Long,
        val batchId: String?,
        val targetPanNo: Int
    ) : SupplementPrefetchResumePreparation()
    data object NoSession : SupplementPrefetchResumePreparation()
    data class Unsafe(val reason: String) : SupplementPrefetchResumePreparation()
}

object SupplementPrefetchResumePlanner {
    fun nextActionAfter(lastConfirmedStage: String?): String? = when (lastConfirmedStage) {
        "in1" -> "in2"
        "in2" -> "in3"
        "in3" -> "in4"
        else -> null
    }

    fun prepareAfterOrder(
        repository: SupplementPrefetchSessionRepository,
        orderNo: String
    ): SupplementPrefetchResumePreparation {
        require(orderNo.isNotBlank()) { "订单号不能为空" }
        val current = repository.load() ?: return SupplementPrefetchResumePreparation.NoSession
        if (current.pendingOrderNo != orderNo) {
            return SupplementPrefetchResumePreparation.Unsafe(
                "双 lane 暂停订单不匹配：expected=${current.pendingOrderNo}，actual=$orderNo"
            )
        }
        val delivery = current.deliveryLane
            ?: return SupplementPrefetchResumePreparation.Unsafe("双 lane 暂停会话缺少 delivery lane")
        if (current.prefetchLane != null &&
            (delivery.currentAction != "in7" || !delivery.panPlacementApplied)
        ) {
            return SupplementPrefetchResumePreparation.Unsafe("双 lane 暂停组合非法")
        }
        if (delivery.currentAction == "in7" && delivery.panPlacementApplied && current.prefetchLane == null) {
            if (!repository.clearOrderBarrierAndResume(orderNo)) {
                return SupplementPrefetchResumePreparation.Unsafe("双 lane 订单 barrier 清理失败")
            }
            val resumed = requireNotNull(repository.load())
            return SupplementPrefetchResumePreparation.ReplanRemaining(
                resumed.sessionId,
                resumed.revision,
                resumed.batchId,
                delivery.targetPanNo
            )
        }
        val promote = delivery.currentAction == "in7" && delivery.panPlacementApplied
        val lane = if (promote) current.prefetchLane else delivery
        validateResumableLane(lane)?.let { return SupplementPrefetchResumePreparation.Unsafe(it) }
        if (promote) repository.promotePrefetchAfterDelivery(allowPausedCheckpoint = true)
        if (!repository.clearOrderBarrierAndResume(orderNo)) {
            return SupplementPrefetchResumePreparation.Unsafe("双 lane 订单 barrier 清理失败")
        }
        return buildReady(repository.load())
    }

    fun prepareRecovered(
        repository: SupplementPrefetchSessionRepository
    ): SupplementPrefetchResumePreparation {
        val session = repository.load() ?: return SupplementPrefetchResumePreparation.NoSession
        val delivery = session.deliveryLane
            ?: return SupplementPrefetchResumePreparation.Unsafe("双 lane 恢复会话缺少 delivery lane")
        if (session.prefetchLane != null) {
            if (delivery.currentAction != "in7" || !delivery.panPlacementApplied) {
                return buildRecoveryDeliveryReady(session, delivery)
            }
            validateResumableLane(session.prefetchLane)?.let {
                return SupplementPrefetchResumePreparation.Unsafe(it)
            }
            repository.promotePrefetchAfterDelivery(allowPausedCheckpoint = true)
            return buildReady(repository.load())
        }
        if (delivery.currentAction == "in7" && delivery.panPlacementApplied) {
            return SupplementPrefetchResumePreparation.ReplanRemaining(
                session.sessionId,
                session.revision,
                session.batchId,
                delivery.targetPanNo
            )
        }
        return buildReady(session)
    }

    fun prepareRuntimeRecovery(
        repository: SupplementPrefetchSessionRepository
    ): SupplementPrefetchResumePreparation {
        val session = repository.load() ?: return SupplementPrefetchResumePreparation.NoSession
        if (session.phase != SupplementPipelinePhase.PAUSED_FOR_RUNTIME ||
            session.barrierState != SupplementBarrierState.OPEN ||
            session.pendingOrderNo != null ||
            session.mechanicalClaimState != null
        ) {
            return SupplementPrefetchResumePreparation.Unsafe(
                "双 lane 运行态续作前会话门禁不满足：" +
                    "phase=${session.phase}，barrier=${session.barrierState}，" +
                    "pendingOrder=${session.pendingOrderNo}，claim=${session.mechanicalClaimState}"
            )
        }
        return prepareRecovered(repository)
    }

    private fun buildRecoveryDeliveryReady(
        session: SupplementPrefetchPipelineSession,
        lane: SupplementPrefetchLaneCheckpoint
    ): SupplementPrefetchResumePreparation {
        val stage = lane.lastConfirmedStage
        if (session.barrierState != SupplementBarrierState.DELIVERY_COMMITTED ||
            stage !in setOf("in4", "in5", "in6") ||
            lane.currentAction != stage ||
            lane.lifecycle < SupplementActionLifecycle.TERMINAL_CONFIRMED ||
            !lane.boxDeductionApplied ||
            lane.sourceBoxNo == null
        ) {
            return SupplementPrefetchResumePreparation.Unsafe("双 lane 恢复 delivery 检查点不可续作")
        }
        val sourceBoxNo = requireNotNull(lane.sourceBoxNo)
        val confirmedStage = requireNotNull(stage)
        return SupplementPrefetchResumePreparation.Ready(
            SupplementPrefetchResumePlan(
                session.sessionId,
                session.batchId,
                session.resourceMask,
                sourceBoxNo,
                lane.targetPanNo,
                confirmedStage
            )
        )
    }

    private fun buildReady(session: SupplementPrefetchPipelineSession?): SupplementPrefetchResumePreparation {
        session ?: return SupplementPrefetchResumePreparation.NoSession
        if (session.barrierState != SupplementBarrierState.OPEN || session.pendingOrderNo != null) {
            return SupplementPrefetchResumePreparation.Unsafe("双 lane 续作前订单 barrier 未释放")
        }
        val lane = session.deliveryLane
        validateResumableLane(lane)?.let { return SupplementPrefetchResumePreparation.Unsafe(it) }
        return SupplementPrefetchResumePreparation.Ready(
            SupplementPrefetchResumePlan(
                session.sessionId,
                session.batchId,
                session.resourceMask,
                requireNotNull(lane?.sourceBoxNo),
                requireNotNull(lane).targetPanNo,
                requireNotNull(lane.lastConfirmedStage)
            )
        )
    }

    private fun validateResumableLane(lane: SupplementPrefetchLaneCheckpoint?): String? {
        lane ?: return "双 lane 暂停会话缺少可续作 lane"
        val stage = lane.lastConfirmedStage
        if (stage !in setOf("in1", "in2", "in3")) return "双 lane 暂停检查点不可续作：stage=$stage"
        if (lane.currentAction != stage || lane.lifecycle < SupplementActionLifecycle.TERMINAL_CONFIRMED) {
            return "双 lane 暂停动作终态证据不足"
        }
        if (lane.sourceBoxNo == null || lane.targetPanNo !in 1..33) return "双 lane 暂停坐标非法"
        if (stage in setOf("in2", "in3") && !lane.boxDeductionApplied) return "双 lane 暂停缺少扣箱证据"
        return null
    }
}

data class SupplementPrefetchLaneCheckpoint(
    val sourceBoxNo: Int? = null,
    val sourceBoxCandidates: List<Int> = emptyList(),
    val targetPanNo: Int = 0,
    val taste: String? = null,
    val returnPosition: Int? = null,
    val currentAction: String? = null,
    val lifecycle: SupplementActionLifecycle = SupplementActionLifecycle.PREPARED,
    val lastConfirmedStage: String? = null,
    val stockBefore: Int? = null,
    val expectedStockAfter: Int? = null,
    val boxDeductionApplied: Boolean = false,
    val panPlacementApplied: Boolean = false,
    val failedSourceBoxNos: List<Int> = emptyList()
)

data class SupplementPrefetchPipelineSession(
    val schemaVersion: Int = SCHEMA_VERSION,
    val revision: Long = 0L,
    val sessionId: String = "",
    val batchId: String? = null,
    val startedAtMs: Long = System.currentTimeMillis(),
    val resourceMask: Int = ResourceMask.none,
    val deliveryLane: SupplementPrefetchLaneCheckpoint? = null,
    val prefetchLane: SupplementPrefetchLaneCheckpoint? = null,
    val barrierState: SupplementBarrierState = SupplementBarrierState.OPEN,
    val pendingOrderNo: String? = null,
    val phase: SupplementPipelinePhase = SupplementPipelinePhase.RUNNING,
    val mechanicalClaimState: MechanicalClaimState? = null,
    val mechanicalClaimRevision: Long = 0L
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

object SupplementPrefetchOrderPauseSafety {
    fun isSafe(session: SupplementPrefetchPipelineSession): Boolean {
        if (session.phase != SupplementPipelinePhase.PAUSED_FOR_ORDER ||
            session.barrierState != SupplementBarrierState.ORDER_WON ||
            session.pendingOrderNo.isNullOrBlank()
        ) {
            return false
        }
        val delivery = session.deliveryLane ?: return false
        val prefetch = session.prefetchLane
        return if (prefetch == null) {
            isTerminalCheckpoint(delivery) && when (delivery.currentAction) {
                "in1" -> true
                "in2", "in3" -> delivery.boxDeductionApplied
                "in7" -> delivery.boxDeductionApplied && delivery.panPlacementApplied
                else -> false
            }
        } else {
            isTerminalCheckpoint(delivery) &&
                delivery.currentAction == "in7" &&
                delivery.boxDeductionApplied &&
                delivery.panPlacementApplied &&
                isTerminalCheckpoint(prefetch) &&
                prefetch.currentAction in setOf("in1", "in2", "in3") &&
                (prefetch.currentAction == "in1" || prefetch.boxDeductionApplied)
        }
    }

    private fun isTerminalCheckpoint(lane: SupplementPrefetchLaneCheckpoint): Boolean {
        return lane.currentAction != null &&
            lane.currentAction == lane.lastConfirmedStage &&
            lane.lifecycle >= SupplementActionLifecycle.TERMINAL_CONFIRMED
    }
}

object SupplementPrefetchOrderGatePolicy {
    fun isBusy(
        locksIdle: Boolean,
        evidencePresent: Boolean,
        safePausedForOrder: Boolean
    ): Boolean = !locksIdle || (evidencePresent && !safePausedForOrder)
}

interface SupplementPrefetchSessionStore {
    val synchronizationLock: Any
        get() = this

    fun load(): SupplementPrefetchPipelineSession?
    fun read(): cn.niuyannet.kaochang.android.services.recovery.DurableEvidenceReadResult<SupplementPrefetchPipelineSession> =
        load()?.let { cn.niuyannet.kaochang.android.services.recovery.DurableEvidenceReadResult.Valid(it) }
            ?: cn.niuyannet.kaochang.android.services.recovery.DurableEvidenceReadResult.Absent
    fun save(session: SupplementPrefetchPipelineSession)
    fun clear()
}

class SupplementPrefetchSessionCorruptedException(
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

class SupplementPrefetchSessionRepository(
    private val store: SupplementPrefetchSessionStore
) {
    private val lock = store.synchronizationLock

    fun load(): SupplementPrefetchPipelineSession? = synchronized(lock) {
        store.load()?.also(::validatePersistedSession)
    }

    fun create(session: SupplementPrefetchPipelineSession): SupplementPrefetchPipelineSession = synchronized(lock) {
        check(store.load() == null) { "补肠预取会话已存在，禁止覆盖创建" }
        check(session.revision == 0L) { "新建补肠预取会话 revision 必须为 0" }
        val created = session.copy(revision = 1L)
        validatePersistedSession(created)
        store.save(created)
        created
    }

    fun markMechanicalClaimPrepared(sessionId: String): SupplementPrefetchPipelineSession = mutate { current ->
        check(current.sessionId == sessionId) { "双 lane PREPARED owner 已变化" }
        current.copy(
            mechanicalClaimState = MechanicalClaimState.PREPARED,
            mechanicalClaimRevision = current.mechanicalClaimRevision + 1L
        )
    }

    fun markMechanicalClaimed(sessionId: String): SupplementPrefetchPipelineSession = mutate { current ->
        check(current.sessionId == sessionId) { "双 lane CLAIMED owner 已变化" }
        check(current.mechanicalClaimState == MechanicalClaimState.PREPARED) {
            "双 lane CLAIMED 缺少 PREPARED 检查点"
        }
        current.copy(
            mechanicalClaimState = MechanicalClaimState.CLAIMED,
            mechanicalClaimRevision = current.mechanicalClaimRevision + 1L
        )
    }

    fun prepareAction(
        role: SupplementLaneRole,
        lane: SupplementPrefetchLaneCheckpoint,
        actionName: String
    ): SupplementPrefetchPipelineSession = mutate { current ->
        requireActionName(actionName)
        val persistedLane = current.lane(role)
        if (persistedLane != null) {
            check(persistedLane.boxDeductionApplied || !lane.boxDeductionApplied) {
                "$role prepareAction 禁止伪造 in2 扣箱证据"
            }
        }
        if (actionName in ACTIONS_REQUIRING_BOX_DEDUCTION) {
            check(persistedLane?.boxDeductionApplied == true) {
                "$role 进入 $actionName 前必须保留 in2 扣箱证据"
            }
        }
        val prepared = lane.copy(
            currentAction = actionName,
            lifecycle = SupplementActionLifecycle.PREPARED,
            lastConfirmedStage = null
        )
        current.withLane(role, prepared)
    }

    fun restartLaneAfterPickFailure(
        role: SupplementLaneRole,
        failedSourceBoxNo: Int,
        fallbackSourceBoxNo: Int,
        targetPanNo: Int,
        stockBefore: Int?
    ): SupplementPrefetchPipelineSession = synchronized(lock) {
        val current = requireSession()
        check(current.barrierState != SupplementBarrierState.ORDER_WON) {
            "订单已赢得 barrier，禁止切换备用肠箱"
        }
        val lane = current.lane(role) ?: error("$role lane 不存在")
        check(lane.currentAction == "in2") { "$role 只有 in2 取肠失败后才能切换备用箱" }
        check(lane.lifecycle in setOf(
            SupplementActionLifecycle.COMMAND_WRITTEN,
            SupplementActionLifecycle.RUNNING_CONFIRMED,
            SupplementActionLifecycle.TERMINAL_CONFIRMED
        )) {
            "$role in2 物理状态不允许切换备用箱：lifecycle=${lane.lifecycle}"
        }
        check(!lane.boxDeductionApplied && !lane.panPlacementApplied) {
            "$role 已应用物理状态，禁止切换备用箱"
        }
        check(lane.targetPanNo == targetPanNo) {
            "$role 备用箱切换禁止修改目标盘：expected=${lane.targetPanNo}，actual=$targetPanNo"
        }
        check(lane.sourceBoxNo != fallbackSourceBoxNo) { "$role 备用箱必须不同于失败来源箱" }
        check(lane.sourceBoxNo == failedSourceBoxNo) {
            "$role 失败来源箱不匹配：expected=${lane.sourceBoxNo}，actual=$failedSourceBoxNo"
        }
        check(fallbackSourceBoxNo in lane.sourceBoxCandidates) {
            "$role 备用箱不在候选列表：source=$fallbackSourceBoxNo，candidates=${lane.sourceBoxCandidates}"
        }
        check(fallbackSourceBoxNo !in lane.failedSourceBoxNos) {
            "$role 备用箱已在失败候选中：source=$fallbackSourceBoxNo，failed=${lane.failedSourceBoxNos}"
        }
        check(stockBefore != null && stockBefore > 0) {
            "$role 备用箱库存快照必须大于 0：stockBefore=$stockBefore"
        }
        val restarted = lane.copy(
            sourceBoxNo = fallbackSourceBoxNo,
            currentAction = "in1",
            lifecycle = SupplementActionLifecycle.PREPARED,
            lastConfirmedStage = null,
            stockBefore = stockBefore,
            expectedStockAfter = stockBefore - 1,
            boxDeductionApplied = false,
            panPlacementApplied = false,
            failedSourceBoxNos = (lane.failedSourceBoxNos + failedSourceBoxNo).distinct()
        )
        persistTransformed(
            current,
            current.withLane(role, restarted),
            allowLaneReplacement = true
        )
    }

    fun markCommandWritten(
        role: SupplementLaneRole,
        actionName: String
    ): SupplementPrefetchPipelineSession = advanceLifecycle(
        role,
        actionName,
        SupplementActionLifecycle.COMMAND_WRITTEN
    )

    fun markRunningConfirmed(
        role: SupplementLaneRole,
        actionName: String
    ): SupplementPrefetchPipelineSession = advanceLifecycle(
        role,
        actionName,
        SupplementActionLifecycle.RUNNING_CONFIRMED
    )

    fun markTerminalConfirmed(
        role: SupplementLaneRole,
        actionName: String,
        allowWithoutRunningEvidence: Boolean = false
    ): SupplementPrefetchPipelineSession = mutate { current ->
        val lane = current.requireLane(role, actionName)
        if (lane.lifecycle == SupplementActionLifecycle.PHYSICAL_APPLIED) {
            return@mutate current
        }
        val expected = if (allowWithoutRunningEvidence) {
            setOf(
                SupplementActionLifecycle.COMMAND_WRITTEN,
                SupplementActionLifecycle.RUNNING_CONFIRMED,
                SupplementActionLifecycle.TERMINAL_CONFIRMED
            )
        } else {
            setOf(
                SupplementActionLifecycle.RUNNING_CONFIRMED,
                SupplementActionLifecycle.TERMINAL_CONFIRMED
            )
        }
        check(lane.lifecycle in expected) {
            "补肠预取动作不能从 ${lane.lifecycle} 确认终态：role=$role，action=$actionName"
        }
        current.withLane(
            role,
            lane.copy(
                lifecycle = SupplementActionLifecycle.TERMINAL_CONFIRMED,
                lastConfirmedStage = actionName
            )
        )
    }

    fun markPhysicalApplied(
        role: SupplementLaneRole,
        actionName: String,
        boxDeductionApplied: Boolean = false,
        panPlacementApplied: Boolean = false
    ): SupplementPrefetchPipelineSession = mutate { current ->
        val lane = current.requireLane(role, actionName)
        check(
            lane.lifecycle == SupplementActionLifecycle.TERMINAL_CONFIRMED ||
                lane.lifecycle == SupplementActionLifecycle.PHYSICAL_APPLIED
        ) {
            "补肠预取物理状态只能在终态确认后应用：role=$role，action=$actionName，lifecycle=${lane.lifecycle}"
        }
        current.withLane(
            role,
            lane.copy(
                lifecycle = SupplementActionLifecycle.PHYSICAL_APPLIED,
                lastConfirmedStage = actionName,
                boxDeductionApplied = lane.boxDeductionApplied || boxDeductionApplied,
                panPlacementApplied = lane.panPlacementApplied || panPlacementApplied
            )
        )
    }

    fun requestOrderBarrier(orderNo: String): Boolean = synchronized(lock) {
        require(orderNo.isNotBlank()) { "订单号不能为空" }
        val current = requireSession()
        check(current.phase !in setOf(
            SupplementPipelinePhase.MAINTENANCE_REQUIRED,
            SupplementPipelinePhase.COMPLETED
        )) {
            "补肠预取会话处于 ${current.phase}，禁止接受新订单 barrier"
        }
        when (current.barrierState) {
            SupplementBarrierState.OPEN -> {
                persistTransformed(
                    current,
                    current.copy(
                        barrierState = SupplementBarrierState.ORDER_WON,
                        pendingOrderNo = orderNo,
                        phase = SupplementPipelinePhase.PAUSED_FOR_ORDER
                    )
                )
                true
            }
            SupplementBarrierState.ORDER_WON -> current.pendingOrderNo == orderNo
            SupplementBarrierState.DELIVERY_COMMITTED -> {
                if (current.pendingOrderNo == null) {
                    persistTransformed(current, current.copy(pendingOrderNo = orderNo))
                }
                false
            }
        }
    }

    fun tryCommitDelivery(sourceBoxNo: Int, targetPanNo: Int): Boolean = synchronized(lock) {
        val current = requireSession()
        val lane = requireNotNull(current.deliveryLane) { "补肠预取 delivery lane 不存在" }
        check(lane.sourceBoxNo == sourceBoxNo && lane.targetPanNo == targetPanNo) {
            "delivery lane 坐标不匹配：expected=${lane.sourceBoxNo}->${lane.targetPanNo}，actual=$sourceBoxNo->$targetPanNo"
        }
        when (current.barrierState) {
            SupplementBarrierState.OPEN -> {
                persistTransformed(
                    current,
                    current.copy(barrierState = SupplementBarrierState.DELIVERY_COMMITTED)
                )
                true
            }
            SupplementBarrierState.ORDER_WON -> false
            SupplementBarrierState.DELIVERY_COMMITTED -> true
        }
    }

    fun pauseCommittedDeliveryForPendingOrder(): SupplementPrefetchPipelineSession = synchronized(lock) {
        val current = requireSession()
        if (SupplementPrefetchOrderPauseSafety.isSafe(current)) {
            return@synchronized current
        }
        check(current.phase in setOf(
            SupplementPipelinePhase.RUNNING,
            SupplementPipelinePhase.RECOVERING
        )) {
            "当前阶段禁止转订单安全暂停：phase=${current.phase}"
        }
        check(current.barrierState == SupplementBarrierState.DELIVERY_COMMITTED) {
            "仅 DELIVERY_COMMITTED 可转订单安全暂停：barrier=${current.barrierState}"
        }
        check(!current.pendingOrderNo.isNullOrBlank()) { "delivery 已提交但缺少 pendingOrderNo" }
        check(current.prefetchLane == null) { "存在 prefetch lane，禁止按单 lane 已完成路径暂停" }
        val delivery = requireNotNull(current.deliveryLane) { "订单安全暂停缺少 delivery lane" }
        check(delivery.currentAction == "in7" &&
            delivery.lifecycle == SupplementActionLifecycle.PHYSICAL_APPLIED &&
            delivery.boxDeductionApplied &&
            delivery.panPlacementApplied
        ) {
            "delivery 尚未完成安全物理收口：action=${delivery.currentAction}，" +
                "lifecycle=${delivery.lifecycle}，box=${delivery.boxDeductionApplied}，pan=${delivery.panPlacementApplied}"
        }
        persistTransformed(
            current,
            current.copy(
                barrierState = SupplementBarrierState.ORDER_WON,
                phase = SupplementPipelinePhase.PAUSED_FOR_ORDER
            )
        )
    }

    fun clearOrderBarrierAndResume(orderNo: String): Boolean = synchronized(lock) {
        require(orderNo.isNotBlank()) { "订单号不能为空" }
        val current = requireSession()
        if (current.pendingOrderNo != orderNo) {
            return@synchronized false
        }
        when (current.barrierState) {
            SupplementBarrierState.ORDER_WON -> persistTransformed(
                current,
                current.copy(
                    barrierState = SupplementBarrierState.OPEN,
                    pendingOrderNo = null,
                    phase = SupplementPipelinePhase.RUNNING
                ),
                allowBarrierReset = true
            )
            SupplementBarrierState.DELIVERY_COMMITTED -> persistTransformed(
                current,
                current.copy(pendingOrderNo = null)
            )
            SupplementBarrierState.OPEN -> persistTransformed(
                current,
                current.copy(
                    pendingOrderNo = null,
                    phase = SupplementPipelinePhase.RUNNING
                )
            )
        }
        true
    }

    fun promotePrefetchAfterDelivery(
        allowPausedCheckpoint: Boolean = false
    ): SupplementPrefetchPipelineSession = synchronized(lock) {
        val current = requireSession()
        val delivery = requireNotNull(current.deliveryLane) { "补肠预取 delivery lane 不存在" }
        val prefetch = requireNotNull(current.prefetchLane) { "补肠预取 prefetch lane 不存在" }
        check(delivery.panPlacementApplied) { "delivery lane 尚未确认烤肠落盘，禁止提升 prefetch lane" }
        if (allowPausedCheckpoint) {
            check(prefetch.currentAction == prefetch.lastConfirmedStage) { "prefetch lane 暂停检查点不一致" }
            check(prefetch.lastConfirmedStage in setOf("in1", "in2", "in3") &&
                prefetch.lifecycle >= SupplementActionLifecycle.TERMINAL_CONFIRMED
            ) { "prefetch lane 未停在明确终态" }
            check(prefetch.lastConfirmedStage == "in1" || prefetch.boxDeductionApplied) {
                "prefetch lane 暂停检查点缺少扣箱证据"
            }
        } else {
            check(prefetch.lastConfirmedStage == "in3") { "prefetch lane 尚未完成 in3，禁止提升" }
            check(prefetch.boxDeductionApplied) { "prefetch lane 缺少 in2 扣箱证据，禁止提升" }
        }
        val orderPending = !current.pendingOrderNo.isNullOrBlank()
        persistTransformed(
            current,
            current.copy(
                deliveryLane = prefetch,
                prefetchLane = null,
                barrierState = if (orderPending) {
                    SupplementBarrierState.ORDER_WON
                } else {
                    SupplementBarrierState.OPEN
                },
                phase = if (orderPending) {
                    SupplementPipelinePhase.PAUSED_FOR_ORDER
                } else {
                    SupplementPipelinePhase.RUNNING
                }
            ),
            allowBarrierReset = true,
            allowLaneReplacement = true
        )
    }

    fun clearIfRevision(sessionId: String, revision: Long): Boolean = synchronized(lock) {
        val current = store.load()?.also(::validatePersistedSession) ?: return@synchronized false
        if (current.sessionId != sessionId || current.revision != revision) {
            return@synchronized false
        }
        store.clear()
        true
    }

    fun clearIfMechanicalClaimRevision(sessionId: String, mechanicalClaimRevision: Long): Boolean =
        synchronized(lock) {
            val current = store.load()?.also(::validatePersistedSession) ?: return@synchronized false
            if (current.sessionId != sessionId ||
                current.mechanicalClaimRevision != mechanicalClaimRevision
            ) {
                return@synchronized false
            }
            store.clear()
            true
        }

    fun markRecoveryStarted(): SupplementPrefetchPipelineSession = mutate { current ->
        current.copy(phase = SupplementPipelinePhase.RECOVERING)
    }

    fun markRecoveryFinished(): SupplementPrefetchPipelineSession = mutate { current ->
        current.copy(
            phase = if (current.barrierState == SupplementBarrierState.ORDER_WON) {
                SupplementPipelinePhase.PAUSED_FOR_ORDER
            } else {
                SupplementPipelinePhase.RUNNING
            }
        )
    }

    fun markMaintenanceRequired(): SupplementPrefetchPipelineSession = mutate { current ->
        current.copy(phase = SupplementPipelinePhase.MAINTENANCE_REQUIRED)
    }

    fun pauseForRuntimeRecovery(sessionId: String): SupplementPrefetchPipelineSession = mutate { current ->
        check(current.sessionId == sessionId) { "双 lane runtime pause owner 已变化" }
        check(current.barrierState == SupplementBarrierState.OPEN && current.pendingOrderNo == null) {
            "双 lane runtime pause 需要已释放订单 barrier"
        }
        check(current.phase in setOf(
            SupplementPipelinePhase.RUNNING,
            SupplementPipelinePhase.RECOVERING,
            SupplementPipelinePhase.PAUSED_FOR_RUNTIME
        )) { "当前阶段禁止转 runtime pause：phase=${current.phase}" }
        if (current.phase == SupplementPipelinePhase.PAUSED_FOR_RUNTIME &&
            current.mechanicalClaimState == null
        ) {
            return@mutate current
        }
        current.copy(
            phase = SupplementPipelinePhase.PAUSED_FOR_RUNTIME,
            mechanicalClaimState = null,
            mechanicalClaimRevision = current.mechanicalClaimRevision + 1L
        )
    }

    fun resumeFromRuntimeRecovery(sessionId: String): SupplementPrefetchPipelineSession = mutate { current ->
        check(current.sessionId == sessionId) { "双 lane runtime resume owner 已变化" }
        check(current.phase == SupplementPipelinePhase.PAUSED_FOR_RUNTIME) {
            "当前阶段不是 runtime pause：phase=${current.phase}"
        }
        check(current.barrierState == SupplementBarrierState.OPEN && current.pendingOrderNo == null) {
            "双 lane runtime resume 需要已释放订单 barrier"
        }
        current.copy(phase = SupplementPipelinePhase.RUNNING)
    }

    private fun advanceLifecycle(
        role: SupplementLaneRole,
        actionName: String,
        target: SupplementActionLifecycle
    ): SupplementPrefetchPipelineSession = mutate { current ->
        val lane = current.requireLane(role, actionName)
        if (lane.lifecycle == target) {
            return@mutate current
        }
        check(target.ordinal == lane.lifecycle.ordinal + 1) {
            "补肠预取动作生命周期非法：role=$role，action=$actionName，${lane.lifecycle}->$target"
        }
        current.withLane(role, lane.copy(lifecycle = target))
    }

    private fun mutate(
        transform: (SupplementPrefetchPipelineSession) -> SupplementPrefetchPipelineSession
    ): SupplementPrefetchPipelineSession = synchronized(lock) {
        val current = requireSession()
        persistTransformed(current, transform(current))
    }

    private fun persistTransformed(
        current: SupplementPrefetchPipelineSession,
        transformed: SupplementPrefetchPipelineSession,
        allowBarrierReset: Boolean = false,
        allowLaneReplacement: Boolean = false
    ): SupplementPrefetchPipelineSession {
        check(transformed.sessionId == current.sessionId) { "补肠预取会话更新禁止修改 sessionId" }
        check(transformed.schemaVersion == current.schemaVersion) { "补肠预取会话更新禁止修改 schemaVersion" }
        check(transformed.revision == current.revision) { "补肠预取会话 revision 只能由仓库递增" }
        validateTransition(current, transformed, allowBarrierReset, allowLaneReplacement)
        if (transformed == current) {
            return current
        }
        val saved = transformed.copy(revision = current.revision + 1L)
        validatePersistedSession(saved)
        store.save(saved)
        return saved
    }

    private fun requireSession(): SupplementPrefetchPipelineSession =
        requireNotNull(store.load()?.also(::validatePersistedSession)) { "补肠预取会话不存在" }

    private fun validatePersistedSession(session: SupplementPrefetchPipelineSession) {
        SupplementPrefetchSessionValidator.failureReason(session)?.let { reason ->
            throw SupplementPrefetchSessionCorruptedException("补肠预取会话校验失败：$reason")
        }
    }

    private fun validateTransition(
        old: SupplementPrefetchPipelineSession,
        new: SupplementPrefetchPipelineSession,
        allowBarrierReset: Boolean,
        allowLaneReplacement: Boolean
    ) {
        validateLaneTransition(
            SupplementLaneRole.DELIVERY,
            old.deliveryLane,
            new.deliveryLane,
            allowLaneReplacement
        )
        validateLaneTransition(
            SupplementLaneRole.PREFETCH,
            old.prefetchLane,
            new.prefetchLane,
            allowLaneReplacement
        )
        if (!allowBarrierReset) {
            val allowed = when (old.barrierState) {
                SupplementBarrierState.OPEN -> true
                SupplementBarrierState.ORDER_WON -> new.barrierState == SupplementBarrierState.ORDER_WON
                SupplementBarrierState.DELIVERY_COMMITTED ->
                    new.barrierState == SupplementBarrierState.DELIVERY_COMMITTED ||
                        new.barrierState == SupplementBarrierState.ORDER_WON
            }
            check(allowed) { "补肠预取 barrier 非法回退：${old.barrierState}->${new.barrierState}" }
        }
    }

    private fun validateLaneTransition(
        role: SupplementLaneRole,
        old: SupplementPrefetchLaneCheckpoint?,
        new: SupplementPrefetchLaneCheckpoint?,
        allowLaneReplacement: Boolean
    ) {
        if (allowLaneReplacement || old == null) {
            return
        }
        check(new != null) { "$role lane 不能通过普通 update 删除" }
        check(!old.boxDeductionApplied || new.boxDeductionApplied) { "$role boxDeductionApplied 禁止回退" }
        check(!old.panPlacementApplied || new.panPlacementApplied) { "$role panPlacementApplied 禁止回退" }
        if (old.currentAction != new.currentAction) {
            if (
                old.currentAction == null &&
                old.lifecycle == SupplementActionLifecycle.PREPARED &&
                new.currentAction == "in1" &&
                new.lifecycle == SupplementActionLifecycle.PREPARED
            ) {
                return
            }
            check(old.lifecycle.ordinal >= SupplementActionLifecycle.TERMINAL_CONFIRMED.ordinal) {
                "$role lane 当前动作未确认终态，禁止切换：${old.currentAction}->${new.currentAction}"
            }
            check(new.lifecycle == SupplementActionLifecycle.PREPARED) {
                "$role lane 新动作必须从 PREPARED 开始：action=${new.currentAction}，lifecycle=${new.lifecycle}"
            }
            val oldIndex = ACTION_ORDER.indexOf(old.currentAction)
            val newIndex = ACTION_ORDER.indexOf(new.currentAction)
            check(oldIndex >= 0 && newIndex == oldIndex + 1) {
                "$role lane 动作必须顺序推进：${old.currentAction}->${new.currentAction}"
            }
            return
        }
        check(new.lifecycle.ordinal >= old.lifecycle.ordinal) {
            "补肠预取动作生命周期禁止回退：role=$role，action=${old.currentAction}，${old.lifecycle}->${new.lifecycle}"
        }
    }

    private fun requireActionName(actionName: String) {
        require(actionName in ACTION_NAMES) { "不支持的补肠动作：$actionName" }
    }

    private fun SupplementPrefetchPipelineSession.requireLane(
        role: SupplementLaneRole,
        actionName: String
    ): SupplementPrefetchLaneCheckpoint {
        val lane = when (role) {
            SupplementLaneRole.DELIVERY -> deliveryLane
            SupplementLaneRole.PREFETCH -> prefetchLane
        }
        return requireNotNull(lane) { "$role lane 不存在" }.also {
            check(it.currentAction == actionName) {
                "$role lane 动作不匹配：expected=${it.currentAction}，actual=$actionName"
            }
        }
    }

    private fun SupplementPrefetchPipelineSession.lane(
        role: SupplementLaneRole
    ): SupplementPrefetchLaneCheckpoint? = when (role) {
        SupplementLaneRole.DELIVERY -> deliveryLane
        SupplementLaneRole.PREFETCH -> prefetchLane
    }

    private fun SupplementPrefetchPipelineSession.withLane(
        role: SupplementLaneRole,
        lane: SupplementPrefetchLaneCheckpoint
    ): SupplementPrefetchPipelineSession = when (role) {
        SupplementLaneRole.DELIVERY -> copy(deliveryLane = lane)
        SupplementLaneRole.PREFETCH -> copy(prefetchLane = lane)
    }

    private companion object {
        val ACTION_ORDER = (1..7).map { "in$it" }
        val ACTION_NAMES = ACTION_ORDER.toSet()
        val ACTIONS_REQUIRING_BOX_DEDUCTION = (3..7).map { "in$it" }.toSet()
    }
}
