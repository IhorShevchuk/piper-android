package dev.ihorshevchuk.piper.utils

/**
 * Single-producer / single-consumer PCM chunk handoff behind streaming
 * playback with sentence lookahead.
 *
 * The synthesizer thread offers chunks as they are produced (marking the
 * first chunk of every sentence) while the AudioTrack thread takes them in
 * order. Because synthesis runs ahead of playback, the next sentence's
 * `piper_synthesize_start` (espeak + encoder + first decoder inference)
 * overlaps the current sentence's audio instead of gating it: no audible
 * gap at sentence boundaries, even when synthesis runs near real-time on
 * slow devices.
 *
 * The queue is bounded ([capacity] chunks): a runaway producer blocks in
 * [offer] instead of buffering a whole article in RAM. [finish] marks the
 * end of the stream (a draining [take] then returns null); [abort] drops
 * everything and unblocks both sides immediately for stop().
 *
 * Threading: [take] and a full-queue [offer] block with wait/notify and
 * let [InterruptedException] propagate so stop() can interrupt either side.
 */
class AudioChunkQueue(private val capacity: Int = DEFAULT_CAPACITY) {

    data class Chunk(
        val samples: FloatArray,
        /** True for the first chunk of a sentence: the consumer fires onMarker. */
        val sentenceStart: Boolean = false
    )

    private val deque = ArrayDeque<Chunk>()
    private var finished = false
    private var aborted = false

    /**
     * Hands [chunk] to the consumer, blocking while the queue is full.
     * @return false when the queue was aborted: the producer must stop.
     */
    @Synchronized
    fun offer(chunk: Chunk): Boolean {
        while (deque.size >= capacity && !aborted) {
            (this as Object).wait()
        }
        if (aborted) return false
        deque.addLast(chunk)
        (this as Object).notifyAll()
        return true
    }

    /**
     * Takes the next chunk in order, blocking while the queue is empty.
     * @return null once the producer finished and the queue drained, or
     * after [abort].
     */
    @Synchronized
    fun take(): Chunk? {
        while (deque.isEmpty() && !finished && !aborted) {
            (this as Object).wait()
        }
        if (deque.isNotEmpty()) {
            val chunk = deque.removeFirst()
            // Wake a producer blocked on a full queue.
            (this as Object).notifyAll()
            return chunk
        }
        return null
    }

    /** Marks the end of the stream; a draining [take] then returns null. */
    @Synchronized
    fun finish() {
        finished = true
        (this as Object).notifyAll()
    }

    /** Drops all buffered chunks and unblocks both sides immediately. */
    @Synchronized
    fun abort() {
        aborted = true
        deque.clear()
        (this as Object).notifyAll()
    }

    companion object {
        /** 64 chunks ≈ up to ~15s of 22050 Hz audio buffered, ~2 MB worst case. */
        const val DEFAULT_CAPACITY = 64
    }
}
