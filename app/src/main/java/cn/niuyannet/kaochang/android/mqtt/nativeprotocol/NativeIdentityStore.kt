package cn.niuyannet.kaochang.android.mqtt.nativeprotocol

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import cn.niuyannet.kaochang.android.MyApp
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Native MQTT 设备身份与密钥本地存储。
 *
 * P-256 私钥和 AES-GCM 包装密钥都不可导出地保存在 AndroidKeyStore；普通偏好仅保存密文、nonce、keyId
 * 和 credentialNo。该对象不读取 AppConfig，也不参与旧 VMMqtt 的 TCP/password 兼容链。
 */
object NativeIdentityStore {
    private const val PREFS = "CHEERLUCK_NATIVE_MQTT_IDENTITY"
    private const val SIGNING_ALIAS = "cheerluck_native_signing_p256_v1"
    private const val WRAPPING_ALIAS = "cheerluck_native_wrapping_aes_v1"
    private const val KEY_CIPHERTEXT = "sealed_identity"
    private const val KEY_NONCE = "sealed_identity_nonce"
    private const val KEY_KEY_ID = "key_id"
    private const val KEY_CREDENTIAL_NO = "credential_no"
    private const val KEY_PENDING_ENROLLMENT_ID = "pending_enrollment_id"
    private const val KEY_PENDING_PUBLIC_KEY_HASH = "pending_public_key_hash"
    private const val HMAC_BYTES = 32
    private const val PLAIN_BYTES = HMAC_BYTES + Long.SIZE_BYTES
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val b64Decoder = Base64.getUrlDecoder()

    data class BootSession(val bootCounter: Long, val bootEpoch: String)
    class RecoveryRequired(message: String) : IllegalStateException(message)

    /** 返回 AndroidKeyStore P-256 公钥的 DER/SPKI base64url；私钥始终不可导出。 */
    @Synchronized
    fun publicKeySpkiB64Url(): String = b64.encodeToString(signingKeyPair().public.encoded)

    /** 对固定 length-prefix PoP 字节签名，签名为 SHA256withECDSA DER。 */
    @Synchronized
    fun signProof(bytes: ByteArray): String {
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(signingPrivateKey())
        signature.update(bytes)
        return b64.encodeToString(signature.sign())
    }

    /** 按双边契约构造 enrollment PoP；不允许调用方自行改变字段顺序或编码。 */
    @Synchronized
    fun enrollmentProof(enrollmentId: String, serverNonceB64Url: String, credentialNo: String, publicKeyHashB64Url: String, expiresAtRfc3339Utc: String, protocolVersion: Int): String =
        signProof(lengthPrefixed("cheerluck-native-enrollment-v1", enrollmentId, serverNonceB64Url, credentialNo, publicKeyHashB64Url, expiresAtRfc3339Utc, protocolVersion.toString()))

    /** 安全落盘 enrollment 返回的 HMAC 和初始 counter；成功前不允许调用 confirm。 */
    @Synchronized
    fun installPending(credentialNo: String, keyId: String, hmacKeyB64Url: String, enrollmentId: String, publicKeyHashB64Url: String) {
        require(credentialNo.isNotBlank() && keyId.isNotBlank())
        val hmac = decodeFixed(hmacKeyB64Url)
        try {
            persist(credentialNo, keyId, hmac, 0L, enrollmentId, publicKeyHashB64Url)
        } finally {
            hmac.fill(0)
        }
    }

