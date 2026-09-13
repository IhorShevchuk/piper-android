package dev.ihorshevchuk.piper.engine

import dev.ihorshevchuk.piper.utils.AlignmentParser
import dev.ihorshevchuk.piper.utils.MarkerRange
import dev.ihorshevchuk.piper.utils.PhonemeGroup
import dev.ihorshevchuk.piper.utils.PlannedSentence
import dev.ihorshevchuk.piper.utils.SpeechMarker
import dev.ihorshevchuk.piper.utils.SpeechMarkerType
import dev.ihorshevchuk.piper.utils.SynthesisPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [SynthesisOrchestrator], the unit-testable sentence loop behind
 * PiperEngine (port of Piper.doSynthesize). A [FakeNativeSynth] stands in for
 * JNI so the orchestration - sentence skipping, cumulative marker byte
 * offsets, per-sentence option resolution, cancellation - is verified on JVM.
 */
class SynthesisOrchestratorTest {

    private class FakeNativeSynth : NativeSynth {
        data class StartCall(val sentence: String, val options: PiperSynthesizeOptions)

        val startCalls = mutableListOf<StartCall>()
        var startResult: (String) -> Int = { NativeSynth.OK }
        val chunks = ArrayDeque<AudioChunk?>()
        private var streamEnded = true

        override fun start(sentence: String, options: PiperSynthesizeOptions): Int {
            startCalls += StartCall(sentence, options)
            streamEnded = false
            return startResult(sentence)
        }

        override fun next(): AudioChunk? {
            if (streamEnded || chunks.isEmpty()) return null
            val chunk = chunks.removeFirst()
            if (chunk == null || chunk.isLast) streamEnded = true
            return chunk
        }
    }

    private val baseOptions = PiperSynthesizeOptions(speakerId = 3, lengthScale = 0.9f)

    private fun chunk(
        samples: Int,
        sampleRate: Int = 22050,
        isLast: Boolean = true,
        phonemes: IntArray? = null,
        phonemeIds: IntArray? = null,
        alignments: IntArray? = null
    ) = AudioChunk(
        samples = FloatArray(samples),
        sampleRate = sampleRate,
        isLast = isLast,
        phonemes = phonemes,
        phonemeIds = phonemeIds,
        alignments = alignments
    )

    private fun planned(vararg texts: String): List<PlannedSentence> {
        var offset = 0
        return texts.map { text ->
            PlannedSentence(text, MarkerRange(offset, text.length)).also {
                offset += text.length + 1
            }
        }
    }

    @Test
    fun `synthesizes every sentence and streams its samples`() {
        val fake = FakeNativeSynth()
        fake.chunks += chunk(100)
        fake.chunks += chunk(50)
        val orchestrator = SynthesisOrchestrator(fake)
        val seen = mutableListOf<FloatArray>()

        orchestrator.synthesize(
            planned("Hello.", "World."),
            resolveOptions = { baseOptions },
            isCancelled = { false },
            callbacks = SynthesisOrchestrator.Callbacks(onSamples = { seen += it })
        )

        assertEquals(listOf("Hello.", "World."), fake.startCalls.map { it.sentence })
        assertEquals(listOf(100, 50), seen.map { it.size })
    }

    @Test
    fun `markers carry cumulative byte offsets across sentences`() {
        val fake = FakeNativeSynth()
        fake.chunks += chunk(100) // 400 bytes
        fake.chunks += chunk(50)  // 200 bytes
        val orchestrator = SynthesisOrchestrator(fake)
        val markers = mutableListOf<SpeechMarker>()

        val totalBytes = orchestrator.synthesize(
            planned("Hello.", "World."),
            resolveOptions = { baseOptions },
            isCancelled = { false },
            callbacks = SynthesisOrchestrator.Callbacks(onMarkers = { markers += it })
        )

        assertEquals(600L, totalBytes)
        val sentenceMarkers = markers.filter { it.type == SpeechMarkerType.SENTENCE }
        assertEquals(2, sentenceMarkers.size)
        assertEquals(0, sentenceMarkers[0].byteOffset)
        assertEquals(400, sentenceMarkers[1].byteOffset)
        assertEquals(MarkerRange(0, 6), sentenceMarkers[0].range)
        assertEquals(MarkerRange(7, 6), sentenceMarkers[1].range)
    }

