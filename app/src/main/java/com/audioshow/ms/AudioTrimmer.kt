package com.audioshow.ms

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.nio.ByteBuffer

object AudioTrimmer {

    /**
     * Trim [input] from [startMs] to [endMs] and save as AAC/M4A into
     * public Downloads/AudioShow (visible in Files app).
     * Returns path or content URI string on success.
     */
    fun trimAndSave(
        context: Context,
        input: File,
        startMs: Long,
        endMs: Long,
        outName: String
    ): Result<String> {
        return try {
            val extractor = MediaExtractor()
            extractor.setDataSource(input.absolutePath)

            var audioTrack = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrack = i
                    format = f
                    break
                }
            }
            if (audioTrack < 0 || format == null) {
                extractor.release()
                return Result.failure(Exception("Аудиодорожка не найдена"))
            }

            extractor.selectTrack(audioTrack)
            extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val outDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "AudioShow"
            )
            if (!outDir.exists()) outDir.mkdirs()

            val safeName = outName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val outFile = File(outDir, if (safeName.endsWith(".m4a")) safeName else "$safeName.m4a")

            val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val trackIndex = muxer.addTrack(format)
            muxer.start()

            val maxSize = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(256 * 1024)
            val buffer = ByteBuffer.allocate(maxSize)
            val info = MediaCodec.BufferInfo()
            val endUs = endMs * 1000

            while (true) {
                info.offset = 0
                info.size = extractor.readSampleData(buffer, 0)
                if (info.size < 0) break
                val sampleTime = extractor.sampleTime
                if (sampleTime > endUs) break
                if (sampleTime >= startMs * 1000) {
                    info.presentationTimeUs = sampleTime - startMs * 1000
                    info.flags = extractor.sampleFlags
                    muxer.writeSampleData(trackIndex, buffer, info)
                }
                extractor.advance()
            }

            muxer.stop()
            muxer.release()
            extractor.release()

            // Register in MediaStore so it appears in Downloads
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, outFile.name)
                    put(MediaStore.Downloads.MIME_TYPE, "audio/mp4")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/AudioShow")
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                try {
                    context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                } catch (_: Exception) {
                }
            }

            Result.success(outFile.absolutePath)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
