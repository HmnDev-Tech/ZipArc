package com.kerneldroid.karchiver.presentation.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

private val TopCornerRadius = 32.dp
private val WideScreenThreshold = 840.dp
private val WideContentMaxWidth = 840.dp
private const val BAR_HOLD_MILLIS = 5000L
private const val BAR_TINT_MILLIS = 600

@Composable
fun Modifier.detectBarHold(onToggle: () -> Unit): Modifier {
    val latestToggle by rememberUpdatedState(onToggle)
    return pointerInput(Unit) {
        detectTapGestures(
            onPress = {
                try {
                    withTimeout(BAR_HOLD_MILLIS) { awaitRelease() }
                } catch (_: TimeoutCancellationException) {
                    latestToggle()
                }
            }
        )
    }
}

@Composable
fun RoundedTopScaffold(
    modifier: Modifier = Modifier,
    barLifted: Boolean = false,
    topBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    val layoutDirection = LocalLayoutDirection.current
    val bandColor by animateColorAsState(
        targetValue = if (barLifted) MaterialTheme.colorScheme.surfaceContainer
        else MaterialTheme.colorScheme.surface,
        animationSpec = tween(BAR_TINT_MILLIS),
        label = "barBand"
    )
    Scaffold(
        modifier = modifier,
        containerColor = bandColor,
        topBar = topBar,
        floatingActionButton = floatingActionButton,
        snackbarHost = snackbarHost
    ) { innerPadding ->
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val maxContentWidth: Dp =
                if (maxWidth >= WideScreenThreshold) WideContentMaxWidth else Dp.Unspecified
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = maxContentWidth)
                        .padding(top = innerPadding.calculateTopPadding()),
                    shape = RoundedCornerShape(topStart = TopCornerRadius, topEnd = TopCornerRadius),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    content(
                        PaddingValues(
                            start = innerPadding.calculateStartPadding(layoutDirection),
                            end = innerPadding.calculateEndPadding(layoutDirection),
                            bottom = innerPadding.calculateBottomPadding()
                        )
                    )
                }
            }
        }
    }
}
