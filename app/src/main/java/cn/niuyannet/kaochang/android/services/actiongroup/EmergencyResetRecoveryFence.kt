package cn.niuyannet.kaochang.android.services.actiongroup

import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.utils.PreferenceUtils
import com.alibaba.fastjson.JSON

enum class EmergencyResetRecoveryPhase {
    PREPARED,
    RESET_WRITTEN
}

data class EmergencyResetRecoveryFence(
    val schemaVersion: Int = SCHEMA_VERSION,
    val resetId: String = "",
    val originProcessInstanceId: String = "",
    val createdAtMs: Long = 0L,
    val phase: EmergencyResetRecoveryPhase = EmergencyResetRecoveryPhase.PREPARED
) {
    fun matchesReset(expectedResetId: String): Boolean = resetId == expectedResetId

    fun hasCrossedProcessBoundary(currentProcessInstanceId: String): Boolean =
        currentProcessInstanceId.isNotBlank() && currentProcessInstanceId != originProcessInstanceId

    fun markResetWritten(): EmergencyResetRecoveryFence =
        copy(phase = EmergencyResetRecoveryPhase.RESET_WRITTEN).requireValid()

    fun requireValid(): EmergencyResetRecoveryFence {
        check(schemaVersion == SCHEMA_VERSION) { "紧急复位恢复栅栏版本不支持：$schemaVersion" }
        check(resetId.isNotBlank()) { "紧急复位恢复栅栏 resetId 不能为空" }
        check(originProcessInstanceId.isNotBlank()) { "紧急复位恢复栅栏进程实例不能为空" }
        check(createdAtMs > 0L) { "紧急复位恢复栅栏创建时间非法" }
        return this
    }

    companion object {
        const val SCHEMA_VERSION = 1

        fun prepared(
            resetId: String,
            originProcessInstanceId: String,
            createdAtMs: Long = System.currentTimeMillis()
        ): EmergencyResetRecoveryFence = EmergencyResetRecoveryFence(
            resetId = resetId,
            originProcessInstanceId = originProcessInstanceId,
            createdAtMs = createdAtMs
        ).requireValid()
    }
}

class EmergencyResetRecoveryFenceStore(
    private val file: String = AppConfig.Sp_File_URL,
    private val key: String = KEY_EMERGENCY_RESET_RECOVERY_FENCE,
    private val readRaw: () -> String? = {
        PreferenceUtils.getStringPreference(file, key, null)
    },
    private val writeRawDurably: (String) -> Boolean = { raw ->
        PreferenceUtils.saveStringPreferenceDurable(file, key, raw)
    }
) {
    @Synchronized
    fun load(): EmergencyResetRecoveryFence? {
        val raw = readRaw()
        if (raw.isNullOrBlank()) return null
        return try {
            JSON.parseObject(raw, EmergencyResetRecoveryFence::class.java).requireValid()
        } catch (error: Throwable) {
            throw IllegalStateException("紧急复位恢复栅栏损坏，拒绝按无栅栏处理", error)
        }
    }

    @Synchronized
    fun save(fence: EmergencyResetRecoveryFence) {
        fence.requireValid()
        check(writeRawDurably(JSON.toJSONString(fence))) {
            "紧急复位恢复栅栏同步落盘失败"
        }
    }

    @Synchronized
    fun clear() {
        check(writeRawDurably("")) { "紧急复位恢复栅栏清理同步落盘失败" }
    }

    companion object {
        const val KEY_EMERGENCY_RESET_RECOVERY_FENCE = "sp_emergency_reset_recovery_fence_json_v1"
    }
}

object EmergencyResetForceCloseoutPolicy {
    data class Decision(
        val allowed: Boolean,
        val reason: String
    )

    fun evaluate(
        administratorConfirmed: Boolean,
        maintenanceLocked: Boolean,
        resetFenceMatches: Boolean,
        processBoundaryCrossed: Boolean,
        snapshotDecisions: List<ManualDeviceRecoveryCloseoutDecision>,
        panMoveResults203: List<Int?>
    ): Decision = when {
        !administratorConfirmed -> Decision(false, "管理员尚未确认机械现场、烤盘和库存安全")
        !maintenanceLocked -> Decision(false, "设备尚未锁定维护或算法仍开启")
        !resetFenceMatches -> Decision(false, "紧急复位恢复栅栏已变化")
        !processBoundaryCrossed -> Decision(false, "紧急复位后 App 尚未完成冷重启")
        snapshotDecisions.size != 2 -> Decision(false, "必须连续读取两次下位机空闲证据")
        panMoveResults203.size != 2 -> Decision(false, "必须连续读取两次搬盘空闲证据")
        panMoveResults203.any { it == null } -> Decision(false, "搬盘结果寄存器 203 读取失败")
        panMoveResults203.any { it != 0 } -> Decision(false, "搬盘结果寄存器 203 尚未空闲")
        snapshotDecisions.any { !it.canCloseout } -> Decision(
            false,
            snapshotDecisions.first { !it.canCloseout }.reason
        )
        else -> Decision(true, "紧急复位后 App 已冷重启且下位机连续空闲，允许强制收口")
    }
}
