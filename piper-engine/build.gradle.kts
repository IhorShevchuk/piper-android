plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "dev.ihorshevchuk.piper.engine"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                arguments(
                    "-DPIPER1_GPL_DIR=${rootDir}/third-party/piper1-gpl",
                    "-DESPEAK_NG_DIR=${rootDir}/third-party/espeak-ng",
                    "-DSONIC_DIR=${rootDir}/third-party/sonic",
                    "-DONNXRUNTIME_DIR=${rootDir}/third-party/onnxruntime"
                )
                abiFilters("arm64-v8a", "armeabi-v7a", "x86_64")
                cppFlags("-std=c++17")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
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
    implementation(project(":piper-utils"))
    androidTestImplementation(project(":piper-player"))
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
