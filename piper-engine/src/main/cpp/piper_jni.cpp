// piper_jni.cpp - JNI bridge between PiperEngine (Kotlin) and libpiper (C).
//
// The native side holds the piper_synthesizer* inside an EngineContext,
// passed to Kotlin as a jlong. All calls are serialized on a single thread
// by PiperEngine; libpiper is not thread-safe.
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
#include <string>
#include <vector>

#include "piper.h"

namespace {

constexpr int kPiperOk = 0;

// Everything piper hands us per chunk is copied out immediately, so the JNI
// never depends on piper's buffer ownership/lifetime rules.
struct EngineContext {
  piper_synthesizer* synth = nullptr;
  int lastSampleRate = 22050;
  bool lastIsLast = false;
  std::vector<int> lastPhonemes;
  std::vector<int> lastAlignments;
};

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
  piper_synthesizer* synth = piper_create_with_options(&options);
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
  if (ctx->synth != nullptr) piper_free(ctx->synth);
  delete ctx;
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
  return piper_synthesize_start(ctx->synth, textStr.c_str(), &sopts);
}

JNIEXPORT jfloatArray JNICALL
Java_dev_ihorshevchuk_piper_engine_PiperEngine_nativeSynthesizeNext(
    JNIEnv* env, jobject /*thiz*/, jlong handle) {
  EngineContext* ctx = ToContext(handle);
  if (ctx == nullptr || ctx->synth == nullptr) return nullptr;

  piper_audio_chunk chunk{};
  const int rc = piper_synthesize_next(ctx->synth, &chunk);
  if (rc != kPiperOk) {
    // PIPER_DONE or PIPER_ERR: Kotlin treats null as end of the sentence.
    // A mid-sentence error keeps whatever audio was already delivered.
    return nullptr;
  }

  ctx->lastSampleRate = chunk.sample_rate;
  ctx->lastIsLast = chunk.is_last;
  if (chunk.phonemes != nullptr && chunk.num_phonemes > 0) {
    ctx->lastPhonemes.assign(chunk.phonemes,
                             chunk.phonemes + chunk.num_phonemes);
  } else {
    ctx->lastPhonemes.clear();
  }
  if (chunk.alignments != nullptr && chunk.num_alignments > 0) {
    ctx->lastAlignments.assign(chunk.alignments,
                               chunk.alignments + chunk.num_alignments);
  } else {
    ctx->lastAlignments.clear();
  }

  if (chunk.samples == nullptr || chunk.num_samples == 0) {
    return nullptr;
  }
  jfloatArray out =
      env->NewFloatArray(static_cast<jsize>(chunk.num_samples));
  if (out == nullptr) return nullptr;
  env->SetFloatArrayRegion(out, 0, static_cast<jsize>(chunk.num_samples),
                            chunk.samples);
  return out;
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
