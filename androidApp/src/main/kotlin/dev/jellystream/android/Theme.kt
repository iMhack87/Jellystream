package dev.jellystream.android

import android.content.pm.PackageManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex

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

/**
 * tvOS-style D-pad focus: scale + white ring when focused. Inert on touch
 * devices (nothing gains keyboard focus there), so one code path serves
 * both phone and Android TV. `composed` keeps the state local to the
 * modifier so focus changes don't recompose the whole item; zIndex lifts
 * the focused card above its later-drawn siblings.
 */
/**
 * Grabs initial D-pad focus on TV so the screen's primary CTA is one
 * center-press away (the ATV+ pattern) — without it, initial focus lands
 * on whatever the traversal order picks (nav buttons, secondary links)
 * and Play can even be unreachable from them. Inert on touch devices.
 */
fun Modifier.tvDefaultFocus(): Modifier = composed {
    val context = LocalContext.current
    val isTv = remember {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }
    if (isTv) {
        val requester = remember { FocusRequester() }
        LaunchedEffect(Unit) { requester.requestFocus() }
        this.focusRequester(requester)
    } else {
        this
    }
}

fun Modifier.dpadFocusEffect(shape: Shape = RoundedCornerShape(12.dp)): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.08f else 1f,
        label = "dpadFocusScale",
    )
    this
        .onFocusChanged { focused = it.isFocused }
        .zIndex(if (focused) 1f else 0f)
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .then(
            if (focused) Modifier.border(3.dp, Color.White, shape) else Modifier
        )
}
