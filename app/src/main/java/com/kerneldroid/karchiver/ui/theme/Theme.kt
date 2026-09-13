package com.kerneldroid.karchiver.ui.theme

import android.os.Build
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import com.kerneldroid.karchiver.data.ThemeMode
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.rememberDynamicColorScheme

val DefaultSeedColor = Color(0xFF6750A4)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun KArchiverTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    seedColor: Color? = null,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val darkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.OLED -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val amoled = themeMode == ThemeMode.OLED
    val useSystemDynamic = dynamicColor && seedColor == null &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val colorScheme = if (useSystemDynamic) {
        val base = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        if (amoled) base.copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = Color.Black,
            surfaceContainer = Color(0xFF0B0B0B),
            surfaceContainerHigh = Color(0xFF121212),
            surfaceContainerHighest = Color(0xFF1A1A1A)
        ) else base
    } else {
        rememberDynamicColorScheme(
            seedColor = seedColor ?: DefaultSeedColor,
            isDark = darkTheme,
            isAmoled = amoled,
            style = PaletteStyle.TonalSpot,
            specVersion = ColorSpec.SpecVersion.SPEC_2025,
            contrastLevel = 0.0
        )
    }
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        typography = KArchiverTypography,
        shapes = KArchiverShapes,
        content = content
    )
}

object KArchiverMotion {
    const val WIDTH_FRACTION = 0.25f
    val spatialSpring = spring(
        stiffness = Spring.StiffnessMediumLow,
        dampingRatio = 0.82f,
        visibilityThreshold = IntOffset.VisibilityThreshold
    )
    val fadeSpring = spring<Float>(
        stiffness = Spring.StiffnessMedium,
        dampingRatio = Spring.DampingRatioNoBouncy
    )
}
