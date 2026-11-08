package com.jupiterlabs.ghostdrive.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Midnight = darkColorScheme(
    primary = Color(0xFFA6B8FF), onPrimary = Color(0xFF142355), primaryContainer = Color(0xFF26396D),
    onPrimaryContainer = Color(0xFFE1E6FF), secondary = Color(0xFFD1B6FF), onSecondary = Color(0xFF352448),
    secondaryContainer = Color(0xFF3B2D53), tertiary = Color(0xFF8AE0D4),
    background = Color(0xFF0D1021), onBackground = Color(0xFFF0F1FA),
    surface = Color(0xFF151A2F), onSurface = Color(0xFFF0F1FA),
    surfaceVariant = Color(0xFF222A43), onSurfaceVariant = Color(0xFFBFC6DE),
    outline = Color(0xFF525E7C), error = Color(0xFFFFB4AB)
)

private val MatrixGreen = darkColorScheme(
    primary = Color(0xFF00E676), onPrimary = Color(0xFF002911), primaryContainer = Color(0xFF0B3B21),
    onPrimaryContainer = Color(0xFFB9F6CA), secondary = Color(0xFF69F0AE), onSecondary = Color(0xFF002914),
    secondaryContainer = Color(0xFF113823), tertiary = Color(0xFF00BFA5),
    background = Color(0xFF040705), onBackground = Color(0xFFE6F7EC),
    surface = Color(0xFF09110C), onSurface = Color(0xFFE6F7EC),
    surfaceVariant = Color(0xFF112016), onSurfaceVariant = Color(0xFFA3C9B0),
    outline = Color(0xFF235234), error = Color(0xFFFF8A80)
)

private val Merlin = lightColorScheme(
    primary = Color(0xFF775532), onPrimary = Color(0xFFFFF9EE), primaryContainer = Color(0xFFEED9B7),
    onPrimaryContainer = Color(0xFF39280F), secondary = Color(0xFF636547), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4E5C8), tertiary = Color(0xFF9B5F14),
    background = Color(0xFFF5EFE2), onBackground = Color(0xFF29251F),
    surface = Color(0xFFFFFAF0), onSurface = Color(0xFF29251F),
    surfaceVariant = Color(0xFFE8DDCA), onSurfaceVariant = Color(0xFF5F5546),
    outline = Color(0xFF8B7E69), error = Color(0xFF9B3028)
)

@Composable fun GhostTheme(theme: String, content: @Composable () -> Unit) {
    val scheme = when (theme) {
        "merlin" -> Merlin
        "matrix" -> MatrixGreen
        else -> Midnight
    }
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
}
