package cn.niuyannet.kaochang.android.services.recovery

import com.alibaba.fastjson.JSON
import java.security.MessageDigest
import java.util.concurrent.CancellationException

sealed class DurableEvidenceReadResult<out T> {
    data object Absent : DurableEvidenceReadResult<Nothing>()
    data class Valid<T>(val value: T) : DurableEvidenceReadResult<T>()
    data class Corrupted(
        val fingerprint: String,
        val reason: String,
        val rawLength: Int
    ) : DurableEvidenceReadResult<Nothing>()
}
class DurableEvidenceCorruptedException(
    val evidence: DurableEvidenceReadResult.Corrupted
) : IllegalStateException("持久化恢复证据损坏：fingerprint=${evidence.fingerprint}，reason=${evidence.reason}")

object DurableEvidenceCodec {
    inline fun <reified T> read(
        raw: String?,
        validator: (T) -> Boolean
    ): DurableEvidenceReadResult<T> = read(raw, { JSON.parseObject(it, T::class.java) }, validator)

    inline fun <T> read(
        raw: String?,
        parser: (String) -> T?,
        validator: (T) -> Boolean
    ): DurableEvidenceReadResult<T> {
        if (raw.isNullOrBlank()) return DurableEvidenceReadResult.Absent
        val parsed = try {
            parser(raw)
        } catch (error: CancellationException) {
            throw error
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw error
        } catch (error: Exception) {
            return corrupted(raw, "JSON_PARSE_${error.javaClass.simpleName}")
        } ?: return corrupted(raw, "JSON_PARSE_NULL")
        return try {
            if (validator(parsed)) {
                DurableEvidenceReadResult.Valid(parsed)
            } else {
                corrupted(raw, "INVARIANT_INVALID")
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw error
        } catch (error: Exception) {
            corrupted(raw, "INVARIANT_${error.javaClass.simpleName}")
        }
    }

    fun corrupted(raw: String, reason: String): DurableEvidenceReadResult.Corrupted =
        DurableEvidenceReadResult.Corrupted(
            fingerprint = MessageDigest.getInstance("SHA-256")
                .digest(raw.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) },
            reason = reason,
            rawLength = raw.length
        )
}
