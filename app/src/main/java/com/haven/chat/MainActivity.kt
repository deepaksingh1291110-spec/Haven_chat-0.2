// =====================================================================
// MainActivity.kt  (app/src/main/java/com/haven/chat/)
// WHAT: The first and only Android screen (Activity). It starts when
//       the user taps the app icon (see AndroidManifest.xml).
// JOBS: 1) Show the splash: first Android's black system splash, then
//          our own picture (HavenSplash), then the real app (HavenApp).
//       2) Check and request the "All files access" permission, which
//          the app needs to read model files (GGUF) from the phone.
//          Only an Activity can do this, so it lives here and not in
//          the UI files. The result is passed down to the UI.
//       3) PHASE 2: start the native llama.cpp engine ONCE at launch
//          (EngineManager.init). It only starts the engine; it does
//          NOT load the model. The model is loaded later, when the user
//          taps "Load Model" in Settings.
// =====================================================================
package com.haven.chat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.haven.chat.engine.EngineManager
import com.haven.chat.ui.HavenApp
import com.haven.chat.ui.splash.HavenSplash
import com.haven.chat.ui.theme.HavenChatTheme

class MainActivity : ComponentActivity() {

    // True when the app may read files. "mutableStateOf" means the UI
    // redraws by itself when this changes (for example the Settings
    // badge changes from "Tap to allow" to "Allowed").
    private var storageAllowed by mutableStateOf(false)

    // ONLY for old phones (Android 10 and older): the normal pop-up that
    // asks for permission. When the user answers, we re-check.
    private val legacyPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            storageAllowed = hasStorageAccess()
        }

    // onCreate runs ONE time when the screen starts.
    override fun onCreate(savedInstanceState: Bundle?) {
        // IMPORTANT: this line must come BEFORE super.onCreate.
        // It switches from the splash theme (Theme.Haven.Splash) to the
        // normal theme once the app is ready. Without it, the app would
        // stay stuck on the black splash.
        installSplashScreen()

        super.onCreate(savedInstanceState)

        // PHASE 2: start the native engine. "nativeLibraryDir" is the
        // folder where Android put our C++ libraries (.so files).
        // EngineManager.init does the work on a background thread and
        // ignores a second call, so it is safe when the phone rotates
        // and onCreate runs again.
        EngineManager.init(applicationInfo.nativeLibraryDir)

        // Draw behind the status bar and navigation bar (full-screen look).
        enableEdgeToEdge()

        // Check the permission once at start.
        storageAllowed = hasStorageAccess()

        // Everything inside setContent is drawn by Jetpack Compose.
        setContent {
            HavenChatTheme {
                Surface(modifier = Modifier.fillMaxSize()) {

                    // true = show our splash picture, false = show the app.
                    // "rememberSaveable" keeps the value when the phone is
                    // rotated, so the splash does not play a second time.
                    var showSplash by rememberSaveable { mutableStateOf(true) }

                    if (showSplash) {
                        // After 2 seconds the splash calls this lambda.
                        HavenSplash(onFinished = { showSplash = false })
                    } else {
                        HavenApp(
                            storageAllowed = storageAllowed,
                            onRequestStorageAccess = { requestStorageAccess() }
                        )
                    }
                }
            }
        }
    }

    // onResume runs every time the app comes back to the front. The user
    // may have just allowed the permission in the phone's settings page,
    // so we check again.
    override fun onResume() {
        super.onResume()
        storageAllowed = hasStorageAccess()
    }

    // Do we have permission to read files on the phone?
    private fun hasStorageAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ : the special "All files access" switch.
            Environment.isExternalStorageManager()
        } else {
            // Android 10 and older: the normal read-storage permission.
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    // Ask the user for permission.
    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ : open the phone's "All files access" page for
            // OUR app. The user flips the switch by hand.
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (e: Exception) {
                // Some phones lack that page. Fall back to the general list.
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            // Old phones: show the normal permission pop-up.
            legacyPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
}
