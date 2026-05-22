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

object SourceBoxPositionPolicy {
    fun isValidSourceBox(positionSn: Int): Boolean {
        return positionSn in 1..22 && positionSn != 11 && positionSn != 22
    }

    fun validRangeText(): String = "1~10、12~21"
}

object FourFTemperaturePolicy {
    fun clampTargetTemperature(value: Int): Int {
        return when {
            value <= 0 -> 0
            else -> value
        }
    }
}
