package cn.niuyannet.kaochang.android.mqtt

import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.net.DataManagementAPI
import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupTelemetry
import cn.niuyannet.kaochang.android.utils.LogUtils
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import cn.niuyannet.kaochang.android.init.AppConfigBean

object SendServerHelper {
    private const val RUNTIME_STATE_ACK_TIMEOUT_MS = 8_000L
    private const val AVAILABLE_STATE_ACK_TIMEOUT_MS = 8_000L

    private val runtimeStateSeq = AtomicLong(System.currentTimeMillis())
    private val runtimeStateAckHandler = Handler(Looper.getMainLooper())
    private val pendingRuntimeStateAcks = ConcurrentHashMap<Long, RuntimeStatePublishSnapshot>()
    private val runtimeStateHttpFallbackFired = ConcurrentHashMap<Long, Boolean>()
    private val availableStateSeq = AtomicLong(System.currentTimeMillis())
    private val availableStateAckHandler = Handler(Looper.getMainLooper())
    private val pendingAvailableStateAcks = ConcurrentHashMap<Long, AvailableStatePublishSnapshot>()
    @Volatile
    private var lastPublishedRuntimeStateSeq: Long = -1L
    @Volatile
    private var lastAckedRuntimeStateSeq: Long = -1L
    @Volatile
    private var lastPublishedAvailableStateSeq: Long = -1L
    @Volatile
    private var lastAckedAvailableStateSeq: Long = -1L
    @Volatile
    private var activeRuntimeStateSignature: String? = null
    @Volatile
    private var activeRuntimeStateSeq: Long = -1L
    @Volatile
    private var lastRuntimeStateHttpFallbackAtMs: Long = 0L

    data class RuntimeControlAckPayload(
        val controlSeq: Long,
        val applied: Boolean,
        val reason: String,
        val onlineStatus: Int,
        val supplyStatus: Int,
        val scanBlockStatus: Int,
        val inspectionMode: Int,
        val isEnable: Int,
        val restStatusSource: String?,
        val configVersion: Long? = null,
        val appliedFields: List<String> = emptyList(),
        val rejectedFields: List<String> = emptyList(),
        val normalizedFields: Map<String, Int> = emptyMap()
    )

    data class ConcurrencyControlAckPayload(
        val controlSeq: Long,
        val applied: Boolean,
        val changed: Boolean,
        val reason: String,
        val modeType: Int,
        val transitionMode: Int
    )

    data class RuntimeStatePublishSnapshot(
        val runtimeSeq: Long,
        val source: String,
        val status: Int,
        val onlineStatus: Int,
        val supplyStatus: Int,
        val scanBlockStatus: Int,
        val inspectionMode: Int,
        val isEnable: Int,
        val modeType: Int,
        val transitionMode: Int,
        val highConcurrencyResidualDrain: Int,
        val moveStatus: Int,
        val errorStatus: Int,
        val restStatusSource: String?,
        val stateSignature: String,
        val publishedAt: Long
    )

    data class RuntimeStateAckResult(
        val freshAck: Boolean,
        val snapshot: RuntimeStatePublishSnapshot?,
        val ackLatencyMs: Long?
    )

    data class AvailableStateAckPayload(
        val availableSeq: Long,
        val applied: Boolean,
        val reason: String,
        val available: Long,
        val availableCountdownLatched: Int
    )

    data class AvailableStatePublishSnapshot(
        val availableSeq: Long,
        val source: String,
        val available: Long,
        val availableCountdownLatched: Int,
        val publishedAt: Long
    )

    data class AvailableStateAckResult(
        val freshAck: Boolean,
        val snapshot: AvailableStatePublishSnapshot?,
        val ackLatencyMs: Long?
    )

    /**
     * 通过 MQTT 上报订单动作事件（action=1）。
     * `status（订单事件状态）` 的含义仍保持不变：
     * - 1 = 开始夹取
     * - 2 = 单次履约完成
     * - 5 = 单次履约失败
     */
    fun publishServiceUpdateOrder(msgObj: JSONObject): Boolean {
        return VMMqttHelper.publishService(
            TopicMessage().apply {
                action = MqttProtocol.ACTION_ORDER_EVENT
                content = JSON.toJSONString(msgObj)
            }
        )
    }

