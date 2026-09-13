package dev.ihorshevchuk.piper.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of the memory-pressure rules in Piper (piper-objc, Swift):
 * - per-sentence `memoryThresholdBytes` check in doSynthesize,
 * - DispatchSourceMemoryPressure mapping (.warning -> recreate,
 *   .critical -> release).
 */
class MemoryPressurePolicyTest {

    @Test
    fun `shouldRecreate is false when threshold is null`() {
        assertFalse(MemoryPressurePolicy.shouldRecreate(usageBytes = 1_000_000L, thresholdBytes = null))
    }

    @Test
    fun `shouldRecreate is false when usage cannot be determined`() {
        assertFalse(MemoryPressurePolicy.shouldRecreate(usageBytes = null, thresholdBytes = 1L))
    }

    @Test
    fun `shouldRecreate is false when usage is at or below threshold`() {
        assertFalse(MemoryPressurePolicy.shouldRecreate(usageBytes = 100L, thresholdBytes = 100L))
        assertFalse(MemoryPressurePolicy.shouldRecreate(usageBytes = 99L, thresholdBytes = 100L))
    }

    @Test
    fun `shouldRecreate is true when usage exceeds threshold`() {
        assertTrue(MemoryPressurePolicy.shouldRecreate(usageBytes = 101L, thresholdBytes = 100L))
    }

    @Test
    fun `critical trim releases the synthesizer`() {
        assertEquals(
            MemoryPressurePolicy.Action.RELEASE,
            MemoryPressurePolicy.actionForTrimLevel(MemoryPressurePolicy.TRIM_MEMORY_RUNNING_CRITICAL)
        )
    }

    @Test
    fun `low and moderate running trim recreate the synthesizer`() {
        assertEquals(
            MemoryPressurePolicy.Action.RECREATE,
            MemoryPressurePolicy.actionForTrimLevel(MemoryPressurePolicy.TRIM_MEMORY_RUNNING_LOW)
        )
        assertEquals(
            MemoryPressurePolicy.Action.RECREATE,
            MemoryPressurePolicy.actionForTrimLevel(MemoryPressurePolicy.TRIM_MEMORY_RUNNING_MODERATE)
        )
    }

    @Test
    fun `other trim levels are ignored`() {
        assertEquals(
            MemoryPressurePolicy.Action.NONE,
            MemoryPressurePolicy.actionForTrimLevel(MemoryPressurePolicy.TRIM_MEMORY_UI_HIDDEN)
        )
        assertEquals(
            MemoryPressurePolicy.Action.NONE,
            MemoryPressurePolicy.actionForTrimLevel(0)
        )
    }

    @Test
    fun `trim constants match ComponentCallbacks2 values`() {
        // Guard against drift from the framework values the host forwards.
        assertEquals(5, MemoryPressurePolicy.TRIM_MEMORY_RUNNING_MODERATE)
        assertEquals(10, MemoryPressurePolicy.TRIM_MEMORY_RUNNING_LOW)
        assertEquals(15, MemoryPressurePolicy.TRIM_MEMORY_RUNNING_CRITICAL)
        assertEquals(20, MemoryPressurePolicy.TRIM_MEMORY_UI_HIDDEN)
    }
}
