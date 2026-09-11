package com.kerneldroid.karchiver.ui.theme

import android.os.Build
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import com.materialkolor.DynamicMaterialExpressiveTheme
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.rememberDynamicColorScheme

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun KArchiverTheme(
    seedColor: Color = Color(0xFF6750A4),
    darkTheme: Boolean = androidx.compose.foundation.isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val useDynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    if (useDynamic) {
        val scheme = rememberDynamicColorScheme(
            seedColor = seedColor,
            isDark = darkTheme,
            isAmoled = false,
            style = PaletteStyle.TonalSpot,
            specVersion = ColorSpec.SpecVersion.SPEC_2025,
            contrastLevel = 0.0
        )
        MaterialExpressiveTheme(
            colorScheme = scheme,
            motionScheme = MotionScheme.expressive(),
            typography = KArchiverTypography,
            shapes = KArchiverShapes,
            content = content
        )
    } else {
        DynamicMaterialExpressiveTheme(
            seedColor = seedColor,
            isDark = darkTheme,
            style = PaletteStyle.TonalSpot,
            specVersion = ColorSpec.SpecVersion.SPEC_2025,
            motionScheme = MotionScheme.expressive(),
            typography = KArchiverTypography,
            content = content
        )
    }
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
