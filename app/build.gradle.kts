plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "dev.ihorshevchuk.piper.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.ihorshevchuk.piper.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(project(":piper-engine"))
    implementation(project(":piper-utils"))
    implementation(project(":piper-player"))
}