    @Test
    fun `skips sentence that fails to start and continues with the rest`() {
        val fake = FakeNativeSynth()
        fake.startResult = { sentence ->
            if (sentence == "Bad.") NativeSynth.ERR_GENERIC else NativeSynth.OK
        }
        fake.chunks += chunk(100)
        fake.chunks += chunk(50)
        val orchestrator = SynthesisOrchestrator(fake)
        val seen = mutableListOf<FloatArray>()

        orchestrator.synthesize(
            planned("Good.", "Bad.", "Also good."),
            resolveOptions = { baseOptions },
            isCancelled = { false },
            callbacks = SynthesisOrchestrator.Callbacks(onSamples = { seen += it })
        )

        assertEquals(3, fake.startCalls.size)
        assertEquals(2, seen.size)
        assertEquals(listOf(100, 50), seen.map { it.size })
    }

    @Test
    fun `stops between sentences when cancelled`() {
        val fake = FakeNativeSynth()
        fake.chunks += chunk(100)
        fake.chunks += chunk(50)
        val orchestrator = SynthesisOrchestrator(fake)
        var calls = 0

        orchestrator.synthesize(
            planned("One.", "Two."),
            resolveOptions = { baseOptions },
            isCancelled = { ++calls > 1 },
            callbacks = SynthesisOrchestrator.Callbacks()
        )

        assertEquals(listOf("One."), fake.startCalls.map { it.sentence })
    }

    @Test
    fun `resolves options per sentence`() {
        val fake = FakeNativeSynth()
        fake.chunks += chunk(10)
        fake.chunks += chunk(10)
        val orchestrator = SynthesisOrchestrator(fake)

        orchestrator.synthesize(
            planned("Slow.", "Fast."),
            resolveOptions = { sentence ->
                baseOptions.copy(
                    lengthScale = if (sentence.text == "Slow.") 2.0f else 0.5f
                )
            },
            isCancelled = { false },
            callbacks = SynthesisOrchestrator.Callbacks()
        )

        assertEquals(
            listOf(2.0f, 0.5f),
            fake.startCalls.map { it.options.lengthScale }
        )
        assertTrue(fake.startCalls.all { it.options.speakerId == 3 })
    }

    @Test
    fun `emits alignment groups per chunk and word markers from them`() {
        val fake = FakeNativeSynth()
        fake.chunks += chunk(
            samples = 250,
            phonemes = intArrayOf('H'.code, 'i'.code, 0),
            phonemeIds = intArrayOf(5, 6),
            alignments = intArrayOf(100, 150)
        )
        val orchestrator = SynthesisOrchestrator(fake)
        val groups = mutableListOf<PhonemeGroup>()
        val markers = mutableListOf<SpeechMarker>()

        orchestrator.synthesize(
            planned("Hi."),
            resolveOptions = { baseOptions },
            isCancelled = { false },
            callbacks = SynthesisOrchestrator.Callbacks(
                onAlignment = { groups += it },
                onMarkers = { markers += it }
            )
        )

        val expected = AlignmentParser.group(
            intArrayOf('H'.code, 'i'.code, 0),
            intArrayOf(5, 6),
            intArrayOf(100, 150)
        )
        assertEquals(expected, groups)
        // Sentence marker plus at least one word marker from the alignment.
        assertTrue(markers.count { it.type == SpeechMarkerType.SENTENCE } == 1)
        assertTrue(markers.any { it.type == SpeechMarkerType.WORD })
    }

    @Test
    fun `sentence without a source range is synthesized but emits no markers`() {
        val fake = FakeNativeSynth()
        fake.chunks += chunk(100)
        val orchestrator = SynthesisOrchestrator(fake)
        val seen = mutableListOf<FloatArray>()
        val markers = mutableListOf<SpeechMarker>()

        orchestrator.synthesize(
            listOf(
                PlannedSentence("Hello world.", SynthesisPlanner.RANGE_NOT_FOUND)
            ),
            resolveOptions = { baseOptions },
            isCancelled = { false },
            callbacks = SynthesisOrchestrator.Callbacks(
                onSamples = { seen += it },
                onMarkers = { markers += it }
            )
        )

        // Audio still produced (Swift synthesizes with NSNotFound), markers skipped.
        assertEquals(listOf("Hello world."), fake.startCalls.map { it.sentence })
        assertEquals(1, seen.size)
        assertTrue(markers.isEmpty())
    }

