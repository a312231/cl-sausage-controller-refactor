package cn.niuyannet.kaochang.android.services.actiongroup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch

class SupplementResumeSingleFlightTest {

    @Test
    fun concurrentResume_shouldAllowOnlyOneAttemptUntilOwnerExits() {
        val gate = SupplementResumeSingleFlight()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var firstEntered = false

        val first = Thread {
            firstEntered = gate.tryEnter()
            entered.countDown()
            release.await()
            gate.exit()
        }
        first.start()
        entered.await()

        assertTrue(firstEntered)
        assertFalse(gate.tryEnter())

        release.countDown()
        first.join()
        assertTrue(gate.tryEnter())
        gate.exit()
    }
}
