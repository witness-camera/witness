package com.witnessrecord.app.ui

import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.witnessrecord.app.capture.CaptureSessionController.PreviewGeometry
import com.witnessrecord.app.viewmodel.CaptureViewModel
import kotlin.math.min

/**
 * Hosts the two camera preview outputs: the back camera fills the view, the
 * front camera sits as a small rounded picture-in-picture in the top-right
 * corner (shown only when dual-camera is active) - the same arrangement as
 * the iOS build's `CameraPreviewView`.
 *
 * Both surfaces are created as soon as the recording screen appears and stay
 * attached to the capture session for the whole recording, whether or not the
 * viewfinder is being shown. Hiding the viewfinder just draws black over the
 * top ([visible]), so the toggle is instant and, critically, never
 * reconfigures the camera - the footage runs on uninterrupted.
 *
 * A [TextureView] rather than a `SurfaceView`, which is the one thing here that
 * departs from the iOS structure and does so for a concrete reason. The camera
 * delivers frames in the sensor's own orientation - a quarter turn from the way
 * the phone is held, on nearly every device - and a SurfaceView cannot rotate
 * its buffer: it only stretches it to fill the layer, non-uniformly, which on
 * the first device this ran on squeezed the picture horizontally by exactly the
 * aspect ratio it had been given. A TextureView takes an arbitrary transform,
 * so the frame can be turned upright *and* fitted without distortion in one
 * step. See [previewTransform].
 */
