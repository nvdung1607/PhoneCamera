package com.example.phonecamera.ui.theme

import android.util.Log
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

private val LightColorScheme = lightColorScheme(
    primary = BluePrimary,
    onPrimary = Color.White,
    primaryContainer = BlueLight,
    onPrimaryContainer = BluePrimary,

    secondary = BackgroundLight,
    onSecondary = TextDark,
    secondaryContainer = BlueSurface,
    onSecondaryContainer = TextDark,

    background = BackgroundLight,
    onBackground = TextDark,

    surface = Color.White,
    onSurface = TextDark,
    surfaceVariant = BlueSurface,
    onSurfaceVariant = TextDarkSecondary,

    error = RedError,
    onError = Color.White,
    errorContainer = RedErrorSurface,
    onErrorContainer = RedError,

    outline = DividerLight,
    outlineVariant = TextHint,
    inverseSurface = TextDark,
    inverseOnSurface = Color.White,
    scrim = OverlayDark
)

@Composable
fun PhoneCameraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    
    Log.d("PhoneCameraTheme", "Applying theme: darkTheme=$darkTheme (System is ${if (isSystemInDarkTheme()) "Dark" else "Light"})")

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content
    )
}
