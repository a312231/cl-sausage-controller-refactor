package cn.niuyannet.kaochang.scheduler

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SupplementTasteMixStrategyTest {

    private val strategy = RelativeDeficitTasteMixStrategy()

    @Test
    fun strategy_shouldExposeStableVersionedId() {
        assertEquals(StrategyId("relative-deficit-taste-mix-v1"), strategy.id)
    }

    @Test
    fun plan_shouldUseRelativeThenAbsoluteDeficitAndTasteCode() {
        assertEquals(
            listOf("F"),
            plan(
                targets = mapOf("A" to 6, "F" to 3),
                stock = mapOf("A" to 3, "F" to 3),
                occupied = (1..3).associateWith { "A" },
                positions = listOf(4)
            ).assignments.map { it.tasteCode }
        )
        assertEquals(
            listOf("A"),
            plan(
                targets = mapOf("A" to 6, "F" to 2),
                stock = mapOf("A" to 3, "F" to 3),
                occupied = mapOf(1 to "A", 2 to "A", 3 to "A", 4 to "F"),
                positions = listOf(5)
            ).assignments.map { it.tasteCode }
        )
        assertEquals(
            listOf("A", "C", "F"),
            plan(
                targets = linkedMapOf("F" to 3, "A" to 3, "C" to 3),
                stock = linkedMapOf("F" to 3, "A" to 3, "C" to 3),
                positions = listOf(1, 2, 3)
            ).assignments.map { it.tasteCode }
        )
    }

    @Test
    fun plan_shouldConvergeAcrossBatchesWithoutExceedingStock() {
        val first = plan(
            targets = mapOf("A" to 6, "F" to 3),
            stock = mapOf("A" to 20, "F" to 20),
            occupied = mapOf(1 to "A", 2 to "A", 3 to "A"),
            positions = listOf(4, 5, 6)
        )
        val occupiedAfterFirst = mapOf(1 to "A", 2 to "A", 3 to "A") +
            first.assignments.associate { it.positionSn to it.tasteCode }
        val stockAfterFirst = mapOf(
            "A" to 20 - first.assignments.count { it.tasteCode == "A" },
            "F" to 20 - first.assignments.count { it.tasteCode == "F" }
        )
        val second = plan(
            targets = mapOf("A" to 6, "F" to 3),
            stock = stockAfterFirst,
            occupied = occupiedAfterFirst,
            positions = listOf(7, 8, 9)
        )

        assertEquals(
            mapOf("A" to 3, "F" to 3),
            (first.assignments + second.assignments).groupingBy { it.tasteCode }.eachCount()
        )

        val limited = plan(
            targets = mapOf("A" to 6, "F" to 3),
            stock = mapOf("A" to 2, "F" to 1),
            positions = (1..6).toList()
        )
        assertEquals(mapOf("A" to 2, "F" to 1), limited.assignments.groupingBy { it.tasteCode }.eachCount())
        assertTrue(limited.stockLimited)
    }

    @Test
    fun plan_shouldPreserveBlankCapacityPreAssignmentCountsAndPositionSemantics() {
        val result = plan(
            frontCapacity = 3,
            targets = mapOf("A" to 3),
            stock = mapOf("A" to 3),
            occupied = linkedMapOf(1 to "", 2 to "A"),
            positions = listOf(2, 3, 3, 4)
        )

        assertEquals(listOf(SupplementTasteAssignment(2, "A")), result.assignments)
        assertEquals(mapOf("A" to 1), result.occupiedCountByTaste)
        assertTrue(result.stockLimited)
    }

    @Test
    fun plan_shouldFilterInvalidTasteTargetsAndStock() {
        val result = plan(
            targets = linkedMapOf("" to 3, " " to 3, "A" to 0, "B" to -1, "C" to 2),
            stock = linkedMapOf("" to 3, " " to 3, "A" to 2, "B" to 2, "C" to 1),
            positions = listOf(1, 2)
        )

        assertEquals(mapOf("C" to 2), result.targetCountByTaste)
        assertEquals(listOf(SupplementTasteAssignment(1, "C")), result.assignments)
        assertTrue(result.stockLimited)
        assertTrue(plan(targets = emptyMap(), stock = emptyMap()).assignments.isEmpty())
    }

    @Test
    fun inputAndOutput_shouldBeDefensiveUnmodifiableSnapshots() {
        val targets = linkedMapOf("A" to 2)
        val stock = linkedMapOf("A" to 2)
        val occupied = linkedMapOf(1 to "A")
        val positions = mutableListOf(2, 3)
        val input = SupplementTasteMixInput(3, targets, stock, occupied, positions)

        targets["B"] = 9
        stock["A"] = 0
        occupied[2] = "B"
        positions.clear()

        assertEquals(mapOf("A" to 2), input.targetCountByTaste)
        assertEquals(mapOf("A" to 2), input.availableStockByTaste)
        assertEquals(mapOf(1 to "A"), input.occupiedTasteByPosition)
        assertEquals(listOf(2, 3), input.assignablePositions)

        val result = strategy.plan(input)
        assertEquals(
            listOf(SupplementTasteAssignment(2, "A"), SupplementTasteAssignment(3, "A")),
            result.assignments
        )
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (input.targetCountByTaste as MutableMap<String, Int>)["X"] = 1
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (result.targetCountByTaste as MutableMap<String, Int>)["X"] = 1
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (result.assignments as MutableList<SupplementTasteAssignment>).add(SupplementTasteAssignment(9, "X"))
        }
    }

    @Test
    fun strategy_shouldMatchFrozenLegacyOracleForTenThousandInputs() {
        val random = Random(0x1C2A5EEDL)
        repeat(10_000) { index ->
            val input = randomInput(random, index)
            val expected = frozenLegacyPlan(input)
            val actual = strategy.plan(input)

            assertEquals("index=$index targets", expected.targetCountByTaste, actual.targetCountByTaste)
            assertEquals("index=$index occupied", expected.occupiedCountByTaste, actual.occupiedCountByTaste)
            assertEquals("index=$index assignments", expected.assignments, actual.assignments)
            assertEquals("index=$index stockLimited", expected.stockLimited, actual.stockLimited)
        }
    }

    private fun plan(
        frontCapacity: Int = 9,
        targets: Map<String, Int>,
        stock: Map<String, Int>,
        occupied: Map<Int, String> = emptyMap(),
        positions: List<Int> = listOf(1, 2, 3)
    ): SupplementTasteMixPlan = strategy.plan(
        SupplementTasteMixInput(frontCapacity, targets, stock, occupied, positions)
    )

    private fun randomInput(random: Random, index: Int): SupplementTasteMixInput {
        val tastes = listOf("", " ", "A", "C", "F", "x", "中")
        val counts = listOf(Int.MIN_VALUE, -2, -1, 0, 1, 2, 3, 9, Int.MAX_VALUE)
        fun randomMap(): LinkedHashMap<String, Int> = linkedMapOf<String, Int>().apply {
            tastes.shuffled(random).take(random.nextInt(tastes.size + 1)).forEach { taste ->
                put(taste, counts[random.nextInt(counts.size)])
            }
        }
        val occupied = linkedMapOf<Int, String>()
        repeat(random.nextInt(7)) {
            occupied[random.nextInt(9) - 2] = tastes[random.nextInt(tastes.size)]
        }
        val positions = MutableList(random.nextInt(10)) { random.nextInt(9) - 2 }
        val capacities = intArrayOf(Int.MIN_VALUE, -2, -1, 0, 1, 2, 3, 9, Int.MAX_VALUE)
        return SupplementTasteMixInput(
            frontCapacity = capacities[(index + random.nextInt(capacities.size)) % capacities.size],
            targetCountByTaste = randomMap(),
            availableStockByTaste = randomMap(),
            occupiedTasteByPosition = occupied,
            assignablePositions = positions
        )
    }

    private data class FrozenPlan(
        val targetCountByTaste: Map<String, Int>,
        val occupiedCountByTaste: Map<String, Int>,
        val assignments: List<SupplementTasteAssignment>,
        val stockLimited: Boolean
    )

    /** 2026-08-13 冻结的迁移前算法；不得委托生产策略或 App 适配器。 */
    private fun frozenLegacyPlan(input: SupplementTasteMixInput): FrozenPlan {
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
                val tasteCode = remainingStock
                    .filterValues { it > 0 }
                    .keys
                    .filter { (normalizedTargets[it] ?: 0) > 0 }
                    .sortedWith { left, right ->
                        val leftTarget = normalizedTargets.getValue(left)
                        val rightTarget = normalizedTargets.getValue(right)
                        val leftDeficit = (leftTarget - (occupiedCounts[left] ?: 0)).coerceAtLeast(0)
                        val rightDeficit = (rightTarget - (occupiedCounts[right] ?: 0)).coerceAtLeast(0)
                        val relative = (rightDeficit.toLong() * leftTarget)
                            .compareTo(leftDeficit.toLong() * rightTarget)
                        if (relative != 0) relative else {
                            val absolute = rightDeficit.compareTo(leftDeficit)
                            if (absolute != 0) absolute else left.compareTo(right)
                        }
                    }
                    .firstOrNull()
                    ?: return@forEach
                assignments += SupplementTasteAssignment(positionSn, tasteCode)
                occupiedCounts[tasteCode] = (occupiedCounts[tasteCode] ?: 0) + 1
                remainingStock[tasteCode] = (remainingStock[tasteCode] ?: 0) - 1
            }
        }

        return FrozenPlan(
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

    private fun <T> List<T>.shuffled(random: Random): List<T> = toMutableList().also { list ->
        for (index in list.lastIndex downTo 1) {
            val swap = random.nextInt(index + 1)
            val value = list[index]
            list[index] = list[swap]
            list[swap] = value
        }
    }
}
