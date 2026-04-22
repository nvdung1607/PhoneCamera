package com.example.phonecamera.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = CyanNeon,
    onPrimary = NavyDeep,
    primaryContainer = CyanGlow,
    onPrimaryContainer = CyanNeon,

    secondary = NavySurface,
    onSecondary = TextPrimary,
    secondaryContainer = NavyCard,
    onSecondaryContainer = TextPrimary,

    background = NavyDeep,
    onBackground = TextPrimary,

    surface = NavyMid,
    onSurface = TextPrimary,
    surfaceVariant = NavySurface,
    onSurfaceVariant = TextSecondary,

    error = RedError,
    onError = Color.White,
    errorContainer = RedErrorSurface,
    onErrorContainer = RedError,

    outline = DividerColor,
    outlineVariant = TextHint,
    inverseSurface = TextPrimary,
    inverseOnSurface = NavyDeep,
    scrim = OverlayDark
)

@Composable
fun PhoneCameraTheme(
    content: @Composable () -> Unit
) {
    // Always use dark theme for security/surveillance aesthetics
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = AppTypography,
        content = content
    )
}