    /**
     * 通过 MQTT 上报设备运行时状态（action=2）。
     * 当前第一批收口的主字段：
     * - onlineStatus（设备营业状态）
     * - isEnable（烤肠算法开关）
     *
     * 仍保留必要运行态字段，便于服务端继续复用现有消费逻辑。
     */
    fun publishServiceUpdateStatus(source: String = "runtime_state:auto"): Boolean {
        val config = AppConfig.getAppConfig()
        val now = System.currentTimeMillis()
        val stateSignature = buildRuntimeStateSignature(config)
        val (seq, reusedSeq) = resolveRuntimeStateSeq(stateSignature)
        val payload = JSONObject()
        payload["messageType"] = MqttProtocol.TYPE_RUNTIME_STATE
        payload["deviceCode"] = AppConfig.getDeviceId()
        payload["runtimeSeq"] = seq
        payload["status"] = config.status
        payload["onlineStatus"] = config.onlineStatus
        payload["supplyStatus"] = config.supplyStatus
        payload["scanBlockStatus"] = config.scanBlockStatus
        payload["inspectionMode"] = config.inspectionMode
        payload["isEnable"] = AppConfig.getAlgorithmEnableFlag()
        payload["modeType"] = config.modeType
        payload["transitionMode"] = config.transitionMode
        payload["highConcurrencyResidualDrain"] = config.highConcurrencyResidualDrain
        payload["moveStatus"] = config.moveStatus
        payload["errorStatus"] = config.errorStatus
        payload["foodSafetyAlertCode"] = config.foodSafetyAlertCode ?: ""
        payload["foodSafetyAlertReason"] = config.foodSafetyAlertReason ?: ""
        payload["pendingDiscardPositions"] = config.pendingDiscardPositions
        if (config.errorStatus != 0) {
            config.errorCode?.takeIf { it.isNotBlank() }?.let { payload["errorCode"] = it }
            config.errorCategory?.takeIf { it.isNotBlank() }?.let { payload["errorCategory"] = it }
            config.errorReason?.takeIf { it.isNotBlank() }?.let { payload["errorReason"] = it }
        }
        payload["restStatusSource"] = config.restStatusSource
        payload["source"] = source
        payload["reportedAt"] = now
        val ok = VMMqttHelper.publishService(
            TopicMessage().apply {
                action = MqttProtocol.ACTION_SERVICE_STATE
                content = JSON.toJSONString(payload)
            }
        )
        if (ok) {
            lastPublishedRuntimeStateSeq = seq
            val snapshot = RuntimeStatePublishSnapshot(
                runtimeSeq = seq,
                source = source,
                status = config.status,
                onlineStatus = config.onlineStatus,
                supplyStatus = config.supplyStatus,
                scanBlockStatus = config.scanBlockStatus,
                inspectionMode = config.inspectionMode,
                isEnable = AppConfig.getAlgorithmEnableFlag(),
                modeType = config.modeType,
                transitionMode = config.transitionMode,
                highConcurrencyResidualDrain = config.highConcurrencyResidualDrain,
                moveStatus = config.moveStatus,
                errorStatus = config.errorStatus,
                restStatusSource = config.restStatusSource,
                stateSignature = stateSignature,
                publishedAt = now
            )
            pendingRuntimeStateAcks[seq] = snapshot
            scheduleRuntimeStateAckTimeout(snapshot)
            LogUtils.d(
                "【MQTT运行态】已上报运行时状态：" +
                    "runtimeSeq=$seq，reusedSeq=$reusedSeq，source=$source，status=${config.status}，" +
                    "onlineStatus=${config.onlineStatus}，supplyStatus=${config.supplyStatus}，" +
                    "scanBlockStatus=${config.scanBlockStatus}，inspectionMode=${config.inspectionMode}，" +
                    "isEnable=${AppConfig.getAlgorithmEnableFlag()}，" +
                    "modeType=${config.modeType}，transitionMode=${config.transitionMode}，" +
                    "highConcurrencyResidualDrain（高并发残留消化态）=${config.highConcurrencyResidualDrain}，" +
                    "moveStatus=${config.moveStatus}，errorStatus=${config.errorStatus}，" +
                    "restStatusSource=${config.restStatusSource}"
            )
            if (!reusedSeq) {
                LogUtils.i("【云端同步】设备状态已上报：${onlineStatusLabel(config.onlineStatus)}")
            }
        } else {
            LogUtils.w(
                "【云端同步】设备状态上报失败：状态=${onlineStatusLabel(config.onlineStatus)}，将等待下一次重试"
            )
        }
        return ok
    }

