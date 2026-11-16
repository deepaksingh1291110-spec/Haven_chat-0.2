// =====================================================================
// build.gradle.kts  (project ROOT folder)
// WHAT: Declares WHICH build plugins the project uses and their VERSIONS.
// NOTE: "apply false" means "load the plugin, but don't use it here".
//       The real use happens in app/build.gradle.kts.
// TIP:  To update a version later, change it only here.
// =====================================================================

plugins {
    // Android plugin: turns our Kotlin code into an APK.
    id("com.android.application") version "8.7.3" apply false

    // Kotlin language support for Android.
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false

    // Jetpack Compose compiler plugin (draws our UI with code).
    // Its version MUST match the Kotlin version above.
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
