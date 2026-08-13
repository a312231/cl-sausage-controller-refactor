package cn.niuyannet.kaochang.scheduler

import java.lang.reflect.Modifier
import java.lang.reflect.InvocationTargetException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SchedulerProfileTest {

    @Test
    fun `legacy A binds the five reviewed components and derives its descriptor`() {
        val profile = LegacySchedulerProfileA.create()

        assertEquals(SchedulerProfileId("legacy-scheduler-profile-a-v1"), profile.id)
        assertEquals(
            SchedulerProfileDescriptor(
                profileId = SchedulerProfileId("legacy-scheduler-profile-a-v1"),
                sellCandidateStrategyId = StrategyId("legacy-oldest-holding-v1"),
                targetAllocationStrategyId = StrategyId("inventory-proportional-target-v1"),
                tasteMixStrategyId = StrategyId("relative-deficit-taste-mix-v1"),
                batchHeatPositionStrategyId = StrategyId("legacy-fixed-batch-heat-position-v1"),
                demandTransitionStrategyId = StrategyId("legacy-demand-timeout-transition-v1")
            ),
            profile.descriptor
        )
    }

    @Test
    fun `profile cannot be copied or directly constructed`() {
        val constructors = SchedulerProfile::class.java.declaredConstructors
            .filterNot { it.isSynthetic }
        assertTrue(constructors.isNotEmpty())
        assertTrue(constructors.all { Modifier.isPrivate(it.modifiers) })
        assertTrue(SchedulerProfile::class.java.declaredMethods.none { it.name == "copy" })
    }

    @Test
    fun `profile rejects Java platform null for every required binding`() {
        val a = LegacySchedulerProfileA.create()
        val constructor = SchedulerProfile::class.java.declaredConstructors
            .single { !it.isSynthetic }
            .apply { isAccessible = true }
        val valid = arrayOf<Any?>(
            a.id,
            a.sellCandidateStrategy,
            a.targetAllocationStrategy,
            a.tasteMixStrategy,
            a.batchHeatPositionStrategy,
            a.demandTransitionStrategy
        )

        valid.indices.forEach { nullIndex ->
            val arguments = valid.copyOf().also { it[nullIndex] = null }
            val failure = assertThrows(InvocationTargetException::class.java) {
                constructor.newInstance(*arguments)
            }
            assertTrue("null binding $nullIndex must fail", failure.cause is NullPointerException)
        }
    }

    @Test
    fun `reserved A id rejects a mixed component profile`() {
        val testB = createDistinctTestProfile("test-b")

        assertThrows(IllegalArgumentException::class.java) {
            SchedulerProfile.createForTesting(
                id = LegacySchedulerProfileA.ID,
                sellCandidateStrategy = testB.sellCandidateStrategy,
                targetAllocationStrategy = testB.targetAllocationStrategy,
                tasteMixStrategy = testB.tasteMixStrategy,
                batchHeatPositionStrategy = testB.batchHeatPositionStrategy,
                demandTransitionStrategy = testB.demandTransitionStrategy
            )
        }
    }

    @Test
    fun `catalog is exact rejects duplicates and never falls back`() {
        val a = LegacySchedulerProfileA.create()
        val b = createDistinctTestProfile("test-b").profile
        val catalog = SchedulerProfileCatalog(listOf(a, b))

        assertSame(a, catalog.find(a.id))
        assertSame(b, catalog.find(b.id))
        assertEquals(null, catalog.find(SchedulerProfileId("missing-profile-v1")))
        assertThrows(UnsupportedOperationException::class.java) {
            (catalog.registeredIds() as MutableSet<SchedulerProfileId>).clear()
        }
        assertThrows(IllegalArgumentException::class.java) {
            SchedulerProfileCatalog(listOf(a, LegacySchedulerProfileA.create()))
        }
    }

    @Test
    fun `pin keeps the exact complete profile and all five component instances`() {
        val a = LegacySchedulerProfileA.create()
        val b = createDistinctTestProfile("test-b")
        val catalog = SchedulerProfileCatalog(listOf(a, b.profile))
        val pin = (PinnedSchedulerProfile.create(catalog, b.profile.id.value) as ProfilePinResult.Pinned).profile

        val pinConstructors = PinnedSchedulerProfile::class.java.declaredConstructors
            .filterNot { it.isSynthetic }
        assertTrue(pinConstructors.isNotEmpty())
        assertTrue(pinConstructors.all { Modifier.isPrivate(it.modifiers) })

        assertSame(b.profile, pin.activeProfile)
        assertSame(b.sellCandidateStrategy, pin.activeProfile.sellCandidateStrategy)
        assertSame(b.targetAllocationStrategy, pin.activeProfile.targetAllocationStrategy)
        assertSame(b.tasteMixStrategy, pin.activeProfile.tasteMixStrategy)
        assertSame(b.batchHeatPositionStrategy, pin.activeProfile.batchHeatPositionStrategy)
        assertSame(b.demandTransitionStrategy, pin.activeProfile.demandTransitionStrategy)
        assertEquals(b.profile.descriptor, pin.descriptor)
        assertEquals(42, b.sellCandidateStrategy.selectPosition(emptySellCandidateInput()))
        assertEquals(
            mapOf("test-b-target" to 2),
            b.targetAllocationStrategy.allocate(
                SupplementTargetAllocationInput(2, mapOf("A" to 2), SupplementTargetScenario.STANDARD)
            ).targetCountByTaste
        )
        assertEquals(
            listOf(SupplementTasteAssignment(42, "test-b-taste")),
            b.tasteMixStrategy.plan(
                SupplementTasteMixInput(1, mapOf("A" to 1), mapOf("A" to 1), emptyMap(), listOf(1))
            ).assignments
        )
        assertEquals(
            LowHeatAction.START_13_15,
            b.batchHeatPositionStrategy.planLowHeat(LowHeatBatchInput(0, 0, 0)).action
        )
        assertEquals(
            TrafficTransitionOutcome.LOW_TO_HIGH_EFFECTIVE_DEMAND,
            b.demandTransitionStrategy.planLowToHigh(LowToHighDemandInput(0, false))
        )
    }

    @Test
    fun `existing A pin and all components stay fixed after a B request is planned`() {
        val a = LegacySchedulerProfileA.create()
        val b = createDistinctTestProfile("test-b").profile
        val catalog = SchedulerProfileCatalog(listOf(a, b))
        val pin = (PinnedSchedulerProfile.create(catalog, a.id.value) as ProfilePinResult.Pinned).profile
        val original = pin.activeProfile
        val originalComponents = listOf(
            original.sellCandidateStrategy,
            original.targetAllocationStrategy,
            original.tasteMixStrategy,
            original.batchHeatPositionStrategy,
            original.demandTransitionStrategy
        )

        val plan = SchedulerProfileSwitchPolicy.plan(
            catalog = catalog,
            currentId = a.id.value,
            requestedId = b.id.value,
            runtime = SchedulerProfileSwitchRuntimeSnapshot(
                deviceIdle = true,
                hasActiveOrder = false,
                hasWholeOrderClaim = false,
                hasOutActionSession = false,
                hasSupplementOwner = false,
                hasCleaningOwner = false,
                hasMaintenanceOwner = false
            )
        )

        assertEquals(SchedulerProfileSwitchDecisionCode.SWITCH_ALLOWED, plan.code)
        assertSame(original, pin.activeProfile)
        listOf(
            pin.activeProfile.sellCandidateStrategy,
            pin.activeProfile.targetAllocationStrategy,
            pin.activeProfile.tasteMixStrategy,
            pin.activeProfile.batchHeatPositionStrategy,
            pin.activeProfile.demandTransitionStrategy
        ).zip(originalComponents).forEach { (actual, expected) -> assertSame(expected, actual) }
    }

    @Test
    fun `pin rejects invalid and unknown profile ids without fallback`() {
        val catalog = SchedulerProfileCatalog(listOf(LegacySchedulerProfileA.create()))

        listOf<String?>(null, "", " legacy-scheduler-profile-a-v1", "ALGORITHM-A", "ab", "profile/a")
            .forEach { raw ->
                assertEquals(ProfilePinResult.InvalidId, PinnedSchedulerProfile.create(catalog, raw))
            }
        assertEquals(
            ProfilePinResult.NotRegistered,
            PinnedSchedulerProfile.create(catalog, "missing-profile-v1")
        )
    }

    @Test
    fun `legacy A component implementations have no mutable instance state`() {
        listOf(
            OldestHoldingFirstStrategy::class.java,
            InventoryProportionalTargetStrategy::class.java,
            RelativeDeficitTasteMixStrategy::class.java,
            LegacyFixedBatchHeatPositionStrategy::class.java,
            LegacyDemandTimeoutTransitionStrategy::class.java
        ).forEach { type ->
            val mutableFields = type.declaredFields.filter { field ->
                !Modifier.isStatic(field.modifiers) && !Modifier.isFinal(field.modifiers)
            }
            assertTrue("${type.simpleName} mutable fields: $mutableFields", mutableFields.isEmpty())
        }
    }

    private fun emptySellCandidateInput() = SellCandidateInput(
        nowMs = 0,
        tasteCode = "A",
        productId = 1,
        sellRange = 1..1,
        pendingDiscardPositions = emptySet(),
        pans = emptyList()
    )
}