    /**
     * 通过 MQTT 上报首页展示相关状态（action=2）。
     * 当前只收口：
     * - available（二维码页预计时间）
     * - availableCountdownLatched（二维码页预计时间锁存标记）
     */
    fun publishAvailableState(source: String): Boolean {
        val config = AppConfig.getAppConfig()
        val seq = availableStateSeq.incrementAndGet()
        val now = System.currentTimeMillis()
        val payload = JSONObject()
        payload["messageType"] = MqttProtocol.TYPE_AVAILABLE_STATE
        payload["deviceCode"] = AppConfig.getDeviceId()
        payload["availableSeq"] = seq
        payload["available"] = config.available
        payload["availableCountdownLatched"] = config.availableCountdownLatched
        payload["onlineStatus"] = config.onlineStatus
        payload["status"] = config.status
        payload["source"] = source
        payload["reportedAt"] = now
        val ok = VMMqttHelper.publishService(
            TopicMessage().apply {
                action = MqttProtocol.ACTION_SERVICE_STATE
                content = JSON.toJSONString(payload)
            }
        )
        if (ok) {
            lastPublishedAvailableStateSeq = seq
            val snapshot = AvailableStatePublishSnapshot(
                availableSeq = seq,
                source = source,
                available = config.available,
                availableCountdownLatched = config.availableCountdownLatched,
                publishedAt = now
            )
            pendingAvailableStateAcks[seq] = snapshot
            scheduleAvailableStateAckTimeout(snapshot)
            LogUtils.d(
                "【MQTT展示态】已上报首页展示态：" +
                    "availableSeq=$seq，source=$source，available=${config.available}，" +
                    "availableCountdownLatched=${config.availableCountdownLatched}，reportedAt=$now"
            )
        } else {
            LogUtils.w(
                "【MQTT展示态】首页展示态上报失败：" +
                    "source=$source，available=${config.available}，" +
                    "availableCountdownLatched=${config.availableCountdownLatched}"
            )
        }
        return ok
    }

    /**
     * 回执远端运行时控制（action=2）。
     * ack 仅确认“是否应用了这条 controlSeq”，不承担设备状态同步职责。
     */
    fun publishRuntimeControlAck(ack: RuntimeControlAckPayload): Boolean {
        val payload = JSONObject()
        payload["messageType"] = MqttProtocol.TYPE_RUNTIME_CONTROL_ACK
        payload["deviceCode"] = AppConfig.getDeviceId()
        payload["controlSeq"] = ack.controlSeq
        payload["applied"] = ack.applied
        payload["reason"] = ack.reason
        payload["onlineStatus"] = ack.onlineStatus
        payload["supplyStatus"] = ack.supplyStatus
        payload["scanBlockStatus"] = ack.scanBlockStatus
        payload["inspectionMode"] = ack.inspectionMode
        payload["isEnable"] = ack.isEnable
        payload["restStatusSource"] = ack.restStatusSource
        ack.configVersion?.let { payload["configVersion"] = it }
        if (ack.appliedFields.isNotEmpty()) {
            payload["appliedFields"] = ack.appliedFields
        }
        if (ack.rejectedFields.isNotEmpty()) {
            payload["rejectedFields"] = ack.rejectedFields
        }
        if (ack.normalizedFields.isNotEmpty()) {
            payload["normalizedFields"] = ack.normalizedFields
        }
        payload["ackTs"] = System.currentTimeMillis()
        return VMMqttHelper.publishService(
            TopicMessage().apply {
                action = MqttProtocol.ACTION_SERVICE_STATE
                content = JSON.toJSONString(payload)
            }
        )
    }