@Composable
fun CameraPreview(
    visible: Boolean,
    showFront: Boolean,
    swapped: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val surfaces = remember { PreviewSurfaces() }
    val backGeometry by CaptureViewModel.previewGeometry.collectAsState()
    val frontGeometry by CaptureViewModel.frontPreviewGeometry.collectAsState()

    DisposableEffect(Unit) {
        onDispose {
            surfaces.back = null
            surfaces.front = null
            CaptureViewModel.setPreviewSurfaces(null, null)
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize().background(Color.Black)) {
        // 28% of the screen width at 16:9, matching the iOS PiP proportions.
        val viewWidth = maxWidth
        val viewHeight = maxHeight
        val pipWidth = maxWidth * 0.28f
        val pipHeight = pipWidth * 16f / 9f
        // Ignored outside dual-camera mode: with only one camera recording,
        // it always fills the screen regardless of a stray `swapped` value.
        val effectiveSwap = swapped && showFront

        Box(
            modifier = Modifier
                .fillMaxSize()
                .alpha(if (visible) 1f else 0f)
        ) {
            // Modifier.align(TopEnd) needs the BoxScope of *this* Box to
            // place the inset correctly, so it is built here rather than
            // hoisted above - a val declared outside this lambda would
            // resolve align() against the outer BoxWithConstraints instead.
            val pipModifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .size(width = pipWidth, height = pipHeight)
                .clip(RoundedCornerShape(12.dp))
                .border(2.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(12.dp))

            // Whichever camera is the small inset is emitted second, so it
            // draws on top of the one filling the screen - and is also
            // brought to front explicitly (zOrderOnTop), since these wrap
            // native TextureViews rather than plain Compose content.
            // Stealth landscape: the recording is only the centred 16:9 strip of
            // this frame, so the rest is dimmed - the viewfinder shows exactly
            // what is being recorded.
            val guide = backGeometry?.takeIf { it.stripGuide }
            if (guide != null && !effectiveSwap) {
                StripGuide(geometry = guide, viewWidth = viewWidth, viewHeight = viewHeight)
            }
            if (effectiveSwap) {
                CameraTexture(
                    geometry = frontGeometry,
                    onSurface = { surfaces.front = it; surfaces.publish() },
                    modifier = Modifier.fillMaxSize(),
                )
                CameraTexture(
                    geometry = backGeometry,
                    onSurface = { surfaces.back = it; surfaces.publish() },
                    zOrderOnTop = true,
                    modifier = pipModifier,
                )
            } else {
                CameraTexture(
                    geometry = backGeometry,
                    onSurface = { surfaces.back = it; surfaces.publish() },
                    modifier = Modifier.fillMaxSize(),
                )
                if (showFront) {
                    CameraTexture(
                        geometry = frontGeometry,
                        onSurface = { surfaces.front = it; surfaces.publish() },
                        zOrderOnTop = true,
                        modifier = pipModifier,
                    )
                }
            }
        }
    }
}

/**
 * Dims everything outside the centred 16:9 strip of the fitted, upright
 * camera frame - the part stealth landscape actually records - and rules the
 * strip's edges.
 */
@Composable
private fun StripGuide(geometry: PreviewGeometry, viewWidth: androidx.compose.ui.unit.Dp, viewHeight: androidx.compose.ui.unit.Dp) {
    val turned = geometry.rotationDegrees % 180 != 0
    val uprightWidth = (if (turned) geometry.bufferSize.height else geometry.bufferSize.width).toFloat()
    val uprightHeight = (if (turned) geometry.bufferSize.width else geometry.bufferSize.height).toFloat()
    val scale = min(viewWidth.value / uprightWidth, viewHeight.value / uprightHeight)
    val contentWidth = uprightWidth * scale
    val contentHeight = uprightHeight * scale
    val stripHeight = (contentWidth * 9f / 16f).coerceAtMost(contentHeight)
    val bar = ((contentHeight - stripHeight) / 2f).coerceAtLeast(0f)
    val left = (viewWidth.value - contentWidth) / 2f
    val top = (viewHeight.value - contentHeight) / 2f
    val dim = Color.Black.copy(alpha = 0.6f)

    Box(
        modifier = Modifier
            .padding(start = left.dp, top = top.dp)
            .size(width = contentWidth.dp, height = bar.dp)
            .background(dim)
    )
    Box(
        modifier = Modifier
            .padding(start = left.dp, top = (top + bar + stripHeight).dp)
            .size(width = contentWidth.dp, height = bar.dp)
            .background(dim)
    )
    Box(
        modifier = Modifier
            .padding(start = left.dp, top = (top + bar).dp)
            .size(width = contentWidth.dp, height = stripHeight.dp)
            .border(1.dp, Color.White.copy(alpha = 0.55f))
    )
}

/**
 * One camera's frames, turned upright and fitted to whatever space the layout
 * gives this view.
 */
@Composable
private fun CameraTexture(
    geometry: PreviewGeometry?,
    onSurface: (Surface?) -> Unit,
    modifier: Modifier = Modifier,
    zOrderOnTop: Boolean = false,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            TextureView(context).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(
                        texture: SurfaceTexture, width: Int, height: Int,
                    ) {
                        onSurface(Surface(texture))
                    }

                    override fun onSurfaceTextureSizeChanged(
                        texture: SurfaceTexture, width: Int, height: Int,
                    ) = Unit

                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                        onSurface(null)
                        // False: the camera session may still hold this
                        // texture, and letting the platform release it from
                        // under an active capture request kills the recording.
                        return false
                    }

                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                }
                // The transform depends on the view's own size, which is not
                // known yet the first time `update` runs on a freshly created
                // view - and on every recording after the first the geometry
                // is identical to the last one, so nothing re-triggers
                // `update` once layout does happen. Left alone, the view then
                // keeps TextureView's default of stretching the buffer to fit,
                // which squeezes a landscape sensor frame into a portrait view.
                addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                    applyPreviewTransform(v as TextureView)
                }
            }
        },
        update = { view ->
            if (zOrderOnTop) view.bringToFront()
            view.tag = geometry
            val size = geometry ?: return@AndroidView
            // Driven at a size the camera actually offers, rather than at
            // whatever the view happens to measure: an unsupported request is
            // quietly rounded to something else, and the mismatch is exactly
            // what the transform below would then have to absorb.
            view.surfaceTexture?.setDefaultBufferSize(
                size.bufferSize.width, size.bufferSize.height
            )
            applyPreviewTransform(view)
        },
    )
}

