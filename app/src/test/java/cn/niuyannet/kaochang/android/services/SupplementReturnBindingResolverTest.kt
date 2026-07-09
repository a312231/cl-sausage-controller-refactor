package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.Taste
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupplementReturnBindingResolverTest {

    @Test
    fun resolveReturnTarget_shouldUseExactIntentBindingAndRestoreFrontReservation() {
        val frontPans = (1..9).map { position -> frontPan(position, "A") }
        val sourcePan = sourcePan(18, "A")
        val intent = intent(
            type = SupplementBatchType.LOW_HEAT_16_18,
            sourcePositionSn = 18,
            tasteCode = "A",
            returnPositionSn = 3
        )

        val result = SupplementReturnBindingResolver.resolveReturnTarget(
            sourcePan = sourcePan,
            frontPans = frontPans,
            activeIntent = intent,
            allowedTypes = setOf(SupplementBatchType.LOW_HEAT_16_18),
            returnPositionRange = 1..9
        )

        assertEquals(3, result.targetPan?.positionSn)
        assertEquals("A", result.targetPan?.taste?.tasteCode)
        assertTrue(result.targetPan!!.isHasGrilling)
        assertEquals(SupplementReturnBindingResolver.FailureReason.NONE, result.failureReason)
    }

    @Test
    fun resolveReturnTarget_shouldRejectOccupiedBoundTargetWithoutSelectingAnotherPan() {
        val frontPans = (1..9).map { position -> frontPan(position, "A") }
        frontPans.first { it.positionSn == 3 }.isHasSausage = true
        val sourcePan = sourcePan(18, "A")
        val intent = intent(
            type = SupplementBatchType.LOW_HEAT_16_18,
            sourcePositionSn = 18,
            tasteCode = "A",
            returnPositionSn = 3
        )

        val result = SupplementReturnBindingResolver.resolveReturnTarget(
            sourcePan = sourcePan,
            frontPans = frontPans,
            activeIntent = intent,
            allowedTypes = setOf(SupplementBatchType.LOW_HEAT_16_18),
            returnPositionRange = 1..9
        )

        assertNull(result.targetPan)
        assertEquals(SupplementReturnBindingResolver.FailureReason.TARGET_OCCUPIED, result.failureReason)
    }

    @Test
    fun resolveReturnTarget_shouldUseRetainedLowHeatBindingInsideHighHeatIntent() {
        val frontPans = (1..9).map { position -> frontPan(position, "A") }
        val sourcePan = sourcePan(18, "A")
        val intent = SupplementBatchIntent(
            batchId = "mixed",
            type = SupplementBatchType.HIGH_HEAT_25_33,
            targets = mutableListOf(
                SupplementBatchIntentTarget(
                    positionSn = 18,
                    tasteCode = "A",
                    returnPositionSn = 3,
                    completed = true
                ),
                SupplementBatchIntentTarget(
                    positionSn = 25,
                    tasteCode = "A",
                    returnPositionSn = 10,
                    completed = false
                )
            )
        )

        val result = SupplementReturnBindingResolver.resolveReturnTarget(
            sourcePan = sourcePan,
            frontPans = frontPans,
            activeIntent = intent,
            allowedTypes = setOf(SupplementBatchType.LOW_HEAT_16_18),
            returnPositionRange = 1..9
        )

        assertEquals(3, result.targetPan?.positionSn)
        assertEquals(SupplementReturnBindingResolver.FailureReason.NONE, result.failureReason)
    }

    @Test
    fun resolveReturnTarget_shouldNotUseLegacyFallbackWhenActiveIntentExistsWithoutBinding() {
        val frontPans = (1..9).map { position -> frontPan(position, "A") }
        frontPans.filter { it.positionSn != 3 }.forEach { it.isHasSausage = true }
        val sourcePan = sourcePan(18, "A")
        val intent = SupplementBatchIntent(
            batchId = "active-high",
            type = SupplementBatchType.HIGH_HEAT_25_33,
            targets = mutableListOf(
                SupplementBatchIntentTarget(
                    positionSn = 25,
                    tasteCode = "A",
                    returnPositionSn = 10,
                    completed = false
                )
            )
        )

        val result = SupplementReturnBindingResolver.resolveReturnTarget(
            sourcePan = sourcePan,
            frontPans = frontPans,
            activeIntent = intent,
            allowedTypes = setOf(SupplementBatchType.LOW_HEAT_16_18),
            returnPositionRange = 1..9,
            allowLegacyFallback = true
        )

        assertNull(result.targetPan)
        assertEquals(SupplementReturnBindingResolver.FailureReason.NO_BINDING, result.failureReason)
    }

    private fun frontPan(positionSn: Int, tasteCode: String): KaoPan =
        KaoPan().apply {
            this.positionSn = positionSn
            isHasSausage = false
            status = KaoPan.STATUS_EMPTY
            taste = Taste(tasteCode)
        }

    private fun sourcePan(positionSn: Int, tasteCode: String): KaoPan =
        KaoPan().apply {
            this.positionSn = positionSn
            isHasSausage = true
            status = KaoPan.STATUS_WAITING_RETURN_KEEP_WARM
            taste = Taste(tasteCode)
        }

    private fun intent(
        type: SupplementBatchType,
        sourcePositionSn: Int,
        tasteCode: String,
        returnPositionSn: Int
    ): SupplementBatchIntent =
        SupplementBatchIntent(
            batchId = "test",
            type = type,
            targets = mutableListOf(
                SupplementBatchIntentTarget(
                    positionSn = sourcePositionSn,
                    tasteCode = tasteCode,
                    returnPositionSn = returnPositionSn,
                    completed = true
                )
            )
        )
}
