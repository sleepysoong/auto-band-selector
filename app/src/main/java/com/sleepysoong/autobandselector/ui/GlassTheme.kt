package com.sleepysoong.autobandselector.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily

// PretendardFACE asset ids live in app resources; resolved by the caller to keep this pure.
object GlassPalette {
    val DarkBackground = Color(0xFF0B0B0D)
    val DarkBackgroundEnd = Color(0xFF18181C)
    val LightBackground = Color(0xFFF7F7F9)
    val LightBackgroundEnd = Color(0xFFECECEF)
    val DarkSurface = Color(0x14FFFFFF)
    val LightSurface = Color(0x0D000000)
    val Accent = Color(0xFFFFD54F)
}

data class GlassEffects(
    val blurPx: Float?,
    val lens: Boolean
)

/** Lens needs API 33, blur needs API 31, opaque-but-readable fallback is used below. */
fun supportedGlassEffects(sdk: Int): GlassEffects = GlassEffects(
    blurPx = if (sdk >= Build.VERSION_CODES.S) 18f else null,
    lens = sdk >= Build.VERSION_CODES.TIRAMISU
)
