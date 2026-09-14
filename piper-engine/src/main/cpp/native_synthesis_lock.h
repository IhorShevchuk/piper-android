// native_synthesis_lock.h - process-wide serialization for espeak-ng.
//
// espeak-ng keeps process-global state (voice tables, phoneme data, the
// init/terminate refcount), so two threads must never be inside espeak at
// once - not even on separate piper_synthesizer instances. Reproduced on
// the pinned espeak-ng 699e7969: 4 threads changing voice + phonemizing
// concurrently crashed 5/5 runs; even same-voice concurrent phonemization
// crashed intermittently (SIGSEGV in InterpretPhoneme <- MakePhonemeList <-
// TranslateClauseWithTerminator <- espeak_TextToPhonemesWithTerminator <-
// piper_synthesize_start).
//
// The lock lives in OUR JNI translation unit (piper_jni.cpp), never in the
// pristine third-party/piper1-gpl checkout: every native synthesize session
// holds it from piper_synthesize_start until the final piper_synthesize_next
// returns PIPER_DONE, and piper_create_with_options / piper_free serialize
// their espeak_Initialize / espeak_Terminate through it too.
//
// DEADLOCK-FREE TEARDOWN INVARIANT (read before touching destroy paths):
//   * close()/destroy must NEVER block on the process mutex. A blocked
//     destroy wedges the calling thread while another engine may be
//     mid-utterance, and a destroy that frees while another thread is
//     inside espeak corrupts the process.
//   * Therefore destroy: (1) releases its own session hold first - never
//     blocks, never self-deadlocks; (2) frees the synthesizer immediately
//     only when try_lock succeeds (nobody is inside espeak); (3) otherwise
//     defers the pointer to DeferredFreeList, which the current lock holder
//     drains while still holding the lock at session end.
//   * The deferred queue has its own short-critical-section mutex. Lock
//     order is always process-mutex -> queue-mutex, never the reverse, so a
//     thread deferring can never deadlock against a thread draining.
//
// This header is plain C++17 with no JNI/piper dependencies so the
// contract is unit-testable on the host; see tests/native_synthesis_lock_test.cpp.

#pragma once

#include <mutex>
#include <vector>

namespace piper_jni {

// The single process-wide mutex serializing espeak-ng access.
// Function-local static: one instance per process, created on first use.
inline std::mutex& EspeakProcessMutex() {
  static std::mutex mutex;
  return mutex;
}

// One native synthesize session's hold on the process mutex. A session
// spans JNI calls (start ... next*), so the hold is explicit rather than
// scope-based; the destructor still releases as a safety net.
// One instance is owned by each EngineContext and is only ever touched
// from that engine's thread.
class EspeakSessionLock {
 public:
  EspeakSessionLock() = default;
  EspeakSessionLock(const EspeakSessionLock&) = delete;
  EspeakSessionLock& operator=(const EspeakSessionLock&) = delete;

  ~EspeakSessionLock() { release(); }

  // Blocks until the process mutex is held by this session. Re-acquiring
  // drops the previous hold first: a session abandoned mid-utterance
  // (cancel/close without draining to PIPER_DONE) cannot wedge the
  // process, and the engine's next start() proceeds normally.
  void acquire() {
    release();
    EspeakProcessMutex().lock();
    holds_ = true;
  }

  // Acquires only when not already held. The JNI session uses this at
  // sentence boundaries and on every chunk: unlike acquire() it never
  // opens a release/re-acquire window in which another engine could barge
  // into espeak mid-utterance.
  void acquireIfNotHeld() {
    if (!holds_) acquire();
  }

  // Releases the hold; safe to call when not held. The caller is
  // responsible for draining deferred frees (DeferredFreeList::drain)
  // BEFORE release while still holding the process mutex.
  void release() {
    if (holds_) {
      EspeakProcessMutex().unlock();
      holds_ = false;
    }
  }

  bool holds() const { return holds_; }

 private:
  bool holds_ = false;
};

// Queue of synthesizer handles whose free was deferred because another
// thread was inside espeak when destroy ran. defer() never touches the
// process mutex (short critical section on its own mutex only); drain()
// must be called with the process mutex already held.
class DeferredFreeList {
 public:
  void defer(void* handle) {
    std::lock_guard<std::mutex> lock(queue_mutex_);
    queue_.push_back(handle);
  }

  // Frees every queued handle via free_fn, exactly once. Must be called
  // with EspeakProcessMutex() held; releases the queue mutex before
  // invoking free_fn so a slow free cannot block deferrers.
  template <typename FreeFn>
  void drain(FreeFn free_fn) {
    std::vector<void*> pending;
    {
      std::lock_guard<std::mutex> lock(queue_mutex_);
      pending.swap(queue_);
    }
    for (void* handle : pending) {
      free_fn(handle);
    }
  }

  bool empty() const {
    std::lock_guard<std::mutex> lock(queue_mutex_);
    return queue_.empty();
  }

 private:
  mutable std::mutex queue_mutex_;
  std::vector<void*> queue_;
};

}  // namespace piper_jni
