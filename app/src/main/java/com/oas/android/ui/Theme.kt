// OASX theme adapted from OASX/lib/config/theme.dart (GPL-3.0).
package com.oas.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val OasxLight = lightColorScheme(
    primary = Color(0xFF6750A4), onPrimary = Color.White,
    primaryContainer = Color(0xFFEADDFF), onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFF625B71), secondaryContainer = Color(0xFFE8DEF8), onSecondaryContainer = Color(0xFF1D192B),
    tertiary = Color(0xFF7D5260), tertiaryContainer = Color(0xFFFFD8E4), onTertiaryContainer = Color(0xFF31111D),
    background = Color(0xFFFFFBFF), onBackground = Color(0xFF1C1B1F),
    surface = Color(0xFFFFFBFF), onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFE7E0EC), onSurfaceVariant = Color(0xFF49454F),
    outline = Color(0xFF79747E), outlineVariant = Color(0xFFCAC4D0),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF7F2FA),
    surfaceContainer = Color(0xFFF3EDF7), surfaceContainerHigh = Color(0xFFECE6F0), surfaceContainerHighest = Color(0xFFE6E0E9)
)

private val OasxDark = darkColorScheme(
    primary = Color(0xFFD0BCFF), onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B), onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFFCCC2DC), secondaryContainer = Color(0xFF4A4458), onSecondaryContainer = Color(0xFFE8DEF8),
    tertiary = Color(0xFFEFB8C8), tertiaryContainer = Color(0xFF633B48), onTertiaryContainer = Color(0xFFFFD8E4),
    background = Color(0xFF313033), onBackground = Color(0xFFE6E1E5),
    surface = Color(0xFF313033), onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF49454F), onSurfaceVariant = Color(0xFFCAC4D0),
    outline = Color(0xFF938F99), outlineVariant = Color(0xFF49454F),
    surfaceContainerLowest = Color(0xFF211F26), surfaceContainerLow = Color(0xFF38363B),
    surfaceContainer = Color(0xFF3D3B40), surfaceContainerHigh = Color(0xFF454249), surfaceContainerHighest = Color(0xFF4D4952)
)

@Composable
fun OasTheme(mode: String = "system", content: @Composable () -> Unit) {
    val dark = when(mode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    MaterialTheme(
        colorScheme = if(dark) OasxDark else OasxLight,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(4.dp),
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(12.dp),
            large = RoundedCornerShape(16.dp),
            extraLarge = RoundedCornerShape(28.dp)
        ),
        content = content
    )
}
