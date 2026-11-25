package com.witnessrecord.app.service

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Renders a crew edit as one video: each stretch of the timeline taken from
 * the angle chosen for it, with the sound of that angle. The iOS build's
 * `CrewEditExport`, on Media3's Transformer.
 *
 * Phones film in different orientations and sizes; every angle is fitted,
 * whole, into one 16:9 frame, black where it doesn't fill it, and where
 * nobody was filming the picture is black and the sound is silence.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object CrewEditExport {
    class Piece(
        /** Null for a stretch no camera covered. */
        val uri: Uri?,
        /** Where in that angle's video the stretch starts, in seconds. */
        val sourceStart: Double,
        val duration: Double,
    )

    class ExportFailed(message: String) : Exception(message)

    private const val WIDTH = 1280
    private const val HEIGHT = 720

    /** The pieces of an edit, from its timeline: each segment with where in its source it begins. */
    fun pieces(timeline: CrewTimeline, from: Double, to: Double, uriFor: (String) -> Uri?): List<Piece> =
        timeline.segments(from, to).map { segment ->
            val angle = segment.angleID?.let { id -> timeline.angles.firstOrNull { it.id == id } }
            val uri = angle?.let { uriFor(it.id) }
            if (angle == null || uri == null) Piece(null, 0.0, segment.end - segment.start)
            else Piece(uri, segment.start - (angle.startedAt - timeline.origin), segment.end - segment.start)
        }

    /** Writes the edit under the cache directory (which the share sheet can reach) and returns the file. */
    suspend fun export(context: Context, pieces: List<Piece>, title: String): File {
        val usable = pieces.filter { it.duration > 0 }
        if (usable.none { it.uri != null }) {
            throw ExportFailed(context.getString(com.witnessrecord.app.R.string.crew_export_no_footage))
        }
        val safe = MulticamExport.safeName(title)
        val directory = File(context.cacheDir, "crew-export").apply { mkdirs() }
        val destination = File(directory, "$safe – Witness edit.mp4").apply { delete() }

        val fit: List<Effect> = listOf(Presentation.createForWidthAndHeight(WIDTH, HEIGHT, Presentation.LAYOUT_SCALE_TO_FIT))
        val items = EditedMediaItemSequence.Builder()
        for (piece in usable) {
            val uri = piece.uri
            if (uri == null) {
                items.addGap((piece.duration * 1_000_000).toLong())
                continue
            }
            val start = (piece.sourceStart * 1000).toLong().coerceAtLeast(0)
            val clip = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(start)
                .setEndPositionMs(start + (piece.duration * 1000).toLong())
                .build()
            val media = MediaItem.Builder().setUri(uri).setClippingConfiguration(clip).build()
            items.addItem(
                EditedMediaItem.Builder(media)
                    .setEffects(Effects(emptyList(), fit))
                    .build()
            )
        }
        val composition = Composition.Builder(items.build())
            // The picture of a gap, and the frame every angle is fitted into.
            .setEffects(Effects(emptyList(), fit))
            // So a stretch with no sound, or an angle filmed without a microphone, is silence rather than a hole.
            .experimentalSetForceAudioTrack(true)
            .build()

        // Transformer wants a thread with a looper: the main one.
        return suspendCancellableCoroutine { continuation ->
            Handler(Looper.getMainLooper()).post {
                val transformer = Transformer.Builder(context)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (continuation.isActive) continuation.resume(destination)
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            EventLogger.log("crew_edit.export_failed", detail = exportException.message)
                            if (continuation.isActive) continuation.resumeWithException(exportException)
                        }
                    })
                    .build()
                continuation.invokeOnCancellation { Handler(Looper.getMainLooper()).post { transformer.cancel() } }
                transformer.start(composition, destination.absolutePath)
            }
        }
    }
}
