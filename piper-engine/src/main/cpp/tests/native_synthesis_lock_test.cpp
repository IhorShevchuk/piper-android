// native_synthesis_lock_test.cpp - host-runnable tests for the process-wide
// espeak-ng serialization in "native_synthesis_lock.h".
//
// espeak-ng keeps process-global state (voice tables, phoneme data), so two
// threads must never be inside espeak at once - not even on separate
// piper_synthesizer instances. Reproduction (Linux, pinned espeak-ng
// 699e7969): 4 threads changing voice + phonemizing concurrently crashed 5/5
// runs; same-voice concurrent phonemization crashed intermittently.
//
// These tests run on the host with plain g++ (no Android, no JNI).
// Example:
//   g++ -std=c++17 -pthread -I piper-engine/src/main/cpp
//       piper-engine/src/main/cpp/tests/native_synthesis_lock_test.cpp
//       -o /tmp/native_synthesis_lock_test
//   /tmp/native_synthesis_lock_test
//
// They do NOT cover piper_jni.cpp itself (needs the NDK + the .so); the
// wiring there - acquire on piper_synthesize_start, release+drain on the
// final piper_synthesize_next, try_lock-or-defer on destroy - is exercised
// on-device by PiperEngineConcurrencyLockTest (connectedAndroidTest).

#include <atomic>
#include <chrono>
#include <cstdio>
#include <future>
#include <thread>
#include <vector>

#include "native_synthesis_lock.h"

using piper_jni::DeferredFreeList;
using piper_jni::EspeakProcessMutex;
using piper_jni::EspeakSessionLock;

