package com.kerneldroid.karchiver.presentation.utils

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.min

fun calculateBorderRadiusForGridItem(index: Int, count: Int, columnCount: Int): List<Dp> {
    val rounded = 20.dp
    val flat = 4.dp
    val columns = min(count, columnCount).coerceAtLeast(1)
    val rows = ceil(count * 1.0 / columns).toInt().coerceAtLeast(1)
    val isLeft = index % columns == 0
    val isRight = index % columns == columns - 1 || index == count - 1
    val isTop = index / columns == 0
    val isBottom = index / columns == rows - 1
    return listOf(
        if (isTop && isLeft) rounded else flat,
        if (isTop && isRight) rounded else flat,
        if (isBottom && isRight) rounded else flat,
        if (isBottom && isLeft) rounded else flat,
    )
}

fun calculateBorderRadiusForListItem(index: Int, count: Int): List<Dp> {
    val rounded = 16.dp
    val flat = 4.dp
    if (count == 1) return List(4) { rounded }
    val isFirst = index == 0
    val isLast = index == count - 1
    return listOf(
        if (isFirst) rounded else flat,
        if (isFirst) rounded else flat,
        if (isLast) rounded else flat,
        if (isLast) rounded else flat,
    )
}