internal class TestProfileFixture(
    val profile: SchedulerProfile,
    val sellCandidateStrategy: SellCandidateStrategy,
    val targetAllocationStrategy: SupplementTargetAllocationStrategy,
    val tasteMixStrategy: SupplementTasteMixStrategy,
    val batchHeatPositionStrategy: SupplementBatchHeatPositionStrategy,
    val demandTransitionStrategy: TrafficDemandTransitionStrategy
)

internal fun createDistinctTestProfile(prefix: String): TestProfileFixture {
    val sell = object : SellCandidateStrategy {
        override val id = StrategyId("$prefix-sell-v1")
        override fun selectPosition(input: SellCandidateInput): Int = 42
    }
    val target = object : SupplementTargetAllocationStrategy {
        override val id = StrategyId("$prefix-target-v1")
        override fun allocate(input: SupplementTargetAllocationInput) =
            SupplementTargetAllocationPlan(mapOf("$prefix-target" to 2), emptyMap())
    }
    val taste = object : SupplementTasteMixStrategy {
        override val id = StrategyId("$prefix-taste-v1")
        override fun plan(input: SupplementTasteMixInput) = SupplementTasteMixPlan(
            targetCountByTaste = mapOf("$prefix-taste" to 1),
            occupiedCountByTaste = emptyMap(),
            assignments = listOf(SupplementTasteAssignment(42, "$prefix-taste")),
            stockLimited = false
        )
    }
    val batch = object : SupplementBatchHeatPositionStrategy {
        override val id = StrategyId("$prefix-batch-v1")
        override fun planLowHeat(input: LowHeatBatchInput) = LowHeatBatchPlan(LowHeatAction.START_13_15)
        override fun planHighHeatSteady(input: HighHeatSteadyInput) =
            HighHeatBatchPlan(HighHeatAction.START_25_33)
        override fun planPositions(input: SupplementPositionInput) =
            SupplementPositionPlan(listOf(SupplementPositionPair(42, 43)))
    }
    val demand = object : TrafficDemandTransitionStrategy {
        override val id = StrategyId("$prefix-demand-v1")
        override fun planLowToHigh(input: LowToHighDemandInput) =
            TrafficTransitionOutcome.LOW_TO_HIGH_EFFECTIVE_DEMAND
        override fun planHighToLow(input: HighToLowDemandInput) =
            TrafficTransitionOutcome.HIGH_TO_LOW_INACTIVITY_TIMEOUT
    }
    val profile = SchedulerProfile.createForTesting(
        id = SchedulerProfileId("$prefix-scheduler-profile-v1"),
        sellCandidateStrategy = sell,
        targetAllocationStrategy = target,
        tasteMixStrategy = taste,
        batchHeatPositionStrategy = batch,
        demandTransitionStrategy = demand
    )
    return TestProfileFixture(profile, sell, target, taste, batch, demand)
}
