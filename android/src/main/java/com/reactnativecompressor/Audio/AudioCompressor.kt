package com.reactnativecompressor.Audio

import android.annotation.SuppressLint
import android.media.*
import android.os.Build
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableMap
import com.reactnativecompressor.Utils.MediaCache
import com.reactnativecompressor.Utils.Utils
import com.reactnativecompressor.Utils.Utils.addLog
import java.io.File
import java.io.IOException

class AudioCompressor {
  companion object {
    val TAG = "AudioMain"
    private const val TIMEOUT_USEC = 10000L
    private const val DEFAULT_BITRATE = 128000 // 128 kbps
    private const val MIN_BITRATE = 32000 // 32 kbps
    private const val MAX_BITRATE = 320000 // 320 kbps

    @JvmStatic
    fun CompressAudio(
      fileUrl: String,
      optionMap: ReadableMap,
      context: ReactApplicationContext,
      promise: Promise,
    ) {
      addLog("Starting audio compression for: $fileUrl")
      
      // Input validation
      if (fileUrl.isBlank()) {
        addLog("Error: Empty file URL provided")
        promise.reject("INVALID_INPUT", "File URL cannot be empty")
        return
      }

      val realPath = Utils.getRealPath(fileUrl, context)
      if (realPath == null) {
        addLog("Error: Could not resolve real path for: $fileUrl")
        promise.reject("INVALID_PATH", "Could not resolve file path")
        return
      }

      val filePathWithoutFileUri = realPath.replace("file://", "")
      val inputFile = File(filePathWithoutFileUri)
      
      if (!inputFile.exists()) {
        addLog("Error: Input file does not exist: $filePathWithoutFileUri")
        promise.reject("FILE_NOT_FOUND", "Input file does not exist")
        return
      }

      if (!inputFile.canRead()) {
        addLog("Error: Cannot read input file: $filePathWithoutFileUri")
        promise.reject("FILE_NOT_READABLE", "Cannot read input file")
        return
      }

      try {
        var inputPath = filePathWithoutFileUri
        var tempFiles = mutableListOf<String>()
        
        // Handle MP4 files by extracting audio first
        if (fileUrl.endsWith(".mp4", ignoreCase = true)) {
          addLog("MP4 file detected, extracting audio track")
          val mp3Path = Utils.generateCacheFilePath("mp3", context)
          
          try {
            AudioExtractor().genVideoUsingMuxer(fileUrl, mp3Path, -1, -1, true, false)
            inputPath = mp3Path
            tempFiles.add(mp3Path)
            addLog("Audio extraction successful: $mp3Path")
          } catch (e: Exception) {
            addLog("Error extracting audio from MP4: ${e.localizedMessage}")
            promise.reject("EXTRACTION_FAILED", "Failed to extract audio from MP4: ${e.localizedMessage}")
            return
          }
        }

        compressWithMediaCodec(
          inputPath = inputPath,
          originalPath = filePathWithoutFileUri,
          optionMap = optionMap,
          context = context,
          tempFiles = tempFiles
        ) { outputPath, success, errorMessage ->
          // Cleanup temporary files
          tempFiles.forEach { tempFile ->
            try {
              File(tempFile).delete()
              addLog("Cleaned up temp file: $tempFile")
            } catch (e: Exception) {
              addLog("Warning: Could not delete temp file $tempFile: ${e.localizedMessage}")
            }
          }
          
          if (success && outputPath != null) {
            val returnableFilePath = "file://$outputPath"
            addLog("Audio compression completed successfully: $returnableFilePath")
            MediaCache.removeCompletedImagePath(fileUrl)
            promise.resolve(returnableFilePath)
          } else {
            addLog("Audio compression failed: $errorMessage")
            promise.reject("COMPRESSION_FAILED", errorMessage ?: "Audio compression failed")
          }
        }
      } catch (e: Exception) {
        addLog("Unexpected error in CompressAudio: ${e.localizedMessage}")
        e.printStackTrace()
        promise.reject("UNEXPECTED_ERROR", "Unexpected error: ${e.localizedMessage}")
      }
    }

    @SuppressLint("WrongConstant")
    private fun compressWithMediaCodec(
      inputPath: String,
      originalPath: String,
      optionMap: ReadableMap,
      context: ReactApplicationContext,
      @Suppress("UNUSED_PARAMETER") tempFiles: MutableList<String>,
      completeCallback: (String?, Boolean, String?) -> Unit
    ) {
      addLog("Starting MediaCodec compression")
      
      try {
        val options = AudioHelper.fromMap(optionMap)
        val outputPath = Utils.generateCacheFilePath("m4a", context)
        
        // Get audio information from input file
        val audioInfo = getAudioInfo(inputPath)
        if (audioInfo == null) {
          completeCallback(null, false, "Failed to analyze input audio file")
          return
        }
        
        addLog("Input audio info - SampleRate: ${audioInfo.sampleRate}, Channels: ${audioInfo.channelCount}, Mime: ${audioInfo.mimeType}")
        
        // Validate and calculate output parameters
        val outputBitrate = calculateOutputBitrate(options, originalPath, audioInfo)
        val outputSampleRate = calculateOutputSampleRate(options, audioInfo)
        val outputChannels = calculateOutputChannels(options, audioInfo)
        
        addLog("Output parameters - Bitrate: $outputBitrate, SampleRate: $outputSampleRate, Channels: $outputChannels")
        
        // Validate output parameters
        if (!validateOutputParameters(outputBitrate, outputSampleRate, outputChannels)) {
          completeCallback(null, false, "Invalid output parameters calculated")
          return
        }
        
        // Perform AAC encoding
        encodeToAAC(
          inputPath = inputPath,
          outputPath = outputPath,
          bitrate = outputBitrate,
          sampleRate = outputSampleRate,
          channelCount = outputChannels,
          inputAudioInfo = audioInfo,
          callback = completeCallback
        )
        
      } catch (e: Exception) {
        addLog("Error in compressWithMediaCodec: ${e.localizedMessage}")
        e.printStackTrace()
        completeCallback(null, false, "MediaCodec compression error: ${e.localizedMessage}")
      }
    }
    
    private fun calculateOutputBitrate(options: AudioHelper, originalPath: String, @Suppress("UNUSED_PARAMETER") audioInfo: AudioInfo): Int {
      return when {
        options.bitrate != -1 -> {
          // User specified bitrate, clamp to valid range
          options.bitrate.coerceIn(MIN_BITRATE, MAX_BITRATE)
        }
        else -> {
          // Use quality-based calculation or default
          val quality = options.quality
          if (!quality.isNullOrEmpty()) {
            val qualityBitrate = AudioHelper.getDestinationBitrateByQuality(originalPath, quality) * 1000
            qualityBitrate.coerceIn(MIN_BITRATE, MAX_BITRATE)
          } else {
            DEFAULT_BITRATE
          }
        }
      }
    }
    
    private fun calculateOutputSampleRate(options: AudioHelper, audioInfo: AudioInfo): Int {
      return if (options.samplerate != -1) {
        // Validate sample rate
        when (options.samplerate) {
          8000, 11025, 16000, 22050, 44100, 48000 -> options.samplerate
          else -> {
            addLog("Warning: Unusual sample rate ${options.samplerate}, using input sample rate ${audioInfo.sampleRate}")
            audioInfo.sampleRate
          }
        }
      } else {
        audioInfo.sampleRate
      }
    }
    
    private fun calculateOutputChannels(options: AudioHelper, audioInfo: AudioInfo): Int {
      return if (options.channels != -1) {
        // Validate channel count
        when (options.channels) {
          1, 2 -> options.channels
          else -> {
            addLog("Warning: Unsupported channel count ${options.channels}, using input channels ${audioInfo.channelCount}")
            audioInfo.channelCount.coerceIn(1, 2)
          }
        }
      } else {
        audioInfo.channelCount.coerceIn(1, 2) // AAC encoder supports 1-2 channels
      }
    }
    
    private fun validateOutputParameters(bitrate: Int, sampleRate: Int, channels: Int): Boolean {
      val isValid = bitrate in MIN_BITRATE..MAX_BITRATE &&
                   sampleRate > 0 &&
                   channels in 1..2
      
      if (!isValid) {
        addLog("Invalid parameters - Bitrate: $bitrate, SampleRate: $sampleRate, Channels: $channels")
      }
      
      return isValid
    }
    
    private fun encodeToAAC(
      inputPath: String,
      outputPath: String,
      bitrate: Int,
      sampleRate: Int,
      channelCount: Int,
      @Suppress("UNUSED_PARAMETER") inputAudioInfo: AudioInfo,
      callback: (String?, Boolean, String?) -> Unit
    ) {
      addLog("Starting AAC encoding")
      
      var mediaExtractor: MediaExtractor? = null
      var mediaCodec: MediaCodec? = null
      var mediaMuxer: MediaMuxer? = null
      
      try {
        // Setup and validate extractor
        mediaExtractor = MediaExtractor()
        mediaExtractor.setDataSource(inputPath)
        
        val audioTrackIndex = findAudioTrack(mediaExtractor)
        if (audioTrackIndex == -1) {
          callback(null, false, "No audio track found in input file")
          return
        }
        
        mediaExtractor.selectTrack(audioTrackIndex)
        val inputFormat = mediaExtractor.getTrackFormat(audioTrackIndex)
        addLog("Selected audio track $audioTrackIndex with format: $inputFormat")
        
        // Setup encoder with robust configuration
        val outputFormat = createOutputFormat(bitrate, sampleRate, channelCount)
        
        try {
          mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        } catch (e: IOException) {
          callback(null, false, "AAC encoder not available on this device: ${e.localizedMessage}")
          return
        }
        
        mediaCodec.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        mediaCodec.start()
        addLog("MediaCodec encoder started successfully")
        
        // Setup muxer
        try {
          mediaMuxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (e: IOException) {
          callback(null, false, "Failed to create output file: ${e.localizedMessage}")
          return
        }
        
        // Process audio frames
        val success = processAudioFrames(mediaExtractor, mediaCodec, mediaMuxer)
        
        if (success) {
          // Verify output file
          val outputFile = File(outputPath)
          if (outputFile.exists() && outputFile.length() > 0) {
            addLog("AAC encoding completed successfully, output size: ${outputFile.length()} bytes")
            callback(outputPath, true, null)
          } else {
            callback(null, false, "Output file is empty or was not created")
          }
        } else {
          callback(null, false, "Audio frame processing failed")
        }
        
      } catch (e: Exception) {
        addLog("Error in encodeToAAC: ${e.localizedMessage}")
        e.printStackTrace()
        callback(null, false, "AAC encoding failed: ${e.localizedMessage}")
      } finally {
        // Ensure proper cleanup
        try {
          mediaExtractor?.release()
          addLog("MediaExtractor released")
        } catch (e: Exception) {
          addLog("Warning: Error releasing MediaExtractor: ${e.localizedMessage}")
        }
        
        try {
          mediaCodec?.stop()
          mediaCodec?.release()
          addLog("MediaCodec stopped and released")
        } catch (e: Exception) {
          addLog("Warning: Error stopping/releasing MediaCodec: ${e.localizedMessage}")
        }
        
        try {
          mediaMuxer?.stop()
          mediaMuxer?.release()
          addLog("MediaMuxer stopped and released")
        } catch (e: Exception) {
          addLog("Warning: Error stopping/releasing MediaMuxer: ${e.localizedMessage}")
        }
      }
    }
    
    private fun findAudioTrack(extractor: MediaExtractor): Int {
      for (i in 0 until extractor.trackCount) {
        val format = extractor.getTrackFormat(i)
        val mime = format.getString(MediaFormat.KEY_MIME)
        if (mime?.startsWith("audio/") == true) {
          addLog("Found audio track $i with mime type: $mime")
          return i
        }
      }
      addLog("No audio tracks found in input file")
      return -1
    }
    
    private fun createOutputFormat(bitrate: Int, sampleRate: Int, channelCount: Int): MediaFormat {
      val outputFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount)
      outputFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
      outputFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
      
      // Set additional parameters for better encoding
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        outputFormat.setInteger(MediaFormat.KEY_COMPLEXITY, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
      }
      
      addLog("Created output format: $outputFormat")
      return outputFormat
    }
    
    private fun processAudioFrames(
      extractor: MediaExtractor,
      encoder: MediaCodec,
      muxer: MediaMuxer
    ): Boolean {
      addLog("Starting audio frame processing")
      
      val bufferInfo = MediaCodec.BufferInfo()
      var outputTrackIndex = -1
      var muxerStarted = false
      var inputDone = false
      var outputDone = false
      var frameCount = 0
      var errorCount = 0
      val maxErrors = 10
      
      try {
        while (!outputDone && errorCount < maxErrors) {
          // Feed input to encoder
          if (!inputDone) {
            val inputBufferIndex = encoder.dequeueInputBuffer(TIMEOUT_USEC)
            if (inputBufferIndex >= 0) {
              val inputBuffer = getInputBuffer(encoder, inputBufferIndex)
              
              if (inputBuffer != null) {
                inputBuffer.clear()
                val sampleSize = extractor.readSampleData(inputBuffer, 0)
                
                if (sampleSize < 0) {
                  // End of input stream
                  encoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                  inputDone = true
                  addLog("Reached end of input stream, processed $frameCount frames")
                } else {
                  val presentationTime = extractor.sampleTime
                  encoder.queueInputBuffer(inputBufferIndex, 0, sampleSize, presentationTime, 0)
                  extractor.advance()
                  frameCount++
                  
                  if (frameCount % 1000 == 0) {
                    addLog("Processed $frameCount input frames")
                  }
                }
              } else {
                addLog("Warning: Got null input buffer")
                errorCount++
              }
            }
          }
          
          // Get output from encoder
          val outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC)
          when {
            outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
              // No output available yet, continue
            }
            outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
              if (!muxerStarted) {
                val newFormat = encoder.outputFormat
                addLog("Encoder output format changed: $newFormat")
                outputTrackIndex = muxer.addTrack(newFormat)
                muxer.start()
                muxerStarted = true
                addLog("MediaMuxer started with track index: $outputTrackIndex")
              }
            }
            outputBufferIndex >= 0 -> {
              val outputBuffer = getOutputBuffer(encoder, outputBufferIndex)
              
              if (outputBuffer != null && bufferInfo.size > 0) {
                // Skip codec config buffers
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                  addLog("Skipping codec config buffer")
                } else if (muxerStarted) {
                  // Write encoded data to muxer
                  outputBuffer.position(bufferInfo.offset)
                  outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                  muxer.writeSampleData(outputTrackIndex, outputBuffer, bufferInfo)
                }
              }
              
              encoder.releaseOutputBuffer(outputBufferIndex, false)
              
              if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                outputDone = true
                addLog("Encoding completed, output stream ended")
              }
            }
            else -> {
              addLog("Warning: Unexpected output buffer index: $outputBufferIndex")
              errorCount++
            }
          }
        }
        
        if (errorCount >= maxErrors) {
          addLog("Error: Too many errors during processing ($errorCount), aborting")
          return false
        }
        
        addLog("Audio frame processing completed successfully, total frames: $frameCount")
        return outputDone
        
      } catch (e: Exception) {
        addLog("Error in processAudioFrames: ${e.localizedMessage}")
        e.printStackTrace()
        return false
      }
    }
    
    private fun getInputBuffer(encoder: MediaCodec, index: Int): java.nio.ByteBuffer? {
      return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        encoder.getInputBuffer(index)
      } else {
        @Suppress("DEPRECATION")
        encoder.inputBuffers.getOrNull(index)
      }
    }
    
    private fun getOutputBuffer(encoder: MediaCodec, index: Int): java.nio.ByteBuffer? {
      return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        encoder.getOutputBuffer(index)
      } else {
        @Suppress("DEPRECATION")
        encoder.outputBuffers.getOrNull(index)
      }
    }
    
    private fun getAudioInfo(filePath: String): AudioInfo? {
      addLog("Analyzing audio file: $filePath")
      
      var extractor: MediaExtractor? = null
      try {
        extractor = MediaExtractor()
        extractor.setDataSource(filePath)
        
        for (i in 0 until extractor.trackCount) {
          val format = extractor.getTrackFormat(i)
          val mime = format.getString(MediaFormat.KEY_MIME)
          
          if (mime?.startsWith("audio/") == true) {
            // Extract audio information with validation
            val sampleRate = format.getIntegerSafely(MediaFormat.KEY_SAMPLE_RATE, 44100)
            val channelCount = format.getIntegerSafely(MediaFormat.KEY_CHANNEL_COUNT, 2)
            val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) {
              format.getLong(MediaFormat.KEY_DURATION)
            } else 0L
            
            // Additional validation
            if (sampleRate <= 0 || channelCount <= 0) {
              addLog("Warning: Invalid audio parameters - SampleRate: $sampleRate, Channels: $channelCount")
              continue
            }
            
            val bitrate = if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
              format.getInteger(MediaFormat.KEY_BIT_RATE)
            } else 0
            
            val audioInfo = AudioInfo(sampleRate, channelCount, duration, mime, bitrate)
            addLog("Audio info extracted successfully: $audioInfo")
            return audioInfo
          }
        }
        
        addLog("No valid audio track found in file")
        return null
        
      } catch (e: Exception) {
        addLog("Error getting audio info: ${e.localizedMessage}")
        e.printStackTrace()
        return null
      } finally {
        try {
          extractor?.release()
        } catch (e: Exception) {
          addLog("Warning: Error releasing MediaExtractor in getAudioInfo: ${e.localizedMessage}")
        }
      }
    }
    
    // Extension function to safely get integer values from MediaFormat
    private fun MediaFormat.getIntegerSafely(key: String, defaultValue: Int): Int {
      return try {
        if (containsKey(key)) getInteger(key) else defaultValue
      } catch (e: Exception) {
        addLog("Warning: Could not get $key from MediaFormat, using default $defaultValue")
        defaultValue
      }
    }
    
    private data class AudioInfo(
      val sampleRate: Int,
      val channelCount: Int,
      val duration: Long,
      val mimeType: String,
      val bitrate: Int
    ) {
      override fun toString(): String {
        return "AudioInfo(sampleRate=$sampleRate, channels=$channelCount, duration=${duration/1000}ms, mime=$mimeType, bitrate=$bitrate)"
      }
    }

  }
}
