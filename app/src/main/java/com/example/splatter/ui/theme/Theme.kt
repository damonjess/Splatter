package com.example.splatter.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Splatter's single, dark-first Material 3 theme.
 *
 * The app is a 3D capture tool, so the viewport should stay dominant — we
 * deliberately ship one curated dark scheme instead of following the system
 * light/dark setting or Material You dynamic colour. That keeps the branded
 * mint/periwinkle accents consistent across every device.
 */
private val SplatterColorScheme = darkColorScheme(
    primary = Mint,
    onPrimary = OnMint,
    primaryContainer = MintDeep,
    onPrimaryContainer = Mint,
    secondary = Periwinkle,
    onSecondary = Ink,
    tertiary = Sky,
    onTertiary = Ink,
    background = Ink,
    onBackground = Snow,
    surface = InkElevated,
    onSurface = Snow,
    surfaceVariant = InkContainer,
    onSurfaceVariant = Fog,
    outline = InkHairline,
    outlineVariant = InkHairline,
    error = Coral,
    onError = OnCoral,
    scrim = Ink
)

@Composable
fun SplatterTheme(
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Ink.toArgb()
            window.navigationBarColor = Ink.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = SplatterColorScheme,
        typography = Typography,
        content = content
    )
}
