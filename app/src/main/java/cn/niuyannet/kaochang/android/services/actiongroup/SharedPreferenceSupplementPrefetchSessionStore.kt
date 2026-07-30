package cn.niuyannet.kaochang.android.services.actiongroup

import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.services.SourceBoxPositionPolicy
import cn.niuyannet.kaochang.android.services.recovery.DurableEvidenceCodec
import cn.niuyannet.kaochang.android.services.recovery.DurableEvidenceReadResult
import cn.niuyannet.kaochang.android.utils.PreferenceUtils
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject

class SharedPreferenceSupplementPrefetchSessionStore(
    private val file: String = AppConfig.Sp_File_URL,
    private val key: String = KEY_SUPPLEMENT_PREFETCH_SESSION,
    private val readRaw: () -> String? = {
        PreferenceUtils.getStringPreference(file, key, null)
    },
    private val writeRawDurably: (String) -> Boolean = { raw ->
        PreferenceUtils.saveStringPreferenceDurable(file, key, raw)
    },
    private val mutationAllowed: (String) -> Boolean = { true }
) : SupplementPrefetchSessionStore {

    override val synchronizationLock: Any = sharedLock(file, key)

    override fun load(): SupplementPrefetchPipelineSession? {
        return when (val result = read()) {
            DurableEvidenceReadResult.Absent -> null
            is DurableEvidenceReadResult.Valid -> result.value
            is DurableEvidenceReadResult.Corrupted -> throw SupplementPrefetchSessionCorruptedException(
                "补肠预取会话损坏：fingerprint=${result.fingerprint}，reason=${result.reason}"
            )
        }
    }

    override fun read(): DurableEvidenceReadResult<SupplementPrefetchPipelineSession> =
        DurableEvidenceCodec.read(
            readRaw(),
            ::decodeSupplementPrefetchSession,
            SupplementPrefetchSessionValidator::isValid
        )

    override fun save(session: SupplementPrefetchPipelineSession) {
        check(mutationAllowed("save")) { "恢复证据损坏锁存期间禁止写入补肠预取会话" }
        check(SupplementPrefetchSessionValidator.isValid(session)) {
            "拒绝写入无效补肠预取会话：sessionId=${session.sessionId}，revision=${session.revision}，resourceMask=${session.resourceMask}"
        }
        check(writeRawDurably(JSON.toJSONString(session))) {
            "补肠预取会话检查点同步落盘失败：sessionId=${session.sessionId}，revision=${session.revision}"
        }
    }

    override fun clear() {
        check(mutationAllowed("clear")) { "恢复证据损坏锁存期间禁止清理补肠预取会话" }
        check(writeRawDurably("")) { "补肠预取会话清理同步落盘失败" }
    }

    companion object {
        const val KEY_SUPPLEMENT_PREFETCH_SESSION = "sp_supplement_prefetch_pipeline_session_json_v1"

        private val sharedLocks = mutableMapOf<Pair<String, String>, Any>()

        @Synchronized
        private fun sharedLock(file: String, key: String): Any =
            sharedLocks.getOrPut(file to key) { Any() }
    }
}

private fun decodeSupplementPrefetchSession(raw: String): SupplementPrefetchPipelineSession {
    val json = JSON.parseObject(raw)
    return SupplementPrefetchPipelineSession(
        schemaVersion = json.requiredInt("schemaVersion"),
        revision = json.requiredLong("revision"),
        sessionId = json.requiredString("sessionId"),
        batchId = json.optionalString("batchId"),
        startedAtMs = json.requiredLong("startedAtMs"),
        resourceMask = json.requiredInt("resourceMask"),
        deliveryLane = json.requiredObject("deliveryLane").toSupplementPrefetchLane(),
        prefetchLane = json.optionalObject("prefetchLane")?.toSupplementPrefetchLane(),
        barrierState = json.requiredEnum("barrierState"),
        pendingOrderNo = json.optionalString("pendingOrderNo"),
        phase = json.requiredEnum("phase"),
        mechanicalClaimState = json.optionalEnum<MechanicalClaimState>("mechanicalClaimState"),
        mechanicalClaimRevision = json.optionalLong("mechanicalClaimRevision") ?: 0L
    )
}

