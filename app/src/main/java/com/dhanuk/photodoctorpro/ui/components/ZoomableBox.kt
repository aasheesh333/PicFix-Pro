package com.dhanuk.photodoctorpro.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged

class ZoomableBoxState {
    var scale by mutableStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }
}

@Composable
fun rememberZoomableBoxState() = remember { ZoomableBoxState() }

@Composable
fun ZoomableBox(
    modifier: Modifier = Modifier,
    state: ZoomableBoxState = rememberZoomableBoxState(),
    minScale: Float = 1f,
    maxScale: Float = 10f,
    enableZoom: Boolean = true,
    onTap: ((Offset) -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(enableZoom) {
                if (enableZoom) {
                    awaitEachGesture {
                        var zoom = 1f
                        var pan = Offset.Zero
                        var pastTouchSlop = false
                        val touchSlop = viewConfiguration.touchSlop

                        awaitFirstDown(requireUnconsumed = false)

                        do {
                            val event = awaitPointerEvent()
                            val canceled = event.changes.any { it.isConsumed }
                            if (!canceled) {
                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()

                                if (!pastTouchSlop) {
                                    zoom *= zoomChange
                                    pan += panChange
                                    val centroidSize = event.calculateCentroidSize(useCurrent = false)
                                    val zoomMotion = kotlin.math.abs(1 - zoom) * centroidSize
                                    val panMotion = pan.getDistance()

                                    if (zoomMotion > touchSlop ||
                                        panMotion > touchSlop
                                    ) {
                                        pastTouchSlop = true
                                    }
                                }

                                if (pastTouchSlop) {
                                    if (event.changes.size >= 2) {
                                         // Only zoom/pan if 2 or more fingers
                                         val centroid = event.calculateCentroid(useCurrent = false)
                                         val effectiveZoom = event.calculateZoom()
                                         val effectivePan = event.calculatePan()

                                         // Apply Zoom
                                         val oldScale = state.scale
                                         val newScale = (state.scale * effectiveZoom).coerceIn(minScale, maxScale)
                                         state.scale = newScale

                                         // Apply Pan (adjusted for zoom to keep centroid stable-ish)
                                         // Simple pan:
                                         state.offset += effectivePan

                                         event.changes.forEach {
                                             if (it.positionChanged()) {
                                                 it.consume()
                                             }
                                         }
                                    }
                                }
                            }
                        } while (!canceled && event.changes.any { it.pressed })
                    }
                }
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = state.scale,
                    scaleY = state.scale,
                    translationX = state.offset.x,
                    translationY = state.offset.y
                ),
            content = content
        )
    }
}
