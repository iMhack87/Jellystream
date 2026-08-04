package dev.jellystream.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Cinematic palette: deep blacks, white CTAs, muted grays — Apple TV+ mood. */
object CinemaColors {
    val Background = Color(0xFF0A0A0C)
    val Surface = Color(0xFF141418)
    val SurfaceVariant = Color(0xFF1D1D23)
    val TextPrimary = Color(0xFFF5F5F7)
    val TextSecondary = Color(0xFF9E9EA7)
    val Accent = Color(0xFFF5F5F7)
    val ProgressTrack = Color(0x66FFFFFF)
}

private val colorScheme = darkColorScheme(
    background = CinemaColors.Background,
    onBackground = CinemaColors.TextPrimary,
    surface = CinemaColors.Background,
    onSurface = CinemaColors.TextPrimary,
    surfaceVariant = CinemaColors.SurfaceVariant,
    onSurfaceVariant = CinemaColors.TextSecondary,
    primary = CinemaColors.Accent,
    onPrimary = Color(0xFF0A0A0C),
    secondary = CinemaColors.TextSecondary,
    onSecondary = CinemaColors.Background,
    error = Color(0xFFFF6B6B),
)

private val typography = Typography().let { base ->
    base.copy(
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun JellystreamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        content = content,
    )
}
