package cn.niuyannet.kaochang.android.services

internal object SupplementTasteMixPlanner {

    data class Assignment(
        val positionSn: Int,
        val tasteCode: String
    )

    data class Plan(
        val targetCountByTaste: Map<String, Int>,
        val occupiedCountByTaste: Map<String, Int>,
        val assignments: List<Assignment>,
        val stockLimited: Boolean
    )

    fun plan(
        frontCapacity: Int,
        targetCountByTaste: Map<String, Int>,
        availableStockByTaste: Map<String, Int>,
        occupiedTasteByPosition: Map<Int, String>,
        assignablePositions: List<Int>
    ): Plan {
        val normalizedTargets = targetCountByTaste
            .filterKeys { it.isNotBlank() }
            .mapValues { (_, count) -> count.coerceAtLeast(0) }
            .filterValues { it > 0 }
        val remainingStock = availableStockByTaste
            .filterKeys { it.isNotBlank() }
            .mapValues { (_, count) -> count.coerceAtLeast(0) }
            .filterValues { it > 0 }
            .toMutableMap()
        val occupiedCounts = occupiedTasteByPosition.values
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
            .toMutableMap()
        val assignments = mutableListOf<Assignment>()
        val maxAssignments = (frontCapacity - occupiedTasteByPosition.size)
            .coerceAtLeast(0)
            .coerceAtMost(assignablePositions.distinct().size)

        if (frontCapacity > 0) {
            assignablePositions.distinct().take(maxAssignments).forEach { positionSn ->
                val tasteCode = selectTaste(normalizedTargets, occupiedCounts, remainingStock)
                    ?: return@forEach
                assignments += Assignment(positionSn, tasteCode)
                occupiedCounts[tasteCode] = (occupiedCounts[tasteCode] ?: 0) + 1
                remainingStock[tasteCode] = (remainingStock[tasteCode] ?: 0) - 1
            }
        }

        return Plan(
            targetCountByTaste = normalizedTargets.toSortedMap(),
            occupiedCountByTaste = occupiedTasteByPosition.values
                .filter { it.isNotBlank() }
                .groupingBy { it }
                .eachCount()
                .toSortedMap(),
            assignments = assignments,
            stockLimited = assignments.size < assignablePositions.distinct().size
        )
    }

    fun committedReturnTasteByPosition(
        intent: SupplementBatchIntent?,
        returnPositionRange: IntRange
    ): Map<Int, String> {
        return intent
            ?.targets
            ?.asSequence()
            ?.filter { !it.cancelledByFaultTimeout && !it.skippedBySupplyDegrade }
            ?.mapNotNull { target ->
                val returnPosition = target.returnPositionSn?.takeIf { it in returnPositionRange }
                    ?: return@mapNotNull null
                val tasteCode = target.tasteCode?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                returnPosition to tasteCode
            }
            ?.distinctBy { it.first }
            ?.toMap()
            .orEmpty()
    }

    private fun selectTaste(
        targetCountByTaste: Map<String, Int>,
        occupiedCountByTaste: Map<String, Int>,
        remainingStockByTaste: Map<String, Int>
    ): String? {
        return remainingStockByTaste
            .filterValues { it > 0 }
            .keys
            .filter { (targetCountByTaste[it] ?: 0) > 0 }
            .sortedWith { left, right -> compareTastePriority(left, right, targetCountByTaste, occupiedCountByTaste) }
            .firstOrNull()
    }

    private fun compareTastePriority(
        left: String,
        right: String,
        targetCountByTaste: Map<String, Int>,
        occupiedCountByTaste: Map<String, Int>
    ): Int {
        val leftTarget = targetCountByTaste.getValue(left)
        val rightTarget = targetCountByTaste.getValue(right)
        val leftOccupied = occupiedCountByTaste[left] ?: 0
        val rightOccupied = occupiedCountByTaste[right] ?: 0
        val leftDeficit = (leftTarget - leftOccupied).coerceAtLeast(0)
        val rightDeficit = (rightTarget - rightOccupied).coerceAtLeast(0)

        val relativeComparison = (rightDeficit.toLong() * leftTarget)
            .compareTo(leftDeficit.toLong() * rightTarget)
        if (relativeComparison != 0) {
            return relativeComparison
        }
        val absoluteComparison = rightDeficit.compareTo(leftDeficit)
        if (absoluteComparison != 0) {
            return absoluteComparison
        }
        return left.compareTo(right)
    }
}