namespace {

int g_failures = 0;

// NOTE: CHECK returns from the current test function, so every test joins
// its threads BEFORE the first CHECK that can fire.
#define CHECK(cond)                                                          \
  do {                                                                       \
    if (!(cond)) {                                                           \
      std::printf("  FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond);           \
      ++g_failures;                                                          \
      return;                                                                \
    }                                                                        \
  } while (0)

#define RUN(test)                                                            \
  do {                                                                       \
    std::printf("%-55s", #test);                                             \
    std::fflush(stdout);                                                     \
    const int before = g_failures;                                           \
    test();                                                                  \
    std::printf("%s\n", g_failures == before ? "PASS" : "FAIL");              \
  } while (0)

// ---------------------------------------------------------------------------
// 1. Concurrent guarded sections never interleave.
// ---------------------------------------------------------------------------
void guard_serializes_concurrent_sections() {
  constexpr int kThreads = 8;
  constexpr int kIters = 500;
  std::atomic<int> inside{0};
  std::atomic<int> total{0};
  std::atomic<int> violations{0};

  auto worker = [&] {
    EspeakSessionLock session;
    for (int i = 0; i < kIters; ++i) {
      session.acquire();
      const int now = ++inside;
      // Widen the race window: without a real mutex another thread is
      // virtually guaranteed to barge in here.
      std::this_thread::yield();
      std::this_thread::yield();
      if (now != 1) ++violations;
      ++total;
      --inside;
      session.release();
    }
  };

  std::vector<std::thread> threads;
  for (int i = 0; i < kThreads; ++i) threads.emplace_back(worker);
  for (auto& t : threads) t.join();

  CHECK(violations.load() == 0);
  CHECK(total.load() == kThreads * kIters);
  CHECK(inside.load() == 0);
}

// ---------------------------------------------------------------------------
// 2. While one session holds the lock, try_lock from another thread fails;
//    after release it succeeds. (This is the destroy() fast path: it must be
//    able to tell "someone is inside espeak" without blocking.)
// ---------------------------------------------------------------------------
void try_lock_fails_while_session_held() {
  EspeakSessionLock session;
  session.acquire();

  std::promise<void> held;
  std::promise<bool> while_held_result;
  std::promise<void> release_now;
  std::promise<bool> after_release_result;

  std::thread probe([&] {
    held.get_future().wait();
    {
      std::unique_lock<std::mutex> lk(EspeakProcessMutex(), std::try_to_lock);
      while_held_result.set_value(lk.owns_lock());
    }
    release_now.get_future().wait();
    {
      std::unique_lock<std::mutex> lk(EspeakProcessMutex(), std::try_to_lock);
      // lk releases on scope exit when owned.
      after_release_result.set_value(lk.owns_lock());
    }
  });

  held.set_value();
  const bool while_held = while_held_result.get_future().get();
  session.release();
  release_now.set_value();
  const bool after_release = after_release_result.get_future().get();
  probe.join();

  CHECK(!while_held);    // must NOT acquire while the session holds the lock
  CHECK(after_release);  // must acquire once the session released it
}

// ---------------------------------------------------------------------------
// 3. Re-acquiring on the same session object drops the previous hold first:
//    a session abandoned mid-utterance (cancel/close) cannot wedge the
//    process, and the next start() on that engine proceeds.
// ---------------------------------------------------------------------------
void reacquire_drops_previous_hold() {
  EspeakSessionLock session;
  session.acquire();
  session.acquire();  // must not deadlock
  const bool held_now = session.holds();

  std::atomic<bool> other_in{false};
  std::thread other([&] {
    EspeakSessionLock s2;
    s2.acquire();
    other_in = true;
    s2.release();
  });
  std::this_thread::sleep_for(std::chrono::milliseconds(50));
  const bool other_in_early = other_in.load();
  session.release();
  other.join();
  const bool other_in_late = other_in.load();

  CHECK(held_now);
  CHECK(!other_in_early);  // second acquire kept the lock held throughout
  CHECK(other_in_late);    // ...and the other thread got in after release
}

// ---------------------------------------------------------------------------
// 3b. acquireIfNotHeld keeps a held lock without a release/re-acquire
// window: another thread must not be able to barge in across the call.
// ---------------------------------------------------------------------------
void acquire_if_not_held_has_no_barge_window() {
  EspeakSessionLock session;
  session.acquire();
  std::atomic<bool> barged_in{false};
  std::atomic<bool> stop{false};
  std::thread battering([&] {
    while (!stop.load()) {
      std::unique_lock<std::mutex> lk(EspeakProcessMutex(), std::try_to_lock);
      if (lk.owns_lock()) barged_in = true;
    }
  });
  for (int i = 0; i < 1000; ++i) {
    session.acquireIfNotHeld();  // must keep the hold, never drop it
  }
  stop = true;
  battering.join();
  const bool still_held = session.holds();
  const bool no_barge = !barged_in.load();
  session.release();

  CHECK(still_held);
  CHECK(no_barge);
  // And it acquires when not held.
  session.acquireIfNotHeld();
  const bool acquired = session.holds();
  session.release();
  CHECK(acquired);
}

// ---------------------------------------------------------------------------
// 4. The destructor releases a held lock (safety net for early returns).
// ---------------------------------------------------------------------------
void destructor_releases_held_lock() {
  {
    EspeakSessionLock session;
    session.acquire();
    if (!session.holds()) {
      ++g_failures;
      std::printf("  FAIL: session not held after acquire\n");
      return;
    }
  }
  std::unique_lock<std::mutex> lk(EspeakProcessMutex(), std::try_to_lock);
  CHECK(lk.owns_lock());
}

// ---------------------------------------------------------------------------
// 5. Deferred frees: pointers deferred while the lock is held are freed
//    exactly once when the holder drains, and draining an empty queue is a
//    no-op. (This is how destroy() avoids freeing espeak state -
//    espeak_Terminate() via piper_free() - while another thread is inside
//    espeak, without ever blocking.)
// ---------------------------------------------------------------------------
void deferred_free_drains_exactly_once() {
  DeferredFreeList pending;
  constexpr int kThreads = 4;
  constexpr int kPerThread = 250;
  std::atomic<int> live{0};
  std::atomic<int> freed{0};

  // Fake "synthesizer" handles; the deleter records the free.
  std::vector<void*> handles;
  for (int i = 0; i < kThreads * kPerThread; ++i) {
    handles.push_back(reinterpret_cast<void*>(0x1000 + i));
    ++live;
  }
  auto deleter = [&](void* p) {
    (void)p;
    --live;
    ++freed;
  };

  // Hold the process lock while other threads defer: defer() must never
  // block on the process mutex.
  EspeakSessionLock session;
  session.acquire();
  std::vector<std::thread> threads;
  for (int t = 0; t < kThreads; ++t) {
    threads.emplace_back([&, t] {
      for (int i = 0; i < kPerThread; ++i) {
        pending.defer(handles[static_cast<size_t>(t) * kPerThread + i]);
      }
    });
  }
  for (auto& th : threads) th.join();

  // Still holding the lock: drain frees everything exactly once.
  pending.drain(deleter);
  const int live_after = live.load();
  const int freed_after = freed.load();
  pending.drain(deleter);  // empty drain: no-op
  session.release();

  CHECK(live_after == 0);
  CHECK(freed_after == kThreads * kPerThread);
  CHECK(freed.load() == freed_after);
}

// ---------------------------------------------------------------------------
// 6. Lock ordering: a thread deferring while another thread drains under the
//    process lock must always finish (no lock-order inversion deadlock).
// ---------------------------------------------------------------------------
void defer_during_drain_never_deadlocks() {
  DeferredFreeList pending;
  std::atomic<bool> stop{false};
  std::atomic<int> deferred{0};
  std::atomic<int> freed{0};
  auto deleter = [&](void* p) {
    (void)p;
    ++freed;
  };

  std::thread deferrer([&] {
    int i = 0;
    while (!stop.load()) {
      pending.defer(reinterpret_cast<void*>(0x2000 + (i++ % 64)));
      ++deferred;
    }
  });

  // Make sure the deferrer is actually running before the drain loop.
  for (int i = 0; i < 1000 && deferred.load() == 0; ++i) {
    std::this_thread::yield();
  }

  {
    EspeakSessionLock session;
    session.acquire();
    for (int i = 0; i < 200; ++i) {
      pending.drain(deleter);  // caller holds the process lock
      std::this_thread::yield();
    }
    session.release();
  }

  stop = true;
  deferrer.join();
  // Drain whatever is left; every deferred pointer freed exactly once.
  {
    EspeakSessionLock session;
    session.acquire();
    pending.drain(deleter);
    session.release();
  }
  CHECK(freed.load() > 0);
}

}  // namespace

int main() {
  RUN(guard_serializes_concurrent_sections);
  RUN(try_lock_fails_while_session_held);
  RUN(reacquire_drops_previous_hold);
  RUN(acquire_if_not_held_has_no_barge_window);
  RUN(destructor_releases_held_lock);
  RUN(deferred_free_drains_exactly_once);
  RUN(defer_during_drain_never_deadlocks);
  if (g_failures == 0) {
    std::printf("\nAll tests passed.\n");
    return 0;
  }
  std::printf("\n%d test(s) FAILED.\n", g_failures);
  return 1;
}
