package dev.ihorshevchuk.piper.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies [OnnxThreadPolicy]: how many ONNX Runtime intra-op threads the
 * native engine should request from the device's CPU count.
 *
 * Context: pinned piper1-gpl hard-codes SetIntraOpNumThreads(1), leaving
 * 7 of 8 cores idle on e.g. a Galaxy A13 - one sentence takes 7-10 s to
 * synthesize. The native patch asks the JVM for this number instead.
 */
class OnnxThreadPolicyTest {

    @Test
    fun `zero or unknown core count falls back to single thread`() {
        assertEquals(1, OnnxThreadPolicy.intraOpThreads(0))
        assertEquals(1, OnnxThreadPolicy.intraOpThreads(-4))
    }

    @Test
    fun `single and dual core devices stay single threaded`() {
        assertEquals(1, OnnxThreadPolicy.intraOpThreads(1))
        assertEquals(1, OnnxThreadPolicy.intraOpThreads(2))
    }

    @Test
    fun `four cores use two threads leaving headroom for audio io`() {
        assertEquals(2, OnnxThreadPolicy.intraOpThreads(4))
    }

    @Test
    fun `eight cores use six threads`() {
        assertEquals(6, OnnxThreadPolicy.intraOpThreads(8))
    }

    @Test
    fun `thread count never exceeds eight even on many core devices`() {
        assertEquals(8, OnnxThreadPolicy.intraOpThreads(12))
        assertEquals(8, OnnxThreadPolicy.intraOpThreads(64))
    }

    @Test
    fun `thread count never exceeds available cores`() {
        for (cores in 1..16) {
            assertTrue(
                "threads for $cores cores must be in 1..min(8, cores)",
                OnnxThreadPolicy.intraOpThreads(cores) in 1..minOf(8, cores)
            )
        }
    }

    @Test
    fun `default uses the runtime processor count`() {
        val threads = OnnxThreadPolicy.intraOpThreads()
        assertTrue(threads >= 1)
        assertTrue(threads <= 8)
    }
}
