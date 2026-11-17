// =====================================================================
// app/build.gradle.kts  (inside the "app" folder)
// WHAT: Build settings for the app module: app name/ID, Android versions,
//       CPU type, the C++ engine build, and the LIBRARIES (dependencies).
//
// PHASE 2 changes (marked "PHASE 2"): the C++ engine (llama.cpp) is now
// built together with the app using the Android NDK and CMake.
// =====================================================================

plugins {
    id("com.android.application")                 // builds the APK
    id("org.jetbrains.kotlin.android")            // Kotlin language
    id("org.jetbrains.kotlin.plugin.compose")     // Jetpack Compose UI
}

android {
    // "namespace" = the code package. Our Kotlin files use this name.
    // The C++ function names (Java_com_haven_chat_...) depend on it too,
    // so never change it.
    namespace = "com.haven.chat"

    // Android version we compile against (newest tools).
    compileSdk = 35

    // PHASE 2: the NDK (C++ compiler for Android). The workflow installs
    // exactly this version, so both places must say the same number.
    ndkVersion = "27.2.12479018"

    defaultConfig {
        // Unique app ID on the phone. Do not change after installing,
        // or Android treats it as a different app.
        applicationId = "com.haven.chat"

        // Oldest Android the app supports (Android 8.0).
        minSdk = 26
        // Android version the app is tested for.
        targetSdk = 35

        // Version shown to the user, and a counter that must go up.
        versionCode = 2
        versionName = "0.2.0"

        // CPU ONLY, arm64-v8a ONLY (your Realme P3).
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        // PHASE 2: settings passed to CMake (the C++ build tool).
        externalNativeBuild {
            cmake {
                // Release is essential: a Debug build of llama.cpp is very slow.
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                arguments += "-DANDROID_STL=c++_shared"

                // llama.cpp: shared libraries, CPU only.
                arguments += "-DBUILD_SHARED_LIBS=ON"
                arguments += "-DLLAMA_BUILD_APP=OFF"
                arguments += "-DLLAMA_BUILD_COMMON=ON"
                arguments += "-DLLAMA_OPENSSL=OFF"

                // ggml: build several CPU variants and pick the best one
                // for the phone at run time.
                arguments += "-DGGML_NATIVE=OFF"
                arguments += "-DGGML_BACKEND_DL=ON"
                arguments += "-DGGML_CPU_ALL_VARIANTS=ON"
                arguments += "-DGGML_LLAMAFILE=OFF"
            }
        }
    }

    // PHASE 2: where our CMakeLists.txt is, and which CMake version to use
    // (the workflow installs the same version).
    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    // PHASE 2: the CPU variant libraries are loaded from the app's native
    // library folder, so they must be extracted to disk on install.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildTypes {
        // We only build "debug" APKs (easy install, no signing key needed).
        release {
            isMinifyEnabled = false
        }
    }

    // Java/Kotlin language level 17 (matches GitHub's build machine).
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // Turn on Jetpack Compose (UI written as Kotlin code, no XML layouts).
    buildFeatures {
        compose = true
    }
}

dependencies {
    // Compose BOM = one version number that keeps all Compose
    // libraries compatible with each other.
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    // Core Android helpers
    implementation("androidx.core:core-ktx:1.15.0")
    // Splash screen library (needed by Theme.SplashScreen in themes.xml)
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Compose UI pieces
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")

    // Material 3 = the look of your designs (cards, buttons, sliders)
    implementation("androidx.compose.material3:material3")
    // Extra icons (menu, settings, send arrow, etc.)
    implementation("androidx.compose.material:material-icons-extended")

    // Screen-to-screen navigation (chat, settings)
    implementation("androidx.navigation:navigation-compose:2.8.5")
    // ViewModel = keeps screen data alive when the phone rotates
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // Debug-only: lets Android Studio preview the UI. Not in the APK logic.
    debugImplementation("androidx.compose.ui:ui-tooling")
}
