package app.streamforge.stream

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecList
import android.os.Build
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer

/**
 * Sequential MediaCodec video decoder for scene overlays.
 *
 * Unlike MediaMetadataRetriever.getFrameAtTime(), this keeps the codec and
 * extractor running between frames. Android selects the platform decoder
 * (hardware decoder where the device exposes one for the source format).
 */
internal class HardwareVideoFrameDecoder(
    context: Context,
    uri: Uri
) : AutoCloseable {
    private val extractor = MediaExtractor()
    private val decoder: MediaCodec
    private val trackIndex: Int
    val durationMs: Long
    val frameRate: Int
    private var inputEnded = false
    private var outputEnded = false
    private var closed = false

    init {
        extractor.setDataSource(context, uri, null)
        trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        } ?: throw IllegalArgumentException("No video track")
        val format = extractor.getTrackFormat(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: throw IllegalArgumentException("Missing video MIME")
        durationMs = (if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000L else 0L)
            .coerceAtLeast(1L)
        frameRate = (if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) format.getInteger(MediaFormat.KEY_FRAME_RATE) else 30)
            .coerceIn(5, 60)
        extractor.selectTrack(trackIndex)
        // Request flexible YUV output so frames can be composited into the
        // existing scene bitmap without a CPU seek/decode for every timestamp.
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.COLOR_FORMAT_YUV420_FLEXIBLE)
        // We convert decoded frames to Bitmaps via Image planes, so only select
        // codecs that explicitly advertise flexible YUV output. Picking the
        // first hardware decoder by MIME alone can select a surface-only codec:
        // it may start successfully but never provide advancing output Images.
        val compatibleDecoders = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .filter { info ->
                if (info.isEncoder || info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) {
                    false
                } else {
                    runCatching {
                        info.getCapabilitiesForType(mime).colorFormats
                            .contains(MediaCodecInfo.COLOR_FORMAT_YUV420_FLEXIBLE)
                    }.getOrDefault(false)
                }
            }
        val selectedDecoder = compatibleDecoders.firstOrNull { info ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.isHardwareAccelerated
            else !info.name.startsWith("OMX.google.", ignoreCase = true) &&
                !info.name.startsWith("c2.android.", ignoreCase = true)
        } ?: compatibleDecoders.firstOrNull()
            ?: throw IllegalStateException("No decoder supports flexible YUV output for this video")
        decoder = MediaCodec.createByCodecName(selectedDecoder.name)
        decoder.configure(format, null, null, 0)
        decoder.start()
    }

    @Synchronized
    fun nextFrame(loop: Boolean): Bitmap? {
        if (closed || outputEnded) return null
        val info = MediaCodec.BufferInfo()
        repeat(80) {
            if (!inputEnded) {
                val inputIndex = decoder.dequeueInputBuffer(5_000)
                if (inputIndex >= 0) {
                    val input = decoder.getInputBuffer(inputIndex) ?: return@repeat
                    input.clear()
                    val sampleSize = extractor.readSampleData(input, 0)
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEnded = true
                    } else {
                        decoder.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime.coerceAtLeast(0L), 0)
                        extractor.advance()
                    }
                }
            }

            val outputIndex = decoder.dequeueOutputBuffer(info, 10_000)
            if (outputIndex >= 0) {
                var bitmap: Bitmap? = null
                if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0) {
                    runCatching { decoder.getOutputImage(outputIndex)?.use { bitmap = imageToBitmap(it) } }
                }
                decoder.releaseOutputBuffer(outputIndex, false)
                if (bitmap != null) return bitmap
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    if (!loop) {
                        outputEnded = true
                        return null
                    }
                    restartAtBeginning()
                }
            } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ||
                outputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED ||
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (inputEnded && outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // Allow the codec another short drain cycle on the next pass.
                }
            }
        }
        return null
    }

    private fun restartAtBeginning() {
        runCatching { decoder.flush() }
        extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        inputEnded = false
        outputEnded = false
    }

    private fun imageToBitmap(image: android.media.Image): Bitmap {
        val width = image.width
        val height = image.height
        val planes = image.planes
        require(planes.size >= 3) { "Decoder did not return YUV image" }
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val yRow = yPlane.rowStride
        val uRow = uPlane.rowStride
        val vRow = vPlane.rowStride
        val uPixel = uPlane.pixelStride
        val vPixel = vPlane.pixelStride
        val pixels = IntArray(width * height)
        var out = 0
        for (row in 0 until height) {
            val yRowOffset = row * yRow
            val uvRowU = (row / 2) * uRow
            val uvRowV = (row / 2) * vRow
            for (col in 0 until width) {
                val y = (yBuffer.get(yRowOffset + col).toInt() and 0xff) - 16
                val uvCol = col / 2
                val u = (uBuffer.get(uvRowU + uvCol * uPixel).toInt() and 0xff) - 128
                val v = (vBuffer.get(uvRowV + uvCol * vPixel).toInt() and 0xff) - 128
                val yy = (y.coerceAtLeast(0) * 1192)
                val r = ((yy + 1634 * v) shr 10).coerceIn(0, 255)
                val g = ((yy - 833 * v - 400 * u) shr 10).coerceIn(0, 255)
                val b = ((yy + 2066 * u) shr 10).coerceIn(0, 255)
                pixels[out++] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { decoder.stop() }
        runCatching { decoder.release() }
        runCatching { extractor.release() }
    }

    private object MediaCodecInfo {
        const val COLOR_FORMAT_YUV420_FLEXIBLE = 0x7F420888
    }
}
