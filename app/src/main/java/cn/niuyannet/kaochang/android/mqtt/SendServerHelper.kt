package cn.niuyannet.kaochang.android.mqtt

import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.net.DataManagementAPI
import cn.niuyannet.kaochang.android.net.ApiResponseCompat
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.services.KaoPanCloudSyncReporter
import cn.niuyannet.kaochang.android.services.LowerPlatformEpisodeReducer
import cn.niuyannet.kaochang.android.services.PanPhysicalStateDeltaReporter
import cn.niuyannet.kaochang.android.services.SellablePanPhysicalStateProjection
import cn.niuyannet.kaochang.android.services.SourceBoxPositionPolicy
import cn.niuyannet.kaochang.android.services.SnapshotSyncPolicy
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
    private const val PAN_STATE_DELTA_ACK_TIMEOUT_MS = 8_000L
    private const val CONSUMABLE_EVENT_ACK_TIMEOUT_MS = 8_000L
    private const val ACTION_GROUP_TELEMETRY_ACK_TIMEOUT_MS = 8_000L
    private const val ACTION_GROUP_TELEMETRY_RETRY_DELAY_MS = 15_000L
    private const val ACTION_GROUP_TELEMETRY_MAX_RETRY = 3
    private const val ACTION_GROUP_TELEMETRY_MAX_PENDING = 200

    private val runtimeStateSeq = AtomicLong(System.currentTimeMillis())
    private val runtimeStateAckHandler = Handler(Looper.getMainLooper())
    private val pendingRuntimeStateAcks = ConcurrentHashMap<Long, RuntimeStatePublishSnapshot>()
    private val runtimeStateAckCallbackRegistry = RuntimeStateAckCallbackRegistry()
    private val runtimeStateHttpFallbackFired = ConcurrentHashMap<Long, Boolean>()
    private val availableStateSeq = AtomicLong(System.currentTimeMillis())
    private val availableStateAckHandler = Handler(Looper.getMainLooper())
    private val pendingAvailableStateAcks = ConcurrentHashMap<Long, AvailableStatePublishSnapshot>()
    private val panStateDeltaSeq = AtomicLong(System.currentTimeMillis())
    private val panStateDeltaAckHandler = Handler(Looper.getMainLooper())
    private val pendingPanStateDeltaAcks = ConcurrentHashMap<Long, PanStateDeltaPublishSnapshot>()
    private val pendingPanPhysicalStateDeltaAcks = ConcurrentHashMap<Long, PanPhysicalStateDeltaPublishSnapshot>()
    private val sellableRulesPhysicalDeltaDeduper = SellableRulesPhysicalDeltaDeduper()
    private val consumableEventAckHandler = Handler(Looper.getMainLooper())
    private val pendingConsumableEventAcks = ConcurrentHashMap<String, ConsumableEventPublishSnapshot>()
    private val actionGroupTelemetryAckHandler = Handler(Looper.getMainLooper())
    private val pendingActionGroupTelemetryAcks = ConcurrentHashMap<String, ActionGroupTelemetryPublishSnapshot>()
    private val actionGroupTelemetryHttpFallbackInFlight = ConcurrentHashMap<String, Boolean>()
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
    @Volatile
    private var lastPanStateDeltaHttpFallbackAtMs: Long = 0L

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

    data class RuntimeStateServerAck(
        val runtimeSeq: Long,
        val received: Boolean,
        val applied: Boolean,
        val reason: String,
        val onlineStatus: Int,
        val errorStatus: Int
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

    data class PanStateDeltaPublishSnapshot(
        val panDeltaSeq: Long,
        val eventId: String,
        val reason: String,
        val source: String,
        val publishedAt: Long
    )

    data class PanStateDeltaAckResult(
        val freshAck: Boolean,
        val snapshot: PanStateDeltaPublishSnapshot?,
        val ackLatencyMs: Long?
    )

    data class PanPhysicalStateDeltaPublishSnapshot(
        val panDeltaSeq: Long,
        val eventId: String,
        val reason: String,
        val source: String,
        val publishedAt: Long
    )

    data class PanPhysicalStateDeltaAckResult(
        val freshAck: Boolean,
        val snapshot: PanPhysicalStateDeltaPublishSnapshot?,
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

    data class ActionGroupTelemetryPublishSnapshot(
        val eventId: String,
        val payload: JSONObject,
        val sessionId: String,
        val actionName: String,
        val eventPhase: String,
        val publishedAt: Long,
        val retryCount: Int = 0
    )

    data class ActionGroupTelemetryAckResult(
        val freshAck: Boolean,
        val snapshot: ActionGroupTelemetryPublishSnapshot?,
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
    fun publishServiceUpdateStatus(
        source: String = "runtime_state:auto",
        lowerPlatformSnapshot: LowerPlatformEpisodeReducer.StateSnapshot? = null,
        forceNewSeq: Boolean = false,
        ackListener: RuntimeStateAckCallbackRegistry.Listener? = null
    ): Boolean {
        val config = AppConfig.getAppConfig()
        val sellableRulesSignature = SellablePanPhysicalStateProjection.rulesSignature(config)
        sellableRulesPhysicalDeltaDeduper.publishIfChanged(sellableRulesSignature) {
            PanPhysicalStateDeltaReporter.reportRulesChanged(
                reason = "sellable_rules_changed",
                source = "$source:sellable_rules"
            )
        }
        val frozenLowerPlatform = lowerPlatformSnapshot ?: LowerPlatformEpisodeReducer.snapshot(config)
        val now = System.currentTimeMillis()
        val stateSignature = buildRuntimeStateSignature(config, frozenLowerPlatform)
        val (seq, reusedSeq) = resolveRuntimeStateSeq(stateSignature, forceNewSeq)
        val payload = RuntimeStatePayloadBuilder.build(
            deviceCode = AppConfig.getDeviceId(),
            runtimeSeq = seq,
            config = config,
            source = source,
            reportedAt = now,
            lowerPlatformSnapshot = frozenLowerPlatform,
            sellableRulesSignature = sellableRulesSignature
        )
        if (ackListener != null) {
            val registered = runtimeStateAckCallbackRegistry.register(
                runtimeSeq = seq,
                onlineStatus = config.onlineStatus,
                errorStatus = config.errorStatus,
                listener = ackListener
            )
            if (!registered) {
                return false
            }
            scheduleRuntimeStateAckCallbackTimeout(seq)
        }
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
            if (ackListener != null) {
                runtimeStateAckCallbackRegistry.cancel(ackListener)
            }
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
     * 通过 MQTT 上报可售烤盘投影增量（action=2）。
     * 这是轻量热状态，不替代 HTTP 全量快照的权威落库。
     */
    fun publishPanStateDelta(
        before: KaoPanCloudSyncReporter.SellableSnapshot,
        after: KaoPanCloudSyncReporter.SellableSnapshot,
        reason: String,
        source: String = "sellable_state_changed"
    ): Boolean {
        val seq = panStateDeltaSeq.incrementAndGet()
        val eventId = "${AppConfig.getDeviceId()}:pan_delta:$seq"
        val now = System.currentTimeMillis()
        val payload = PanStateDeltaPayloadBuilder.build(
            deviceCode = AppConfig.getDeviceId(),
            panDeltaSeq = seq,
            eventId = eventId,
            before = before,
            after = after,
            reason = reason,
            source = source,
            changedAt = now
        )
        val ok = VMMqttHelper.publishService(
            TopicMessage().apply {
                action = MqttProtocol.ACTION_SERVICE_STATE
                content = JSON.toJSONString(payload)
            },
            priority = MqttPublishPriority.HIGH,
            coalesceKey = "pan_state_delta"
        )
        if (ok) {
            val snapshot = PanStateDeltaPublishSnapshot(
                panDeltaSeq = seq,
                eventId = eventId,
                reason = reason,
                source = source,
                publishedAt = now
            )
            pendingPanStateDeltaAcks.clear()
            pendingPanStateDeltaAcks[seq] = snapshot
            schedulePanStateDeltaAckTimeout(snapshot)
            LogUtils.d(
                "【烤盘增量】可售烤盘投影已进入 MQTT 发送队列：" +
                    "panDeltaSeq=$seq，eventId=$eventId，reason=$reason，source=$source"
            )
        } else {
            LogUtils.w(
                "【烤盘增量】可售烤盘投影进入 MQTT 发送队列失败：" +
                    "panDeltaSeq=$seq，reason=$reason，source=$source"
            )
        }
        return ok
    }

    /**
     * 通过 MQTT 上报完整物理烤盘/箱位状态增量（action=2）。
     * 这是服务端 Redis 热状态的主通道；HTTP reportSnapshot 仍作为基线和冲突兜底。
     */
    fun publishPanPhysicalStateDelta(
        allPans: List<KaoPan>,
        allStocks: List<KaoPanBox>,
        changedPans: List<KaoPan>,
        changedStocks: List<KaoPanBox>,
        reason: String,
        source: String = "physical_state_changed"
    ): Boolean {
        val baseline = PanPhysicalStateBaselineStore.currentBaseline()
        if (baseline == null) {
            LogUtils.w(
                "【烤盘物理增量】缺少已确认 HTTP 快照基线，跳过 MQTT 增量并触发 HTTP 快照兜底：" +
                    "reason=$reason，source=$source"
            )
            triggerPanPhysicalStateDeltaSnapshotFallback("pan_physical_delta_missing_base:$reason")
            return false
        }
        val seq = PanPhysicalStateDeltaSequenceStore.nextSequence()
        val eventId = "${AppConfig.getDeviceId()}:pan_physical_delta:$seq"
        val now = System.currentTimeMillis()
        val payload = PanPhysicalStateDeltaPayloadBuilder.build(
            deviceCode = AppConfig.getDeviceId(),
            panDeltaSeq = seq,
            eventId = eventId,
            baseSnapshotSequence = baseline.sequence,
            baseSnapshotHash = baseline.snapshotHash,
            allPans = allPans,
            allStocks = allStocks,
            changedPans = changedPans,
            changedStocks = changedStocks,
            reason = reason,
            source = source,
            changedAt = now,
            config = AppConfig.getAppConfig()
        )
        val ok = VMMqttHelper.publishService(
            TopicMessage().apply {
                action = MqttProtocol.ACTION_SERVICE_STATE
                content = JSON.toJSONString(payload)
            },
            priority = MqttPublishPriority.HIGH
        )
        if (ok) {
            val snapshot = PanPhysicalStateDeltaPublishSnapshot(
                panDeltaSeq = seq,
                eventId = eventId,
                reason = reason,
                source = source,
                publishedAt = now
            )
            pendingPanPhysicalStateDeltaAcks[seq] = snapshot
            schedulePanPhysicalStateDeltaAckTimeout(snapshot)
            LogUtils.d(
                "【烤盘物理增量】完整物理状态已进入 MQTT 发送队列：" +
                    "panDeltaSeq=$seq，eventId=$eventId，reason=$reason，source=$source，" +
                    "changedPans=${changedPans.map { it.positionSn }}，changedStocks=${changedStocks.map { it.positionSn }}"
            )
        } else {
            LogUtils.w(
                "【烤盘物理增量】完整物理状态进入 MQTT 发送队列失败，触发 HTTP 快照兜底：" +
                    "panDeltaSeq=$seq，eventId=$eventId，reason=$reason，source=$source"
            )
            triggerPanPhysicalStateDeltaSnapshotFallback("pan_physical_delta_publish_failed:$reason")
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
        val payload = ActionGroupTelemetryPayloadBuilder.build(
            deviceCode = AppConfig.getDeviceId(),
            telemetry = telemetry
        )
        val eventId = payload.getString("eventId").orEmpty()
        val sessionId = payload.getString("sessionId").orEmpty()
        val actionName = payload.getString("actionName").orEmpty()
        val eventPhase = payload.getString("eventPhase").orEmpty()
        val ok = VMMqttHelper.publishService(
            TopicMessage().apply {
                action = MqttProtocol.ACTION_DEVICE_LOG
                content = JSON.toJSONString(payload)
            },
            priority = MqttPublishPriority.LOW
        )
        if (ok) {
            val snapshot = ActionGroupTelemetryPublishSnapshot(
                eventId = eventId,
                payload = payload,
                sessionId = sessionId,
                actionName = actionName,
                eventPhase = eventPhase,
                publishedAt = System.currentTimeMillis()
            )
            trimActionGroupTelemetryPendingIfNeeded()
            pendingActionGroupTelemetryAcks[eventId] = snapshot
            scheduleActionGroupTelemetryAckTimeout(snapshot)
            LogUtils.d(
                "【300/400动作组】结构化动作日志已进入 MQTT 发送队列：" +
                    "sessionId=${telemetry.sessionId}，actionName=${telemetry.actionName}，" +
                    "eventPhase=${telemetry.eventPhase}，eventId=${telemetry.eventId}，" +
                    "physicalCheckpoint=${telemetry.physicalCheckpoint}，physicalLocation=${telemetry.physicalLocation}，" +
                    "errorCode=${telemetry.errorCode}，elapsedMs=${telemetry.elapsedMs}"
            )
        } else {
            LogUtils.w(
                "【300/400动作组】结构化动作日志进入 MQTT 发送队列失败，触发 HTTP 兜底：" +
                    "sessionId=${telemetry.sessionId}，actionName=${telemetry.actionName}，" +
                    "eventPhase=${telemetry.eventPhase}，eventId=${telemetry.eventId}，" +
                    "physicalCheckpoint=${telemetry.physicalCheckpoint}，physicalLocation=${telemetry.physicalLocation}，" +
                    "errorCode=${telemetry.errorCode}"
            )
            val snapshot = ActionGroupTelemetryPublishSnapshot(
                eventId = eventId,
                payload = payload,
                sessionId = sessionId,
                actionName = actionName,
                eventPhase = eventPhase,
                publishedAt = System.currentTimeMillis()
            )
            triggerActionGroupTelemetryHttpFallback(snapshot)
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

    private fun scheduleRuntimeStateAckCallbackTimeout(runtimeSeq: Long) {
        runtimeStateAckHandler.postDelayed({
            runtimeStateAckCallbackRegistry.timeout(runtimeSeq)
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

    private fun schedulePanStateDeltaAckTimeout(snapshot: PanStateDeltaPublishSnapshot) {
        panStateDeltaAckHandler.postDelayed({
            val pending = pendingPanStateDeltaAcks[snapshot.panDeltaSeq] ?: return@postDelayed
            if (pending.publishedAt != snapshot.publishedAt) {
                return@postDelayed
            }
            pendingPanStateDeltaAcks.remove(snapshot.panDeltaSeq)
            val ageMs = System.currentTimeMillis() - pending.publishedAt
            LogUtils.w(
                "【烤盘增量】可售烤盘投影上报后仍未收到服务端确认，触发 HTTP 快照兜底：" +
                    "panDeltaSeq=${pending.panDeltaSeq}，eventId=${pending.eventId}，" +
                    "reason=${pending.reason}，source=${pending.source}，ageMs=$ageMs"
            )
            triggerPanStateDeltaSnapshotFallback("pan_delta_ack_timeout:${pending.reason}")
        }, PAN_STATE_DELTA_ACK_TIMEOUT_MS)
    }

    private fun schedulePanPhysicalStateDeltaAckTimeout(snapshot: PanPhysicalStateDeltaPublishSnapshot) {
        panStateDeltaAckHandler.postDelayed({
            val pending = pendingPanPhysicalStateDeltaAcks[snapshot.panDeltaSeq] ?: return@postDelayed
            if (pending.publishedAt != snapshot.publishedAt) {
                return@postDelayed
            }
            pendingPanPhysicalStateDeltaAcks.remove(snapshot.panDeltaSeq)
            val ageMs = System.currentTimeMillis() - pending.publishedAt
            LogUtils.w(
                "【烤盘物理增量】完整物理状态上报后仍未收到服务端确认，触发 HTTP 快照兜底：" +
                    "panDeltaSeq=${pending.panDeltaSeq}，eventId=${pending.eventId}，" +
                    "reason=${pending.reason}，source=${pending.source}，ageMs=$ageMs"
            )
            triggerPanPhysicalStateDeltaSnapshotFallback("pan_physical_delta_ack_timeout:${pending.reason}")
        }, PAN_STATE_DELTA_ACK_TIMEOUT_MS)
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

    private fun scheduleActionGroupTelemetryAckTimeout(snapshot: ActionGroupTelemetryPublishSnapshot) {
        actionGroupTelemetryAckHandler.postDelayed({
            val pending = pendingActionGroupTelemetryAcks[snapshot.eventId] ?: return@postDelayed
            if (pending.publishedAt != snapshot.publishedAt) {
                return@postDelayed
            }
            val ageMs = System.currentTimeMillis() - pending.publishedAt
            LogUtils.w(
                "【300/400动作组】结构化动作日志上报后仍未收到服务端确认，触发 HTTP 兜底：" +
                    "eventId=${pending.eventId}，actionName=${pending.actionName}，" +
                    "eventPhase=${pending.eventPhase}，ageMs=$ageMs"
            )
            triggerActionGroupTelemetryHttpFallback(pending)
        }, ACTION_GROUP_TELEMETRY_ACK_TIMEOUT_MS)
    }

    private fun triggerActionGroupTelemetryHttpFallback(pending: ActionGroupTelemetryPublishSnapshot) {
        if (actionGroupTelemetryHttpFallbackInFlight.putIfAbsent(pending.eventId, true) != null) {
            LogUtils.w(
                "【300/400动作组】结构化动作日志 HTTP 兜底已在途，跳过重复请求：" +
                    "eventId=${pending.eventId}，actionName=${pending.actionName}，eventPhase=${pending.eventPhase}"
            )
            return
        }
        DataManagementAPI.uploadActionGroupTelemetry(pending.payload) { code, content ->
            actionGroupTelemetryHttpFallbackInFlight.remove(pending.eventId)
            if (pendingActionGroupTelemetryAcks[pending.eventId]?.publishedAt != pending.publishedAt) {
                LogUtils.d(
                    "【300/400动作组】结构化动作日志 HTTP 兜底回调已过期，忽略：" +
                        "eventId=${pending.eventId}，actionName=${pending.actionName}，eventPhase=${pending.eventPhase}"
                )
                return@uploadActionGroupTelemetry
            }
            if (code == 0 && isActionGroupTelemetryHttpAccepted(content)) {
                pendingActionGroupTelemetryAcks.remove(pending.eventId)
                LogUtils.i(
                    "【300/400动作组】结构化动作日志 HTTP 兜底已被服务端接收：" +
                        "eventId=${pending.eventId}，actionName=${pending.actionName}，eventPhase=${pending.eventPhase}"
                )
            } else {
                LogUtils.w(
                    "【300/400动作组】结构化动作日志 HTTP 兜底未确认接收，进入有界重试：" +
                        "eventId=${pending.eventId}，actionName=${pending.actionName}，" +
                        "eventPhase=${pending.eventPhase}，code=$code"
                )
                enqueueActionGroupTelemetryRetry(pending)
            }
        }
    }

    private fun enqueueActionGroupTelemetryRetry(snapshot: ActionGroupTelemetryPublishSnapshot) {
        if (snapshot.retryCount >= ACTION_GROUP_TELEMETRY_MAX_RETRY) {
            pendingActionGroupTelemetryAcks.remove(snapshot.eventId)
            LogUtils.e(
                "【300/400动作组】结构化动作日志 HTTP 兜底重试已达上限，停止内存重试：" +
                    "eventId=${snapshot.eventId}，actionName=${snapshot.actionName}，eventPhase=${snapshot.eventPhase}"
            )
            return
        }
        val retrySnapshot = snapshot.copy(
            publishedAt = System.currentTimeMillis(),
            retryCount = snapshot.retryCount + 1
        )
        pendingActionGroupTelemetryAcks[retrySnapshot.eventId] = retrySnapshot
        actionGroupTelemetryAckHandler.postDelayed({
            val pending = pendingActionGroupTelemetryAcks[retrySnapshot.eventId] ?: return@postDelayed
            if (pending.publishedAt != retrySnapshot.publishedAt) {
                return@postDelayed
            }
            triggerActionGroupTelemetryHttpFallback(pending)
        }, ACTION_GROUP_TELEMETRY_RETRY_DELAY_MS)
    }

    private fun isActionGroupTelemetryHttpAccepted(content: String?): Boolean {
        if (content.isNullOrBlank()) {
            return false
        }
        return try {
            val root = JSON.parseObject(content)
            val data = root.getJSONObject("data")
            ApiResponseCompat.isSuccessful(root) && (
                data == null ||
                    data.getBooleanValue("accepted") ||
                    data.getBooleanValue("duplicate") ||
                    data.getBooleanValue("persisted")
                )
        } catch (e: Exception) {
            false
        }
    }

    private fun trimActionGroupTelemetryPendingIfNeeded() {
        if (pendingActionGroupTelemetryAcks.size < ACTION_GROUP_TELEMETRY_MAX_PENDING) {
            return
        }
        val oldest = pendingActionGroupTelemetryAcks.values.minByOrNull { it.publishedAt } ?: return
        pendingActionGroupTelemetryAcks.remove(oldest.eventId)
        LogUtils.w(
            "【300/400动作组】结构化动作日志 pending 队列达到上限，丢弃最旧待确认事件：" +
                "eventId=${oldest.eventId}，actionName=${oldest.actionName}，eventPhase=${oldest.eventPhase}"
        )
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

    fun markPanStateDeltaAcked(panDeltaSeq: Long): PanStateDeltaAckResult {
        if (panDeltaSeq <= 0L) {
            return PanStateDeltaAckResult(false, null, null)
        }
        val snapshot = pendingPanStateDeltaAcks.remove(panDeltaSeq)
        val ackLatencyMs = snapshot?.let { System.currentTimeMillis() - it.publishedAt }
        return PanStateDeltaAckResult(
            freshAck = snapshot != null,
            snapshot = snapshot,
            ackLatencyMs = ackLatencyMs
        )
    }

    fun markPanPhysicalStateDeltaAcked(panDeltaSeq: Long, eventId: String? = null): PanPhysicalStateDeltaAckResult {
        if (panDeltaSeq <= 0L) {
            return PanPhysicalStateDeltaAckResult(false, null, null)
        }
        val snapshot = pendingPanPhysicalStateDeltaAcks[panDeltaSeq]
        if (snapshot != null && (eventId.isNullOrBlank() || snapshot.eventId != eventId)) {
            return PanPhysicalStateDeltaAckResult(false, null, null)
        }
        val removed = pendingPanPhysicalStateDeltaAcks.remove(panDeltaSeq)
        val ackLatencyMs = removed?.let { System.currentTimeMillis() - it.publishedAt }
        return PanPhysicalStateDeltaAckResult(
            freshAck = removed != null,
            snapshot = removed,
            ackLatencyMs = ackLatencyMs
        )
    }

    fun hasPendingPanPhysicalStateDelta(panDeltaSeq: Long): Boolean =
        pendingPanPhysicalStateDeltaAcks.containsKey(panDeltaSeq)

    fun handleRejectedPanStateDeltaAck(panDeltaSeq: Long, reason: String) {
        triggerPanStateDeltaSnapshotFallback("pan_delta_rejected:$panDeltaSeq:${reason.ifBlank { "unknown" }}")
    }

    fun handleRejectedPanPhysicalStateDeltaAck(panDeltaSeq: Long, reason: String, needFullSnapshot: Boolean) {
        if (!needFullSnapshot) {
            LogUtils.w(
                "【烤盘物理增量】服务端未应用完整物理状态，但未要求 HTTP 全量：" +
                    "panDeltaSeq=$panDeltaSeq，reason=${reason.ifBlank { "unknown" }}"
            )
            return
        }
        triggerPanPhysicalStateDeltaSnapshotFallback(
            "pan_physical_delta_rejected:$panDeltaSeq:${reason.ifBlank { "unknown" }}"
        )
    }

    fun handleUnverifiedPanPhysicalStateDeltaAck(panDeltaSeq: Long, reason: String) {
        triggerPanPhysicalStateDeltaSnapshotFallback(
            "pan_physical_delta_ack_unverified:$panDeltaSeq:${reason.ifBlank { "unknown" }}"
        )
    }

    private fun triggerPanStateDeltaSnapshotFallback(reason: String) {
        val now = System.currentTimeMillis()
        val minIntervalMs = AppConfig.getAppConfig().httpFallbackMinIntervalMs.takeIf { it > 0L } ?: 30_000L
        if (lastPanStateDeltaHttpFallbackAtMs > 0L && now - lastPanStateDeltaHttpFallbackAtMs < minIntervalMs) {
            LogUtils.w(
                "【烤盘增量】HTTP 快照兜底被限频跳过：" +
                    "reason=$reason，minIntervalMs=$minIntervalMs"
            )
            return
        }
        lastPanStateDeltaHttpFallbackAtMs = now
        SnapshotSyncPolicy.maybeUploadSnapshot(reason)
    }

    private fun triggerPanPhysicalStateDeltaSnapshotFallback(reason: String) {
        SnapshotSyncPolicy.forceUploadSnapshot(reason)
    }

    fun markConsumableEventAcked(sourceEventKey: String?): ConsumableEventAckResult {
        if (sourceEventKey.isNullOrBlank()) {
            return ConsumableEventAckResult(false, null, null)
        }
        val snapshot = pendingConsumableEventAcks.remove(sourceEventKey)
        val ackLatencyMs = snapshot?.let { System.currentTimeMillis() - it.publishedAt }
        return ConsumableEventAckResult(snapshot != null, snapshot, ackLatencyMs)
    }

    fun markActionGroupTelemetryAcked(eventId: String?): ActionGroupTelemetryAckResult {
        if (eventId.isNullOrBlank()) {
            return ActionGroupTelemetryAckResult(false, null, null)
        }
        val snapshot = pendingActionGroupTelemetryAcks.remove(eventId)
        actionGroupTelemetryHttpFallbackInFlight.remove(eventId)
        val ackLatencyMs = snapshot?.let { System.currentTimeMillis() - it.publishedAt }
        return ActionGroupTelemetryAckResult(snapshot != null, snapshot, ackLatencyMs)
    }

    fun handleRejectedActionGroupTelemetryAck(eventId: String?, reason: String) {
        if (eventId.isNullOrBlank()) {
            LogUtils.w("【300/400动作组】服务端拒绝结构化动作日志但未携带 eventId，无法触发精确兜底：reason=$reason")
            return
        }
        val pending = pendingActionGroupTelemetryAcks[eventId]
        if (pending == null) {
            LogUtils.w("【300/400动作组】服务端拒绝结构化动作日志，但本地已无 pending 事件：eventId=$eventId，reason=$reason")
            return
        }
        LogUtils.w(
            "【300/400动作组】服务端拒绝结构化动作日志，保留 pending 并触发 HTTP 兜底：" +
                "eventId=$eventId，actionName=${pending.actionName}，eventPhase=${pending.eventPhase}，reason=$reason"
        )
        triggerActionGroupTelemetryHttpFallback(pending)
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

    fun notifyRuntimeStateAck(ack: RuntimeStateServerAck) {
        runtimeStateAckCallbackRegistry.complete(ack.runtimeSeq, ack)
    }

    fun createRuntimeStateAckListener(
        callback: (RuntimeStateServerAck) -> Unit
    ): RuntimeStateAckCallbackRegistry.Listener = runtimeStateAckCallbackRegistry.createListener(callback)

    fun cancelRuntimeStateAckListener(listener: RuntimeStateAckCallbackRegistry.Listener) {
        runtimeStateAckCallbackRegistry.cancel(listener)
    }

    private fun buildRuntimeStateSignature(
        config: AppConfigBean,
        lowerPlatformSnapshot: LowerPlatformEpisodeReducer.StateSnapshot
    ): String {
        return RuntimeStateSignatureBuilder.build(config, lowerPlatformSnapshot)
    }

    private fun resolveRuntimeStateSeq(
        stateSignature: String,
        forceNewSeq: Boolean
    ): Pair<Long, Boolean> {
        synchronized(pendingRuntimeStateAcks) {
            if (!forceNewSeq && activeRuntimeStateSeq > 0L && stateSignature == activeRuntimeStateSignature) {
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
    fun pendingPanStateDeltaAckCount(): Int = pendingPanStateDeltaAcks.size
    fun pendingPanPhysicalStateDeltaAckCount(): Int = pendingPanPhysicalStateDeltaAcks.size
    fun pendingActionGroupTelemetryAckCount(): Int = pendingActionGroupTelemetryAcks.size

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
