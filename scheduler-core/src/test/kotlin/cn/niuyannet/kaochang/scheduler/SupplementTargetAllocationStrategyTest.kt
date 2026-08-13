package cn.niuyannet.kaochang.scheduler

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SupplementTargetAllocationStrategyTest {

    private val strategy = InventoryProportionalTargetStrategy()

    @Test
    fun strategy_shouldExposeStableVersionedId() {
        assertEquals(StrategyId("inventory-proportional-target-v1"), strategy.id)
    }

    @Test
    fun standard_shouldPreserveLegacyRatioMinimumRemainderAndReductionRules() {
        val ratio = allocate(
            target = 21,
            stock = linkedMapOf("A" to 16, "B" to 24),
            scenario = SupplementTargetScenario.STANDARD
        )
        assertEquals(listOf("B" to 13, "A" to 8), ratio.targetCountByTaste.entries.map { it.toPair() })

        val remainder = allocate(
            target = 4,
            stock = linkedMapOf("B" to 1, "A" to 2),
            scenario = SupplementTargetScenario.STANDARD
        )
        assertEquals(listOf("A" to 3, "B" to 1), remainder.targetCountByTaste.entries.map { it.toPair() })

        val minimumAndReduction = allocate(
            target = 3,
            stock = linkedMapOf("C" to 1, "B" to 1, "A" to 100),
            scenario = SupplementTargetScenario.STANDARD
        )
        assertEquals(
            listOf("A" to 1, "B" to 1, "C" to 1),
            minimumAndReduction.targetCountByTaste.entries.map { it.toPair() }
        )
    }

    @Test
    fun highHeat_shouldPreserveIntegerThenCyclicRemainderZeroKeysAndOrder() {
        val plan = allocate(
            target = 3,
            stock = linkedMapOf("C" to 1, "A" to 100, "B" to 1),
            scenario = SupplementTargetScenario.HIGH_HEAT
        )

        assertEquals(
            listOf("A" to 3, "B" to 0, "C" to 0),
            plan.targetCountByTaste.entries.map { it.toPair() }
        )
    }

    @Test
    fun allocation_shouldDropByStockThenTasteButKeepAllStockInDenominator() {
        val standard = allocate(
            target = 2,
            stock = linkedMapOf("D" to 100, "C" to 100, "B" to 1, "A" to 100),
            scenario = SupplementTargetScenario.STANDARD
        )
        val highHeat = allocate(
            target = 2,
            stock = linkedMapOf("D" to 100, "C" to 100, "B" to 1, "A" to 100),
            scenario = SupplementTargetScenario.HIGH_HEAT
        )

        assertEquals(listOf("A" to 1, "C" to 1), standard.targetCountByTaste.entries.map { it.toPair() })
        assertEquals(listOf("D" to 100, "B" to 1), standard.droppedStockByTaste.entries.map { it.toPair() })
        assertEquals(listOf("A" to 1, "C" to 1), highHeat.targetCountByTaste.entries.map { it.toPair() })
        assertEquals(listOf("D" to 100, "B" to 1), highHeat.droppedStockByTaste.entries.map { it.toPair() })
    }

    @Test
    fun allocation_shouldPreserveLegacyEmptyAndSafeOverflowResults() {
        SupplementTargetScenario.values().forEach { scenario ->
            assertTrue(allocate(-1, mapOf("A" to 1), scenario).targetCountByTaste.isEmpty())
            assertTrue(allocate(0, mapOf("A" to 1), scenario).targetCountByTaste.isEmpty())
            assertTrue(allocate(3, emptyMap(), scenario).targetCountByTaste.isEmpty())
            assertTrue(
                allocate(3, linkedMapOf("A" to Int.MAX_VALUE, "B" to 1), scenario)
                    .targetCountByTaste
                    .isEmpty()
            )
            assertTrue(
                allocate(
                    3,
                    linkedMapOf("A" to Int.MAX_VALUE, "B" to Int.MAX_VALUE, "C" to 2),
                    scenario
                ).targetCountByTaste.isEmpty()
            )
        }

        val positiveWrapStandard = allocate(
            3,
            linkedMapOf("A" to Int.MAX_VALUE, "B" to Int.MAX_VALUE, "C" to 3),
            SupplementTargetScenario.STANDARD
        )
        assertEquals(
            listOf("A" to Int.MAX_VALUE - 1, "B" to Int.MAX_VALUE - 1, "C" to 8),
            positiveWrapStandard.targetCountByTaste.entries.map { it.toPair() }
        )

        val positiveWrapHigh = allocate(
            3,
            linkedMapOf("A" to Int.MAX_VALUE, "B" to Int.MAX_VALUE, "C" to 3),
            SupplementTargetScenario.HIGH_HEAT
        )
        assertEquals(
            listOf("A" to Int.MAX_VALUE, "B" to Int.MAX_VALUE, "C" to 9),
            positiveWrapHigh.targetCountByTaste.entries.map { it.toPair() }
        )
    }

    @Test
    fun inputAndOutput_shouldBeDefensiveUnmodifiableSnapshots() {
        val stock = linkedMapOf("B" to 24, "A" to 16)
        val input = SupplementTargetAllocationInput(21, stock, SupplementTargetScenario.STANDARD)
        stock.clear()

        assertEquals(listOf("B" to 24, "A" to 16), input.availableStockByTaste.entries.map { it.toPair() })
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (input.availableStockByTaste as MutableMap<String, Int>)["X"] = 1
        }

        val plan = strategy.allocate(input)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (plan.targetCountByTaste as MutableMap<String, Int>)["X"] = 1
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (plan.droppedStockByTaste as MutableMap<String, Int>)["X"] = 1
        }
    }

    @Test
    fun strategy_shouldMatchFrozenLegacyOracleForTenThousandInputs() {
        val random = Random(0x1C3A110CL)
        repeat(10_000) { index ->
            val input = randomInput(random, index)
            val expected = frozenLegacyAllocate(input)
            val actual = strategy.allocate(input)

            assertEquals(
                "index=$index target entries",
                expected.targetCountByTaste.entries.map { it.toPair() },
                actual.targetCountByTaste.entries.map { it.toPair() }
            )
            assertEquals(
                "index=$index dropped entries",
                expected.droppedStockByTaste.entries.map { it.toPair() },
                actual.droppedStockByTaste.entries.map { it.toPair() }
            )
        }
    }

    private fun allocate(
        target: Int,
        stock: Map<String, Int>,
        scenario: SupplementTargetScenario
    ): SupplementTargetAllocationPlan = strategy.allocate(
        SupplementTargetAllocationInput(target, stock, scenario)
    )

    private fun randomInput(random: Random, index: Int): SupplementTargetAllocationInput {
        val tasteCount = random.nextInt(41)
        val tastes = (0 until tasteCount).map { "T%02d".format(it) }.shuffled(random)
        val includeSingleExtreme = tastes.isNotEmpty() && index % 23 == 0
        val stock = linkedMapOf<String, Int>()
        tastes.forEachIndexed { tasteIndex, taste ->
            stock[taste] = if (includeSingleExtreme && tasteIndex == 0) {
                Int.MAX_VALUE
            } else {
                random.nextInt(10_000) + 1
            }
        }
        return SupplementTargetAllocationInput(
            targetPanCount = random.nextInt(36) - 2,
            availableStockByTaste = stock,
            scenario = if (random.nextBoolean()) {
                SupplementTargetScenario.STANDARD
            } else {
                SupplementTargetScenario.HIGH_HEAT
            }
        )
    }

    private data class FrozenPlan(
        val targetCountByTaste: Map<String, Int>,
        val droppedStockByTaste: Map<String, Int>
    )

    /** 2026-08-13 冻结的迁移前“聚合库存 -> 目标配额”算法，不得委托生产策略或 App。 */
    private fun frozenLegacyAllocate(input: SupplementTargetAllocationInput): FrozenPlan {
        if (input.targetPanCount <= 0 || input.availableStockByTaste.isEmpty()) {
            return FrozenPlan(emptyMap(), emptyMap())
        }
        val totalCount = input.availableStockByTaste.values.sum().toFloat()
        if (totalCount <= 0f) {
            return FrozenPlan(emptyMap(), emptyMap())
        }
        val sortedByStock = input.availableStockByTaste.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        val dropped = linkedMapOf<String, Int>().apply {
            sortedByStock.drop(input.targetPanCount).forEach { put(it.key, it.value) }
        }
        val sortedFlavors = sortedByStock
            .take(input.targetPanCount)
            .map { it.key to it.value / totalCount }

        val allocation = when (input.scenario) {
            SupplementTargetScenario.STANDARD -> frozenStandard(input.targetPanCount, sortedFlavors)
            SupplementTargetScenario.HIGH_HEAT -> frozenHighHeat(input.targetPanCount, sortedFlavors)
        }
        return FrozenPlan(allocation, dropped)
    }

    private fun frozenStandard(
        targetPanCount: Int,
        sortedFlavors: List<Pair<String, Float>>
    ): Map<String, Int> {
        val rawAllocations = sortedFlavors.associate { (flavor, ratio) ->
            flavor to (targetPanCount * ratio)
        }
        val allocation = rawAllocations.mapValues { (_, value) ->
            maxOf(1, value.toInt())
        }.toMutableMap()
        var currentSum = allocation.values.sum()

        if (currentSum < targetPanCount) {
            val sortedByRemainder = rawAllocations
                .map { (flavor, value) -> flavor to (value - value.toInt()) }
                .sortedByDescending { it.second }
            var index = 0
            while (currentSum < targetPanCount) {
                val flavor = sortedByRemainder[index % sortedByRemainder.size].first
                allocation[flavor] = allocation.getValue(flavor) + 1
                currentSum++
                index++
            }
        } else if (currentSum > targetPanCount) {
            val sortedByCount = allocation.entries.sortedByDescending { it.value }.map { it.key }
            var index = 0
            while (currentSum > targetPanCount) {
                if (index >= sortedByCount.size) break
                val flavor = sortedByCount[index]
                if (allocation.getValue(flavor) > 1) {
                    allocation[flavor] = allocation.getValue(flavor) - 1
                    currentSum--
                }
                index++
            }
        }
        return allocation
    }

    private fun frozenHighHeat(
        targetPanCount: Int,
        sortedFlavors: List<Pair<String, Float>>
    ): Map<String, Int> {
        val allocation = mutableMapOf<String, Int>()
        val extraAllocations = sortedFlavors.map { (flavor, ratio) ->
            flavor to (targetPanCount * ratio).toInt()
        }.toMap()
        var unallocated = targetPanCount - extraAllocations.values.sum()
        var index = 0
        while (unallocated > 0) {
            val flavor = sortedFlavors[index % sortedFlavors.size].first
            allocation[flavor] = (allocation[flavor] ?: 0) + 1
            unallocated--
            index++
        }
        extraAllocations.forEach { (flavor, count) ->
            allocation[flavor] = (allocation[flavor] ?: 0) + count
        }
        return allocation
    }

    private fun <T> List<T>.shuffled(random: Random): List<T> = toMutableList().also { list ->
        for (index in list.lastIndex downTo 1) {
            val swapIndex = random.nextInt(index + 1)
            val value = list[index]
            list[index] = list[swapIndex]
            list[swapIndex] = value
        }
    }
}
