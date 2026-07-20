package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.Taste
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrphanSupplementReturnPlannerTest {

    @Test
    fun plan_shouldAssignMatureSourcesToSortedCurrentEmptyFrontSlots() {
        val sources = listOf(sourcePan(25, "A"), sourcePan(26, "A"), sourcePan(27, "A"))
        val front = (1..9).map { position ->
            frontPan(position, null).apply {
                isHasSausage = position !in setOf(2, 5, 8)
            }
        }

        val result = OrphanSupplementReturnPlanner.plan(sources, front, emptySet())

        assertEquals(listOf(25, 26, 27), result.bindings.map { it.sourcePositionSn })
        assertEquals(listOf(2, 5, 8), result.bindings.map { it.returnPositionSn })
        assertEquals(null, result.failure)
    }

    @Test
    fun plan_shouldRejectWhenAnySourceTasteMissing() {
        val sources = listOf(
            sourcePan(25, "A"),
            sourcePan(26, "A").apply { taste = null }
        )

        val result = OrphanSupplementReturnPlanner.plan(
            sources,
            (1..9).map { frontPan(it, null) },
            emptySet()
        )

        assertTrue(result.bindings.isEmpty())
        assertEquals(OrphanSupplementReturnPlanner.Failure.NO_TASTE, result.failure)
    }

    @Test
    fun plan_shouldNotUseOccupiedOrReservedFrontSlots() {
        val sources = listOf(sourcePan(13, "B"), sourcePan(14, "B"))
        val front = (1..9).map { position ->
            frontPan(position, "B").apply { isHasSausage = position == 1 }
        }

        val result = OrphanSupplementReturnPlanner.plan(sources, front, setOf(2))

        assertEquals(listOf(3, 4), result.bindings.map { it.returnPositionSn })
    }

    @Test
    fun plan_shouldSkipReturnPositionsHeldByExistingCompletedBindings() {
        val sources = listOf(sourcePan(25, "A"), sourcePan(26, "A"))
        val front = (1..9).map { frontPan(it, "A") }

        val result = OrphanSupplementReturnPlanner.plan(sources, front, setOf(1, 2))

        assertEquals(listOf(3, 4), result.bindings.map { it.returnPositionSn })
    }

    @Test
    fun plan_shouldRejectWhenFrontCapacityIsInsufficient() {
        val sources = (25..27).map { sourcePan(it, "A") }
        val front = (1..9).map { position ->
            frontPan(position, "A").apply {
                isHasSausage = position !in setOf(2, 5)
            }
        }

        val result = OrphanSupplementReturnPlanner.plan(sources, front, emptySet())

        assertTrue(result.bindings.isEmpty())
        assertEquals(OrphanSupplementReturnPlanner.Failure.INSUFFICIENT_TARGETS, result.failure)
    }

    @Test
    fun plan_shouldRejectLowHeatSourceThatHasNoSausage() {
        val source = sourcePan(13, "B").apply { isHasSausage = false }

        val result = OrphanSupplementReturnPlanner.plan(
            sources = listOf(source),
            frontPans = (1..9).map { frontPan(it, "B") },
            reservedFrontPositions = emptySet()
        )

        assertTrue(result.bindings.isEmpty())
        assertEquals(OrphanSupplementReturnPlanner.Failure.UNSAFE_SOURCE, result.failure)
    }

    private fun frontPan(positionSn: Int, tasteCode: String?): KaoPan = KaoPan().apply {
        this.positionSn = positionSn
        isHasSausage = false
        status = KaoPan.STATUS_EMPTY
        taste = tasteCode?.let { Taste(it) }
    }

    private fun sourcePan(positionSn: Int, tasteCode: String): KaoPan = KaoPan().apply {
        this.positionSn = positionSn
        isHasSausage = true
        status = KaoPan.STATUS_WAITING_RETURN_KEEP_WARM
        holdingTime = 2_000L
        taste = Taste(tasteCode)
    }
}
