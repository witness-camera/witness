package com.witnessrecord.app.ui

import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.witnessrecord.app.R

/**
 * Loops the bundled Pro promo, muted and without controls - a short look at
 * what Pro adds, shown in the product's own screens. There is no audio track
 * in the file at all, so it can never interrupt whatever the person is
 * listening to, and the player never asks for audio focus.
 *
 * Held on its opening frame instead of looping when the system's animations
 * are switched off, since it is decoration, not information the page depends
 * on. A [TextureView] rather than a `VideoView`, so the rounded corners clip
 * the picture as well as the box around it.
 */
@Composable
fun PromoVideo(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val animationsOff = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }

    AndroidView(
        modifier = modifier
            .aspectRatio(4f / 3f)
            .clip(RoundedCornerShape(14.dp)),
        factory = { viewContext ->
            TextureView(viewContext).apply {
                var player: MediaPlayer? = null
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                        player = runCatching {
                            val descriptor = viewContext.resources.openRawResourceFd(R.raw.pro_promo)
                            MediaPlayer().apply {
                                setAudioAttributes(
                                    AudioAttributes.Builder()
                                        .setUsage(AudioAttributes.USAGE_MEDIA)
                                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                                        .build()
                                )
                                setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
                                descriptor.close()
                                setSurface(Surface(texture))
                                setVolume(0f, 0f)
                                isLooping = !animationsOff
                                setOnPreparedListener { prepared ->
                                    prepared.start()
                                    // One frame is enough to show something; let it
                                    // render, then hold.
                                    if (animationsOff) {
                                        Handler(Looper.getMainLooper()).postDelayed({
                                            runCatching { prepared.pause() }
                                        }, 200)
                                    }
                                }
                                prepareAsync()
                            }
                        }.getOrNull()
                    }

                    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                        runCatching { player?.release() }
                        player = null
                        return true
                    }

                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                }
            }
        },
    )
}
