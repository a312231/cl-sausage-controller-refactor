package cn.niuyannet.kaochang.android.net

import com.lzy.okgo.model.Response as OkGoResponse
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody
import okio.Buffer
import okio.Okio
import okio.Source
import okio.Timeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SnapshotHttpErrorCallbackTest {

    @Test
    fun deliver_realOkGoError_readsRawBodyWhenWrapperBodyIsNull() {
        val response = errorResponse(
            code = 502,
            body = ResponseBody.create(null, "upstream body"),
            exception = SocketTimeoutException("read timed out")
        )
        assertNull(response.body())

        val callback = captureCallback(response)
        val evidence = requireNotNull(SnapshotHttpErrorEvidenceCodec.decode(callback.content))

        assertEquals(1, callback.count)
        assertEquals(1, callback.code)
        assertEquals(502, evidence.httpStatus)
        assertEquals(SocketTimeoutException::class.java.name, evidence.exceptionType)
        assertEquals("upstream body", evidence.responseBody)
    }

    @Test
    fun deliver_withoutRawResponse_stillInvokesCallbackWithExceptionEvidence() {
        val response = OkGoResponse.error<String>(
            false,
            null,
            null,
            IOException("network unavailable")
        )

        val callback = captureCallback(response)
        val evidence = requireNotNull(SnapshotHttpErrorEvidenceCodec.decode(callback.content))

        assertEquals(1, callback.count)
        assertNull(evidence.httpStatus)
        assertEquals(IOException::class.java.name, evidence.exceptionType)
        assertNull(evidence.responseBody)
    }

    @Test
    fun deliver_emptyRawBody_preservesEmptyBodyAndInvokesCallback() {
        val response = errorResponse(503, ResponseBody.create(null, ""), IOException("bad gateway"))

        val callback = captureCallback(response)
        val evidence = requireNotNull(SnapshotHttpErrorEvidenceCodec.decode(callback.content))

        assertEquals(1, callback.count)
        assertEquals(503, evidence.httpStatus)
        assertEquals("", evidence.responseBody)
    }

    @Test
    fun deliver_rawBodyReadFailure_closesBodyAndStillInvokesCallback() {
        var closed = false
        val throwingSource = Okio.buffer(object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                throw IOException("read failed")
            }

            override fun timeout(): Timeout = Timeout.NONE

            override fun close() {
                closed = true
            }
        })
        val response = errorResponse(
            502,
            ResponseBody.create(null, -1L, throwingSource),
            IOException("upstream failed")
        )

        val callback = captureCallback(response)
        val evidence = requireNotNull(SnapshotHttpErrorEvidenceCodec.decode(callback.content))

        assertEquals(1, callback.count)
        assertEquals(502, evidence.httpStatus)
        assertNull(evidence.responseBody)
        assertTrue(closed)
    }

    @Test
    fun deliver_largeRawBody_readsAtMostConfiguredLimitAndClosesSource() {
        var bytesRead = 0L
        var closed = false
        val payload = "B".repeat(20_000)
        val delegate = Buffer().writeUtf8(payload)
        val countingSource = Okio.buffer(object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                val read = delegate.read(sink, byteCount)
                if (read > 0) {
                    bytesRead += read
                }
                return read
            }

            override fun timeout(): Timeout = Timeout.NONE

            override fun close() {
                closed = true
            }
        })
        val response = errorResponse(
            502,
            ResponseBody.create(null, payload.length.toLong(), countingSource),
            IOException("large upstream response")
        )

        val callback = captureCallback(response)
        val evidence = requireNotNull(SnapshotHttpErrorEvidenceCodec.decode(callback.content))

        assertTrue(bytesRead < payload.length)
        assertEquals(SnapshotHttpErrorEvidenceCodec.RESPONSE_BODY_LIMIT, evidence.responseBody?.length)
        assertTrue(closed)
    }

    @Test
    fun deliver_blockingRawBody_returnsBeforeReadAndPostsExactlyOnceToMainDispatcher() {
        val readEntered = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val ioFinished = CountDownLatch(1)
        val delegate = Buffer().writeUtf8("slow body")
        val blockingSource = Okio.buffer(object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                readEntered.countDown()
                assertTrue(releaseRead.await(5, TimeUnit.SECONDS))
                return delegate.read(sink, byteCount)
            }

            override fun timeout(): Timeout = Timeout.NONE

            override fun close() = Unit
        })
        val response = errorResponse(
            502,
            ResponseBody.create(null, 9L, blockingSource),
            SocketTimeoutException("slow response")
        )
        var ioTask: (() -> Unit)? = null
        var mainTask: (() -> Unit)? = null
        val callback = CallbackCapture()
        val dispatcher = SnapshotHttpErrorCallbackDispatcher(
            dispatchIo = { task ->
                ioTask = task
                true
            },
            dispatchMain = { task ->
                mainTask = task
                true
            }
        )

        dispatcher.deliver(response) { code, content ->
            callback.count += 1
            callback.code = code
            callback.content = content
        }

        assertNotNull(ioTask)
        assertEquals(1L, readEntered.count)
        assertEquals(0, callback.count)

        val worker = Thread {
            try {
                requireNotNull(ioTask).invoke()
            } finally {
                ioFinished.countDown()
            }
        }
        worker.start()
        assertTrue(readEntered.await(5, TimeUnit.SECONDS))
        assertEquals(0, callback.count)
        releaseRead.countDown()
        assertTrue(ioFinished.await(5, TimeUnit.SECONDS))
        assertEquals(0, callback.count)
        assertNotNull(mainTask)

        requireNotNull(mainTask).invoke()
        assertEquals(1, callback.count)
        assertEquals(1, callback.code)
        assertEquals("slow body", SnapshotHttpErrorEvidenceCodec.decode(callback.content)?.responseBody)
    }

    @Test
    fun deliver_ioDispatchRejected_closesRawBodyAndFallsBackOnceOnCallerMainThread() {
        var closed = false
        val body = ResponseBody.create(
            null,
            4L,
            Okio.buffer(object : Source {
                private val delegate = Buffer().writeUtf8("body")

                override fun read(sink: Buffer, byteCount: Long): Long = delegate.read(sink, byteCount)
                override fun timeout(): Timeout = Timeout.NONE
                override fun close() {
                    closed = true
                }
            })
        )
        val response = errorResponse(503, body, IOException("dispatcher rejected"))
        val callerThreadId = Thread.currentThread().id
        var callbackThreadId = -1L
        val callback = CallbackCapture()
        var mainDispatchCount = 0
        val dispatcher = SnapshotHttpErrorCallbackDispatcher(
            dispatchIo = { false },
            dispatchMain = {
                mainDispatchCount += 1
                true
            }
        )

        dispatcher.deliver(response) { code, content ->
            callback.count += 1
            callback.code = code
            callback.content = content
            callbackThreadId = Thread.currentThread().id
        }

        val evidence = requireNotNull(SnapshotHttpErrorEvidenceCodec.decode(callback.content))
        assertTrue(closed)
        assertEquals(1, callback.count)
        assertEquals(callerThreadId, callbackThreadId)
        assertEquals(0, mainDispatchCount)
        assertEquals(503, evidence.httpStatus)
        assertEquals(IOException::class.java.name, evidence.exceptionType)
        assertNull(evidence.responseBody)
    }

    @Test
    fun deliver_duplicateIoAndMainExecution_invokesCallbackExactlyOnce() {
        val response = errorResponse(502, ResponseBody.create(null, "body"), IOException("duplicate"))
        val callback = CallbackCapture()
        val dispatcher = SnapshotHttpErrorCallbackDispatcher(
            dispatchIo = { task ->
                task()
                task()
                true
            },
            dispatchMain = { task ->
                task()
                task()
                true
            }
        )

        dispatcher.deliver(response) { code, content ->
            callback.count += 1
            callback.code = code
            callback.content = content
        }

        assertEquals(1, callback.count)
        assertEquals("body", SnapshotHttpErrorEvidenceCodec.decode(callback.content)?.responseBody)
    }

    @Test
    fun deliver_mainDispatchReturnsFalse_fallsBackOnCurrentIoThreadOnce() {
        val callback = deliverWithMainDispatcher { false }

        assertEquals(1, callback.count)
        assertEquals(1, callback.code)
    }

    @Test
    fun deliver_mainDispatchThrows_fallsBackOnCurrentIoThreadOnce() {
        val callback = deliverWithMainDispatcher {
            throw IllegalStateException("main dispatcher unavailable")
        }

        assertEquals(1, callback.count)
        assertEquals(1, callback.code)
    }

    @Test
    fun deliver_mainDispatchReturnsFalseThenRunsTaskLater_doesNotDoubleCallback() {
        var lateTask: (() -> Unit)? = null
        val callback = deliverWithMainDispatcher { task ->
            lateTask = task
            false
        }

        assertEquals(1, callback.count)
        requireNotNull(lateTask).invoke()
        assertEquals(1, callback.count)
    }

    @Test
    fun deliver_mainDispatchThrowsThenRunsTaskLater_doesNotDoubleCallback() {
        var lateTask: (() -> Unit)? = null
        val callback = deliverWithMainDispatcher { task ->
            lateTask = task
            throw IllegalStateException("posted then failed")
        }

        assertEquals(1, callback.count)
        requireNotNull(lateTask).invoke()
        assertEquals(1, callback.count)
    }

    private fun deliverWithMainDispatcher(
        dispatchMain: ((() -> Unit) -> Boolean)
    ): CallbackCapture {
        val response = errorResponse(502, ResponseBody.create(null, "body"), IOException("main dispatch"))
        val callback = CallbackCapture()
        val dispatcher = SnapshotHttpErrorCallbackDispatcher(
            dispatchIo = { task ->
                task()
                true
            },
            dispatchMain = dispatchMain
        )
        dispatcher.deliver(response) { code, content ->
            callback.count += 1
            callback.code = code
            callback.content = content
        }
        return callback
    }

    private fun captureCallback(response: OkGoResponse<String>?): CallbackCapture {
        val capture = CallbackCapture()
        val dispatcher = SnapshotHttpErrorCallbackDispatcher(
            dispatchIo = { task ->
                task()
                true
            },
            dispatchMain = { task ->
                task()
                true
            }
        )
        dispatcher.deliver(response) { code, content ->
            capture.count += 1
            capture.code = code
            capture.content = content
        }
        return capture
    }

    private fun errorResponse(
        code: Int,
        body: ResponseBody,
        exception: Throwable
    ): OkGoResponse<String> {
        val rawResponse = okhttp3.Response.Builder()
            .request(Request.Builder().url("https://example.test/snapshot").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test error")
            .body(body)
            .build()
        return OkGoResponse.error(false, null, rawResponse, exception)
    }

    private data class CallbackCapture(
        var count: Int = 0,
        var code: Int = -1,
        var content: String? = null
    )
}
