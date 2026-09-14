// piper_jni.cpp - JNI bridge between PiperEngine (Kotlin) and libpiper (C).
//
// The native side holds the piper_synthesizer* inside an EngineContext,
// passed to Kotlin as a jlong. Calls for one engine are serialized on its
// dedicated thread by PiperEngine; espeak-ng itself is process-global, so
// CROSS-engine concurrency is serialized by the process-wide session lock in
// native_synthesis_lock.h (see that header for the deadlock-free teardown
// invariant). A synthesize session holds the lock from piper_synthesize_start
// until the final piper_synthesize_next returns PIPER_DONE; create/destroy
// serialize their espeak_Initialize / espeak_Terminate through it as well.
//
// Verified against OHF-Voice/piper1-gpl libpiper/include/piper.h:
//   #define PIPER_OK 0 / PIPER_DONE 1 / PIPER_ERR_GENERIC (-1)
//   piper_synthesizer* piper_create_with_options(const piper_create_options*);
//       (NULL on failure; set struct_size via piper_init_create_options first)
//   void piper_free(piper_synthesizer*);
//   piper_synthesize_options piper_default_synthesize_options(piper_synthesizer*);
//       (returned by value)
//   int piper_synthesize_start(piper_synthesizer*, const char*,
//                              const piper_synthesize_options*);
//   int piper_synthesize_next(piper_synthesizer*, piper_audio_chunk*);
//       (PIPER_DONE when complete; chunk memory is invalidated by the next call)
//   const char* piper_version();
//   struct piper_audio_chunk { const float* samples; size_t num_samples;
//       int sample_rate; bool is_last; const char32_t* phonemes;
//       size_t num_phonemes; const int* phoneme_ids; size_t num_phoneme_ids;
//       const int* alignments; size_t num_alignments; };

#include <jni.h>

#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "native_synthesis_lock.h"
#include "piper.h"

