package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.mqtt.ConsumableEvent
import cn.niuyannet.kaochang.android.services.actiongroup.MechanicalClaimState
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscardJournalTest {
    @Test
    fun journal_shouldCarryClaimAndCommandCheckpointAcrossSerialization() {
        val journal = DiscardJournal(
            owner = "discard:7:1",
            positionSn = 7,
            checkpoint = DiscardCheckpoint.COMMAND_ISSUED,
            createdAtMs = 1L,
            mechanicalClaimState = MechanicalClaimState.CLAIMED,
            mechanicalClaimRevision = 2L,
            wasteConsumableEvent = wasteEvent()
        )

        val restored = com.alibaba.fastjson.JSON.parseObject(
            com.alibaba.fastjson.JSON.toJSONString(journal),
            DiscardJournal::class.java
        )

        assertEquals(journal, restored)
    }

    @Test
    fun recoveryPolicy_shouldRejectCommandIssuedJournalWithoutStableIdleAndManualConfirmation() {
        val journal = journal(DiscardCheckpoint.COMMAND_ISSUED)

        assertFalse(
            DiscardJournalRecoveryPolicy.evaluate(
                journal = journal,
                onlineStatus = 3,
                isEnable = 0,
                moveStatus = 4,
                status201Readings = listOf(0, 1),
                actionGroupResultStates = listOf(AtomicActionCodes.IDLE, AtomicActionCodes.COMPLETED),
                physicalResult = DiscardPhysicalResult.DISCARDED
            ).canClose
        )
        assertFalse(
            DiscardJournalRecoveryPolicy.evaluate(
                journal = journal,
                onlineStatus = 3,
                isEnable = 0,
                moveStatus = 4,
                status201Readings = listOf(0, 0),
                actionGroupResultStates = listOf(AtomicActionCodes.IDLE, AtomicActionCodes.COMPLETED),
                physicalResult = null
            ).canClose
        )
    }

    @Test
    fun recoveryPolicy_shouldAllowCommandIssuedCloseoutOnlyFromMaintenanceWithMatchingBusyState() {
        val journal = journal(DiscardCheckpoint.COMMAND_ISSUED)

        assertFalse(
            DiscardJournalRecoveryPolicy.evaluate(
                journal = journal,
                onlineStatus = 1,
                isEnable = 0,
                moveStatus = 4,
                status201Readings = listOf(0, 0),
                actionGroupResultStates = listOf(AtomicActionCodes.IDLE),
                physicalResult = DiscardPhysicalResult.NOT_DISCARDED
            ).canClose
        )
        assertFalse(
            DiscardJournalRecoveryPolicy.evaluate(
                journal = journal,
                onlineStatus = 3,
                isEnable = 1,
                moveStatus = 4,
                status201Readings = listOf(0, 0),
                actionGroupResultStates = listOf(AtomicActionCodes.IDLE),
                physicalResult = DiscardPhysicalResult.NOT_DISCARDED
            ).canClose
        )
        assertFalse(
            DiscardJournalRecoveryPolicy.evaluate(
                journal = journal,
                onlineStatus = 3,
                isEnable = 0,
                moveStatus = 0,
                status201Readings = listOf(0, 0),
                actionGroupResultStates = listOf(AtomicActionCodes.IDLE),
                physicalResult = DiscardPhysicalResult.NOT_DISCARDED
            ).canClose
        )
        assertTrue(
            DiscardJournalRecoveryPolicy.evaluate(
                journal = journal,
                onlineStatus = 3,
                isEnable = 0,
                moveStatus = 4,
                status201Readings = listOf(0, 0),
                actionGroupResultStates = listOf(AtomicActionCodes.IDLE, AtomicActionCodes.COMPLETED),
                physicalResult = DiscardPhysicalResult.NOT_DISCARDED
            ).canClose
        )
    }

    @Test
    fun recoveryPolicy_shouldAllowPreparedJournalWithoutPhysicalResultWhenMachineIsStableIdle() {
        val decision = DiscardJournalRecoveryPolicy.evaluate(
            journal = journal(DiscardCheckpoint.PREPARED),
            onlineStatus = 3,
            isEnable = 0,
            moveStatus = 4,
            status201Readings = listOf(0, 0),
            actionGroupResultStates = listOf(AtomicActionCodes.IDLE, AtomicActionCodes.COMPLETED),
            physicalResult = null
        )

        assertTrue(decision.canClose)
        assertEquals(false, decision.clearPan)
    }

    @Test
    fun recoveryPolicy_shouldTellCommitWhetherConfirmedPanMustBeCleared() {
        val journal = journal(DiscardCheckpoint.COMMAND_ISSUED)

        val discarded = DiscardJournalRecoveryPolicy.evaluate(
            journal = journal,
            onlineStatus = 3,
            isEnable = 0,
            moveStatus = 4,
            status201Readings = listOf(0, 0),
            actionGroupResultStates = listOf(AtomicActionCodes.IDLE, AtomicActionCodes.COMPLETED),
            physicalResult = DiscardPhysicalResult.DISCARDED
        )
        val notDiscarded = DiscardJournalRecoveryPolicy.evaluate(
            journal = journal,
            onlineStatus = 3,
            isEnable = 0,
            moveStatus = 4,
            status201Readings = listOf(0, 0),
            actionGroupResultStates = listOf(AtomicActionCodes.IDLE, AtomicActionCodes.COMPLETED),
            physicalResult = DiscardPhysicalResult.NOT_DISCARDED
        )

        assertTrue(discarded.canClose)
        assertTrue(discarded.clearPan)
        assertTrue(notDiscarded.canClose)
        assertFalse(notDiscarded.clearPan)
    }

    @Test
    fun recoveryPolicy_shouldRejectUnreadableOrRunningActionGroupResults() {
        val journal = journal(DiscardCheckpoint.COMMAND_ISSUED)

        assertFalse(
            DiscardJournalRecoveryPolicy.evaluate(
                journal = journal,
                onlineStatus = 3,
                isEnable = 0,
                moveStatus = 4,
                status201Readings = listOf(0, 0),
                actionGroupResultStates = null,
                physicalResult = DiscardPhysicalResult.DISCARDED
            ).canClose
        )
        assertFalse(
            DiscardJournalRecoveryPolicy.evaluate(
                journal = journal,
                onlineStatus = 3,
                isEnable = 0,
                moveStatus = 4,
                status201Readings = listOf(0, 0),
                actionGroupResultStates = listOf(AtomicActionCodes.IDLE, AtomicActionCodes.RUNNING),
                physicalResult = DiscardPhysicalResult.DISCARDED
            ).canClose
        )
        assertFalse(
            DiscardJournalRecoveryPolicy.evaluate(
                journal = journal,
                onlineStatus = 3,
                isEnable = 0,
                moveStatus = 4,
                status201Readings = listOf(0, 0),
                actionGroupResultStates = listOf(AtomicActionCodes.IDLE, AtomicActionCodes.PARAM_ERROR),
                physicalResult = DiscardPhysicalResult.DISCARDED
            ).canClose
        )
        assertFalse(
            DiscardJournalRecoveryPolicy.evaluate(
                journal = journal,
                onlineStatus = 3,
                isEnable = 0,
                moveStatus = 4,
                status201Readings = listOf(0, 0),
                actionGroupResultStates = listOf(AtomicActionCodes.IDLE, AtomicActionCodes.MOVE_TIMEOUT),
                physicalResult = DiscardPhysicalResult.DISCARDED
            ).canClose
        )
    }

    @Test
    fun recoveryPolicy_shouldRejectCorruptJournalSentinel() {
        val corrupt = journal(DiscardCheckpoint.COMMAND_ISSUED).copy(positionSn = 0)

        val decision = DiscardJournalRecoveryPolicy.evaluate(
            journal = corrupt,
            onlineStatus = 3,
            isEnable = 0,
            moveStatus = 4,
            status201Readings = listOf(0, 0),
            actionGroupResultStates = listOf(AtomicActionCodes.IDLE),
            physicalResult = DiscardPhysicalResult.NOT_DISCARDED
        )

        assertFalse(decision.canClose)
    }

    @Test
    fun recoveryPolicy_shouldMigrateLegacySourceOnlyFromPendingDiscardEvidence() {
        val legacy = journal(DiscardCheckpoint.COMMAND_ISSUED).copy(source = null)

        assertEquals(
            "过保到期",
            DiscardJournalRecoveryPolicy.resolveSource(legacy, pendingDiscardPositions = setOf(7))
        )
        assertEquals(
            null,
            DiscardJournalRecoveryPolicy.resolveSource(legacy, pendingDiscardPositions = emptySet())
        )
    }

    private fun journal(checkpoint: DiscardCheckpoint) = DiscardJournal(
        owner = "discard:7:1",
        positionSn = 7,
        checkpoint = checkpoint,
        createdAtMs = 1L,
        mechanicalClaimState = MechanicalClaimState.CLAIMED,
        mechanicalClaimRevision = 2L
    )

    private fun wasteEvent() = ConsumableEvent(
        deviceCode = "device-001",
        consumableType = "WASTE",
        eventType = "WASTE_DISCARD_EXPIRED",
        deltaValue = 1L,
        consumableEventEpoch = "epoch-a",
        consumableEventSeq = 7L,
        sourceEventKey = "device-001:epoch-a:7",
        source = "discardSausage_success",
        detail = "pan=7",
        eventTime = 1L
    )
}
