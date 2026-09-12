// Root build file: plugin versions only. Module configuration lives in each
// module's own build.gradle.kts.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("com.android.library") version "8.5.2" apply false
    kotlin("android") version "2.0.21" apply false
    kotlin("jvm") version "2.0.21" apply false
}

// Group used for composite-build substitution by piper-app-android:
// dev.ihorshevchuk.piper:piper-engine (etc.) resolves to these modules.
subprojects {
    group = "dev.ihorshevchuk.piper"
}
