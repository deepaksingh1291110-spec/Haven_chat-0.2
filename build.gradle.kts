// =====================================================================
// build.gradle.kts  (project ROOT folder)
// WHAT: Declares WHICH build plugins the project uses and their VERSIONS.
// NOTE: "apply false" means "load the plugin, but don't use it here".
//       The real use happens in app/build.gradle.kts.
// TIP:  To update a version later, change it only here.
//
// PHASE 3 CHANGE (the only change in this file):
//   Added the KSP plugin (last block below).
//   KSP = "Kotlin Symbol Processing". Room (our database library) uses
//   it at build time to WRITE database code for us from the short
//   descriptions we give it (entities and DAOs). Without KSP, Room
//   cannot build.
//   RULE: the KSP version starts with the Kotlin version. Kotlin is
//   2.0.21, so KSP must be 2.0.21-x.y.z. If you ever change Kotlin,
//   change KSP too.
// =====================================================================

plugins {
    // Android plugin: turns our Kotlin code into an APK.
    id("com.android.application") version "8.7.3" apply false

    // Kotlin language support for Android.
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false

    // Jetpack Compose compiler plugin (draws our UI with code).
    // Its version MUST match the Kotlin version above.
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false

    // PHASE 3 (NEW): KSP, used by Room to generate database code.
    // The part before the dash (2.0.21) = our Kotlin version.
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
}
