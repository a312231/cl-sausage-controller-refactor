package cn.niuyannet.kaochang.scheduler

import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SchedulerProfileSwitchPolicyTest {

    private val algorithmA = LegacySchedulerProfileA.create()
    private val algorithmB = createDistinctTestProfile("test-b").profile
    private val catalog = SchedulerProfileCatalog(listOf(algorithmA, algorithmB))
    private val idle = SchedulerProfileSwitchRuntimeSnapshot(
        deviceIdle = true,
        hasActiveOrder = false,
        hasWholeOrderClaim = false,
        hasOutActionSession = false,
        hasSupplementOwner = false,
        hasCleaningOwner = false,
        hasMaintenanceOwner = false
    )

    @Test
    fun `complete profiles switch in either direction only when fully idle`() {
        val constructors = SchedulerProfileSwitchPlan::class.java.declaredConstructors
            .filterNot { it.isSynthetic }
        assertTrue(constructors.isNotEmpty())
        assertTrue(constructors.all { Modifier.isPrivate(it.modifiers) })

        val aToB = plan(algorithmA.id.value, algorithmB.id.value, idle)
        assertEquals(SchedulerProfileSwitchDecisionCode.SWITCH_ALLOWED, aToB.code)
        assertEquals(algorithmA.id, aToB.currentProfileId)
        assertEquals(algorithmB.id, aToB.requestedProfileId)
        assertTrue(aToB.requiresPersistence)

        val bToA = plan(algorithmB.id.value, algorithmA.id.value, idle)
        assertEquals(SchedulerProfileSwitchDecisionCode.SWITCH_ALLOWED, bToA.code)
    }

    @Test
    fun `same registered profile is no change even while runtime is busy`() {
        val result = plan(
            algorithmA.id.value,
            algorithmA.id.value,
            idle.copy(deviceIdle = false, hasActiveOrder = true)
        )

        assertEquals(SchedulerProfileSwitchDecisionCode.NO_CHANGE, result.code)
        assertFalse(result.requiresPersistence)
        assertTrue(result.blockers.isEmpty())
    }

    @Test
    fun `invalid and unknown ids use stable fail closed precedence`() {
        listOf<String?>(null, "", " ${algorithmB.id.value}", "ALGORITHM-B", "ab", "profile/b")
            .forEach { raw ->
                assertEquals(
                    SchedulerProfileSwitchDecisionCode.REQUESTED_PROFILE_ID_INVALID,
                    plan(algorithmA.id.value, raw, idle).code
                )
                assertEquals(
                    SchedulerProfileSwitchDecisionCode.CURRENT_PROFILE_ID_INVALID,
                    plan(raw, algorithmB.id.value, idle).code
                )
            }

        val busy = idle.copy(deviceIdle = false, hasActiveOrder = true)
        assertEquals(
            SchedulerProfileSwitchDecisionCode.REQUESTED_PROFILE_NOT_REGISTERED,
            plan("missing-profile-v1", "missing-profile-v2", busy).code
        )
        assertEquals(
            SchedulerProfileSwitchDecisionCode.CURRENT_PROFILE_NOT_REGISTERED,
            plan("missing-profile-v1", algorithmB.id.value, busy).code
        )
    }

    @Test
    fun `each runtime owner blocks a real profile change`() {
        val cases = listOf(
            idle.copy(deviceIdle = false) to SchedulerProfileSwitchBlocker.DEVICE_NOT_IDLE,
            idle.copy(hasActiveOrder = true) to SchedulerProfileSwitchBlocker.ACTIVE_ORDER,
            idle.copy(hasWholeOrderClaim = true) to SchedulerProfileSwitchBlocker.WHOLE_ORDER_CLAIM,
            idle.copy(hasOutActionSession = true) to SchedulerProfileSwitchBlocker.OUT_ACTION_SESSION,
            idle.copy(hasSupplementOwner = true) to SchedulerProfileSwitchBlocker.SUPPLEMENT_OWNER,
            idle.copy(hasCleaningOwner = true) to SchedulerProfileSwitchBlocker.CLEANING_OWNER,
            idle.copy(hasMaintenanceOwner = true) to SchedulerProfileSwitchBlocker.MAINTENANCE_OWNER
        )

        cases.forEach { (snapshot, blocker) ->
            val result = plan(algorithmA.id.value, algorithmB.id.value, snapshot)
            assertEquals(SchedulerProfileSwitchDecisionCode.RUNTIME_BUSY, result.code)
            assertEquals(listOf(blocker), result.blockers)
            assertFalse(result.requiresPersistence)
        }
    }

    @Test
    fun `multiple blockers are complete deterministic and immutable`() {
        val result = plan(
            algorithmA.id.value,
            algorithmB.id.value,
            SchedulerProfileSwitchRuntimeSnapshot(
                deviceIdle = false,
                hasActiveOrder = true,
                hasWholeOrderClaim = true,
                hasOutActionSession = true,
                hasSupplementOwner = true,
                hasCleaningOwner = true,
                hasMaintenanceOwner = true
            )
        )

        assertEquals(SchedulerProfileSwitchBlocker.entries.toList(), result.blockers)
        assertThrows(UnsupportedOperationException::class.java) {
            (result.blockers as MutableList<SchedulerProfileSwitchBlocker>).clear()
        }
    }

    private fun plan(
        currentId: String?,
        requestedId: String?,
        runtime: SchedulerProfileSwitchRuntimeSnapshot
    ): SchedulerProfileSwitchPlan = SchedulerProfileSwitchPolicy.plan(
        catalog = catalog,
        currentId = currentId,
        requestedId = requestedId,
        runtime = runtime
    )
}
