package cn.niuyannet.kaochang.android.modbus

class EmergencyResetMechanicalWriteGate(
    private val mechanicalCommandAddresses: Set<Int> = setOf(
        200,
        202,
        310, 320, 330, 340, 350, 360, 370,
        410, 420, 430, 440, 450
    )
) {
    private val lock = Any()
    private var mechanicalCommandBlocked = false

    fun <T> executeWrite(
        address: Int,
        rejected: () -> T,
        write: () -> T
    ): T = synchronized(lock) {
        if (mechanicalCommandBlocked && address in mechanicalCommandAddresses) {
            rejected()
        } else {
            write()
        }
    }

    fun <T> blockAndExecuteEmergencyReset(writeReset: () -> T): T = synchronized(lock) {
        mechanicalCommandBlocked = true
        writeReset()
    }

    fun blockMechanicalCommands() = synchronized(lock) {
        mechanicalCommandBlocked = true
    }

    fun clearAfterRecovery() = synchronized(lock) {
        mechanicalCommandBlocked = false
    }

    fun isMechanicalCommandBlocked(): Boolean = synchronized(lock) {
        mechanicalCommandBlocked
    }
}
