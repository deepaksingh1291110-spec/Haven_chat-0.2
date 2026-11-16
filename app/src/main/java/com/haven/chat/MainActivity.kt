// =====================================================================
// MainActivity.kt  (app/src/main/java/com/haven/chat/)
// WHAT: The FIRST and ONLY Android screen (Activity). Android starts it
//       when the user taps the app icon (see AndroidManifest.xml).
//       All our other screens (chat, drawer, settings) will be drawn
//       INSIDE it by Jetpack Compose.
// STATUS: TEMPORARY. It shows a simple "Build OK" text to prove the
//         build pipeline and the theme work. Later we replace
//         PlaceholderScreen() with the real app (navigation + screens).
// =====================================================================
package com.haven.chat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.haven.chat.ui.theme.HavenChatTheme

class MainActivity : ComponentActivity() {

    // onCreate runs ONE time when the screen starts.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Let the app draw behind the status bar and navigation bar
        // (modern full-screen look, like your designs).
        enableEdgeToEdge()

        // setContent = "everything inside the braces is drawn by Compose".
        setContent {
            // Wrap everything in our theme (colors + text styles).
            HavenChatTheme {
                // Surface = a background that uses the theme color.
                Surface(modifier = Modifier.fillMaxSize()) {
                    PlaceholderScreen()
                }
            }
        }
    }
}

// TEMPORARY screen: centered text. Will be deleted in a later step.
@Composable
fun PlaceholderScreen() {
    // Box with fillMaxSize + center = text in the middle of the screen.
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Haven Chat 0.2\nBuild OK",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}