    fun publishConcurrencyControlAck(ack: ConcurrencyControlAckPayload): Boolean {
        val payload = JSONObject()
        payload["messageType"] = MqttProtocol.TYPE_CONCURRENCY_CONTROL_ACK
        payload["deviceCode"] = AppConfig.getDeviceId()
        payload["controlSeq"] = ack.controlSeq
        payload["applied"] = ack.applied
        payload["changed"] = ack.changed
        payload["reason"] = ack.reason
        payload["modeType"] = ack.modeType
        payload["transitionMode"] = ack.transitionMode
        payload["ackTs"] = System.currentTimeMillis()
        return VMMqttHelper.publishService(
            TopicMessage().apply {
                action = MqttProtocol.ACTION_SERVICE_STATE
                content = JSON.toJSONString(payload)
            }
        )
    }

    fun publishActionGroupTelemetry(telemetry: ActionGroupTelemetry): Boolean {
        val payload = JSONObject()
        payload["messageType"] = MqttProtocol.TYPE_ACTION_GROUP_TELEMETRY
        payload["deviceCode"] = AppConfig.getDeviceId()
        payload["reportedAt"] = System.currentTimeMillis()
        payload["actionProtocolVersion"] = telemetry.actionProtocolVersion
        payload["sessionId"] = telemetry.sessionId
        payload["actionName"] = telemetry.actionName
        payload["registerAddress"] = telemetry.registerAddress
        payload["resultRegister"] = telemetry.resultRegister
        payload["errorCode"] = telemetry.errorCode
        payload["errorMessage"] = telemetry.errorMessage
        payload["boxNo"] = telemetry.boxNo
        payload["panNo"] = telemetry.panNo
        payload["orderId"] = telemetry.orderId
        payload["resourceLocks"] = telemetry.resourceLocks
        payload["elapsedMs"] = telemetry.elapsedMs
        val ok = VMMqttHelper.publishService(
            TopicMessage().apply {
                action = MqttProtocol.ACTION_DEVICE_LOG
                content = JSON.toJSONString(payload)
            }
        )
        if (ok) {
            LogUtils.d(
                "【300/400动作组】已上报结构化动作日志：" +
                    "sessionId=${telemetry.sessionId}，actionName=${telemetry.actionName}，" +
                    "errorCode=${telemetry.errorCode}，elapsedMs=${telemetry.elapsedMs}"
            )
        } else {
            LogUtils.w(
                "【300/400动作组】结构化动作日志上报失败：" +
                    "sessionId=${telemetry.sessionId}，actionName=${telemetry.actionName}，errorCode=${telemetry.errorCode}"
            )
        }
        return ok
    }

    private fun scheduleRuntimeStateAckTimeout(snapshot: RuntimeStatePublishSnapshot) {
        runtimeStateAckHandler.postDelayed({
            val pending = pendingRuntimeStateAcks[snapshot.runtimeSeq] ?: return@postDelayed
            if (pending.publishedAt != snapshot.publishedAt) {
                return@postDelayed
            }
            if (pending.runtimeSeq <= lastAckedRuntimeStateSeq) {
                pendingRuntimeStateAcks.remove(snapshot.runtimeSeq)
                return@postDelayed
            }
            val ageMs = System.currentTimeMillis() - pending.publishedAt
            LogUtils.w(
                "【云端同步】设备状态上报后仍未收到云端确认：状态=${onlineStatusLabel(pending.onlineStatus)}，" +
                    "等待=${ageMs}ms，将继续保留本地状态"
            )
            triggerRuntimeStateHttpFallbackIfNeeded(pending)
        }, RUNTIME_STATE_ACK_TIMEOUT_MS)
    }

    private fun triggerRuntimeStateHttpFallbackIfNeeded(snapshot: RuntimeStatePublishSnapshot) {
        if (runtimeStateHttpFallbackFired.putIfAbsent(snapshot.runtimeSeq, true) != null) {
            return
        }
        val now = System.currentTimeMillis()
        val minIntervalMs = AppConfig.getAppConfig().httpFallbackMinIntervalMs.takeIf { it > 0L } ?: 30_000L
        if (now - lastRuntimeStateHttpFallbackAtMs < minIntervalMs) {
            LogUtils.w(
                "【云端同步】runtime_state ACK 超时 HTTP 兜底被限频跳过：" +
                    "runtimeSeq=${snapshot.runtimeSeq}，source=${snapshot.source}，minIntervalMs=$minIntervalMs"
            )
            return
        }
        lastRuntimeStateHttpFallbackAtMs = now
        LogUtils.w(
            "【云端同步】runtime_state ACK 超时，触发 HTTP 兜底上传设备快照：" +
                "runtimeSeq=${snapshot.runtimeSeq}，source=${snapshot.source}"
        )
        DataManagementAPI.uploadDeviceInfo()
    }

