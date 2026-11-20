package com.niutrip.app.ui.common

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import coil.compose.AsyncImage
import kotlin.math.max
import kotlin.math.min

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal fun ZoomablePhoto(
    url: String,
    description: String,
    isActive: Boolean,
    onZoomedChange: (Boolean) -> Unit,
) {
    var scale by remember(url) { mutableFloatStateOf(1f) }
    var offset by remember(url) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var aspectRatio by remember(url) { mutableFloatStateOf(1f) }
    val notifyZoomed by rememberUpdatedState(onZoomedChange)
    val transform = rememberTransformableState { zoom, pan, _ ->
        val nextScale = (scale * zoom).coerceIn(1f, 6f)
        offset = constrainPhotoPan(offset * (nextScale / scale) + pan, viewport, aspectRatio, nextScale)
        scale = nextScale
        notifyZoomed(scale > 1.001f)
    }
    LaunchedEffect(isActive) {
        if (!isActive) {
            scale = 1f
            offset = Offset.Zero
        }
    }
    Box(
        Modifier.fillMaxSize().clipToBounds()
            .onSizeChanged {
                viewport = it
                offset = constrainPhotoPan(offset, it, aspectRatio, scale)
            }
            .pointerInput(url, viewport, aspectRatio) {
                detectTapGestures(onDoubleTap = { point ->
                    val nextScale = if (scale > 1.001f) 1f else 2.5f
                    val center = Offset(viewport.width / 2f, viewport.height / 2f)
                    offset = constrainPhotoPan((center - point) * (nextScale - 1f), viewport, aspectRatio, nextScale)
                    scale = nextScale
                    notifyZoomed(scale > 1.001f)
                })
            }
            .transformable(state = transform, canPan = { scale > 1.001f }),
    ) {
        AsyncImage(
            model = url,
            contentDescription = description,
            contentScale = ContentScale.Fit,
            onSuccess = { result ->
                val drawable = result.result.drawable
                if (drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
                    aspectRatio = drawable.intrinsicWidth.toFloat() / drawable.intrinsicHeight
                    offset = constrainPhotoPan(offset, viewport, aspectRatio, scale)
                }
            },
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
        )
    }
}

internal fun constrainPhotoPan(offset: Offset, viewport: IntSize, aspectRatio: Float, scale: Float): Offset {
    if (viewport.width <= 0 || viewport.height <= 0 || aspectRatio <= 0 || scale <= 1f) return Offset.Zero
    val imageWidth = min(viewport.width.toFloat(), viewport.height * aspectRatio)
    val imageHeight = imageWidth / aspectRatio
    val maxX = max(0f, (imageWidth * scale - viewport.width) / 2f)
    val maxY = max(0f, (imageHeight * scale - viewport.height) / 2f)
    return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
}
