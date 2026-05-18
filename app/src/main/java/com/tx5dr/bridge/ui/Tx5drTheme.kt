package com.tx5dr.bridge.ui

import android.app.Activity
import android.os.Build
import android.view.View
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView

private val Tx5drLightColors = lightColorScheme(
    primary = Color(0xFFE11D48),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9DF),
    onPrimaryContainer = Color(0xFF3F0011),
    secondary = Color(0xFF7B2D3F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFD9DF),
    onSecondaryContainer = Color(0xFF301019),
    tertiary = Color(0xFF8A4A00),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDDB7),
    onTertiaryContainer = Color(0xFF2C1600),
    background = Color(0xFFFFF7F7),
    onBackground = Color(0xFF2C1315),
    surface = Color(0xFFFFFBFF),
    onSurface = Color(0xFF2C1315),
    surfaceVariant = Color(0xFFF5DDDF),
    onSurfaceVariant = Color(0xFF534346),
    outline = Color(0xFF857376),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val Tx5drDarkColors = darkColorScheme(
    primary = Color(0xFFFFB2BF),
    onPrimary = Color(0xFF65001F),
    primaryContainer = Color(0xFF92002F),
    onPrimaryContainer = Color(0xFFFFD9DF),
    secondary = Color(0xFFE9BDC5),
    onSecondary = Color(0xFF46242D),
    secondaryContainer = Color(0xFF603A43),
    onSecondaryContainer = Color(0xFFFFD9DF),
    tertiary = Color(0xFFFFB86E),
    onTertiary = Color(0xFF4A2800),
    tertiaryContainer = Color(0xFF693B00),
    onTertiaryContainer = Color(0xFFFFDDB7),
    background = Color(0xFF19090D),
    onBackground = Color(0xFFFFF1F2),
    surface = Color(0xFF181113),
    onSurface = Color(0xFFFFF1F2),
    surfaceVariant = Color(0xFF534346),
    onSurfaceVariant = Color(0xFFD8C2C6),
    outline = Color(0xFFA08C90),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun Tx5drTheme(
    controlSystemBars: Boolean = true,
    content: @Composable () -> Unit,
) {
    val darkTheme = isSystemInDarkTheme()
    val colors = if (darkTheme) Tx5drDarkColors else Tx5drLightColors
    val view = LocalView.current

    if (controlSystemBars && !view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = colors.background.toArgb()
            window.navigationBarColor = colors.surface.toArgb()
            @Suppress("DEPRECATION")
            var flags = window.decorView.systemUiVisibility
            @Suppress("DEPRECATION")
            flags = if (darkTheme) {
                flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() and if (Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv() else -1
            } else {
                flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or if (Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
            }
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = flags
        }
    }

    MaterialTheme(
        colorScheme = colors,
        content = content,
    )
}
