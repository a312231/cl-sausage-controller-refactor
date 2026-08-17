package cn.niuyannet.kaochang.android.net

import android.os.Handler
import android.os.Looper
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject
import okhttp3.ResponseBody
import okio.Buffer
import java.util.concurrent.atomic.AtomicBoolean

internal object SnapshotHttpErrorEvidenceCodec {
    const val PREFIX = "@@KC_SNAPSHOT_HTTP_ERROR_V1@@"
    const val EXCEPTION_TYPE_LIMIT = 160
    const val EXCEPTION_MESSAGE_LIMIT = 256
    const val RESPONSE_BODY_LIMIT = 512
    const val IDENTITY_REASON = "http_transport_failure"

    private val FIELD_NAMES = setOf(
        "httpStatus",
        "exceptionType",
        "exceptionMessage",
        "responseBody"
    )

    data class Evidence(
        val httpStatus: Int?,
        val exceptionType: String?,
        val exceptionMessage: String?,
        val responseBody: String?
    )

    fun encode(httpStatus: Int?, exception: Throwable?, responseBody: String?): String {
        return runCatching {
            val json = JSONObject()
            httpStatus?.takeIf { it in 100..599 }?.let { json["httpStatus"] = it }
            exception?.javaClass?.name
                ?.take(EXCEPTION_TYPE_LIMIT)
                ?.let { json["exceptionType"] = it }
            exception?.message
                ?.take(EXCEPTION_MESSAGE_LIMIT)
                ?.let { json["exceptionMessage"] = it }
            responseBody?.take(RESPONSE_BODY_LIMIT)?.let { json["responseBody"] = it }
            PREFIX + json.toJSONString()
        }.getOrElse {
            PREFIX + "{}"
        }
    }

    fun decode(content: String?): Evidence? {
        if (content == null || !content.startsWith(PREFIX)) {
            return null
        }
        return runCatching {
            val json = JSON.parseObject(content.substring(PREFIX.length)) ?: return@runCatching null
            if (json.keys.any { it !in FIELD_NAMES }) {
                return@runCatching null
            }
            val httpStatus = when (val value = json["httpStatus"]) {
                null -> null
                is Int -> value.takeIf { it in 100..599 } ?: return@runCatching null
                is Long -> value.takeIf { it in 100L..599L }?.toInt() ?: return@runCatching null
                else -> return@runCatching null
            }
            fun stringField(name: String, limit: Int): String? {
                val value = json[name] ?: return null
                check(value is String && value.length <= limit)
                return value
            }
            Evidence(
                httpStatus = httpStatus,
                exceptionType = stringField("exceptionType", EXCEPTION_TYPE_LIMIT),
                exceptionMessage = stringField("exceptionMessage", EXCEPTION_MESSAGE_LIMIT),
                responseBody = stringField("responseBody", RESPONSE_BODY_LIMIT)
            )
        }.getOrNull()
    }
}

internal class SnapshotHttpErrorCallbackDispatcher(
    private val dispatchIo: ((() -> Unit) -> Boolean),
    private val dispatchMain: ((() -> Unit) -> Boolean)
) {
    fun deliver(
        response: com.lzy.okgo.model.Response<String>?,
        callback: ((code: Int, content: String?) -> Unit)?
    ) {
        val ioStarted = AtomicBoolean(false)
        val callbackDelivered = AtomicBoolean(false)
        val deliverOnce = { content: String ->
            if (callbackDelivered.compareAndSet(false, true)) {
                callback?.invoke(1, content)
            }
        }
        val ioTask = {
            if (ioStarted.compareAndSet(false, true)) {
                val content = captureEvidence(response, includeRawBody = true)
                val callbackTask = { deliverOnce(content) }
                val dispatched = runCatching { dispatchMain(callbackTask) }.getOrDefault(false)
                if (!dispatched) {
                    deliverOnce(content)
                }
            }
        }
        val scheduled = runCatching { dispatchIo(ioTask) }.getOrDefault(false)
        if (!scheduled && ioStarted.compareAndSet(false, true)) {
            closeRawBody(response)
            deliverOnce(captureEvidence(response, includeRawBody = false))
        }
    }

    private fun captureEvidence(
        response: com.lzy.okgo.model.Response<String>?,
        includeRawBody: Boolean
    ): String = runCatching {
        val rawBody = if (includeRawBody) {
            readRawBodyOnce(response?.rawResponse?.body())
        } else {
            null
        }
        SnapshotHttpErrorEvidenceCodec.encode(
            httpStatus = response?.code()?.takeIf { it > 0 },
            exception = response?.exception,
            responseBody = rawBody ?: response?.body()?.takeIf { includeRawBody }
        )
    }.getOrElse {
        SnapshotHttpErrorEvidenceCodec.encode(
            httpStatus = response?.code()?.takeIf { it > 0 },
            exception = response?.exception,
            responseBody = null
        )
    }

    private fun readRawBodyOnce(body: ResponseBody?): String? {
        if (body == null) {
            return null
        }
        return body.use {
            val source = it.source()
            val captured = Buffer()
            var remaining = SnapshotHttpErrorEvidenceCodec.RESPONSE_BODY_LIMIT.toLong()
            while (remaining > 0L) {
                val read = source.read(captured, remaining)
                if (read <= 0L) {
                    break
                }
                remaining -= read
            }
            captured.readUtf8()
        }
    }

    private fun closeRawBody(response: com.lzy.okgo.model.Response<String>?) {
        runCatching { response?.rawResponse?.body()?.close() }
    }
}

internal object SnapshotHttpErrorCallback {
    private val mainHandler by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Handler(Looper.getMainLooper())
    }
    private val dispatcher by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SnapshotHttpErrorCallbackDispatcher(
            dispatchIo = DataManagementAPI::dispatchSnapshotErrorIo,
            dispatchMain = { task -> mainHandler.post(task) }
        )
    }

    fun deliver(
        response: com.lzy.okgo.model.Response<String>?,
        callback: ((code: Int, content: String?) -> Unit)?
    ) {
        dispatcher.deliver(response, callback)
    }
}

internal object SnapshotUploadFailureCompletion {
    fun complete(
        persistFailure: () -> CloudSyncRetryStore.PendingPayload,
        processEvidence: (CloudSyncRetryStore.PendingPayload) -> Unit,
        notifyResult: (Boolean) -> Unit
    ) {
        val pending = persistFailure()
        try {
            processEvidence(pending)
        } finally {
            notifyResult(false)
        }
    }
}
