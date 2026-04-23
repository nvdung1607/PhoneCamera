package com.example.phonecamera.ui.theme

import android.util.Log
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Indigo400,
    onPrimary = Slate900,
    primaryContainer = IndigoContainerDark,
    onPrimaryContainer = Indigo400,

    secondary = Slate700,
    onSecondary = TextPrimaryDark,
    secondaryContainer = Slate800,
    onSecondaryContainer = TextPrimaryDark,

    background = Slate900,
    onBackground = TextPrimaryDark,

    surface = Slate800,
    onSurface = TextPrimaryDark,
    surfaceVariant = Slate700,
    onSurfaceVariant = TextSecondaryDark,

    error = Rose500,
    onError = Color.White,
    errorContainer = RoseContainerDark,
    onErrorContainer = Rose500,

    outline = DividerDark,
    outlineVariant = TextSecondaryDark,
    inverseSurface = TextPrimaryLight,
    inverseOnSurface = Slate900,
    scrim = OverlayDark
)

private val LightColorScheme = lightColorScheme(
    primary = Indigo600,
    onPrimary = Color.White,
    primaryContainer = IndigoContainerLight,
    onPrimaryContainer = Indigo600,

    secondary = Slate100,
    onSecondary = TextPrimaryLight,
    secondaryContainer = Slate50,
    onSecondaryContainer = TextPrimaryLight,

    background = Slate50,
    onBackground = TextPrimaryLight,

    surface = Color.White,
    onSurface = TextPrimaryLight,
    surfaceVariant = Slate100,
    onSurfaceVariant = TextSecondaryLight,

    error = Rose500,
    onError = Color.White,
    errorContainer = RoseContainerLight,
    onErrorContainer = Rose500,

    outline = DividerLight,
    outlineVariant = TextSecondaryLight,
    inverseSurface = TextPrimaryDark,
    inverseOnSurface = Color.White,
    scrim = OverlayDark
)

@Composable
fun PhoneCameraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    
    Log.d("PhoneCamera", "Applying Slate & Indigo theme: darkTheme=$darkTheme")

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content
    )
}
