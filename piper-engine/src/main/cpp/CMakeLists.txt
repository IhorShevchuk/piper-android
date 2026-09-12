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
#   -DSONIC_DIR=...       waywardgeek/sonic checkout (sonic.c)
# ---------------------------------------------------------------------------
if(NOT PIPER1_GPL_DIR OR NOT ESPEAK_NG_DIR OR NOT ONNXRUNTIME_DIR OR NOT SONIC_DIR)
  message(FATAL_ERROR
    "Set PIPER1_GPL_DIR, ESPEAK_NG_DIR, ONNXRUNTIME_DIR and SONIC_DIR. "
    "Run scripts/fetch-native-deps.sh first.")
endif()

# ---------------------------------------------------------------------------
# espeak-ng. Source selection mirrors the iOS espeak-ng-spm package
# (IhorShevchuk/espeak-ng-spm), which is the known-good configuration:
# all of src/libespeak-ng/*.c, the ucd library, speechPlayer and sonic,
# with the feature flags from its config.h.
# ---------------------------------------------------------------------------
file(GLOB ESPEAK_SOURCES "${ESPEAK_NG_DIR}/src/libespeak-ng/*.c")
file(GLOB UCD_SOURCES "${ESPEAK_NG_DIR}/src/ucd-tools/src/*.c")
file(GLOB SPEECHPLAYER_SOURCES "${ESPEAK_NG_DIR}/src/speechPlayer/src/*.cpp")
set(SONIC_SOURCES "${SONIC_DIR}/sonic.c")

# config.h: static copy of the espeak-ng-spm values (USE_KLATT=1,
# USE_LIBSONIC=1, USE_SPEECHPLAYER=1, everything audio-output related off).
configure_file(
  "${CMAKE_CURRENT_SOURCE_DIR}/cmake-config/config.h"
  "${CMAKE_CURRENT_BINARY_DIR}/generated/config.h"
  COPYONLY)
add_definitions(-DHAVE_CONFIG_H)

# ---------------------------------------------------------------------------
# libpiper (OHF-Voice/piper1-gpl): the synthesis core, including the Chinese
# phonemizer (pinyin support lives here, not in Kotlin).
# ---------------------------------------------------------------------------
set(PIPER_SOURCES
  "${PIPER1_GPL_DIR}/libpiper/src/piper.cpp"
  "${PIPER1_GPL_DIR}/libpiper/src/chinese_phonemizer.cpp"
)

add_library(piper_jni SHARED
  piper_jni.cpp
  ${PIPER_SOURCES}
  ${ESPEAK_SOURCES}
  ${UCD_SOURCES}
  ${SPEECHPLAYER_SOURCES}
  ${SONIC_SOURCES}
)

target_include_directories(piper_jni PRIVATE
  "${CMAKE_CURRENT_BINARY_DIR}/generated"       # config.h (first)
  "${ESPEAK_NG_DIR}/src/include"                # espeak-ng public headers
  "${ESPEAK_NG_DIR}/src/libespeak-ng"           # espeak-ng internal headers
  "${ESPEAK_NG_DIR}/src/ucd-tools/src/include"  # ucd/ucd.h
  "${ESPEAK_NG_DIR}/src/speechPlayer/include"   # speechPlayer.h
  "${SONIC_DIR}"                                # sonic.h
  "${PIPER1_GPL_DIR}/libpiper/include"          # piper.h
  "${ONNXRUNTIME_DIR}/include"                   # onnxruntime C/C++ API
)

# Mirrors the espeak-ng-spm cSettings (.define("ESPEAK_NG_API", to: ""),
# .define("N_PATH_HOME", to: "1024")) plus the LIBESPEAK_NG_EXPORT=1 and
# PATH_ESPEAK_DATA defaults from espeak-ng's own CMakeLists. The app always
# passes a real espeak data path at runtime (PiperCreateOptions), so the
# compiled-in default is a fallback that should never be hit.
target_compile_definitions(piper_jni PRIVATE
  ESPEAK_NG_API=
  N_PATH_HOME=1024
  LIBESPEAK_NG_EXPORT=1
  "PATH_ESPEAK_DATA=\"/usr/share/espeak-ng-data\""
)

# onnxruntime: prebuilt per-ABI .so extracted from the onnxruntime-android AAR
# by scripts/fetch-native-deps.sh. ANDROID_ABI is set by the NDK toolchain.
add_library(onnxruntime SHARED IMPORTED)
set_target_properties(onnxruntime PROPERTIES IMPORTED_LOCATION
  "${ONNXRUNTIME_DIR}/lib/${ANDROID_ABI}/libonnxruntime.so")

target_link_libraries(piper_jni onnxruntime log m)
