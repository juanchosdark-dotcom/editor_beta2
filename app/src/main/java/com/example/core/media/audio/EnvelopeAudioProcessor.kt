package com.example.core.media.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.example.core.model.DuckingWindow
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * Sample-accurate 16-bit PCM [BaseAudioProcessor] applying direct clip gain, linear fade-in/out,
 * and automatic ducking envelopes inside both Media3 `CompositionPlayer` preview and `Transformer` export.
 */
@OptIn(UnstableApi::class)
class EnvelopeAudioProcessor(
  private val baseVolume: Float,
  private val fadeInMs: Long,
  private val fadeOutMs: Long,
  private val clipStartOnTimelineMs: Long,
  private val clipDurationMs: Long,
  private val duckingWindows: List<DuckingWindow> = emptyList(),
) : BaseAudioProcessor() {

  private var processedFrames: Long = 0L

  override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
    if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
      throw UnhandledAudioFormatException(inputAudioFormat)
    }
    return inputAudioFormat
  }

  override fun queueInput(inputBuffer: ByteBuffer) {
    if (!inputBuffer.hasRemaining()) return

    val channelCount = inputAudioFormat.channelCount.coerceAtLeast(1)
    val sampleRate = inputAudioFormat.sampleRate.coerceAtLeast(1)
    val bytesPerFrame = channelCount * 2
    val remainingBytes = inputBuffer.remaining()
    val outputBuffer = replaceOutputBuffer(remainingBytes)

    val frameCount = remainingBytes / bytesPerFrame
    for (frame in 0 until frameCount) {
      val currentFrameIndex = processedFrames + frame
      val clipOffsetMs = (currentFrameIndex * 1000L) / sampleRate
      val gain =
        AudioTrackMixer.computeClipEnvelopeGainAt(
          clipStartOnTimelineMs = clipStartOnTimelineMs,
          clipOffsetMs = clipOffsetMs,
          clipDurationMs = clipDurationMs,
          baseVolume = baseVolume,
          fadeInMs = fadeInMs,
          fadeOutMs = fadeOutMs,
          duckingWindows = duckingWindows,
        )

      for (ch in 0 until channelCount) {
        val sample = inputBuffer.short
        val scaled = (sample * gain).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        outputBuffer.putShort(scaled.toShort())
      }
    }

    // Copy any trailing partial bytes safely
    while (inputBuffer.hasRemaining()) {
      outputBuffer.put(inputBuffer.get())
    }

    processedFrames += frameCount
    outputBuffer.flip()
  }

  override fun onFlush() {
    processedFrames = 0L
  }

  override fun onReset() {
    processedFrames = 0L
  }
}
