// =====================================================================
// Theme.kt  (app/src/main/java/com/haven/chat/ui/theme/)
// WHAT: Joins our colors (Color.kt) and text styles (Type.kt) into ONE
//       theme called HavenChatTheme.
// HOW:  Every screen is wrapped in HavenChatTheme { ... }. Inside it,
//       any widget can read colors with MaterialTheme.colorScheme.xxx
//       and text styles with MaterialTheme.typography.xxx.
// DARK/LIGHT: follows the phone's setting automatically.
// =====================================================================
package com.haven.chat.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

// Color set for DARK mode (matches your Stitch designs).
private val DarkColors = darkColorScheme(
    primary = DarkPrimary,                 // purple buttons, sliders
    onPrimary = DarkOnPrimary,             // text on purple buttons
    background = DarkBackground,           // screen background
    onBackground = DarkOnBackground,       // text on the background
    surface = DarkSurface,                 // cards, AI bubble
    onSurface = DarkOnBackground,          // text on cards
    surfaceVariant = DarkSurfaceVariant,   // thinking box, chips
    onSurfaceVariant = DarkOnSurfaceMuted, // small gray text
    error = ErrorRed
)

// Color set for LIGHT mode.
private val LightColors = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnBackground,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceMuted
)

// @Composable = this function DRAWS UI (or wraps UI). Compose's way of
// saying "this is a UI building block".
@Composable
fun HavenChatTheme(
    // By default, ask the phone: is dark mode on?
    darkTheme: Boolean = isSystemInDarkTheme(),
    // "content" = whatever screen we put inside the theme.
    content: @Composable () -> Unit
) {
    // Pick the color set that matches the mode.
    val colors = if (darkTheme) DarkColors else LightColors

    // MaterialTheme applies colors + text styles to everything inside.
    MaterialTheme(
        colorScheme = colors,
        typography = HavenTypography,
        content = content
    )
}
