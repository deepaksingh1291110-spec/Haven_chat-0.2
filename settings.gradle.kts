// =====================================================================
// settings.gradle.kts  (project ROOT folder)
// WHAT: Tells Gradle (the Android build tool) which projects exist
//       and where to download libraries from.
// WHEN: Read first, before every other build file.
// =====================================================================

// Where Gradle finds its PLUGINS (e.g. the Android plugin).
pluginManagement {
    repositories {
        google()            // Google's repository: Android plugins and libraries
        mavenCentral()      // The big public library store
        gradlePluginPortal()// Other Gradle plugins
    }
}

// Where the app finds its LIBRARIES (e.g. Compose, Material 3).
dependencyResolutionManagement {
    // Stops sub-projects from adding their own repositories.
    // This keeps all downloads in one place (easy to debug).
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// The name of the whole project.
rootProject.name = "HavenChat"

// The modules inside the project. We have one module: ":app".
// It is the folder named "app" that holds our Android app.
include(":app")
