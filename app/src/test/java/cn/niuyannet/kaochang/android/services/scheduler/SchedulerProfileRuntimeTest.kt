package cn.niuyannet.kaochang.android.services.scheduler

import cn.niuyannet.kaochang.scheduler.LegacySchedulerProfileA
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SchedulerProfileRuntimeTest {

    @Test
    fun activeProfile_isTheSingleCompleteLegacyAInstance() {
        val active = SchedulerProfileRuntime.activeProfile

        assertSame(active, SchedulerProfileRuntime.activeProfile)
        assertEquals(LegacySchedulerProfileA.ID, active.id)
        assertEquals(active.id, active.descriptor.profileId)
        assertEquals("legacy-oldest-holding-v1", active.descriptor.sellCandidateStrategyId.value)
        assertEquals("inventory-proportional-target-v1", active.descriptor.targetAllocationStrategyId.value)
        assertEquals("relative-deficit-taste-mix-v1", active.descriptor.tasteMixStrategyId.value)
        assertEquals("legacy-fixed-batch-heat-position-v1", active.descriptor.batchHeatPositionStrategyId.value)
        assertEquals("legacy-demand-timeout-transition-v1", active.descriptor.demandTransitionStrategyId.value)
    }

    @Test
    fun runtime_exposesNoSwitchSetterOrCatalog() {
        val publicNames = SchedulerProfileRuntime::class.java.methods.map { it.name.lowercase() }

        assertTrue(publicNames.none { it.contains("switch") })
        assertTrue(publicNames.none { it.startsWith("set") })
        assertTrue(publicNames.none { it.contains("catalog") })
    }
}
