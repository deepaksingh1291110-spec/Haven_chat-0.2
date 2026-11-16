// =====================================================================
// Color.kt  (app/src/main/java/com/haven/chat/ui/theme/)
// WHAT: All the colors of the app in ONE place, taken from your designs.
// HOW:  Color(0xFFRRGGBB) = a color. "FF" = fully visible, then
//       two digits each for Red, Green, Blue.
// TIP:  To change the look of the whole app, edit only this file.
// =====================================================================
package com.haven.chat.ui.theme

import androidx.compose.ui.graphics.Color

// ---------- DARK THEME (matches your screenshots) ----------
val DarkBackground = Color(0xFF121212)      // chat screen background
val DarkDrawerBackground = Color(0xFF000000) // side drawer (pure black)
val DarkSurface = Color(0xFF1E1E1E)         // AI message bubble, cards
val DarkSurfaceVariant = Color(0xFF2A2A2A)  // "Thinking process" box, chips
val DarkOnBackground = Color(0xFFE6E1E5)    // main text
val DarkOnSurfaceMuted = Color(0xFF9E9E9E)  // small gray text (t/s, hints)

val DarkPrimary = Color(0xFFCFBCFF)         // purple: Load Model, sliders
val DarkOnPrimary = Color(0xFF381E72)       // text on purple buttons
val DarkSettingsCard = Color(0xFF1D1B20)    // settings cards

val UserBubbleBlue = Color(0xFF0B57D0)      // your message bubble
val OnUserBubble = Color(0xFFFFFFFF)        // text inside your bubble
val SendButtonBlue = Color(0xFFA8C7FA)      // round send button
val StatusGreen = Color(0xFF81C995)         // "gemma4-e2b • Ready"

// ---------- LIGHT THEME (our own simple version) ----------
val LightBackground = Color(0xFFFFFBFE)
val LightSurface = Color(0xFFF3EDF7)
val LightSurfaceVariant = Color(0xFFE7E0EC)
val LightOnBackground = Color(0xFF1C1B1F)
val LightOnSurfaceMuted = Color(0xFF6B6B6B)

val LightPrimary = Color(0xFF6750A4)
val LightOnPrimary = Color(0xFFFFFFFF)

// ---------- ERROR (both themes) ----------
val ErrorRed = Color(0xFFF2B8B5)
