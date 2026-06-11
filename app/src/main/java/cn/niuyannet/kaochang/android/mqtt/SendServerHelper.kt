package cn.niuyannet.kaochang.android.mqtt

import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.net.DataManagementAPI
import cn.niuyannet.kaochang.android.services.SourceBoxPositionPolicy
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
    private const val CONSUMABLE_EVENT_ACK_TIMEOUT_MS = 8_000L

    private val runtimeStateSeq = AtomicLong(System.currentTimeMillis())
    private val runtimeStateAckHandler = Handler(Looper.getMainLooper())
    private val pendingRuntimeStateAcks = ConcurrentHashMap<Long, RuntimeStatePublishSnapshot>()
    private val runtimeStateHttpFallbackFired = ConcurrentHashMap<Long, Boolean>()
    private val availableStateSeq = AtomicLong(System.currentTimeMillis())
    private val availableStateAckHandler = Handler(Looper.getMainLooper())
    private val pendingAvailableStateAcks = ConcurrentHashMap<Long, AvailableStatePublishSnapshot>()
    private val consumableEventAckHandler = Handler(Looper.getMainLooper())
    private val pendingConsumableEventAcks = ConcurrentHashMap<String, ConsumableEventPublishSnapshot>()
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
        val onlineStatus: Int,
        val supplyStatus: Int,
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

    data class ConsumableEventPublishSnapshot(
        val event: ConsumableEvent,
        val publishedAt: Long
    )

    data class ConsumableEventAckResult(
        val freshAck: Boolean,
        val snapshot: ConsumableEventPublishSnapshot?,
        val ackLatencyMs: Long?
    )

    data class AppUpdateCheckResultPayload(
        val triggerId: String,
        val success: Boolean,
        val hasUpdate: Boolean,
        val targetVersionCode: Int?,
        val targetVersionName: String?,
        val forceUpdate: Boolean,
        val reason: String
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
            },
            priority = MqttPublishPriority.HIGH
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
        payload["onlineStatus"] = config.onlineStatus
        payload["supplyStatus"] = config.supplyStatus
        payload["inspectionMode"] = config.inspectionMode
        payload["isEnable"] = AppConfig.getAlgorithmEnableFlag()
        payload["modeType"] = config.modeType
        payload["transitionMode"] = config.transitionMode
        payload["highConcurrencyResidualDrain"] = config.highConcurrencyResidualDrain
        payload["moveStatus"] = config.moveStatus
        payload["errorStatus"] = config.errorStatus
        payload["sourceBoxPolicyVersion"] = SourceBoxPositionPolicy.POLICY_VERSION
        payload["validSourceBoxRanges"] = SourceBoxPositionPolicy.validRangePayloadText()
        payload["disabledSourceBoxes"] = SourceBoxPositionPolicy.disabledBoxes()
        payload["foodSafetyAlertCode"] = RuntimeStateSignatureBuilder.reportableFoodSafetyAlertCode(config)
        payload["foodSafetyAlertReason"] = RuntimeStateSignatureBuilder.reportableFoodSafetyAlertReason(config)
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
            },
            priority = MqttPublishPriority.NORMAL
        )
        if (ok) {
            lastPublishedRuntimeStateSeq = seq
            val snapshot = RuntimeStatePublishSnapshot(
                runtimeSeq = seq,
                source = source,
                onlineStatus = config.onlineStatus,
                supplyStatus = config.supplyStatus,
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
                "【MQTT运行态】运行时状态已进入 MQTT 发送队列：" +
                    "runtimeSeq=$seq，reusedSeq=$reusedSeq，source=$source，" +
                    "onlineStatus=${config.onlineStatus}，supplyStatus=${config.supplyStatus}，" +
                    "inspectionMode=${config.inspectionMode}，" +
                    "isEnable=${AppConfig.getAlgorithmEnableFlag()}，" +
                    "modeType=${config.modeType}，transitionMode=${config.transitionMode}，" +
                    "highConcurrencyResidualDrain（高并发残留消化态）=${config.highConcurrencyResidualDrain}，" +
                    "moveStatus=${config.moveStatus}，errorStatus=${config.errorStatus}，" +
                    "restStatusSource=${config.restStatusSource}"
            )
            if (!reusedSeq) {
                LogUtils.i("【云端同步】设备状态已进入 MQTT 发送队列：${onlineStatusLabel(config.onlineStatus)}")
            }
        } else {
            LogUtils.w(
                "【云端同步】设备状态进入 MQTT 发送队列失败：状态=${onlineStatusLabel(config.onlineStatus)}，将等待下一次重试"
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
        payload["source"] = source
        payload["reportedAt"] = now
        val ok = VMMqttHelper.publishService(
            TopicMessage().apply {
                action = MqttProtocol.ACTION_SERVICE_STATE
                content = JSON.toJSONString(payload)
            },
            priority = MqttPublishPriority.LOW
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
                "【MQTT展示态】首页展示态已进入 MQTT 发送队列：" +
                    "availableSeq=$seq，source=$source，available=${config.available}，" +
                    "availableCountdownLatched=${config.availableCountdownLatched}，reportedAt=$now"
            )
        } else {
            LogUtils.w(
                "【MQTT展示态】首页展示态进入 MQTT 发送队列失败：" +
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
            },
            priority = MqttPublishPriority.HIGH
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
            },
            priority = MqttPublishPriority.HIGH
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
            },
            priority = MqttPublishPriority.LOW
        )
        if (ok) {
            LogUtils.d(
                "【300/400动作组】结构化动作日志已进入 MQTT 发送队列：" +
                    "sessionId=${telemetry.sessionId}，actionName=${telemetry.actionName}，" +
                    "errorCode=${telemetry.errorCode}，elapsedMs=${telemetry.elapsedMs}"
            )
        } else {
            LogUtils.w(
                "【300/400动作组】结构化动作日志进入 MQTT 发送队列失败：" +
                    "sessionId=${telemetry.sessionId}，actionName=${telemetry.actionName}，errorCode=${telemetry.errorCode}"
            )
        }
        return ok
    }

    fun publishIllegalSourceBoxBlocked(sourceBoxNo: Int, targetPanNo: Int?, reason: String): Boolean {
        val payload = JSONObject()
        payload["messageType"] = MqttProtocol.TYPE_ILLEGAL_SOURCE_BOX_BLOCKED
        payload["deviceCode"] = AppConfig.getDeviceId()
        payload["eventType"] = "ILLEGAL_SOURCE_BOX_BLOCKED"
        payload["severity"] = "FATAL"
        payload["reportedAt"] = System.currentTimeMillis()
        payload["sourceBoxNo"] = sourceBoxNo
        targetPanNo?.let { payload["targetPanNo"] = it }
        payload["sourceBoxPolicyVersion"] = SourceBoxPositionPolicy.POLICY_VERSION
        payload["validSourceBoxRanges"] = SourceBoxPositionPolicy.validRangePayloadText()
        payload["disabledSourceBoxes"] = SourceBoxPositionPolicy.disabledBoxes()
        payload["reason"] = reason
        val ok = VMMqttHelper.publishService(
            TopicMessage().apply {
                action = MqttProtocol.ACTION_DEVICE_LOG
                content = JSON.toJSONString(payload)
            },
            priority = MqttPublishPriority.HIGH
        )
        if (ok) {
            LogUtils.e("【补肠安全】非法源肠箱拦截事件已进入 MQTT 发送队列：sourceBox=$sourceBoxNo，targetPan=$targetPanNo，reason=$reason")
        } else {
            LogUtils.e("【补肠安全】非法源肠箱拦截事件进入 MQTT 发送队列失败：sourceBox=$sourceBoxNo，targetPan=$targetPanNo，reason=$reason")
        }
        return ok
    }

    fun publishConsumableEvent(event: ConsumableEvent): Boolean {
        val payload = ConsumableEventPayloadBuilder.buildPayload(event)
        val ok = VMMqttHelper.publishService(
            TopicMessage().apply {
                action = MqttProtocol.ACTION_SERVICE_STATE
                content = JSON.toJSONString(payload)
            },
            priority = MqttPublishPriority.HIGH
        )
        if (ok) {
            val snapshot = ConsumableEventPublishSnapshot(event, System.currentTimeMillis())
            pendingConsumableEventAcks[event.sourceEventKey] = snapshot
            scheduleConsumableEventAckTimeout(snapshot)
            LogUtils.i(
                "【耗材事件】耗材事件已进入 MQTT 发送队列：" +
                    "sourceEventKey=${event.sourceEventKey}，eventType=${event.eventType}，delta=${event.deltaValue}"
            )
        } else {
            LogUtils.w(
                "【耗材事件】耗材事件进入 MQTT 队列失败，触发 HTTP 兜底：" +
                    "sourceEventKey=${event.sourceEventKey}，eventType=${event.eventType}"
            )
            DataManagementAPI.uploadConsumableEvent(event)
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

    private fun scheduleConsumableEventAckTimeout(snapshot: ConsumableEventPublishSnapshot) {
        consumableEventAckHandler.postDelayed({
            val pending = pendingConsumableEventAcks[snapshot.event.sourceEventKey] ?: return@postDelayed
            if (pending.publishedAt != snapshot.publishedAt) {
                return@postDelayed
            }
            val ageMs = System.currentTimeMillis() - pending.publishedAt
            LogUtils.w(
                "【耗材事件】耗材事件上报后仍未收到服务端确认，触发 HTTP 兜底：" +
                    "sourceEventKey=${pending.event.sourceEventKey}，eventType=${pending.event.eventType}，ageMs=$ageMs"
            )
            DataManagementAPI.uploadConsumableEvent(pending.event)
        }, CONSUMABLE_EVENT_ACK_TIMEOUT_MS)
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

    fun markConsumableEventAcked(sourceEventKey: String?): ConsumableEventAckResult {
        if (sourceEventKey.isNullOrBlank()) {
            return ConsumableEventAckResult(false, null, null)
        }
        val snapshot = pendingConsumableEventAcks.remove(sourceEventKey)
        val ackLatencyMs = snapshot?.let { System.currentTimeMillis() - it.publishedAt }
        return ConsumableEventAckResult(snapshot != null, snapshot, ackLatencyMs)
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
    fun lastAckedRuntimeStateSeq(): Long = lastAckedRuntimeStateSeq
    fun pendingRuntimeAckCount(): Int = pendingRuntimeStateAcks.size
    fun pendingAvailableAckCount(): Int = pendingAvailableStateAcks.size

    fun publishAppUpgradeNotice(
        currentVersionCode: Int,
        currentVersionName: String,
        targetVersionCode: Int,
        targetVersionName: String,
        forceUpdate: Boolean,
        reason: String,
        triggerId: String? = null,
        rawPayload: String? = null
    ): Boolean {
        val payload = JSONObject()
        payload["messageType"] = "app_upgrade_notice"
        payload["deviceCode"] = AppConfig.getDeviceId()
        triggerId?.let { payload["triggerId"] = it }
        payload["currentVersionCode"] = currentVersionCode
        payload["currentVersionName"] = currentVersionName
        payload["targetVersionCode"] = targetVersionCode
        payload["targetVersionName"] = targetVersionName
        payload["forceUpdate"] = forceUpdate
        payload["reason"] = reason
        rawPayload?.let { payload["rawPayload"] = it }
        payload["reportedAt"] = System.currentTimeMillis()
        return VMMqttHelper.publishService(
            TopicMessage().apply {
                action = MqttProtocol.ACTION_DEVICE_LOG
                content = JSON.toJSONString(payload)
            },
            priority = MqttPublishPriority.HIGH
        )
    }

    fun publishAppUpdateCheckAck(triggerId: String, strategyId: Long?, reason: String): Boolean {
        val payload = JSONObject()
        payload["messageType"] = MqttProtocol.TYPE_APP_UPDATE_CHECK_ACK
        payload["deviceCode"] = AppConfig.getDeviceId()
        payload["triggerId"] = triggerId
        strategyId?.let { payload["strategyId"] = it }
        payload["reason"] = reason
        payload["ackTs"] = System.currentTimeMillis()
        return VMMqttHelper.publishService(
            TopicMessage().apply {
                action = MqttProtocol.ACTION_SERVICE_STATE
                content = JSON.toJSONString(payload)
            },
            priority = MqttPublishPriority.HIGH
        )
    }

    fun publishAppUpdateCheckResult(result: AppUpdateCheckResultPayload): Boolean {
        val payload = JSONObject()
        payload["messageType"] = MqttProtocol.TYPE_APP_UPDATE_CHECK_RESULT
        payload["deviceCode"] = AppConfig.getDeviceId()
        payload["triggerId"] = result.triggerId
        payload["success"] = result.success
        payload["hasUpdate"] = result.hasUpdate
        result.targetVersionCode?.let { payload["targetVersionCode"] = it }
        result.targetVersionName?.let { payload["targetVersionName"] = it }
        payload["forceUpdate"] = result.forceUpdate
        payload["reason"] = result.reason
        payload["reportedAt"] = System.currentTimeMillis()
        return VMMqttHelper.publishService(
            TopicMessage().apply {
                action = MqttProtocol.ACTION_SERVICE_STATE
                content = JSON.toJSONString(payload)
            },
            priority = MqttPublishPriority.HIGH
        )
    }

    private fun onlineStatusLabel(value: Int): String = when (value) {
        0 -> "待投运"
        1 -> "运营中"
        2 -> "休息中"
        3 -> "维护中"
        4 -> "工作中/正在出餐"
        else -> "$value（未知）"
    }
}