private fun applyPreviewTransform(view: TextureView) {
    val size = view.tag as? PreviewGeometry ?: return
    if (view.width <= 0 || view.height <= 0) return
    view.setTransform(
        previewTransform(
            viewWidth = view.width.toFloat(),
            viewHeight = view.height.toFloat(),
            bufferWidth = size.bufferSize.width.toFloat(),
            bufferHeight = size.bufferSize.height.toFloat(),
            sensorOrientation = size.sensorOrientation,
            rotationDegrees = size.rotationDegrees,
        )
    )
}

/**
 * Fits the camera's preview texture, turned to match the recording, inside
 * the view.
 *
 * The texture does not hold the sensor's raw frame. The platform composites a
 * camera preview already turned to suit a portrait display, so a sensor
 * mounted at a quarter turn - nearly every phone - delivers a texture that is
 * upright in a portrait view, with its width and height the other way round
 * from the buffer's. What is left for this transform is whatever turn the
 * *recording* has beyond that: none with the phone upright, a quarter turn
 * with it held on its side. Applying the sensor's mounting again, as this
 * once did, lays the picture on its side and stretches it.
 *
 * A [TextureView] draws its texture stretched to the view's bounds, so the
 * transform also has to undo that stretch. Three steps, in order:
 *
 *  1. Undo the stretch, by mapping the view rectangle onto a
 *     correctly-proportioned rectangle of the texture's own shape, centred.
 *  2. Rotate about the centre by the recording's extra turn.
 *  3. Scale uniformly so the result fits inside the view.
 *
 * Fitted, never filled: the whole frame is shown at its own proportions, and
 * where the frame's shape and the view's differ what remains is view rather
 * than something cropped out of the picture. That is the choice the iOS build
 * settled on after trying the alternatives, for the same reason - a viewfinder
 * that hides part of what is being recorded is worse than one with a black
 * margin.
 */
private fun previewTransform(
    viewWidth: Float,
    viewHeight: Float,
    bufferWidth: Float,
    bufferHeight: Float,
    sensorOrientation: Int,
    rotationDegrees: Int,
): Matrix {
    val matrix = Matrix()
    if (viewWidth <= 0f || viewHeight <= 0f || bufferWidth <= 0f || bufferHeight <= 0f) {
        return matrix
    }
    val centerX = viewWidth / 2f
    val centerY = viewHeight / 2f

    // The texture's shape once the platform has undone the sensor's mounting.
    val sensorTurned = sensorOrientation % 180 != 0
    val textureWidth = if (sensorTurned) bufferHeight else bufferWidth
    val textureHeight = if (sensorTurned) bufferWidth else bufferHeight

    // What the recording needs on top of that.
    val extraTurn = ((rotationDegrees - sensorOrientation) % 360 + 360) % 360
    val turned = extraTurn % 180 != 0
    val shownWidth = if (turned) textureHeight else textureWidth
    val shownHeight = if (turned) textureWidth else textureHeight

    // Step 1: cancel the stretch the TextureView applies by default.
    val viewRect = RectF(0f, 0f, viewWidth, viewHeight)
    val textureRect = RectF(0f, 0f, textureWidth, textureHeight).apply {
        offset(centerX - textureWidth / 2f, centerY - textureHeight / 2f)
    }
    matrix.setRectToRect(viewRect, textureRect, Matrix.ScaleToFit.FILL)

    // Steps 2 and 3: turned to match the recording, then fitted whole.
    val scale = min(viewWidth / shownWidth, viewHeight / shownHeight)
    matrix.postScale(scale, scale, centerX, centerY)
    matrix.postRotate(extraTurn.toFloat(), centerX, centerY)
    return matrix
}

private class PreviewSurfaces {
    var back: Surface? = null
    var front: Surface? = null

    fun publish() {
        CaptureViewModel.setPreviewSurfaces(back, front)
    }
}
