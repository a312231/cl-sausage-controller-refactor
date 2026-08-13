package cn.niuyannet.kaochang.scheduler

import java.util.Collections
import java.util.LinkedHashMap

enum class SupplementTargetScenario {
    STANDARD,
    HIGH_HEAT
}

class SupplementTargetAllocationInput(
    val targetPanCount: Int,
    availableStockByTaste: Map<String, Int>,
    val scenario: SupplementTargetScenario
) {
    val availableStockByTaste: Map<String, Int> = availableStockByTaste.unmodifiableTargetSnapshot()
}

class SupplementTargetAllocationPlan(
    targetCountByTaste: Map<String, Int>,
    droppedStockByTaste: Map<String, Int>
) {
    val targetCountByTaste: Map<String, Int> = targetCountByTaste.unmodifiableTargetSnapshot()
    val droppedStockByTaste: Map<String, Int> = droppedStockByTaste.unmodifiableTargetSnapshot()
}

interface SupplementTargetAllocationStrategy {
    val id: StrategyId

    fun allocate(input: SupplementTargetAllocationInput): SupplementTargetAllocationPlan
}

class InventoryProportionalTargetStrategy : SupplementTargetAllocationStrategy {
    override val id: StrategyId = ID

    override fun allocate(input: SupplementTargetAllocationInput): SupplementTargetAllocationPlan {
        if (input.targetPanCount <= 0 || input.availableStockByTaste.isEmpty()) {
            return SupplementTargetAllocationPlan(emptyMap(), emptyMap())
        }
        val totalCount = input.availableStockByTaste.values.sum().toFloat()
        if (totalCount <= 0f) {
            return SupplementTargetAllocationPlan(emptyMap(), emptyMap())
        }

        val sortedByStock = input.availableStockByTaste.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        val droppedStockByTaste = linkedMapOf<String, Int>().apply {
            sortedByStock.drop(input.targetPanCount).forEach { put(it.key, it.value) }
        }
        val sortedFlavors = sortedByStock
            .take(input.targetPanCount)
            .map { it.key to it.value / totalCount }
        val allocation = when (input.scenario) {
            SupplementTargetScenario.STANDARD -> allocateStandard(input.targetPanCount, sortedFlavors)
            SupplementTargetScenario.HIGH_HEAT -> allocateHighHeat(input.targetPanCount, sortedFlavors)
        }
        return SupplementTargetAllocationPlan(allocation, droppedStockByTaste)
    }

    private fun allocateStandard(
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
            val sortedByCount = allocation.entries
                .sortedByDescending { it.value }
                .map { it.key }
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

    private fun allocateHighHeat(
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

    companion object {
        val ID: StrategyId = StrategyId("inventory-proportional-target-v1")
    }
}

private fun <K, V> Map<K, V>.unmodifiableTargetSnapshot(): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(this))
