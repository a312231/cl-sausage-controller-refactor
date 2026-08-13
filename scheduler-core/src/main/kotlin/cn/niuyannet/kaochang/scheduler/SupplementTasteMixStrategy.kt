package cn.niuyannet.kaochang.scheduler

import java.util.Collections
import java.util.LinkedHashMap

class SupplementTasteMixInput(
    val frontCapacity: Int,
    targetCountByTaste: Map<String, Int>,
    availableStockByTaste: Map<String, Int>,
    occupiedTasteByPosition: Map<Int, String>,
    assignablePositions: List<Int>
) {
    val targetCountByTaste: Map<String, Int> = targetCountByTaste.unmodifiableSnapshot()
    val availableStockByTaste: Map<String, Int> = availableStockByTaste.unmodifiableSnapshot()
    val occupiedTasteByPosition: Map<Int, String> = occupiedTasteByPosition.unmodifiableSnapshot()
    val assignablePositions: List<Int> = Collections.unmodifiableList(ArrayList(assignablePositions))
}

data class SupplementTasteAssignment(
    val positionSn: Int,
    val tasteCode: String
)

class SupplementTasteMixPlan(
    targetCountByTaste: Map<String, Int>,
    occupiedCountByTaste: Map<String, Int>,
    assignments: List<SupplementTasteAssignment>,
    val stockLimited: Boolean
) {
    val targetCountByTaste: Map<String, Int> = targetCountByTaste.unmodifiableSnapshot()
    val occupiedCountByTaste: Map<String, Int> = occupiedCountByTaste.unmodifiableSnapshot()
    val assignments: List<SupplementTasteAssignment> =
        Collections.unmodifiableList(ArrayList(assignments))
}

interface SupplementTasteMixStrategy {
    val id: StrategyId

    fun plan(input: SupplementTasteMixInput): SupplementTasteMixPlan
}

class RelativeDeficitTasteMixStrategy : SupplementTasteMixStrategy {
    override val id: StrategyId = ID

    override fun plan(input: SupplementTasteMixInput): SupplementTasteMixPlan {
        val normalizedTargets = input.targetCountByTaste
            .filterKeys { it.isNotBlank() }
            .mapValues { (_, count) -> count.coerceAtLeast(0) }
            .filterValues { it > 0 }
        val remainingStock = input.availableStockByTaste
            .filterKeys { it.isNotBlank() }
            .mapValues { (_, count) -> count.coerceAtLeast(0) }
            .filterValues { it > 0 }
            .toMutableMap()
        val occupiedCounts = input.occupiedTasteByPosition.values
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
            .toMutableMap()
        val assignments = mutableListOf<SupplementTasteAssignment>()
        val distinctPositions = input.assignablePositions.distinct()
        val maxAssignments = (input.frontCapacity - input.occupiedTasteByPosition.size)
            .coerceAtLeast(0)
            .coerceAtMost(distinctPositions.size)

        if (input.frontCapacity > 0) {
            distinctPositions.take(maxAssignments).forEach { positionSn ->
                val tasteCode = selectTaste(normalizedTargets, occupiedCounts, remainingStock)
                    ?: return@forEach
                assignments += SupplementTasteAssignment(positionSn, tasteCode)
                occupiedCounts[tasteCode] = (occupiedCounts[tasteCode] ?: 0) + 1
                remainingStock[tasteCode] = (remainingStock[tasteCode] ?: 0) - 1
            }
        }

        return SupplementTasteMixPlan(
            targetCountByTaste = normalizedTargets.toSortedMap(),
            occupiedCountByTaste = input.occupiedTasteByPosition.values
                .filter { it.isNotBlank() }
                .groupingBy { it }
                .eachCount()
                .toSortedMap(),
            assignments = assignments,
            stockLimited = assignments.size < distinctPositions.size
        )
    }

    private fun selectTaste(
        targetCountByTaste: Map<String, Int>,
        occupiedCountByTaste: Map<String, Int>,
        remainingStockByTaste: Map<String, Int>
    ): String? = remainingStockByTaste
        .filterValues { it > 0 }
        .keys
        .filter { (targetCountByTaste[it] ?: 0) > 0 }
        .sortedWith { left, right ->
            compareTastePriority(left, right, targetCountByTaste, occupiedCountByTaste)
        }
        .firstOrNull()

    private fun compareTastePriority(
        left: String,
        right: String,
        targetCountByTaste: Map<String, Int>,
        occupiedCountByTaste: Map<String, Int>
    ): Int {
        val leftTarget = targetCountByTaste.getValue(left)
        val rightTarget = targetCountByTaste.getValue(right)
        val leftDeficit = (leftTarget - (occupiedCountByTaste[left] ?: 0)).coerceAtLeast(0)
        val rightDeficit = (rightTarget - (occupiedCountByTaste[right] ?: 0)).coerceAtLeast(0)

        val relativeComparison = (rightDeficit.toLong() * leftTarget)
            .compareTo(leftDeficit.toLong() * rightTarget)
        if (relativeComparison != 0) return relativeComparison

        val absoluteComparison = rightDeficit.compareTo(leftDeficit)
        if (absoluteComparison != 0) return absoluteComparison

        return left.compareTo(right)
    }

    companion object {
        val ID: StrategyId = StrategyId("relative-deficit-taste-mix-v1")
    }
}

private fun <K, V> Map<K, V>.unmodifiableSnapshot(): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(this))
