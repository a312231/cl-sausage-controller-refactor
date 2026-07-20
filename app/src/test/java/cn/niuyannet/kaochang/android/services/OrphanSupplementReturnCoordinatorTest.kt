package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.Taste
import com.alibaba.fastjson.JSON
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrphanSupplementReturnCoordinatorTest {

    @Test
    fun preview_shouldRecognizeLegacyHighHeatSourceWithoutMutatingAnyInputPanField() {
        val store = MemoryStore()
        val coordinator = coordinator(store)
        val legacySource = sourcePan(25, "A").apply {
            status = KaoPan.STATUS_SELLABLE_KEEP_WARM
            startTime = 0L
            holdingTime = 2_000L
        }
        val target = frontPan(2).apply {
            cmdStatusMove = 7
            uiReservationState = KaoPan.UI_RESERVATION_NONE
        }
        val sources = listOf(legacySource)
        val frontPans = listOf(target)
        val beforeSources = panSnapshots(sources)
        val beforeFrontPans = panSnapshots(frontPans)

        val result = coordinator.preview(sources, frontPans, emptySet())

        assertNull(result.failure)
        assertEquals(listOf(25), result.bindings.map { it.sourcePositionSn })
        assertEquals(listOf(2), result.bindings.map { it.returnPositionSn })
        assertEquals(beforeSources, panSnapshots(sources))
        assertEquals(beforeFrontPans, panSnapshots(frontPans))
    }

    @Test
    fun preview_shouldExcludeExistingRetainedReturnPositions() {
        val store = MemoryStore().apply { intent = retainedIntent(13, "B", 1, SupplementBatchType.LOW_HEAT_13_15) }
        val coordinator = coordinator(store)
        val reserved = store.intent!!.retainedCompletedHeatReturnTargets()
            .mapNotNull { it.returnPositionSn }
            .toSet()

        val result = coordinator.preview(
            sources = listOf(sourcePan(25, "A")),
            frontPans = listOf(frontPan(1), frontPan(2)),
            reservedFrontPositions = reserved
        )

        assertNull(result.failure)
        assertEquals(listOf(25), result.bindings.map { it.sourcePositionSn })
        assertEquals(listOf(2), result.bindings.map { it.returnPositionSn })
    }

    @Test
    fun commit_shouldRejectStalePreviewWithoutPersisting() {
        val store = MemoryStore()
        var panSaveCalls = 0
        val coordinator = coordinator(store) { panSaveCalls++ }
        val front = listOf(
            frontPan(1).apply { isHasSausage = true },
            frontPan(2),
            frontPan(3)
        )
        val sources = listOf(sourcePan(25, "A"))
        val expected = coordinator.preview(sources, front, emptySet()).bindings
        front.first { it.positionSn == 2 }.isHasSausage = true

        val result = coordinator.commit(expected, sources, front, emptySet(), "测试预览过期")

        assertEquals(OrphanSupplementReturnCoordinator.Failure.STALE_PREVIEW, result.failure)
        assertNull(store.intent)
        assertEquals(0, panSaveCalls)
    }

    @Test
    fun commit_shouldReportIntentPersistFailure() {
        val store = MemoryStore(saveDurableResult = false)
        var panSaveCalls = 0
        val coordinator = coordinator(store) { panSaveCalls++ }
        val sources = listOf(sourcePan(25, "A"))
        val front = listOf(frontPan(2))
        val expected = OrphanSupplementReturnPlanner.plan(sources, front, emptySet()).bindings

        val result = coordinator.commit(expected, sources, front, emptySet(), "测试 intent 持久化失败")

        assertEquals(OrphanSupplementReturnCoordinator.Failure.INTENT_PERSIST_FAILED, result.failure)
        assertNull(store.intent)
        assertEquals(0, panSaveCalls)
    }

    @Test
    fun commit_shouldReportPanPersistFailureAndKeepIntent() {
        val store = MemoryStore()
        val coordinator = coordinator(store) { throw IllegalStateException("盘位落盘失败") }
        val sources = listOf(sourcePan(25, "A"))
        val front = listOf(frontPan(2))
        val expected = OrphanSupplementReturnPlanner.plan(sources, front, emptySet()).bindings

        val result = coordinator.commit(expected, sources, front, emptySet(), "测试盘位持久化失败")

        assertEquals(OrphanSupplementReturnCoordinator.Failure.PAN_PERSIST_FAILED, result.failure)
        assertEquals(listOf(25), store.intent?.targets?.map { it.positionSn })
        assertEquals(listOf(2), store.intent?.targets?.map { it.returnPositionSn })
    }

    @Test
    fun commit_shouldReportConflict() {
        val existing = retainedIntent(13, "B", 2, SupplementBatchType.LOW_HEAT_13_15)
        val store = MemoryStore().apply { intent = existing }
        var panSaveCalls = 0
        val coordinator = coordinator(store) { panSaveCalls++ }
        val sources = listOf(sourcePan(25, "A"))
        val front = listOf(frontPan(2))
        val expected = OrphanSupplementReturnPlanner.plan(sources, front, emptySet()).bindings

        val result = coordinator.commit(expected, sources, front, emptySet(), "测试绑定冲突")

        assertEquals(OrphanSupplementReturnCoordinator.Failure.CONFLICT, result.failure)
        assertEquals(listOf(13), store.intent?.targets?.map { it.positionSn })
        assertEquals(listOf(2), store.intent?.targets?.map { it.returnPositionSn })
        assertEquals(0, panSaveCalls)
    }

    private fun coordinator(
        store: MemoryStore,
        savePansDurable: (List<KaoPan>) -> Unit = {}
    ): OrphanSupplementReturnCoordinator = OrphanSupplementReturnCoordinator(
        repository = SupplementBatchIntentRepository(
            store = store,
            clock = { 1000L },
            infoLogger = {},
            warnLogger = {}
        ),
        savePansDurable = savePansDurable
    )

    private fun retainedIntent(
        sourcePositionSn: Int,
        tasteCode: String,
        returnPositionSn: Int,
        originBatchType: SupplementBatchType
    ): SupplementBatchIntent = SupplementBatchIntent(
        batchId = "existing",
        type = SupplementBatchType.UNKNOWN,
        targets = mutableListOf(
            SupplementBatchIntentTarget(
                positionSn = sourcePositionSn,
                tasteCode = tasteCode,
                returnPositionSn = returnPositionSn,
                completed = true,
                originBatchId = "existing-origin",
                originBatchType = originBatchType
            )
        )
    )

    private fun sourcePan(positionSn: Int, tasteCode: String): KaoPan = KaoPan().apply {
        this.positionSn = positionSn
        isHasSausage = true
        status = KaoPan.STATUS_WAITING_RETURN_KEEP_WARM
        holdingTime = 2_000L
        taste = Taste(tasteCode)
    }

    private fun frontPan(positionSn: Int): KaoPan = KaoPan().apply {
        this.positionSn = positionSn
        isHasSausage = false
        status = KaoPan.STATUS_EMPTY
        taste = null
    }

    private fun panSnapshots(pans: List<KaoPan>): List<String> = pans.map { pan ->
        JSON.toJSONString(pan) +
            "|uiReservationState=${pan.uiReservationState}" +
            "|uiReservationTaste=${pan.uiReservationTaste?.tasteCode}"
    }

    private class MemoryStore(
        private val saveDurableResult: Boolean = true
    ) : SupplementBatchIntentStore {
        var intent: SupplementBatchIntent? = null

        override fun load(): SupplementBatchIntent? = intent

        override fun save(intent: SupplementBatchIntent) {
            this.intent = intent.deepCopy()
        }

        override fun clear() {
            intent = null
        }

        override fun saveDurable(intent: SupplementBatchIntent): Boolean {
            if (!saveDurableResult) {
                return false
            }
            save(intent)
            return true
        }

        private fun SupplementBatchIntent.deepCopy(): SupplementBatchIntent =
            copy(targets = targets.map { it.copy() }.toMutableList())
    }
}
