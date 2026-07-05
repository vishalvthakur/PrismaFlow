package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class VideoProcessorWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "VideoProcessorWorker"
        const val KEY_INPUT_URI = "input_uri"
        const val KEY_OUTPUT_PATH = "output_path"
        const val KEY_PROGRESS = "progress"
        const val KEY_STATUS = "status"
        const val KEY_ERROR = "error"
        const val KEY_STYLE_MODE = "style_mode"
    }

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null

    override suspend fun doWork(): Result {
        val inputUriStr = inputData.getString(KEY_INPUT_URI) ?: return Result.failure(
            workDataOf(KEY_ERROR to "Input Uri is missing")
        )
        val styleMode = inputData.getString(KEY_STYLE_MODE) ?: "cartoon"

        val inputUri = Uri.parse(inputUriStr)
        val cacheDir = context.cacheDir
        val outputFile = File(cacheDir, "cartoonized_${System.currentTimeMillis()}.mp4")
        val outputPath = outputFile.absolutePath

        setProgress(workDataOf(KEY_PROGRESS to 0, KEY_STATUS to "Initializing AI Engine..."))

        // Step 1: Initialize local TF Lite Model
        var useLocalFallback = true
        try {
            val modelBuffer = loadModelFile(context, "cartoonizer.tflite")
            val options = Interpreter.Options()
            
            // Try enabling GPU Delegate
            try {
                gpuDelegate = GpuDelegate()
                options.addDelegate(gpuDelegate)
                Log.i(TAG, "TensorFlow Lite GPU delegate initialized successfully.")
            } catch (e: Exception) {
                Log.w(TAG, "GPU delegate failed to initialize. Falling back to NNAPI or CPU.", e)
                try {
                    options.setUseNNAPI(true)
                    Log.i(TAG, "NNAPI delegate fallback enabled.")
                } catch (nnapiEx: Exception) {
                    Log.w(TAG, "NNAPI delegate failed to initialize. Running on standard CPU.", nnapiEx)
                }
            }
            
            interpreter = Interpreter(modelBuffer, options)
            // If the model loads successfully but is just a text placeholder, checking inputs will fail,
            // which is why we wrap model running in a robust try-catch and set useLocalFallback.
            val inputTensorCount = interpreter?.inputTensorCount ?: 0
            if (inputTensorCount > 0) {
                useLocalFallback = false
                Log.i(TAG, "TFLite interpreter loaded successfully. Model will be used for styles.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to load TFLite model. Falling back to high-performance local stylizer.", e)
            useLocalFallback = true
        }

        // Step 2: Extract Frame Metadata and Set Up Processing Loop
        val retriever = MediaMetadataRetriever()
        var durationMs = 0L
        var originalWidth = 512
        var originalHeight = 512
        var rotation = 0

        try {
            retriever.setDataSource(context, inputUri)
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
            originalWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 512
            originalHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 512
            rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read video metadata", e)
            retriever.release()
            return Result.failure(workDataOf(KEY_ERROR to "Failed to read video metadata: ${e.message}"))
        }

        // We process frames at a target square size (e.g., 480x480 or 512x512) for speed and TFLite compatibility.
        // Let's use 480x480 as it's highly optimized, fits nicely, and is a multiple of 16 (crucial for video encoders).
        val processWidth = 480
        val processHeight = 480
        val fps = 30
        val totalFrames = ((durationMs * fps) / 1000).coerceAtLeast(1L)
        val frameTimeUs = 1000000L / fps

        Log.i(TAG, "Video details: duration=$durationMs ms, size=${originalWidth}x${originalHeight}, rotation=$rotation. Processing $totalFrames frames at ${processWidth}x${processHeight}...")

        // Step 3: Setup MediaCodec Video Encoder & MediaMuxer
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var videoTrackIndex = -1
        var audioTrackIndex = -1
        var muxerStarted = false
        var sourceAudioTrackIndex = -1
        val audioExtractor = MediaExtractor()

        try {
            // Find Audio track in original video
            audioExtractor.setDataSource(context, inputUri, null)
            for (i in 0 until audioExtractor.trackCount) {
                val format = audioExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    sourceAudioTrackIndex = i
                    Log.i(TAG, "Audio track found in source video: $mime")
                    break
                }
            }

            // Create output Muxer
            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            if (rotation != 0) {
                muxer.setOrientationHint(rotation)
            }

            // Setup Video Encoder format
            val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, processWidth, processHeight)
            videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) // NV12
            videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, 2500000) // 2.5 Mbps
            videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            // Pre-allocate input/output data for model
            val inputBuffer = ByteBuffer.allocateDirect(1 * processWidth * processHeight * 3 * 4).apply {
                order(ByteOrder.nativeOrder())
            }
            val outputBuffer = ByteBuffer.allocateDirect(1 * processWidth * processHeight * 3 * 4).apply {
                order(ByteOrder.nativeOrder())
            }

            // Keep track of pixel arrays to minimize heap allocations inside the loop
            val argbArray = IntArray(processWidth * processHeight)
            val nv12Data = ByteArray(processWidth * processHeight * 3 / 2)

            // Step 4: Core Frame Processing Loop
            for (frameIndex in 0 until totalFrames) {
                if (isStopped) {
                    Log.i(TAG, "Worker stopped by user request.")
                    break
                }

                val currentProgress = ((frameIndex * 100) / totalFrames).toInt()
                setProgress(workDataOf(
                    KEY_PROGRESS to currentProgress,
                    KEY_STATUS to "Processing frame ${frameIndex + 1} of $totalFrames (${currentProgress}%)"
                ))

                val timeUs = frameIndex * frameTimeUs
                val rawBitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                if (rawBitmap == null) {
                    Log.w(TAG, "Could not extract frame at $timeUs us. Skipping.")
                    continue
                }

                // Resize to process resolution
                val resizedBitmap = Bitmap.createScaledBitmap(rawBitmap, processWidth, processHeight, true)
                if (rawBitmap != resizedBitmap) {
                    rawBitmap.recycle()
                }

                var styledBitmap: Bitmap
                if (styleMode == "anime") {
                    styledBitmap = animeizeBitmap(resizedBitmap)
                    resizedBitmap.recycle()
                } else if (!useLocalFallback && interpreter != null) {
                    // Real TFLite inference run
                    try {
                        // Pre-process: Bitmap to normalized float ByteBuffer
                        inputBuffer.rewind()
                        resizedBitmap.getPixels(argbArray, 0, processWidth, 0, 0, processWidth, processHeight)
                        for (pixel in argbArray) {
                            val r = (pixel shr 16) and 0xFF
                            val g = (pixel shr 8) and 0xFF
                            val b = pixel and 0xFF
                            // Scale to model's expected range (e.g., [-1.0f, 1.0f] or [0.0f, 1.0f])
                            inputBuffer.putFloat((r / 127.5f) - 1.0f)
                            inputBuffer.putFloat((g / 127.5f) - 1.0f)
                            inputBuffer.putFloat((b / 127.5f) - 1.0f)
                        }

                        outputBuffer.rewind()
                        interpreter!!.run(inputBuffer, outputBuffer)

                        // Post-process: float outputs back to stylized Bitmap
                        outputBuffer.rewind()
                        val resultPixels = IntArray(processWidth * processHeight)
                        for (i in resultPixels.indices) {
                            // Scale from [-1, 1] back to [0, 255]
                            val r = (((outputBuffer.floatValue) + 1.0f) * 127.5f).toInt().coerceIn(0, 255)
                            val g = (((outputBuffer.floatValue) + 1.0f) * 127.5f).toInt().coerceIn(0, 255)
                            val b = (((outputBuffer.floatValue) + 1.0f) * 127.5f).toInt().coerceIn(0, 255)
                            resultPixels[i] = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
                        }
                        styledBitmap = Bitmap.createBitmap(resultPixels, processWidth, processHeight, Bitmap.Config.ARGB_8888)
                        resizedBitmap.recycle()
                    } catch (e: Exception) {
                        Log.e(TAG, "TFLite inference failed on frame $frameIndex. Falling back to local styling.", e)
                        // Fallback to local custom cartoonizer
                        styledBitmap = cartoonizeBitmap(resizedBitmap)
                        resizedBitmap.recycle()
                    }
                } else {
                    // Fallback to super-robust local custom cartoon filter
                    styledBitmap = cartoonizeBitmap(resizedBitmap)
                    resizedBitmap.recycle()
                }

                // Step 5: Encode the Styled Bitmap into MediaCodec
                styledBitmap.getPixels(argbArray, 0, processWidth, 0, 0, processWidth, processHeight)
                styledBitmap.recycle()

                // Convert RGB to YUV420SemiPlanar (NV12)
                encodeYUV420SP(nv12Data, argbArray, processWidth, processHeight)

                // Feed NV12 data into MediaCodec input buffer
                var inputQueued = false
                while (!inputQueued && !isStopped) {
                    val inputBufferIndex = encoder.dequeueInputBuffer(10000)
                    if (inputBufferIndex >= 0) {
                        val codecInputBuffer = encoder.getInputBuffer(inputBufferIndex)!!
                        codecInputBuffer.clear()
                        codecInputBuffer.put(nv12Data)
                        encoder.queueInputBuffer(
                            inputBufferIndex,
                            0,
                            nv12Data.size,
                            timeUs,
                            0
                        )
                        inputQueued = true
                    }
                    drainEncoder(encoder, muxer, muxerStarted, audioExtractor, sourceAudioTrackIndex) { index, started ->
                        videoTrackIndex = index
                        muxerStarted = started
                    }
                }
            }

            // Signal End of Stream to Video Encoder
            if (!isStopped) {
                var eosQueued = false
                while (!eosQueued) {
                    val inputBufferIndex = encoder.dequeueInputBuffer(10000)
                    if (inputBufferIndex >= 0) {
                        encoder.queueInputBuffer(
                            inputBufferIndex,
                            0,
                            0,
                            0L,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        eosQueued = true
                    }
                    drainEncoder(encoder, muxer, muxerStarted, audioExtractor, sourceAudioTrackIndex) { index, started ->
                        videoTrackIndex = index
                        muxerStarted = started
                    }
                }
            }

            // Flush remaining encoder output buffers
            drainEncoder(encoder, muxer, muxerStarted, audioExtractor, sourceAudioTrackIndex, forceEnd = true) { index, started ->
                videoTrackIndex = index
                muxerStarted = started
            }

            // Step 6: Copy Original Audio Track to Output Muxer
            if (!isStopped && muxerStarted && sourceAudioTrackIndex >= 0) {
                setProgress(workDataOf(KEY_PROGRESS to 95, KEY_STATUS to "Syncing audio track..."))
                try {
                    audioExtractor.selectTrack(sourceAudioTrackIndex)
                    val audioFormat = audioExtractor.getTrackFormat(sourceAudioTrackIndex)
                    // Add audio track and write it
                    val maxBufferSize = if (audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                    } else {
                        1024 * 256
                    }
                    val audioBuffer = ByteBuffer.allocateDirect(maxBufferSize)
                    val audioInfo = MediaCodec.BufferInfo()

                    while (true) {
                        audioInfo.offset = 0
                        audioInfo.size = audioExtractor.readSampleData(audioBuffer, 0)
                        if (audioInfo.size < 0) {
                            Log.i(TAG, "Audio copying finished.")
                            break
                        }
                        audioInfo.presentationTimeUs = audioExtractor.sampleTime
                        audioInfo.flags = audioExtractor.sampleFlags
                        
                        if (audioTrackIndex >= 0) {
                            muxer.writeSampleData(audioTrackIndex, audioBuffer, audioInfo)
                        }
                        audioExtractor.advance()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error copying audio track, saving video only", e)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error inside processing pipeline", e)
            return Result.failure(workDataOf(KEY_ERROR to "Pipeline error: ${e.message}"))
        } finally {
            // Clean resources
            try {
                encoder?.stop()
                encoder?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing encoder", e)
            }

            try {
                if (muxerStarted) {
                    muxer?.stop()
                }
                muxer?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing muxer", e)
            }

            try {
                retriever.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing retriever", e)
            }

            try {
                audioExtractor.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing audioExtractor", e)
            }

            try {
                interpreter?.close()
                gpuDelegate?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing TFLite interpreter", e)
            }
        }

        if (isStopped) {
            if (outputFile.exists()) {
                outputFile.delete()
            }
            return Result.failure(workDataOf(KEY_ERROR to "Work was cancelled by user"))
        }

        setProgress(workDataOf(KEY_PROGRESS to 100, KEY_STATUS to "Cartoonization complete!"))
        Log.i(TAG, "Finished successfully. Output file saved at: $outputPath")
        return Result.success(workDataOf(KEY_OUTPUT_PATH to outputPath))
    }

    private fun loadModelFile(context: Context, modelPath: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelPath)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    // Custom pixel-shader cartoonizer. Applies outline edges and color-quantized smooth fills.
    private fun cartoonizeBitmap(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        val outPixels = IntArray(width * height)

        // Pixel loop with fast bilateral color quantization & Sobel edge outlining
        for (y in 1 until height - 1) {
            val offset = y * width
            for (x in 1 until width - 1) {
                val idx = offset + x

                val c = pixels[idx]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF

                // Check gradients with neighbors for Sobel edges
                val left = pixels[idx - 1]
                val right = pixels[idx + 1]
                val top = pixels[idx - width]
                val bottom = pixels[idx + width]

                val lrDiff = Math.abs(((left shr 16) and 0xFF) - ((right shr 16) and 0xFF)) +
                        Math.abs(((left shr 8) and 0xFF) - ((right shr 8) and 0xFF)) +
                        Math.abs((left and 0xFF) - (right and 0xFF))

                val tbDiff = Math.abs(((top shr 16) and 0xFF) - ((bottom shr 16) and 0xFF)) +
                        Math.abs(((top shr 8) and 0xFF) - ((bottom shr 8) and 0xFF)) +
                        Math.abs((top and 0xFF) - (bottom and 0xFF))

                // Threshold to detect edges
                val edge = (lrDiff + tbDiff) > 130

                if (edge) {
                    // Dark ink outline
                    outPixels[idx] = 0xFF121212.toInt()
                } else {
                    // Color quantization - cluster colors to look painted (8 levels)
                    val qr = ((r + 15) / 32) * 32
                    val qg = ((g + 15) / 32) * 32
                    val qb = ((b + 15) / 32) * 32

                    val finalR = qr.coerceIn(0, 255)
                    val finalG = qg.coerceIn(0, 255)
                    val finalB = qb.coerceIn(0, 255)

                    outPixels[idx] = 0xFF000000.toInt() or (finalR shl 16) or (finalG shl 8) or finalB
                }
            }
        }

        // Fill borders gracefully
        for (x in 0 until width) {
            outPixels[x] = pixels[x]
            outPixels[(height - 1) * width + x] = pixels[(height - 1) * width + x]
        }
        for (y in 0 until height) {
            outPixels[y * width] = pixels[y * width]
            outPixels[y * width + (width - 1)] = pixels[y * width + (width - 1)]
        }

        return Bitmap.createBitmap(outPixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun animeizeBitmap(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        val outPixels = IntArray(width * height)

        // Pixel loop with fast anime-style saturation boost, thinner Sobel edges, and bloom/glow highlights
        for (y in 1 until height - 1) {
            val offset = y * width
            for (x in 1 until width - 1) {
                val idx = offset + x

                val c = pixels[idx]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF

                // Sobel edge difference (thinner, more subtle outline for anime)
                val left = pixels[idx - 1]
                val right = pixels[idx + 1]
                val top = pixels[idx - width]
                val bottom = pixels[idx + width]

                val lrDiff = Math.abs(((left shr 16) and 0xFF) - ((right shr 16) and 0xFF)) +
                        Math.abs(((left shr 8) and 0xFF) - ((right shr 8) and 0xFF)) +
                        Math.abs((left and 0xFF) - (right and 0xFF))

                val tbDiff = Math.abs(((top shr 16) and 0xFF) - ((bottom shr 16) and 0xFF)) +
                        Math.abs(((top shr 8) and 0xFF) - ((bottom shr 8) and 0xFF)) +
                        Math.abs((top and 0xFF) - (bottom and 0xFF))

                // Threshold to detect edges (higher threshold = thinner, more delicate lines)
                val edge = (lrDiff + tbDiff) > 180

                if (edge) {
                    // Delicate indigo/dark slate outline (common in anime rather than solid black)
                    outPixels[idx] = 0xFF1A1A2E.toInt()
                } else {
                    // Anime style: Boost saturation and apply vibrant palette mapping
                    val hsv = FloatArray(3)
                    android.graphics.Color.RGBToHSV(r, g, b, hsv)
                    
                    // Boost saturation slightly for vibrant anime look
                    hsv[1] = (hsv[1] * 1.35f).coerceAtMost(1.0f)
                    // Increase brightness for beautiful lit scenes
                    hsv[2] = (hsv[2] * 1.1f).coerceAtMost(1.0f)

                    val animeColor = android.graphics.Color.HSVToColor(hsv)
                    val ar = (animeColor shr 16) and 0xFF
                    val ag = (animeColor shr 8) and 0xFF
                    val ab = animeColor and 0xFF

                    // Soft cell-shading quantization (fewer levels but smoother gradients than cartoon)
                    val qr = ((ar + 20) / 40) * 40
                    val qg = ((ag + 20) / 40) * 40
                    val qb = ((ab + 20) / 40) * 40

                    // Bloom/glow highlight filter: if pixel is very bright, enhance it
                    var finalR = qr.coerceIn(0, 255)
                    var finalG = qg.coerceIn(0, 255)
                    var finalB = qb.coerceIn(0, 255)

                    val brightness = (finalR * 0.299 + finalG * 0.587 + finalB * 0.114)
                    if (brightness > 200) {
                        // Apply soft golden/pinkish celestial bloom highlight
                        finalR = (finalR * 1.15).toInt().coerceAtMost(255)
                        finalG = (finalG * 1.1).toInt().coerceAtMost(255)
                        finalB = (finalB * 1.25).toInt().coerceAtMost(255) // extra boost to blue for anime magic sky/reflection glow
                    }

                    outPixels[idx] = 0xFF000000.toInt() or (finalR shl 16) or (finalG shl 8) or finalB
                }
            }
        }

        // Fill borders gracefully
        for (x in 0 until width) {
            outPixels[x] = pixels[x]
            outPixels[(height - 1) * width + x] = pixels[(height - 1) * width + x]
        }
        for (y in 0 until height) {
            outPixels[y * width] = pixels[y * width]
            outPixels[y * width + (width - 1)] = pixels[y * width + (width - 1)]
        }

        return Bitmap.createBitmap(outPixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun drainEncoder(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        muxerStarted: Boolean,
        audioExtractor: MediaExtractor,
        sourceAudioTrackIndex: Int,
        forceEnd: Boolean = false,
        onMuxerConfigured: (Int, Boolean) -> Unit
    ) {
        var localMuxerStarted = muxerStarted
        var localVideoTrackIndex = -1
        val bufferInfo = MediaCodec.BufferInfo()

        while (true) {
            val outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000)
            if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!forceEnd) break
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (localMuxerStarted) {
                    throw RuntimeException("Format changed after muxer already started")
                }
                val newFormat = encoder.outputFormat
                localVideoTrackIndex = muxer.addTrack(newFormat)
                
                // Also add Audio track if present in source video
                var localAudioTrackIndex = -1
                if (sourceAudioTrackIndex >= 0) {
                    try {
                        val audioFormat = audioExtractor.getTrackFormat(sourceAudioTrackIndex)
                        // Add to output muxer
                        localAudioTrackIndex = muxer.addTrack(audioFormat)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error adding audio track to format", e)
                    }
                }
                
                muxer.start()
                localMuxerStarted = true
                onMuxerConfigured(localVideoTrackIndex, localMuxerStarted)
            } else if (outputBufferIndex >= 0) {
                val encodedData = encoder.getOutputBuffer(outputBufferIndex)!!
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    bufferInfo.size = 0
                }

                if (bufferInfo.size > 0 && localMuxerStarted) {
                    encodedData.position(bufferInfo.offset)
                    encodedData.limit(bufferInfo.offset + bufferInfo.size)
                    muxer.writeSampleData(localVideoTrackIndex.coerceAtLeast(0), encodedData, bufferInfo)
                }

                encoder.releaseOutputBuffer(outputBufferIndex, false)

                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    break
                }
            }
        }
    }

    // Standard high-performance converter from ARGB to NV12 (YUV420SemiPlanar)
    private fun encodeYUV420SP(yuv420sp: ByteArray, argb: IntArray, width: Int, height: Int) {
        val frameSize = width * height
        var yIndex = 0
        var uvIndex = frameSize

        var index = 0
        for (j in 0 until height) {
            for (i in 0 until width) {
                val c = argb[index]
                val r = (c shr 16) and 0xff
                val g = (c shr 8) and 0xff
                val b = c and 0xff

                // RGB to YUV conversion formula
                var y = (66 * r + 129 * g + 25 * b + 128 shr 8) + 16
                var u = (-38 * r - 74 * g + 112 * b + 128 shr 8) + 128
                var v = (112 * r - 94 * g - 18 * b + 128 shr 8) + 128

                y = y.coerceIn(16, 235)
                u = u.coerceIn(16, 240)
                v = v.coerceIn(16, 240)

                yuv420sp[yIndex++] = y.toByte()
                if (j % 2 == 0 && index % 2 == 0) {
                    yuv420sp[uvIndex++] = u.toByte() // U
                    yuv420sp[uvIndex++] = v.toByte() // V
                }
                index++
            }
        }
    }

    private val ByteBuffer.floatValue: Float
        get() = this.float
}
