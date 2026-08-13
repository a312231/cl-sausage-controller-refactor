package cn.niuyannet.kaochang.scheduler

data class SchedulerProfileId(val value: String) {
    init {
        require(value.matches(VALID_VALUE)) {
            "Scheduler profile id must use 3-64 lowercase ASCII letters, digits, '.', '_' or '-': $value"
        }
    }

    private companion object {
        val VALID_VALUE = Regex("[a-z0-9][a-z0-9._-]{2,63}")
    }
}

data class SchedulerProfileDescriptor(
    val profileId: SchedulerProfileId,
    val sellCandidateStrategyId: StrategyId,
    val targetAllocationStrategyId: StrategyId,
    val tasteMixStrategyId: StrategyId,
    val batchHeatPositionStrategyId: StrategyId,
    val demandTransitionStrategyId: StrategyId
)

class SchedulerProfile private constructor(
    val id: SchedulerProfileId,
    val sellCandidateStrategy: SellCandidateStrategy,
    val targetAllocationStrategy: SupplementTargetAllocationStrategy,
    val tasteMixStrategy: SupplementTasteMixStrategy,
    val batchHeatPositionStrategy: SupplementBatchHeatPositionStrategy,
    val demandTransitionStrategy: TrafficDemandTransitionStrategy
) {
    val descriptor = SchedulerProfileDescriptor(
        profileId = id,
        sellCandidateStrategyId = sellCandidateStrategy.id,
        targetAllocationStrategyId = targetAllocationStrategy.id,
        tasteMixStrategyId = tasteMixStrategy.id,
        batchHeatPositionStrategyId = batchHeatPositionStrategy.id,
        demandTransitionStrategyId = demandTransitionStrategy.id
    )

    companion object {
        @JvmSynthetic
        internal fun createLegacyA(): SchedulerProfile = SchedulerProfile(
            id = LegacySchedulerProfileA.ID,
            sellCandidateStrategy = OldestHoldingFirstStrategy(),
            targetAllocationStrategy = InventoryProportionalTargetStrategy(),
            tasteMixStrategy = RelativeDeficitTasteMixStrategy(),
            batchHeatPositionStrategy = LegacyFixedBatchHeatPositionStrategy(),
            demandTransitionStrategy = LegacyDemandTimeoutTransitionStrategy()
        ).also { profile ->
            check(profile.descriptor == LegacySchedulerProfileA.DESCRIPTOR) {
                "The reserved legacy A profile must contain the reviewed legacy A component set"
            }
        }

        @JvmSynthetic
        internal fun createForTesting(
            id: SchedulerProfileId,
            sellCandidateStrategy: SellCandidateStrategy,
            targetAllocationStrategy: SupplementTargetAllocationStrategy,
            tasteMixStrategy: SupplementTasteMixStrategy,
            batchHeatPositionStrategy: SupplementBatchHeatPositionStrategy,
            demandTransitionStrategy: TrafficDemandTransitionStrategy
        ): SchedulerProfile {
            require(id.value.startsWith("test-")) {
                "Arbitrary profile composition is reserved for test profile ids"
            }
            return SchedulerProfile(
                id = id,
                sellCandidateStrategy = sellCandidateStrategy,
                targetAllocationStrategy = targetAllocationStrategy,
                tasteMixStrategy = tasteMixStrategy,
                batchHeatPositionStrategy = batchHeatPositionStrategy,
                demandTransitionStrategy = demandTransitionStrategy
            )
        }
    }
}

object LegacySchedulerProfileA {
    val ID = SchedulerProfileId("legacy-scheduler-profile-a-v1")

    internal val DESCRIPTOR = SchedulerProfileDescriptor(
        profileId = ID,
        sellCandidateStrategyId = OldestHoldingFirstStrategy.ID,
        targetAllocationStrategyId = InventoryProportionalTargetStrategy.ID,
        tasteMixStrategyId = RelativeDeficitTasteMixStrategy.ID,
        batchHeatPositionStrategyId = StrategyId("legacy-fixed-batch-heat-position-v1"),
        demandTransitionStrategyId = StrategyId("legacy-demand-timeout-transition-v1")
    )

    fun create(): SchedulerProfile = SchedulerProfile.createLegacyA()
}

internal fun parseSchedulerProfileIdStrict(raw: String?): SchedulerProfileId? {
    if (raw == null) return null
    return try {
        SchedulerProfileId(raw)
    } catch (_: IllegalArgumentException) {
        null
    }
}
