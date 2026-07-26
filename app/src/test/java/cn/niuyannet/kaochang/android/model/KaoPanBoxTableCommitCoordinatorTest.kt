package cn.niuyannet.kaochang.android.model

import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class KaoPanBoxTableCommitCoordinatorTest {
    @Test
    fun differentPositions_shouldCommitFromLatestTableInsteadOfStaleSnapshots() {
        val coordinator = KaoPanBoxTableCommitCoordinator()
        var persisted = mutableListOf(box(21, 0), box(22, 0))
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit {
                coordinator.updateDurably(
                    load = { copy(persisted) },
                    persist = { persisted = copy(it) }
                ) { boxes ->
                    boxes.first { it.positionSn == 21 }.cmdStatusTake = 229
                    firstEntered.countDown()
                    releaseFirst.await(2, TimeUnit.SECONDS)
                }
            }
            assertTrue(firstEntered.await(1, TimeUnit.SECONDS))

            val second = executor.submit {
                coordinator.updateDurably(
                    load = { copy(persisted) },
                    persist = { persisted = copy(it) }
                ) { boxes ->
                    boxes.first { it.positionSn == 22 }.cmdStatusTake = 229
                }
            }
            Thread.sleep(50)
            assertFalse(second.isDone)

            releaseFirst.countDown()
            first.get(1, TimeUnit.SECONDS)
            second.get(1, TimeUnit.SECONDS)

            assertEquals(229, persisted.first { it.positionSn == 21 }.cmdStatusTake)
            assertEquals(229, persisted.first { it.positionSn == 22 }.cmdStatusTake)
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
        }
    }

    private fun box(positionSn: Int, status: Int) = KaoPanBox().apply {
        this.positionSn = positionSn
        cmdStatusTake = status
    }

    private fun copy(boxes: List<KaoPanBox>): MutableList<KaoPanBox> = boxes.map { source ->
        box(source.positionSn, source.cmdStatusTake)
    }.toMutableList()
}