    private fun scheduleAvailableStateAckTimeout(snapshot: AvailableStatePublishSnapshot) {
        availableStateAckHandler.postDelayed({
            val pending = pendingAvailableStateAcks[snapshot.availableSeq] ?: return@postDelayed
            if (pending.availableSeq <= lastAckedAvailableStateSeq) {
                pendingAvailableStateAcks.remove(snapshot.availableSeq)
                return@postDelayed
            }
            val ageMs = System.currentTimeMillis() - pending.publishedAt
            LogUtils.w(
                "【MQTT展示态】首页展示态上报后仍未收到服务端确认：" +
                    "availableSeq=${pending.availableSeq}，source=${pending.source}，" +
                    "available=${pending.available}，availableCountdownLatched=${pending.availableCountdownLatched}，ageMs=$ageMs"
            )
        }, AVAILABLE_STATE_ACK_TIMEOUT_MS)
    }

    fun markAvailableStateAcked(availableSeq: Long): AvailableStateAckResult {
        if (availableSeq <= 0L) {
            return AvailableStateAckResult(
                freshAck = false,
                snapshot = null,
                ackLatencyMs = null
            )
        }
        val snapshot = pendingAvailableStateAcks.remove(availableSeq)
        val ackLatencyMs = snapshot?.let { System.currentTimeMillis() - it.publishedAt }
        val freshAck = if (availableSeq > lastAckedAvailableStateSeq) {
            lastAckedAvailableStateSeq = availableSeq
            true
        } else {
            false
        }
        return AvailableStateAckResult(
            freshAck = freshAck,
            snapshot = snapshot,
            ackLatencyMs = ackLatencyMs
        )
    }

    fun markRuntimeStateAcked(runtimeSeq: Long): RuntimeStateAckResult {
        if (runtimeSeq <= 0L) {
            return RuntimeStateAckResult(
                freshAck = false,
                snapshot = null,
                ackLatencyMs = null
            )
        }
        val snapshot = pendingRuntimeStateAcks.remove(runtimeSeq)
        runtimeStateHttpFallbackFired.remove(runtimeSeq)
        val ackLatencyMs = snapshot?.let { System.currentTimeMillis() - it.publishedAt }
        val freshAck = if (runtimeSeq > lastAckedRuntimeStateSeq) {
            lastAckedRuntimeStateSeq = runtimeSeq
            true
        } else {
            false
        }
        return RuntimeStateAckResult(
            freshAck = freshAck,
            snapshot = snapshot,
            ackLatencyMs = ackLatencyMs
        )
    }

    private fun buildRuntimeStateSignature(config: AppConfigBean): String {
        return RuntimeStateSignatureBuilder.build(config)
    }

    private fun resolveRuntimeStateSeq(stateSignature: String): Pair<Long, Boolean> {
        synchronized(pendingRuntimeStateAcks) {
            if (activeRuntimeStateSeq > 0L && stateSignature == activeRuntimeStateSignature) {
                return activeRuntimeStateSeq to true
            }
            val nextSeq = runtimeStateSeq.incrementAndGet()
            activeRuntimeStateSignature = stateSignature
            activeRuntimeStateSeq = nextSeq
            return nextSeq to false
        }
    }

    fun lastPublishedRuntimeStateSeq(): Long = lastPublishedRuntimeStateSeq
    fun lastPublishedAvailableStateSeq(): Long = lastPublishedAvailableStateSeq

    private fun onlineStatusLabel(value: Int): String = when (value) {
        0 -> "未启用/离线"
        1 -> "运营中"
        2 -> "休息中"
        3 -> "维护中"
        4 -> "工作中/正在出餐"
        else -> "$value（未知）"
    }
}
