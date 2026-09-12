cmake_minimum_required(VERSION 3.22.1)
project(piper_jni)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# Dependency roots. Passed from piper-engine/build.gradle.kts (which points at
# third-party/, populated by scripts/fetch-native-deps.sh):
#   -DPIPER1_GPL_DIR=...  OHF-Voice/piper1-gpl checkout
#   -DESPEAK_NG_DIR=...   espeak-ng/espeak-ng checkout
#   -DONNXRUNTIME_DIR=... onnxruntime headers + per-ABI libonnxruntime.so
# ---------------------------------------------------------------------------
if(NOT PIPER1_GPL_DIR OR NOT ESPEAK_NG_DIR OR NOT ONNXRUNTIME_DIR)
  message(FATAL_ERROR
    "Set PIPER1_GPL_DIR, ESPEAK_NG_DIR and ONNXRUNTIME_DIR. "
    "Run scripts/fetch-native-deps.sh first.")
endif()

# ---------------------------------------------------------------------------
# espeak-ng. Compiled straight from C sources via the NDK.
#
# KNOWN-ITERATIVE PART: the exclusion list below keeps the CLI and test mains
# out of the shared object. Verify it against the vendored tree on the first
# real build (file names drift between espeak-ng releases) - see README
# "First-build iteration". Symptoms to look for: duplicate main() at link
# time (missed exclusion) or missing symbols (over-exclusion).
# ---------------------------------------------------------------------------
file(GLOB ESPEAK_SOURCES "${ESPEAK_NG_DIR}/src/*.c")
list(FILTER ESPEAK_SOURCES EXCLUDE REGEX "/(espeak-ng|espeak|speak-ng|tests?|.*_test)\\.c$")

# Minimal config.h for the NDK build (espeak-ng normally gets this from
# autotools). Curated from a typical Linux config.h; extend during the first
# real build if the compiler asks for more - see README "First-build iteration".
configure_file(
  "${CMAKE_CURRENT_SOURCE_DIR}/cmake-config/config.h.in"
  "${CMAKE_CURRENT_BINARY_DIR}/generated/config.h"
  @ONLY)
add_definitions(-DHAVE_CONFIG_H)

# ---------------------------------------------------------------------------
# libpiper (OHF-Voice/piper1-gpl): the synthesis core, including the Chinese
# phonemizer (pinyin Phase 1 support lives here, not in Kotlin).
# ---------------------------------------------------------------------------
set(PIPER_SOURCES
  "${PIPER1_GPL_DIR}/libpiper/src/piper.cpp"
  "${PIPER1_GPL_DIR}/libpiper/src/chinese_phonemizer.cpp"
)

add_library(piper_jni SHARED
  piper_jni.cpp
  ${PIPER_SOURCES}
  ${ESPEAK_SOURCES}
)

target_include_directories(piper_jni PRIVATE
  "${CMAKE_CURRENT_BINARY_DIR}/generated"   # config.h
  "${PIPER1_GPL_DIR}/libpiper/include"      # piper.h
  "${ESPEAK_NG_DIR}/src/include"            # espeak-ng public headers
  "${ESPEAK_NG_DIR}/src"                    # espeak-ng internal headers
  "${ONNXRUNTIME_DIR}/include"              # onnxruntime C/C++ API
)

# onnxruntime: prebuilt per-ABI .so extracted from the onnxruntime-android AAR
# by scripts/fetch-native-deps.sh. ANDROID_ABI is set by the NDK toolchain.
add_library(onnxruntime SHARED IMPORTED)
set_target_properties(onnxruntime PROPERTIES IMPORTED_LOCATION
  "${ONNXRUNTIME_DIR}/lib/${ANDROID_ABI}/libonnxruntime.so")

target_link_libraries(piper_jni onnxruntime log)
