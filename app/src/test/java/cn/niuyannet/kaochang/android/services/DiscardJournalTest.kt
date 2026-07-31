package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.services.actiongroup.MechanicalClaimState
import org.junit.Assert.assertEquals
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
            mechanicalClaimRevision = 2L
        )

        val restored = com.alibaba.fastjson.JSON.parseObject(
            com.alibaba.fastjson.JSON.toJSONString(journal),
            DiscardJournal::class.java
        )

        assertEquals(journal, restored)
    }
}
