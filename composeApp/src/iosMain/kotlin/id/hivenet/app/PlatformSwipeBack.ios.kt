package id.hivenet.app

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs

@Composable
actual fun Modifier.platformSwipeBack(enabled: Boolean, onBack: () -> Unit): Modifier {
    if (!enabled) return this
    val density = LocalDensity.current
    val edgePx = with(density) { 32.dp.toPx() }
    val triggerPx = with(density) { 72.dp.toPx() }
    val latestOnBack = remember(onBack) { onBack }
    return pointerInput(enabled, latestOnBack) {
        var startedAtEdge = false
        var totalX = 0f
        var totalY = 0f
        detectDragGestures(
            onDragStart = { offset ->
                startedAtEdge = offset.x <= edgePx
                totalX = 0f
                totalY = 0f
            },
            onDragEnd = {
                if (startedAtEdge && totalX > triggerPx && abs(totalX) > abs(totalY) * 1.5f) latestOnBack()
            },
            onDragCancel = {
                startedAtEdge = false
                totalX = 0f
                totalY = 0f
            },
            onDrag = { change, dragAmount ->
                if (startedAtEdge) {
                    totalX += dragAmount.x
                    totalY += dragAmount.y
                    if (totalX > 0f && abs(totalX) > abs(totalY)) change.consume()
                }
            },
        )
    }
}