private fun JSONObject.toSupplementPrefetchLane() = SupplementPrefetchLaneCheckpoint(
    sourceBoxNo = requiredInt("sourceBoxNo"),
    sourceBoxCandidates = requiredIntList("sourceBoxCandidates"),
    targetPanNo = requiredInt("targetPanNo"),
    taste = optionalString("taste"),
    returnPosition = optionalInt("returnPosition"),
    currentAction = optionalString("currentAction"),
    lifecycle = requiredEnum("lifecycle"),
    lastConfirmedStage = optionalString("lastConfirmedStage"),
    stockBefore = optionalInt("stockBefore"),
    expectedStockAfter = optionalInt("expectedStockAfter"),
    boxDeductionApplied = requiredBoolean("boxDeductionApplied"),
    panPlacementApplied = requiredBoolean("panPlacementApplied"),
    failedSourceBoxNos = optionalIntList("failedSourceBoxNos")
)

private fun JSONObject.requiredInt(field: String): Int {
    val value = requiredIntegralNumber(field)
    require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "$field 超出 Int 范围" }
    return value.toInt()
}

private fun JSONObject.requiredLong(field: String): Long = requiredIntegralNumber(field)

private fun JSONObject.requiredIntegralNumber(field: String): Long {
    val value = this[field]
    require(value is Byte || value is Short || value is Int || value is Long) { "$field 必须是整数" }
    return (value as Number).toLong()
}

private fun JSONObject.optionalInt(field: String): Int? {
    if (!containsKey(field) || this[field] == null) return null
    return requiredInt(field)
}

private fun JSONObject.optionalLong(field: String): Long? {
    if (!containsKey(field) || this[field] == null) return null
    return requiredLong(field)
}

private fun JSONObject.requiredString(field: String): String {
    val value = this[field]
    require(value is String) { "$field 必须是字符串" }
    return value
}

private fun JSONObject.optionalString(field: String): String? {
    if (!containsKey(field) || this[field] == null) return null
    return requiredString(field)
}

private fun JSONObject.requiredBoolean(field: String): Boolean {
    val value = this[field]
    require(value is Boolean) { "$field 必须是布尔值" }
    return value
}

private fun JSONObject.requiredObject(field: String): JSONObject {
    val value = this[field]
    require(value is JSONObject) { "$field 必须是对象" }
    return value
}

private fun JSONObject.optionalObject(field: String): JSONObject? {
    if (!containsKey(field) || this[field] == null) return null
    return requiredObject(field)
}

private fun JSONObject.requiredIntList(field: String): List<Int> {
    val value = this[field]
    require(value is JSONArray) { "$field 必须是整数数组" }
    return value.mapIndexed { index, item ->
        require(item is Byte || item is Short || item is Int || item is Long) {
            "$field[$index] 必须是整数"
        }
        val number = (item as Number).toLong()
        require(number in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "$field[$index] 超出 Int 范围" }
        number.toInt()
    }
}

private fun JSONObject.optionalIntList(field: String): List<Int> {
    if (!containsKey(field) || this[field] == null) return emptyList()
    return requiredIntList(field)
}

private inline fun <reified T : Enum<T>> JSONObject.requiredEnum(field: String): T =
    enumValueOf(requiredString(field))

private inline fun <reified T : Enum<T>> JSONObject.optionalEnum(field: String): T? =
    optionalString(field)?.let { enumValueOf<T>(it) }

object SupplementPrefetchSessionValidator {
    fun isValid(session: SupplementPrefetchPipelineSession): Boolean = failureReason(session) == null

