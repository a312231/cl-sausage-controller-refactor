package cn.niuyannet.kaochang.android.mqtt.nativeprotocol

import cn.niuyannet.kaochang.android.init.AppConfig
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject
import com.lzy.okgo.OkGo
import com.lzy.okgo.callback.StringCallback
import com.lzy.okgo.model.Response
import java.net.URI

/**
 * Native MQTT enrollment 独立 HTTPS 客户端。
 *
 * 该客户端只在显式传入后台一次性 migration/recovery grant 时调用，拒绝 cleartext；不会改写旧 NetApi、
 * AppConfig 的 MQTT password 或 VMMqtt 连接。HMAC 仅进入 NativeIdentityStore 后立即确认，日志不记录响应体。
 */
object NativeEnrollmentHttpClient {
    data class Result(val success: Boolean, val reason: String)

    /**
     * 恢复因进程退出或网络失败中断的 confirm。
     *
     * <p>该入口只会重建 receipt 并调用 confirm，绝不重放 complete；失败时保留 pending 元数据，
     * 以便下一次启动或网络恢复后继续尝试。</p>
     */
    fun resumePendingConfirmation(callback: (Result) -> Unit) {
        val endpoint = try { nativeEndpoint() } catch (exception: IllegalStateException) { callback(Result(false, exception.message ?: "native endpoint 非法")); return }
        val pending = try { NativeIdentityStore.pendingConfirmation() } catch (exception: NativeIdentityStore.RecoveryRequired) {
            callback(Result(false, "native pending identity 不可恢复，必须申请 recovery")); return
        }
        confirm(endpoint, pending.first, pending.second, pending.third, callback)
    }

    fun enroll(migrationGrant: String, callback: (Result) -> Unit) {
        val endpoint = try { nativeEndpoint() } catch (exception: IllegalStateException) { callback(Result(false, exception.message ?: "native endpoint 非法")); return }
        val startPayload = JSONObject().apply {
            this["migrationGrant"] = migrationGrant
            this["publicKeySpkiB64Url"] = NativeIdentityStore.publicKeySpkiB64Url()
        }
        OkGo.post<String>("$endpoint/start").upJson(startPayload.toJSONString()).execute(object : StringCallback() {
            override fun onSuccess(response: Response<String>?) {
                val data = successfulData(response?.body()) ?: run { callback(Result(false, "native enrollment start 被拒绝")); return }
                val enrollmentId = data.getString("enrollmentId") ?: run { callback(Result(false, "native enrollment 缺 enrollmentId")); return }
                val nonce = data.getString("serverNonceB64Url") ?: run { callback(Result(false, "native enrollment 缺 nonce")); return }
                val credentialNo = data.getString("credentialNo") ?: run { callback(Result(false, "native enrollment 缺 credential")); return }
                val keyHash = data.getString("publicKeyHashB64Url") ?: run { callback(Result(false, "native enrollment 缺公钥摘要")); return }
                val expiresAt = data.getString("expiresAtRfc3339Utc") ?: run { callback(Result(false, "native enrollment 缺过期时间")); return }
                val version = data.getInteger("protocolVersion") ?: run { callback(Result(false, "native enrollment 缺协议版本")); return }
                complete(endpoint, enrollmentId, nonce, credentialNo, keyHash, expiresAt, version, callback)
            }
            override fun onError(response: Response<String>?) { callback(Result(false, "native enrollment start 网络失败")) }
        })
    }

    private fun complete(endpoint: String, enrollmentId: String, nonce: String, credentialNo: String, keyHash: String, expiresAt: String, version: Int, callback: (Result) -> Unit) {
        val payload = JSONObject().apply {
            this["enrollmentId"] = enrollmentId
            this["signatureB64Url"] = NativeIdentityStore.enrollmentProof(enrollmentId, nonce, credentialNo, keyHash, expiresAt, version)
        }
        OkGo.post<String>("$endpoint/complete").upJson(payload.toJSONString()).execute(object : StringCallback() {
            override fun onSuccess(response: Response<String>?) {
                val data = successfulData(response?.body()) ?: run { callback(Result(false, "native enrollment complete 被拒绝")); return }
                val keyId = data.getString("keyId") ?: run { callback(Result(false, "native enrollment 缺 keyId")); return }
                val hmac = data.getString("hmacKeyB64Url") ?: run { callback(Result(false, "native enrollment 缺 HMAC")); return }
                try {
                    NativeIdentityStore.installPending(credentialNo, keyId, hmac, enrollmentId, keyHash)
                    confirm(endpoint, enrollmentId, keyId, keyHash, callback)
                } catch (exception: NativeIdentityStore.RecoveryRequired) {
                    callback(Result(false, "native identity 本地封装失败，必须申请 recovery"))
                }
            }
            override fun onError(response: Response<String>?) { callback(Result(false, "native enrollment complete 网络失败；不得重试 complete")) }
        })
    }

    private fun confirm(endpoint: String, enrollmentId: String, keyId: String, keyHash: String, callback: (Result) -> Unit) {
        val payload = try {
            JSONObject().apply {
                this["enrollmentId"] = enrollmentId
                this["receiptB64Url"] = NativeIdentityStore.receipt(enrollmentId, keyId, keyHash)
            }
        } catch (exception: NativeIdentityStore.RecoveryRequired) {
            callback(Result(false, "native pending identity 不可恢复，必须申请 recovery")); return
        }
        OkGo.post<String>("$endpoint/confirm").upJson(payload.toJSONString()).execute(object : StringCallback() {
            override fun onSuccess(response: Response<String>?) {
                if (successfulData(response?.body()) == null) callback(Result(false, "native enrollment confirm 被拒绝；保留 pending 等待 recovery"))
                else try { NativeIdentityStore.markConfirmed(); callback(Result(true, "native enrollment 已确认")) }
                catch (exception: NativeIdentityStore.RecoveryRequired) { callback(Result(false, "native enrollment 已确认但本地状态清理失败，必须申请 recovery")) }
            }
            override fun onError(response: Response<String>?) { callback(Result(false, "native enrollment confirm 网络失败；仅可重试 confirm")) }
        })
    }

    private fun nativeEndpoint(): String {
        val appBase = AppConfig.normalizeApiBaseUrl(AppConfig.getAppConfig().apiBaseUrl).trimEnd('/')
        val marker = "/api/app/device"
        require(appBase.endsWith(marker)) { "native endpoint 无法从 app API 地址推导" }
        val endpoint = appBase.removeSuffix(marker) + "/api/device/native-enrollment"
        require(URI(endpoint).scheme.equals("https", ignoreCase = true)) { "native enrollment 只允许 HTTPS" }
        return endpoint
    }

    private fun successfulData(body: String?): JSONObject? = try {
        val root = JSON.parseObject(body)
        if (root.getInteger("code") == 0) root.getJSONObject("data") else null
    } catch (_: Exception) { null }
}
