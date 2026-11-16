// =====================================================================
// app/build.gradle.kts  (inside the "app" folder)
// WHAT: Build settings for the app module: app name/ID, Android versions,
//       CPU type, and the LIBRARIES (dependencies) the app uses.
// =====================================================================

plugins {
    id("com.android.application")                 // builds the APK
    id("org.jetbrains.kotlin.android")            // Kotlin language
    id("org.jetbrains.kotlin.plugin.compose")     // Jetpack Compose UI
}

android {
    // "namespace" = the code package. Our Kotlin files use this name.
    namespace = "com.haven.chat"

    // Android version we compile against (newest tools).
    compileSdk = 35

    defaultConfig {
        // Unique app ID on the phone. Do not change after installing,
        // or Android treats it as a different app.
        applicationId = "com.haven.chat"

        // Oldest Android the app supports (Android 8.0).
        minSdk = 26
        // Android version the app is tested for.
        targetSdk = 35

        // Version shown to the user, and a counter that must go up.
        versionCode = 1
        versionName = "0.2.0"

        // CPU ONLY, arm64-v8a ONLY (your Realme P3).
        // This keeps the APK small and is needed later for llama.cpp.
        ndk {
            abiFilters += listOf("arm64-v8a")
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

    // PHASE 2 (later): the llama.cpp C++ build will be added here.
}

dependencies {
    // Compose BOM = one version number that keeps all Compose
    // libraries compatible with each other.
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    // Core Android helpers
    implementation("androidx.core:core-ktx:1.15.0")
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
