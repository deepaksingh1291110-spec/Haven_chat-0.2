// =====================================================================
// SplashScreen.kt  (app/src/main/java/com/haven/chat/ui/splash/)
// WHAT: Shows your splash picture (splash_screen.png) full screen for
//       about 2 seconds, then tells MainActivity to open the real app.
// WHY A PICTURE: Android's own splash is too limited for your design
//       (corner glows, tagline, spinner), so we draw the whole design
//       ourselves from one PNG file.
// PICTURE FILE: app/src/main/res/drawable-nodpi/splash_screen.png
//       In code it is called R.drawable.splash_screen (no ".png").
// NAME NOTE: the function is called HavenSplash (not SplashScreen) so it
//       is never confused with Android's own SplashScreen class.
// =====================================================================
package com.haven.chat.ui.splash

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.haven.chat.R
import kotlinx.coroutines.delay

// How long the splash stays on screen, in milliseconds (2000 = 2 seconds).
private const val SPLASH_TIME_MS = 2000L

@Composable
fun HavenSplash(onFinished: () -> Unit) {

    // "rememberUpdatedState" makes sure that when the timer ends we call
    // the NEWEST version of onFinished, even if the screen was redrawn
    // while we were waiting.
    val finished by rememberUpdatedState(onFinished)

    // LaunchedEffect(Unit) runs its block ONE time when the splash appears.
    // "delay" waits without freezing the screen.
    LaunchedEffect(Unit) {
        delay(SPLASH_TIME_MS)
        finished()
    }

    // Black box behind the picture (same black as the system splash),
    // so there is no color jump if the picture does not cover everything.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Image(
            painter = painterResource(id = R.drawable.splash_screen),
            contentDescription = "Haven Chat",
            modifier = Modifier.fillMaxSize(),
            // Crop = fill the whole screen, cutting a little from the edges
            // if the phone's shape differs from the picture's shape.
            contentScale = ContentScale.Crop
        )
    }
}
