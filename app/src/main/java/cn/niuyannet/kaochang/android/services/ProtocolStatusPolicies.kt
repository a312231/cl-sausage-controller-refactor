package cn.niuyannet.kaochang.android.services

enum class PanMove203Decision {
    WaitingForStart,
    Running,
    Success,
    Failed,
    ReadFailed
}

object PanMove203Policy {
    const val pollDelayMs: Long = 500L
    const val waitOtherActionDelayMs: Long = 2_000L

    fun evaluate(
        code: Int,
        hasObservedRunning: Boolean,
        commandRegisterValue: Int? = null
    ): PanMove203Decision {
        return when {
            code == -1 -> PanMove203Decision.ReadFailed
            code == 0x0001 -> PanMove203Decision.Running
            code == 0x0002 && hasObservedRunning -> PanMove203Decision.Success
            code == 0x0002 && commandRegisterValue == 0x0000 -> PanMove203Decision.Success
            code == 0x0000 && hasObservedRunning -> PanMove203Decision.Failed
            code == 0x0000 || code == 0x0002 -> PanMove203Decision.WaitingForStart
            code in 0x00F8..0x00FB -> PanMove203Decision.Failed
            else -> PanMove203Decision.Failed
        }
    }
}

object FourFSelfCleanNoOpPolicy {
    const val message = "4f_self_clean_no_op"

    fun isNoOp(value: String?): Boolean {
        return value == message
    }
}

object Zone3SelfCleanPolicy {
    fun shouldMarkPending(
        zone3Dirty: Int,
        zone3CleanPending: Int,
        zone3CleanSuppressedFor4F: Int,
        zone3HasSausage: Boolean
    ): Boolean {
        return zone3Dirty == 1 &&
            zone3CleanPending != 1 &&
            zone3CleanSuppressedFor4F != 1 &&
            !zone3HasSausage
    }
}

enum class Legacy201Decision {
    WaitingForStart,
    Running,
    Success,
    BusyOrError,
    ReadFailed
}

object Legacy201Policy {
    fun evaluateAfterCommand(status201: Int, hasObservedRunning: Boolean): Legacy201Decision {
        return when (status201) {
            -1 -> Legacy201Decision.ReadFailed
            0 -> if (hasObservedRunning) Legacy201Decision.Success else Legacy201Decision.WaitingForStart
            1 -> Legacy201Decision.Running
            2 -> Legacy201Decision.BusyOrError
            else -> Legacy201Decision.BusyOrError
        }
    }
}

object MoveStatusRecoveryPolicy {
    fun canRecoverIdleFromLegacy201(statusReadings: List<Int>): Boolean {
        return statusReadings.size >= 2 && statusReadings.all { status201 ->
            Legacy201Policy.evaluateAfterCommand(status201, hasObservedRunning = false) == Legacy201Decision.WaitingForStart
        }
    }
}

object SourceBoxPositionPolicy {
    const val POLICY_VERSION = 2
    private val disabledSourceBoxes = setOf(1, 11, 12, 22)

    fun isValidSourceBox(positionSn: Int): Boolean {
        return positionSn in 2..10 || positionSn in 13..21
    }

    fun validRangeText(): String = "2~10、13~21"

    fun validRangePayloadText(): String = "2-10,13-21"

    fun disabledBoxes(): List<Int> = disabledSourceBoxes.sorted()
}

object FourFTemperaturePolicy {
    fun clampTargetTemperature(value: Int): Int {
        return when {
            value <= 0 -> 0
            else -> value
        }
    }
}
