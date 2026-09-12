plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "dev.ihorshevchuk.piper.player"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
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
    testImplementation("junit:junit:4.13.2")
}
