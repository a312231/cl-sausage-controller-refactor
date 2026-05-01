package cn.niuyannet.kaochang.android.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CloudSyncRetryStoreTest {

    @Test
    fun recordFailure_shouldPersistLatestKaoPanPayload() {
        val storage = CloudSyncRetryStore.InMemoryStorage()

        CloudSyncRetryStore.withStorageForTest(storage) {
            CloudSyncRetryStore.recordFailure(
                type = CloudSyncRetryStore.Type.KAO_PAN,
                payload = """[{"position_sn":1}]""",
                error = "timeout",
                nowMillis = 1000L
            )

            val pending = CloudSyncRetryStore.getPending(CloudSyncRetryStore.Type.KAO_PAN)

            assertEquals(CloudSyncRetryStore.Type.KAO_PAN, pending?.type)
            assertEquals("""[{"position_sn":1}]""", pending?.payload)
            assertEquals(1000L, pending?.savedAtMillis)
            assertEquals(1000L, pending?.lastFailureAtMillis)
            assertEquals(1, pending?.failureCount)
            assertEquals("timeout", pending?.lastError)
        }
    }

    @Test
    fun recordFailure_shouldReplaceOldPayloadWithLatestSnapshot() {
        val storage = CloudSyncRetryStore.InMemoryStorage()

        CloudSyncRetryStore.withStorageForTest(storage) {
            CloudSyncRetryStore.recordFailure(
                type = CloudSyncRetryStore.Type.KAO_PAN,
                payload = """[{"position_sn":1}]""",
                error = "timeout",
                nowMillis = 1000L
            )
            CloudSyncRetryStore.recordFailure(
                type = CloudSyncRetryStore.Type.KAO_PAN,
                payload = """[{"position_sn":2}]""",
                error = "network down",
                nowMillis = 2000L
            )

            val pending = CloudSyncRetryStore.getPending(CloudSyncRetryStore.Type.KAO_PAN)

            assertEquals("""[{"position_sn":2}]""", pending?.payload)
            assertEquals(1000L, pending?.savedAtMillis)
            assertEquals(2000L, pending?.lastFailureAtMillis)
            assertEquals(2, pending?.failureCount)
            assertEquals("network down", pending?.lastError)
        }
    }

    @Test
    fun markSucceeded_shouldClearPendingPayload() {
        val storage = CloudSyncRetryStore.InMemoryStorage()

        CloudSyncRetryStore.withStorageForTest(storage) {
            CloudSyncRetryStore.recordFailure(
                type = CloudSyncRetryStore.Type.STOCK,
                payload = """[{"position_sn":1,"num":3}]""",
                error = "timeout",
                nowMillis = 1000L
            )

            val cleared = CloudSyncRetryStore.markSucceeded(CloudSyncRetryStore.Type.STOCK)

            assertEquals(CloudSyncRetryStore.Type.STOCK, cleared?.type)
            assertNull(CloudSyncRetryStore.getPending(CloudSyncRetryStore.Type.STOCK))
        }
    }

    @Test
    fun stockAndKaoPanPendingStates_shouldNotOverwriteEachOther() {
        val storage = CloudSyncRetryStore.InMemoryStorage()

        CloudSyncRetryStore.withStorageForTest(storage) {
            CloudSyncRetryStore.recordFailure(
                type = CloudSyncRetryStore.Type.KAO_PAN,
                payload = """[{"position_sn":1}]""",
                error = "tray timeout",
                nowMillis = 1000L
            )
            CloudSyncRetryStore.recordFailure(
                type = CloudSyncRetryStore.Type.STOCK,
                payload = """[{"position_sn":1,"num":3}]""",
                error = "stock timeout",
                nowMillis = 2000L
            )

            assertEquals("""[{"position_sn":1}]""", CloudSyncRetryStore.getPending(CloudSyncRetryStore.Type.KAO_PAN)?.payload)
            assertEquals(
                """[{"position_sn":1,"num":3}]""",
                CloudSyncRetryStore.getPending(CloudSyncRetryStore.Type.STOCK)?.payload
            )
        }
    }
}
