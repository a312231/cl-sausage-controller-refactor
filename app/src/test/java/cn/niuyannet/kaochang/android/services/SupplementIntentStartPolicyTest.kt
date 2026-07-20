package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.model.bean.Taste
import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.init.AppConfigBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Before

class SupplementIntentStartPolicyTest {
    @Before
    fun setUp() {
        val field = AppConfig::class.java.getDeclaredField("appConfigBean")
        field.isAccessible = true
        field.set(AppConfig, AppConfigBean())
    }

    @Test
    fun shouldCreateSupplementIntent_shouldKeepSingleInitializationTargetDurable() {
        val target = KaoChangOperate.SupplementTarget(frontPan(9, "A"), "A")

        assertTrue(KaoChangOperate.shouldCreateSupplementIntent(listOf(target), SupplementBatchType.LOW_INITIALIZATION_1_9))
        assertTrue(KaoChangOperate.shouldCreateSupplementIntent(listOf(target), SupplementBatchType.HIGH_INITIALIZATION_1_21))
        assertFalse(KaoChangOperate.shouldCreateSupplementIntent(listOf(target), null))
    }

    @Test
    fun initializationGapPositions_shouldKeepAllRequestedTargetsBeforeTasteStockFiltering() {
        val targets = listOf(
            KaoChangOperate.SupplementTarget(frontPan(8, "A"), "A"),
            KaoChangOperate.SupplementTarget(frontPan(9, "B"), "B")
        )
        val availability = planExecutableSupplementTargets(
            targets = targets,
            listBox = listOf(box(positionSn = 18, tasteCode = "A", num = 1))
        )

        assertEquals(listOf(8), availability.executableTargets.map { it.kaoPan.positionSn })
        assertEquals(listOf(9), availability.skippedTargets.map { it.targetPositionSn })
        assertEquals(
            setOf(8, 9),
            KaoChangOperate.initializationGapPositions(targets, SupplementBatchType.LOW_INITIALIZATION_1_9)
        )
    }

    @Test
    fun initializationGapPositions_shouldKeepSingleTargetWhenNoMatchingTasteStockExists() {
        val target = KaoChangOperate.SupplementTarget(frontPan(9, "B"), "B")
        val availability = planExecutableSupplementTargets(
            targets = listOf(target),
            listBox = listOf(box(positionSn = 18, tasteCode = "A", num = 5))
        )

        assertTrue(availability.executableTargets.isEmpty())
        assertEquals(listOf(9), availability.skippedTargets.map { it.targetPositionSn })
        assertEquals(
            setOf(9),
            KaoChangOperate.initializationGapPositions(listOf(target), SupplementBatchType.LOW_INITIALIZATION_1_9)
        )
    }

    private fun frontPan(positionSn: Int, tasteCode: String): KaoPan = KaoPan().apply {
        this.positionSn = positionSn
        taste = Taste(tasteCode)
        status = KaoPan.STATUS_EMPTY
        isHasSausage = false
    }

    private fun box(positionSn: Int, tasteCode: String, num: Int): KaoPanBox = KaoPanBox().apply {
        this.positionSn = positionSn
        this.tasteCode = tasteCode
        this.num = num
    }
}