    /** 构造 enrollment confirm 的 HMAC receipt；调用方只能上传结果，不能取得 HMAC 明文。 */
    @Synchronized
    fun receipt(enrollmentId: String, keyId: String, publicKeyHashB64Url: String): String {
        val state = load()
        if (state.keyId != keyId) throw RecoveryRequired("native enrollment keyId 与安全存储不一致")
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(state.hmacKey, "HmacSHA256"))
            b64.encodeToString(mac.doFinal(lengthPrefixed("cheerluck-native-enrollment-receipt-v1", enrollmentId, keyId, publicKeyHashB64Url)))
        } finally {
            state.hmacKey.fill(0)
        }
    }

    /** 返回重启后 confirm 所需的持久化元数据；确认成功前不得清除。 */
    @Synchronized fun pendingConfirmation(): Triple<String, String, String> {
        val state=load(); state.hmacKey.fill(0)
        val enrollmentId=prefs().getString(KEY_PENDING_ENROLLMENT_ID,null) ?: throw RecoveryRequired("native pending enrollment 缺失")
        val publicKeyHash=prefs().getString(KEY_PENDING_PUBLIC_KEY_HASH,null) ?: throw RecoveryRequired("native pending 公钥摘要缺失")
        return Triple(enrollmentId,state.keyId,publicKeyHash)
    }
    /** 只检查持久化的 pending 元数据，启动期据此决定是否重试 confirm，不解封 HMAC。 */
    @Synchronized fun hasPendingConfirmation(): Boolean =
        prefs().contains(KEY_PENDING_ENROLLMENT_ID) && prefs().contains(KEY_PENDING_PUBLIC_KEY_HASH)
    @Synchronized fun markConfirmed() { if (!prefs().edit().remove(KEY_PENDING_ENROLLMENT_ID).remove(KEY_PENDING_PUBLIC_KEY_HASH).commit()) throw RecoveryRequired("native confirm 状态清理失败") }

    /**
     * 每次 native runtime 启动前递增并以 synchronous commit 持久化 counter。
     * commit 失败或 KeyStore 丢失时抛 RecoveryRequired，调用方必须阻断 native publish。
     */
    @Synchronized
    fun beginNativeRuntime(): BootSession {
        val state = load()
        try {
            if (state.bootCounter == Long.MAX_VALUE) throw RecoveryRequired("native bootCounter 已耗尽")
            persist(state.credentialNo, state.keyId, state.hmacKey, state.bootCounter + 1, prefs().getString(KEY_PENDING_ENROLLMENT_ID, null), prefs().getString(KEY_PENDING_PUBLIC_KEY_HASH, null))
            return BootSession(state.bootCounter + 1, java.util.UUID.randomUUID().toString())
        } finally {
            state.hmacKey.fill(0)
        }
    }

    /** KeyStore 或密文不一致时清除只读本地引用前必须由上层完成受审计 recovery，不自动降级为 legacy secret。 */
    @Synchronized
    fun requiresRecovery(): Boolean = try {
        load().also { it.hmacKey.fill(0) }; false
    } catch (_: RecoveryRequired) { true }

    private data class State(val credentialNo: String, val keyId: String, val hmacKey: ByteArray, val bootCounter: Long)

    private fun load(): State {
        val prefs = prefs()
        val ciphertext = prefs.getString(KEY_CIPHERTEXT, null) ?: throw RecoveryRequired("native enrollment 尚未完成")
        val nonce = prefs.getString(KEY_NONCE, null) ?: throw RecoveryRequired("native identity nonce 缺失")
        val credentialNo = prefs.getString(KEY_CREDENTIAL_NO, null) ?: throw RecoveryRequired("native credential 缺失")
        val keyId = prefs.getString(KEY_KEY_ID, null) ?: throw RecoveryRequired("native keyId 缺失")
        try {
            val plain = cipher(Cipher.DECRYPT_MODE, b64Decoder.decode(nonce)).doFinal(b64Decoder.decode(ciphertext))
            if (plain.size != PLAIN_BYTES) throw RecoveryRequired("native identity 密文长度非法")
            val hmac = plain.copyOfRange(0, HMAC_BYTES)
            val counter = ByteBuffer.wrap(plain, HMAC_BYTES, Long.SIZE_BYTES).long
            plain.fill(0)
            if (counter < 0) throw RecoveryRequired("native bootCounter 非法")
            return State(credentialNo, keyId, hmac, counter)
        } catch (exception: RecoveryRequired) {
            throw exception
        } catch (exception: Exception) {
            throw RecoveryRequired("native identity 无法解封，必须重新 enrollment")
        }
    }

    private fun persist(credentialNo: String, keyId: String, hmac: ByteArray, counter: Long, enrollmentId: String?, publicKeyHash: String?) {
        if (hmac.size != HMAC_BYTES || counter < 0) throw RecoveryRequired("native identity 参数非法")
        val plain = ByteBuffer.allocate(PLAIN_BYTES).put(hmac).putLong(counter).array()
        try {
            val cipher = cipher(Cipher.ENCRYPT_MODE, null)
            val ciphertext = cipher.doFinal(plain)
            val editor = prefs().edit().putString(KEY_CIPHERTEXT, b64.encodeToString(ciphertext))
                .putString(KEY_NONCE, b64.encodeToString(cipher.iv)).putString(KEY_CREDENTIAL_NO, credentialNo)
                .putString(KEY_KEY_ID, keyId)
            if (enrollmentId == null || publicKeyHash == null) editor.remove(KEY_PENDING_ENROLLMENT_ID).remove(KEY_PENDING_PUBLIC_KEY_HASH) else editor.putString(KEY_PENDING_ENROLLMENT_ID,enrollmentId).putString(KEY_PENDING_PUBLIC_KEY_HASH,publicKeyHash)
            val committed = editor.commit()
            if (!committed) throw RecoveryRequired("native identity 持久化失败")
        } finally { plain.fill(0) }
    }

    private fun signingKeyPair(): java.security.KeyPair {
        val store = keyStore()
        val certificate = store.getCertificate(SIGNING_ALIAS)
        if (certificate != null) return java.security.KeyPair(certificate.publicKey, signingPrivateKey())
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        generator.initialize(KeyGenParameterSpec.Builder(SIGNING_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256).build())
        return generator.generateKeyPair()
    }

    private fun signingPrivateKey(): PrivateKey = (keyStore().getKey(SIGNING_ALIAS, null) as? PrivateKey)
        ?: throw RecoveryRequired("native P-256 私钥缺失")
    private fun wrappingKey(): SecretKey {
        val existing = keyStore().getKey(WRAPPING_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        if (prefs().contains(KEY_CIPHERTEXT)) throw RecoveryRequired("native AES 包装密钥丢失")
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(WRAPPING_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return generator.generateKey()
    }
    private fun cipher(mode: Int, nonce: ByteArray?): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
        if (mode == Cipher.ENCRYPT_MODE) init(mode, wrappingKey()) else init(mode, wrappingKey(), GCMParameterSpec(128, nonce))
    }
    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun prefs() = MyApp.instance().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun decodeFixed(value: String): ByteArray = try { b64Decoder.decode(value).also { require(it.size == HMAC_BYTES) } } catch (_: Exception) { throw RecoveryRequired("native HMAC 长度非法") }
    private fun lengthPrefixed(vararg fields: String): ByteArray { val output = java.io.ByteArrayOutputStream(); fields.forEach { field -> val bytes=field.toByteArray(StandardCharsets.UTF_8); output.write(ByteBuffer.allocate(4).putInt(bytes.size).array()); output.write(bytes) }; return output.toByteArray() }
}
