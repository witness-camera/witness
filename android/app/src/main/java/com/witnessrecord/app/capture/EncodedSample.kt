package com.witnessrecord.app.capture

import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteBuffer

/**
 * How an encoder hands finished data to whatever is muxing it. Samples are
 * delivered on the encoder's own drain thread and are only valid for the
 * duration of the call - the receiver must write them out, not retain them.
 */
interface EncoderSink {
    fun onFormat(format: MediaFormat)
    fun onSample(buffer: ByteBuffer, info: MediaCodec.BufferInfo)
}
