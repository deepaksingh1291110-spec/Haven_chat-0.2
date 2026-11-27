// =====================================================================
// MainActivity.kt  (app/src/main/java/com/haven/chat/)
// WHAT: The first and only Android screen (Activity). It starts when
//       the user taps the app icon (see AndroidManifest.xml).
// JOBS: 1) Show the splash: first Android's black system splash, then
//          our own picture (HavenSplash), then the real app (HavenApp).
//       2) PHASE 2: start the native llama.cpp engine ONCE at launch
//          (EngineManager.init). It only starts the engine; it does
//          NOT load the model. The model is loaded later, when the user
//          taps "Load Model" in Settings (or chooses a model on the
//          Model management screen).
//
// PRIVATE MODELS (marked "MODELS"): NO MORE "ALL FILES ACCESS".
//   This file used to check and ask for the permission "All files access"
//   (and READ_EXTERNAL_STORAGE on old phones), because the models were read
//   from a normal phone folder. Now the models are COPIED into the app's
//   private space (ModelStore), which needs no permission at all. So all of
//   that code is gone:
//     - the storageAllowed state and the legacy permission launcher,
//     - hasStorageAccess() and requestStorageAccess(),
//     - onResume() (it only re-checked the permission),
//     - the imports that belonged to them.
//   HavenApp() takes no parameters any more.
// =====================================================================
package com.haven.chat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.haven.chat.engine.EngineManager
import com.haven.chat.ui.HavenApp
import com.haven.chat.ui.splash.HavenSplash
import com.haven.chat.ui.theme.HavenChatTheme

class MainActivity : ComponentActivity() {

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
                        // MODELS: no permission parameters any more.
                        HavenApp()
                    }
                }
            }
        }
    }
}
