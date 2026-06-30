package cn.niuyannet.kaochang.android.mqtt

import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionGroupTelemetryPayloadBuilderTest {

    @Test
    fun build_shouldIncludePhysicalCheckpointFields() {
        val payload = ActionGroupTelemetryPayloadBuilder.build(
            deviceCode = "device-001",
            telemetry = ActionGroupTelemetry(
                eventId = "session-1:in7:100:finished",
                eventPhase = "finished",
                reportedAt = 1779034500000L,
                sessionId = "session-1",
                actionName = "in7",
                registerAddress = 370,
                resultRegister = 371,
                resultCode = 2,
                errorCode = null,
                errorMessage = null,
                boxNo = 5,
                panNo = 9,
                orderId = "order-1",
                resourceLocks = "BOTTOM|LIFT|ABOVE",
                physicalCheckpoint = "SUPPLEMENT_PLACED_ON_PAN",
                physicalLocation = "PAN",
                elapsedMs = 1200L
            )
        )

        assertEquals("action_group_telemetry", payload.getString("messageType"))
        assertEquals("device-001", payload.getString("deviceCode"))
        assertEquals("SUPPLEMENT_PLACED_ON_PAN", payload.getString("physicalCheckpoint"))
        assertEquals("PAN", payload.getString("physicalLocation"))
        assertTrue(payload.containsKey("physicalCheckpoint"))
        assertTrue(payload.containsKey("physicalLocation"))
    }
}
