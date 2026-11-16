// =====================================================================
// Type.kt  (app/src/main/java/com/haven/chat/ui/theme/)
// WHAT: Text styles (font size, weight, spacing) used across the app.
// HOW:  "sp" = scalable pixels. Text size follows the phone's font
//       setting, so it stays readable for everyone.
// NOTE: Styles we do not list here keep Material 3's default values.
// =====================================================================
package com.haven.chat.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val HavenTypography = Typography(

    // Screen titles, e.g. "Haven Chat" in the top bar.
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 22.sp,
        lineHeight = 28.sp
    ),

    // Chat message text (both your bubble and the AI bubble).
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 26.sp,      // roomy lines are easier to read on a phone
        letterSpacing = 0.2.sp
    ),

    // Normal small text: drawer items, settings labels.
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),

    // Tiny hints: "Injected at start of context window", etc.
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),

    // Card headings: "Model Configuration", "Inference Parameters".
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),

    // Buttons and small badges: "Load Model", "Allowed".
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp
    )
)

// Special style for numbers like "35.4 t/s • 311 tokens".
// Monospace = every character has the same width (looks like your design).
val StatsTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    lineHeight = 16.sp
)
