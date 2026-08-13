package cn.niuyannet.kaochang.scheduler

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SellCandidatePolicyTest {

    private val policy = OldestHoldingFirstStrategy()

    @Test
    fun `selects oldest holding sausage and uses position as tie breaker`() {
        val input = input(
            pans = listOf(
                pan(positionSn = 8, holdingTime = 2_000L),
                pan(positionSn = 6, holdingTime = 1_000L),
                pan(positionSn = 3, holdingTime = 1_000L)
            )
        )

        assertEquals(3, policy.selectPosition(input))
    }

    @Test
    fun `sellable time window includes both endpoints`() {
        val startsHoldingNow = input(
            nowMs = 10_000L,
            pans = listOf(pan(positionSn = 1, holdingTime = 10_000L, closeTime = 5_000L))
        )
        val reachesCloseTimeNow = input(
            nowMs = 15_000L,
            pans = listOf(pan(positionSn = 2, holdingTime = 10_000L, closeTime = 5_000L))
        )
        val exceedsCloseTimeByOneMillisecond = input(
            nowMs = 15_001L,
            pans = listOf(pan(positionSn = 3, holdingTime = 10_000L, closeTime = 5_000L))
        )

        assertEquals(1, policy.selectPosition(startsHoldingNow))
        assertEquals(2, policy.selectPosition(reachesCloseTimeNow))
        assertNull(policy.selectPosition(exceedsCloseTimeByOneMillisecond))
    }

    @Test
    fun `excludes future holding time pending discard and positions outside sell range`() {
        val input = input(
            nowMs = 10_000L,
            sellRange = 1..9,
            pendingDiscardPositions = setOf(2),
            pans = listOf(
                pan(positionSn = 1, holdingTime = 10_001L),
                pan(positionSn = 2, holdingTime = 1_000L),
                pan(positionSn = 10, holdingTime = 1_000L)
            )
        )

        assertNull(policy.selectPosition(input))
    }

    @Test
    fun `excludes invalid physical state taste and product`() {
        val input = input(
            pans = listOf(
                pan(positionSn = 1, hasSausage = false),
                pan(positionSn = 2, lifecycle = PanLifecycle.BAKING),
                pan(positionSn = 3, holdingTime = 0L),
                pan(positionSn = 4, closeTime = 0L),
                pan(positionSn = 5, tasteCode = null),
                pan(positionSn = 6, tasteCode = "B"),
                pan(positionSn = 7, productId = 999)
            )
        )

        assertNull(policy.selectPosition(input))
    }

    @Test
    fun `matches frozen legacy rule for ten thousand deterministic valid inputs`() {
        val random = Random(2_504_200L)

        repeat(10_000) { caseIndex ->
            val nowMs = 1_000_000L + random.nextInt(500_000)
            val tasteCode = listOf("A", "B", "C")[random.nextInt(3)]
            val productId = 100 + random.nextInt(4)
            val sellRange = if (random.nextBoolean()) 1..9 else 1..21
            val pending = (1..33).filterTo(linkedSetOf()) { random.nextInt(10) == 0 }
            val pans = (1..33).map { positionSn ->
                PanSnapshot(
                    positionSn = positionSn,
                    hasSausage = random.nextBoolean(),
                    lifecycle = PanLifecycle.entries[random.nextInt(PanLifecycle.entries.size)],
                    holdingTime = when (random.nextInt(6)) {
                        0 -> 0L
                        1 -> nowMs + random.nextInt(10_000) + 1L
                        else -> nowMs - random.nextInt(300_000)
                    },
                    closeTime = when (random.nextInt(6)) {
                        0 -> 0L
                        else -> random.nextInt(300_000).toLong() + 1L
                    },
                    tasteCode = listOf("A", "B", "C")[random.nextInt(3)],
                    productId = 100 + random.nextInt(4)
                )
            }
            val input = SellCandidateInput(
                nowMs = nowMs,
                tasteCode = tasteCode,
                productId = productId,
                sellRange = sellRange,
                pendingDiscardPositions = pending,
                pans = pans
            )

            assertEquals(
                "deterministic case $caseIndex",
                legacySelectPosition(input),
                policy.selectPosition(input)
            )
        }
    }

    @Test
    fun `input keeps an immutable copy of caller collections`() {
        val pending = mutableSetOf<Int>()
        val pans = mutableListOf(pan(positionSn = 1))
        val input = input(pendingDiscardPositions = pending, pans = pans)

        pending.add(1)
        pans.clear()

        assertEquals(1, policy.selectPosition(input))
    }

    private fun legacySelectPosition(input: SellCandidateInput): Int? {
        var selected: PanSnapshot? = null
        for (pan in input.pans) {
            if (pan.positionSn !in input.sellRange ||
                !pan.hasSausage ||
                pan.lifecycle != PanLifecycle.SELLABLE_KEEP_WARM ||
                pan.holdingTime <= 0L ||
                pan.closeTime <= 0L ||
                pan.positionSn in input.pendingDiscardPositions ||
                pan.tasteCode != input.tasteCode ||
                pan.productId != input.productId
            ) {
                continue
            }
            val elapsedMs = input.nowMs - pan.holdingTime
            if (elapsedMs < 0L || elapsedMs > pan.closeTime) {
                continue
            }
            val current = selected
            if (current == null ||
                pan.holdingTime < current.holdingTime ||
                (pan.holdingTime == current.holdingTime && pan.positionSn < current.positionSn)
            ) {
                selected = pan
            }
        }
        return selected?.positionSn
    }

    private fun input(
        nowMs: Long = 100_000L,
        tasteCode: String = "A",
        productId: Int = 104,
        sellRange: IntRange = 1..21,
        pendingDiscardPositions: Set<Int> = emptySet(),
        pans: List<PanSnapshot>
    ): SellCandidateInput = SellCandidateInput(
        nowMs = nowMs,
        tasteCode = tasteCode,
        productId = productId,
        sellRange = sellRange,
        pendingDiscardPositions = pendingDiscardPositions,
        pans = pans
    )

    private fun pan(
        positionSn: Int,
        hasSausage: Boolean = true,
        lifecycle: PanLifecycle = PanLifecycle.SELLABLE_KEEP_WARM,
        holdingTime: Long = 1_000L,
        closeTime: Long = 200_000L,
        tasteCode: String? = "A",
        productId: Int? = 104
    ): PanSnapshot = PanSnapshot(
        positionSn = positionSn,
        hasSausage = hasSausage,
        lifecycle = lifecycle,
        holdingTime = holdingTime,
        closeTime = closeTime,
        tasteCode = tasteCode,
        productId = productId
    )
}
