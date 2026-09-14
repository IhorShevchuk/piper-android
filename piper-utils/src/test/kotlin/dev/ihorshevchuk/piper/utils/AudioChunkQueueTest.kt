package dev.ihorshevchuk.piper.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Covers the producer/consumer handoff behind streaming playback with
 * sentence lookahead: the synthesizer thread offers PCM chunks while the
 * AudioTrack thread takes them, so the next sentence is already being
 * synthesized while the current one is still playing.
 */
class AudioChunkQueueTest {

    private fun chunk(vararg samples: Float, sentenceStart: Boolean = false) =
        AudioChunkQueue.Chunk(floatArrayOf(*samples), sentenceStart)

    @Test
    fun offerThenTake_returnsChunksInOrder() {
        val q = AudioChunkQueue()
        q.offer(chunk(1f))
        q.offer(chunk(2f))
        q.offer(chunk(3f))
        q.finish()

        assertEquals(1f, q.take()!!.samples[0], 0f)
        assertEquals(2f, q.take()!!.samples[0], 0f)
        assertEquals(3f, q.take()!!.samples[0], 0f)
        assertNull(q.take())
    }

    @Test
    fun sentenceStartFlag_survivesRoundTrip() {
        val q = AudioChunkQueue()
        q.offer(chunk(1f, sentenceStart = true))
        q.offer(chunk(2f, sentenceStart = false))
        q.finish()

        assertTrue(q.take()!!.sentenceStart)
        assertFalse(q.take()!!.sentenceStart)
    }

    @Test
    fun take_blocksUntilChunkOffered() {
        val q = AudioChunkQueue()
        val received = AtomicReference<AudioChunkQueue.Chunk?>()
        val taken = CountDownLatch(1)
        Thread {
            received.set(q.take())
            taken.countDown()
        }.apply { isDaemon = true; start() }

        // The taker must still be blocked before the offer lands.
        assertFalse(taken.await(200, TimeUnit.MILLISECONDS))
        q.offer(chunk(7f))
        assertTrue(taken.await(5, TimeUnit.SECONDS))
        assertEquals(7f, received.get()!!.samples[0], 0f)
    }

    @Test
    fun take_onEmptyFinishedQueue_returnsNullImmediately() {
        val q = AudioChunkQueue()
        q.finish()
        assertNull(q.take())
    }

    @Test
    fun finish_unblocksWaitingTake_withNull() {
        val q = AudioChunkQueue()
        val received = AtomicReference<AudioChunkQueue.Chunk?>(chunk(9f))
        val done = CountDownLatch(1)
        Thread {
            received.set(q.take())
            done.countDown()
        }.apply { isDaemon = true; start() }

        assertFalse(done.await(200, TimeUnit.MILLISECONDS))
        q.finish()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertNull(received.get())
    }

    @Test
    fun abort_unblocksWaitingTake_withNull() {
        val q = AudioChunkQueue()
        val received = AtomicReference<AudioChunkQueue.Chunk?>(chunk(9f))
        val done = CountDownLatch(1)
        Thread {
            received.set(q.take())
            done.countDown()
        }.apply { isDaemon = true; start() }

        assertFalse(done.await(200, TimeUnit.MILLISECONDS))
        q.abort()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertNull(received.get())
    }

    @Test
    fun offer_afterAbort_returnsFalse() {
        val q = AudioChunkQueue()
        q.abort()
        assertFalse(q.offer(chunk(1f)))
        assertNull(q.take())
    }

    @Test
    fun offer_blocksWhenFull_untilTakeFreesSpace() {
        val q = AudioChunkQueue(capacity = 2)
        assertTrue(q.offer(chunk(1f)))
        assertTrue(q.offer(chunk(2f)))

        val offered = CountDownLatch(1)
        Thread {
            q.offer(chunk(3f))
            offered.countDown()
        }.apply { isDaemon = true; start() }

        // Queue is full: the third offer must block.
        assertFalse(offered.await(300, TimeUnit.MILLISECONDS))
        assertEquals(1f, q.take()!!.samples[0], 0f)
        assertTrue(offered.await(5, TimeUnit.SECONDS))

        q.finish()
        assertEquals(2f, q.take()!!.samples[0], 0f)
        assertEquals(3f, q.take()!!.samples[0], 0f)
        assertNull(q.take())
    }

    @Test
    fun abort_unblocksProducerWaitingForSpace() {
        val q = AudioChunkQueue(capacity = 1)
        assertTrue(q.offer(chunk(1f)))

        val done = CountDownLatch(1)
        val result = AtomicReference<Boolean?>(null)
        Thread {
            result.set(q.offer(chunk(2f)))
            done.countDown()
        }.apply { isDaemon = true; start() }

        assertFalse(done.await(300, TimeUnit.MILLISECONDS))
        q.abort()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        // The blocked offer must give up instead of hanging.
        assertEquals(false, result.get())
    }
}