    fun failureReason(session: SupplementPrefetchPipelineSession): String? {
        if (session.schemaVersion != SupplementPrefetchPipelineSession.SCHEMA_VERSION) {
            return "不支持的 schemaVersion=${session.schemaVersion}"
        }
        if (session.revision < 0L) return "revision 不能小于 0"
        if (session.sessionId.isBlank()) return "sessionId 不能为空"
        val requiredResourceMask = ResourceMask.bottom or ResourceMask.lift or ResourceMask.above
        if (session.resourceMask != requiredResourceMask) {
            return "resourceMask 必须完整占用 IN 动作组资源：actual=${session.resourceMask}"
        }
        val delivery = session.deliveryLane ?: return "delivery lane 不能为空"
        delivery.persistedLaneFailureReason(SupplementLaneRole.DELIVERY)?.let { return it }
        session.prefetchLane?.persistedLaneFailureReason(SupplementLaneRole.PREFETCH)?.let { return it }
        if (session.prefetchLane?.targetPanNo == delivery.targetPanNo) {
            return "delivery/prefetch lane 禁止指向同一目标烤盘：target=${delivery.targetPanNo}"
        }
        val barrierFailure = when (session.barrierState) {
            SupplementBarrierState.OPEN -> when {
                session.pendingOrderNo != null -> "OPEN barrier 禁止保留 pendingOrderNo"
                else -> null
            }
            SupplementBarrierState.ORDER_WON -> when {
                session.pendingOrderNo.isNullOrBlank() -> "ORDER_WON 必须保存 pendingOrderNo"
                else -> null
            }
            SupplementBarrierState.DELIVERY_COMMITTED -> null
        }
        if (barrierFailure != null) return barrierFailure
        if (session.phase in setOf(
                SupplementPipelinePhase.RECOVERING,
                SupplementPipelinePhase.MAINTENANCE_REQUIRED
            )
        ) {
            return null
        }
        return when (session.barrierState) {
            SupplementBarrierState.OPEN ->
                if (session.phase == SupplementPipelinePhase.RUNNING ||
                    (session.phase == SupplementPipelinePhase.PAUSED_FOR_RUNTIME &&
                        session.mechanicalClaimState == null)
                ) null else "OPEN barrier 阶段非法"
            SupplementBarrierState.ORDER_WON ->
                if (session.phase == SupplementPipelinePhase.PAUSED_FOR_ORDER) null
                else "ORDER_WON 只能处于 PAUSED_FOR_ORDER"
            SupplementBarrierState.DELIVERY_COMMITTED ->
                if (session.phase == SupplementPipelinePhase.RUNNING) null
                else "DELIVERY_COMMITTED 只能处于 RUNNING"
        }
    }
}

private fun SupplementPrefetchLaneCheckpoint.persistedLaneFailureReason(role: SupplementLaneRole): String? {
    val actionNames = (1..7).map { "in$it" }
    if (targetPanNo !in 1..33) return "$role targetPanNo 超出范围：$targetPanNo"
    val source = sourceBoxNo ?: return "$role sourceBoxNo 不能为空"
    if (!SourceBoxPositionPolicy.isValidSourceBox(source)) {
        return "$role sourceBoxNo 非法：$source，合法范围=${SourceBoxPositionPolicy.validRangeText()}"
    }
    if (sourceBoxCandidates.isEmpty()) return "$role sourceBoxCandidates 不能为空"
    if (sourceBoxCandidates.any { !SourceBoxPositionPolicy.isValidSourceBox(it) }) {
        return "$role sourceBoxCandidates 含非法编号"
    }
    if (sourceBoxCandidates.distinct().size != sourceBoxCandidates.size) {
        return "$role sourceBoxCandidates 禁止重复"
    }
    if (source !in sourceBoxCandidates) {
        return "$role sourceBoxNo 必须属于 sourceBoxCandidates：source=$source，candidates=$sourceBoxCandidates"
    }
    if (failedSourceBoxNos.distinct().size != failedSourceBoxNos.size) {
        return "$role failedSourceBoxNos 禁止重复"
    }
    if (failedSourceBoxNos.any { it !in sourceBoxCandidates }) {
        return "$role failedSourceBoxNos 必须属于 sourceBoxCandidates"
    }
    if (source in failedSourceBoxNos) {
        return "$role 当前 sourceBoxNo 不能同时标记为失败候选"
    }
    if (returnPosition?.let { it !in 1..33 } == true) return "$role returnPosition 超出范围"
    if (currentAction != null && currentAction !in actionNames) return "$role currentAction 非法：$currentAction"
    if (currentAction == null && lifecycle != SupplementActionLifecycle.PREPARED) {
        return "$role 无 currentAction 时 lifecycle 只能为 PREPARED"
    }
    if (lifecycle.ordinal >= SupplementActionLifecycle.TERMINAL_CONFIRMED.ordinal &&
        lastConfirmedStage != currentAction
    ) {
        return "$role 终态 lifecycle 必须匹配 lastConfirmedStage"
    }
    if (lastConfirmedStage != null &&
        (lastConfirmedStage != currentAction ||
            lifecycle.ordinal < SupplementActionLifecycle.TERMINAL_CONFIRMED.ordinal)
    ) {
        return "$role lastConfirmedStage 与动作生命周期矛盾"
    }
    val actionIndex = actionNames.indexOf(currentAction)
    if (boxDeductionApplied && actionIndex < actionNames.indexOf("in2")) {
        return "$role boxDeductionApplied 与当前动作矛盾"
    }
    if (panPlacementApplied && currentAction != "in7") {
        return "$role panPlacementApplied 只能出现在 in7"
    }
    return null
}