    @Test
    fun `reports the sample rate of each chunk`() {        val fake = FakeNativeSynth()
        fake.chunks += chunk(10, sampleRate = 16000, isLast = false)
        fake.chunks += chunk(10, sampleRate = 16000, isLast = true)
        val orchestrator = SynthesisOrchestrator(fake)
        val rates = mutableListOf<Int>()

        orchestrator.synthesize(
            planned("Hi."),
            resolveOptions = { baseOptions },
            isCancelled = { false },
            callbacks = SynthesisOrchestrator.Callbacks(onSampleRate = { rates += it })
        )

        assertEquals(listOf(16000, 16000), rates)
    }

    // MARK: - 1.0.8 Kindle / 1.0.12 long-utterance regression guards

    @Test
    fun `byte offsets reset between consecutive runs - Kindle page-turn guard`() {
        // 1.0.8: the second page started at the first page's total byte offset,
        // so Kindle jumped back to the contents. Each synthesize() call must
        // start its markers at 0.
        val fake = FakeNativeSynth()
        val orchestrator = SynthesisOrchestrator(fake)
        val markers = mutableListOf<SpeechMarker>()
        val callbacks = SynthesisOrchestrator.Callbacks(onMarkers = { markers += it })

        fake.chunks += chunk(100) // 400 bytes
        orchestrator.synthesize(
            planned("Page one."),
            resolveOptions = { baseOptions },
            isCancelled = { false },
            callbacks = callbacks
        )
        fake.chunks += chunk(100)
        orchestrator.synthesize(
            planned("Page two."),
            resolveOptions = { baseOptions },
            isCancelled = { false },
            callbacks = callbacks
        )

        val sentenceMarkers = markers.filter { it.type == SpeechMarkerType.SENTENCE }
        assertEquals(2, sentenceMarkers.size)
        assertEquals(0, sentenceMarkers[0].byteOffset)
        assertEquals(
            "Second synthesis must start at 0, not continue at 400 - 1.0.8 Kindle bug",
            0,
            sentenceMarkers[1].byteOffset
        )
    }

    @Test
    fun `cancelling mid-sentence stops the chunk loop`() {
        val fake = FakeNativeSynth()
        fake.chunks += chunk(100, isLast = false)
        fake.chunks += chunk(100, isLast = false)
        fake.chunks += chunk(100, isLast = true)
        val orchestrator = SynthesisOrchestrator(fake)
        val seen = mutableListOf<FloatArray>()
        var polls = 0

        val totalBytes = orchestrator.synthesize(
            planned("Long."),
            resolveOptions = { baseOptions },
            isCancelled = { ++polls > 2 },
            callbacks = SynthesisOrchestrator.Callbacks(onSamples = { seen += it })
        )

        assertTrue("cancelled run must not drain all chunks", seen.size < 3)
        assertEquals(seen.size * 400L, totalBytes)
    }

    @Test
    fun `skipped failed sentence keeps later offsets monotonic - long-post guard`() {
        // Sawyer 1.0.12: a long post with one bad sentence must skip it and
        // keep reading; later sentences keep contiguous, monotonic offsets.
        val fake = FakeNativeSynth()
        fake.startResult = { sentence ->
            if (sentence == "Bad.") NativeSynth.ERR_GENERIC else NativeSynth.OK
        }
        fake.chunks += chunk(100) // "Good." -> 400 bytes
        fake.chunks += chunk(50)  // "Also good." -> 200 bytes
        val orchestrator = SynthesisOrchestrator(fake)
        val markers = mutableListOf<SpeechMarker>()

        val totalBytes = orchestrator.synthesize(
            planned("Good.", "Bad.", "Also good."),
            resolveOptions = { baseOptions },
            isCancelled = { false },
            callbacks = SynthesisOrchestrator.Callbacks(onMarkers = { markers += it })
        )

        assertEquals(600L, totalBytes)
        val sentenceMarkers = markers.filter { it.type == SpeechMarkerType.SENTENCE }
        assertEquals(2, sentenceMarkers.size)
        assertEquals(0, sentenceMarkers[0].byteOffset)
        assertEquals(
            "Skipped sentence must not leave a gap in later offsets",
            400,
            sentenceMarkers[1].byteOffset
        )
    }
}
