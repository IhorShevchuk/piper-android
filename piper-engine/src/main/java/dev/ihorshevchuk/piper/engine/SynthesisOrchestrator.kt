package dev.ihorshevchuk.piper.engine

import dev.ihorshevchuk.piper.utils.AlignmentParser
import dev.ihorshevchuk.piper.utils.PhonemeGroup
import dev.ihorshevchuk.piper.utils.PlannedSentence
import dev.ihorshevchuk.piper.utils.SpeechMarker

/**
 * Sentence-by-sentence synthesis orchestration: a direct port of
 * Piper.doSynthesize (piper-objc).
 *
 * All native calls go through [NativeSynth], so this is pure JVM and
 * unit-testable. Per sentence it:
 * - resolves that sentence's options (plain text: caller's options as-is;
 *   SSML: per-fragment lengthScale from the prosody rate plus speakerId),
 * - skips sentences that fail to start instead of aborting the utterance
 *   (Sawyer 1.0.12 long-post regression),
 * - groups per-chunk alignment into [PhonemeGroup]s (piper.h rule),
 * - emits sentence/word [SpeechMarker]s with cumulative float32 byte
 *   offsets, alignment-aware when groups exist, legacy otherwise.
 *
 * @return total float32 PCM bytes generated across all sentences.
 */
internal class SynthesisOrchestrator(private val native: NativeSynth) {

    /**
     * Sample rate of the most recent audio chunk. SSML `<break>` pauses
     * render silence at this rate; the 22050 default matches the engine's
     * and covers a break placed before any audio.
     */
    private var lastSampleRate = 22050

    data class Callbacks(
        val onSamples: (FloatArray) -> Unit = {},
        val onAlignment: (List<PhonemeGroup>) -> Unit = {},
        val onMarkers: (List<SpeechMarker>) -> Unit = {},
        val onSampleRate: (Int) -> Unit = {}
    )

    fun synthesize(
        planned: List<PlannedSentence>,
        resolveOptions: (PlannedSentence) -> PiperSynthesizeOptions,
        isCancelled: () -> Boolean,
        callbacks: Callbacks = Callbacks()
    ): Long {
        var totalBytes = 0L
        for (sentence in planned) {
            if (isCancelled()) break
            if (sentence.pauseMillis > 0) {
                // SSML <break>: emit silence without touching the native
                // synthesizer. Byte offsets still advance so the speech
                // markers of surrounding sentences stay correct.
                val samples = ((lastSampleRate * sentence.pauseMillis) / 1000)
                    .toInt()
                    .coerceAtLeast(1)
                callbacks.onSamples(FloatArray(samples))
                totalBytes += samples * 4L
                continue
            }
            val sentenceStartByteOffset = totalBytes
            if (native.start(sentence.text, resolveOptions(sentence)) == NativeSynth.ERR_GENERIC) {
                continue
            }
            var sentenceBytes = 0L
            val groups = mutableListOf<PhonemeGroup>()
            while (true) {
                if (isCancelled()) break
                val chunk = native.next() ?: break
                if (chunk.samples.isEmpty() && (chunk.alignments == null || chunk.alignments.isEmpty())) break
                callbacks.onSampleRate(chunk.sampleRate)
                lastSampleRate = chunk.sampleRate
                if (chunk.samples.isNotEmpty()) {
                    callbacks.onSamples(chunk.samples)
                }
                val phonemes = chunk.phonemes
                val ids = chunk.phonemeIds
                val alignments = chunk.alignments
                if (phonemes != null && ids != null && alignments != null) {
                    val chunkGroups = AlignmentParser.group(phonemes, ids, alignments)
                    if (chunkGroups.isNotEmpty()) {
                        groups.addAll(chunkGroups)
                        callbacks.onAlignment(chunkGroups)
                    }
                }
                sentenceBytes += chunk.samples.size * 4L
            }
            totalBytes += sentenceBytes
            if (sentence.range.location >= 0) {
                val markers = if (groups.isNotEmpty()) {
                    SpeechMarker.generateMarkersWithAlignment(
                        sentence = sentence.text,
                        sentenceRange = sentence.range,
                        startByteOffset = sentenceStartByteOffset.toInt(),
                        groups = groups
                    )
                } else {
                    SpeechMarker.generateMarkers(
                        sentence = sentence.text,
                        sentenceRange = sentence.range,
                        startByteOffset = sentenceStartByteOffset.toInt(),
                        totalBytes = sentenceBytes.toInt()
                    )
                }
                callbacks.onMarkers(markers)
            }
        }
        return totalBytes
    }
}