namespace {

constexpr int kPiperOk = 0;
constexpr int kPiperDone = 1;

// Everything piper hands us per chunk is copied out immediately, so the JNI
// never depends on piper's buffer ownership/lifetime rules.
struct EngineContext {
  piper_synthesizer* synth = nullptr;
  int lastSampleRate = 22050;
  bool lastIsLast = false;
  std::vector<int> lastPhonemes;
  std::vector<int> lastPhonemeIds;
  std::vector<int> lastAlignments;
  // This engine's hold on the process-wide espeak session lock. Only ever
  // touched from the engine's own thread.
  piper_jni::EspeakSessionLock sessionLock;
};

// Synthesizers whose destroy landed while another engine was inside espeak.
// Drained by the session-lock holder at session end; see
// native_synthesis_lock.h for the teardown invariant.
piper_jni::DeferredFreeList gDeferredFrees;

void FreeSynth(void* synth) {
  piper_free(static_cast<piper_synthesizer*>(synth));
}

// Ends the context's espeak session: frees any synthesizers whose destroy
// was deferred while this session held the lock, then releases the
// process-wide lock. Idempotent; safe to call without a held session.
void EndSession(EngineContext* ctx) {
  if (ctx == nullptr || !ctx->sessionLock.holds()) return;
  gDeferredFrees.drain(FreeSynth);
  ctx->sessionLock.release();
}

std::string JStringToStdString(JNIEnv* env, jstring s) {
  if (s == nullptr) return {};
  const char* chars = env->GetStringUTFChars(s, nullptr);
  std::string out(chars != nullptr ? chars : "");
  if (chars != nullptr) env->ReleaseStringUTFChars(s, chars);
  return out;
}

const char* NullableCStr(const std::string& s) {
  return s.empty() ? nullptr : s.c_str();
}

EngineContext* ToContext(jlong handle) {
  return reinterpret_cast<EngineContext*>(handle);
}

jintArray ToJIntArray(JNIEnv* env, const std::vector<int>& v) {
  if (v.empty()) return nullptr;
  jintArray out = env->NewIntArray(static_cast<jsize>(v.size()));
  if (out == nullptr) return nullptr;
  env->SetIntArrayRegion(out, 0, static_cast<jsize>(v.size()),
                          reinterpret_cast<const jint*>(v.data()));
  return out;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_dev_ihorshevchuk_piper_engine_PiperEngine_nativeCreate(
    JNIEnv* env, jobject /*thiz*/, jstring modelPath, jstring configPath,
    jstring espeakDataPath, jstring dataDir, jstring g2pwModelDir) {
  const std::string model = JStringToStdString(env, modelPath);
  const std::string config = JStringToStdString(env, configPath);
  const std::string espeak = JStringToStdString(env, espeakDataPath);
  const std::string data = JStringToStdString(env, dataDir);
  const std::string g2pw = JStringToStdString(env, g2pwModelDir);

  if (model.empty()) return 0;

  piper_create_options options{};
  piper_init_create_options(&options); // sets struct_size + nulls paths
  options.model_path = model.c_str();
  options.config_path = NullableCStr(config);
  options.espeak_data_path = NullableCStr(espeak);
  options.data_dir = NullableCStr(data);
  options.g2pw_model_dir = NullableCStr(g2pw);

  // Returns the synthesizer directly, NULL on failure.
  // piper_create_with_options touches process-global espeak state
  // (espeak_Initialize), so creation serializes on the session lock too.
  // A scoped hold (not a session): released before this function returns.
  piper_synthesizer* synth;
  {
    std::lock_guard<std::mutex> lock(piper_jni::EspeakProcessMutex());
    synth = piper_create_with_options(&options);
  }
  if (synth == nullptr) {
    return 0;
  }

  EngineContext* ctx = new (std::nothrow) EngineContext();
  if (ctx == nullptr) {
    piper_free(synth);
    return 0;
  }
  ctx->synth = synth;
  return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_dev_ihorshevchuk_piper_engine_PiperEngine_nativeDestroy(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
  EngineContext* ctx = ToContext(handle);
  if (ctx == nullptr) return;
  // Teardown invariant (native_synthesis_lock.h): never block, and never
  // free espeak state while another thread is inside espeak.
  // 1. Release our own session hold first: never blocks, never self-deadlocks.
  ctx->sessionLock.release();
  piper_synthesizer* synth = ctx->synth;
  ctx->synth = nullptr;
  delete ctx;
  if (synth == nullptr) return;
  // 2. Free immediately only when nobody is inside espeak; otherwise defer
  //    - the current lock holder drains the queue at session end.
  std::unique_lock<std::mutex> lock(piper_jni::EspeakProcessMutex(),
                                    std::try_to_lock);
  if (lock.owns_lock()) {
    gDeferredFrees.drain(FreeSynth);
    piper_free(synth);
  } else {
    gDeferredFrees.defer(synth);
  }
}

JNIEXPORT jfloatArray JNICALL
Java_dev_ihorshevchuk_piper_engine_PiperEngine_nativeDefaultOptions(
    JNIEnv* env, jobject /*thiz*/, jlong handle) {
  EngineContext* ctx = ToContext(handle);
  if (ctx == nullptr || ctx->synth == nullptr) return nullptr;

  // Returned by value (no return code).
  const piper_synthesize_options sopts =
      piper_default_synthesize_options(ctx->synth);
  // [speakerId, lengthScale, noiseScale, noiseWScale]
  const float vals[4] = {static_cast<float>(sopts.speaker_id),
                         sopts.length_scale, sopts.noise_scale,
                         sopts.noise_w_scale};
  jfloatArray out = env->NewFloatArray(4);
  if (out == nullptr) return nullptr;
  env->SetFloatArrayRegion(out, 0, 4, vals);
  return out;
}

JNIEXPORT jint JNICALL
Java_dev_ihorshevchuk_piper_engine_PiperEngine_nativeSynthesizeStart(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jstring text, jint speakerId,
    jfloat lengthScale, jfloat noiseScale, jfloat noiseWScale) {
  EngineContext* ctx = ToContext(handle);
  if (ctx == nullptr || ctx->synth == nullptr) return -1;

  piper_synthesize_options sopts{};
  sopts.speaker_id = speakerId;
  sopts.length_scale = lengthScale;
  sopts.noise_scale = noiseScale;
  sopts.noise_w_scale = noiseWScale;

  const std::string textStr = JStringToStdString(env, text);
  // The session now owns the process-wide espeak lock until the final
  // piper_synthesize_next returns PIPER_DONE (see nativeSynthesizeNext) or
  // the session is ended early (error below, nativeEndSession). Held across
  // JNI calls: piper's chunk buffers are only valid until the next piper
  // call, and espeak_SetVoiceByName here already touches global state.
  // acquireIfNotHeld: no release/re-acquire window for a continued session.
  ctx->sessionLock.acquireIfNotHeld();
  const int rc = piper_synthesize_start(ctx->synth, textStr.c_str(), &sopts);
  if (rc != kPiperOk) {
    // No session started; don't hold the lock for nothing. The next
    // sentence's start() re-acquires if the utterance continues.
    EndSession(ctx);
  }
  return rc;
}

JNIEXPORT jfloatArray JNICALL
Java_dev_ihorshevchuk_piper_engine_PiperEngine_nativeSynthesizeNext(
    JNIEnv* env, jobject /*thiz*/, jlong handle) {
  EngineContext* ctx = ToContext(handle);
  if (ctx == nullptr || ctx->synth == nullptr) return nullptr;

  // Normally already held from nativeSynthesizeStart; re-acquire when not
  // (e.g. a critical-pressure release recreated the synthesizer between
  // chunks, and nativeDestroy dropped the hold). acquireIfNotHeld never
  // opens a release/re-acquire window on the hot path.
  ctx->sessionLock.acquireIfNotHeld();

  piper_audio_chunk chunk{};
  const int rc = piper_synthesize_next(ctx->synth, &chunk);
  const bool errored = (rc != kPiperOk && rc != kPiperDone);
  // The session is over when piper reports DONE - with the final chunk
  // attached or with an empty chunk (queue already drained) - or on error.
  const bool sessionOver = errored || (rc == kPiperDone);

  // Copy everything out of piper's buffers BEFORE releasing the session
  // lock: the next piper call - possibly from another engine, the moment
  // we release - invalidates chunk memory.
  const bool hasSamples = chunk.samples != nullptr && chunk.num_samples > 0;
  const bool hasAlignments =
      chunk.alignments != nullptr && chunk.num_alignments > 0;

  ctx->lastSampleRate = chunk.sample_rate;
  ctx->lastIsLast = chunk.is_last;
  if (chunk.phonemes != nullptr && chunk.num_phonemes > 0) {
    ctx->lastPhonemes.assign(chunk.phonemes,
                             chunk.phonemes + chunk.num_phonemes);
  } else {
    ctx->lastPhonemes.clear();
  }
  if (chunk.phoneme_ids != nullptr && chunk.num_phoneme_ids > 0) {
    ctx->lastPhonemeIds.assign(chunk.phoneme_ids,
                               chunk.phoneme_ids + chunk.num_phoneme_ids);
  } else {
    ctx->lastPhonemeIds.clear();
  }
  if (chunk.alignments != nullptr && chunk.num_alignments > 0) {
    ctx->lastAlignments.assign(chunk.alignments,
                               chunk.alignments + chunk.num_alignments);
  } else {
    ctx->lastAlignments.clear();
  }

  // piper.h contract: each call fills the chunk, and the final chunk of a
  // sentence arrives WITH PIPER_DONE and is_last set (piper-objc consumes
  // the chunk before checking the status). Only an empty DONE chunk - the
  // queue already drained by an earlier call - ends the stream with null.
  // PIPER_ERR_GENERIC: Kotlin treats null as end of the sentence; a
  // mid-sentence error keeps whatever audio was already delivered.
  //
  // The jfloatArray is built BEFORE EndSession releases the lock: chunk
  // buffers are only valid until the next piper call, which another engine
  // may make the moment we release.
  jfloatArray out;
  if (errored || (!hasSamples && !hasAlignments)) {
    out = nullptr;
  } else if (chunk.samples == nullptr || chunk.num_samples == 0) {
    // Alignment-only chunk (e.g. punctuation): return an empty, non-null
    // array so Kotlin can still harvest the alignment. Null is reserved for
    // the drained-queue DONE and PIPER_ERR (end of sentence).
    out = env->NewFloatArray(0);
  } else {
    out = env->NewFloatArray(static_cast<jsize>(chunk.num_samples));
    if (out != nullptr) {
      env->SetFloatArrayRegion(out, 0, static_cast<jsize>(chunk.num_samples),
                               chunk.samples);
    }
  }
  if (sessionOver) {
    EndSession(ctx);
  }
  return out;
}

/**
 * Ends the engine's espeak session early, releasing the process-wide lock.
 * Called from PiperEngine after each utterance (finally block): a
 * cancelled or errored utterance that never drained to PIPER_DONE must not
 * keep the lock held and wedge every other engine in the process.
 * Idempotent; safe with no session in flight.
 */
JNIEXPORT void JNICALL
Java_dev_ihorshevchuk_piper_engine_PiperEngine_nativeEndSession(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
  EndSession(ToContext(handle));
}

JNIEXPORT jint JNICALL
Java_dev_ihorshevchuk_piper_engine_PiperEngine_nativeLastChunkSampleRate(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
  EngineContext* ctx = ToContext(handle);
  return ctx != nullptr ? ctx->lastSampleRate : 0;
}

JNIEXPORT jboolean JNICALL
Java_dev_ihorshevchuk_piper_engine_PiperEngine_nativeLastChunkIsLast(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
  EngineContext* ctx = ToContext(handle);
  return ctx != nullptr && ctx->lastIsLast ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jintArray JNICALL
Java_dev_ihorshevchuk_piper_engine_PiperEngine_nativeLastChunkPhonemes(
    JNIEnv* env, jobject /*thiz*/, jlong handle) {
  EngineContext* ctx = ToContext(handle);
  if (ctx == nullptr) return nullptr;
  return ToJIntArray(env, ctx->lastPhonemes);
}

JNIEXPORT jintArray JNICALL
Java_dev_ihorshevchuk_piper_engine_PiperEngine_nativeLastChunkPhonemeIds(
    JNIEnv* env, jobject /*thiz*/, jlong handle) {
  EngineContext* ctx = ToContext(handle);
  if (ctx == nullptr) return nullptr;
  return ToJIntArray(env, ctx->lastPhonemeIds);
}

JNIEXPORT jintArray JNICALL
Java_dev_ihorshevchuk_piper_engine_PiperEngine_nativeLastChunkAlignments(
    JNIEnv* env, jobject /*thiz*/, jlong handle) {
  EngineContext* ctx = ToContext(handle);
  if (ctx == nullptr) return nullptr;
  return ToJIntArray(env, ctx->lastAlignments);
}

JNIEXPORT jstring JNICALL
Java_dev_ihorshevchuk_piper_engine_PiperEngine_nativeVersion(
    JNIEnv* env, jobject /*thiz*/) {
  const char* v = piper_version();
  return env->NewStringUTF(v != nullptr ? v : "unknown");
}

}  // extern "C"
