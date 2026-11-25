package com.example.core.media.audio

import com.example.core.model.DuckingWindow
import com.example.core.model.TimelineClip
import com.example.core.model.TimelineState
import com.example.core.model.Track
import com.example.core.model.TrackType
import kotlin.math.max
import kotlin.math.min

/**
 * Pure audio mixing rules for track Mute/Solo resolution, basic side-chain ducking,
 * and sample-accurate gain + fade-in/fade-out envelope calculation.
 */
object AudioTrackMixer {

  /**
   * Resolves effective audibility per track ID.
   * When any audio-bearing track has `isSolo == true`, non-solo tracks are silenced.
   * Explicit `isMuted == true` always silences the track.
   */
  fun resolveTrackAudibility(tracks: List<Track>): Map<String, Boolean> {
    val audioBearingTracks =
      tracks.filter { it.type == TrackType.VIDEO || it.type == TrackType.AUDIO }
    val anySoloActive = audioBearingTracks.any { it.isSolo }

    return tracks.associate { track ->
      val audible =
        when {
          track.type != TrackType.VIDEO && track.type != TrackType.AUDIO -> false
          track.isMuted -> false
          anySoloActive -> track.isSolo
          else -> true
        }
      track.trackId to audible
    }
  }

  /**
   * Computes ducking intervals on `state.duckingConfig.targetTrackId` wherever active,
   * audible clips are playing on `state.duckingConfig.triggerTrackId`.
   */
  fun computeDuckingWindows(state: TimelineState): List<DuckingWindow> {
    val config = state.duckingConfig
    if (!config.enabled) return emptyList()

    val audibility = resolveTrackAudibility(state.tracks)
    if (audibility[config.triggerTrackId] != true) return emptyList()
    if (audibility[config.targetTrackId] != true) return emptyList()

    val triggerClips =
      state.clips
        .filter { it.trackId == config.triggerTrackId && it.isEnabled }
        .sortedBy { it.timelineStartMs }

    if (triggerClips.isEmpty()) return emptyList()

    // Merge overlapping/adjacent trigger clips into clean continuous ducking spans
    val merged = mutableListOf<Pair<Long, Long>>()
    for (clip in triggerClips) {
      val last = merged.lastOrNull()
      if (last != null && clip.timelineStartMs <= last.second + config.rampMs) {
        merged[merged.lastIndex] = last.first to max(last.second, clip.timelineEndMs)
      } else {
        merged.add(clip.timelineStartMs to clip.timelineEndMs)
      }
    }

    val clampedAttenuation = config.attenuationFactor.coerceIn(0.05f, 1.0f)
    return merged.map { (start, end) ->
      DuckingWindow(
        startMs = start,
        endMs = end,
        attenuationFactor = clampedAttenuation,
        rampMs = config.rampMs.coerceAtLeast(0L),
      )
    }
  }

  /**
   * Returns the linear volume multiplier at [clipOffsetMs] inside a clip of [clipDurationMs],
   * combining base volume, direct fade-in/fade-out ramps, and any overlapping [duckingWindows].
   */
  fun computeClipEnvelopeGainAt(
    clipStartOnTimelineMs: Long,
    clipOffsetMs: Long,
    clipDurationMs: Long,
    baseVolume: Float,
    fadeInMs: Long,
    fadeOutMs: Long,
    duckingWindows: List<DuckingWindow>,
  ): Float {
    if (baseVolume <= 0f || clipDurationMs <= 0L) return 0f

    val safeOffset = clipOffsetMs.coerceIn(0L, clipDurationMs)
    val fadeInFactor =
      if (fadeInMs > 0L && safeOffset < fadeInMs) {
        (safeOffset.toFloat() / fadeInMs.toFloat()).coerceIn(0f, 1f)
      } else {
        1f
      }

    val remainingMs = (clipDurationMs - safeOffset).coerceAtLeast(0L)
    val fadeOutFactor =
      if (fadeOutMs > 0L && remainingMs < fadeOutMs) {
        (remainingMs.toFloat() / fadeOutMs.toFloat()).coerceIn(0f, 1f)
      } else {
        1f
      }

    val timelinePosMs = clipStartOnTimelineMs + safeOffset
    var duckFactor = 1f
    for (window in duckingWindows) {
      val ramp = window.rampMs
      val expandedStart = (window.startMs - ramp).coerceAtLeast(0L)
      val expandedEnd = window.endMs + ramp
      if (timelinePosMs in expandedStart..expandedEnd) {
        val factor =
          when {
            timelinePosMs < window.startMs && ramp > 0L -> {
              val t = (timelinePosMs - expandedStart).toFloat() / ramp.toFloat()
              1f - (1f - window.attenuationFactor) * t.coerceIn(0f, 1f)
            }
            timelinePosMs > window.endMs && ramp > 0L -> {
              val t = (timelinePosMs - window.endMs).toFloat() / ramp.toFloat()
              window.attenuationFactor + (1f - window.attenuationFactor) * t.coerceIn(0f, 1f)
            }
            else -> window.attenuationFactor
          }
        duckFactor = min(duckFactor, factor)
      }
    }

    return (baseVolume * fadeInFactor * fadeOutFactor * duckFactor).coerceIn(0f, 2f)
  }

  /**
   * Filters ducking windows relevant to a specific [clip].
   */
  fun duckingWindowsForClip(
    clip: TimelineClip,
    allTargetWindows: List<DuckingWindow>,
  ): List<DuckingWindow> {
    return allTargetWindows.filter { window ->
      (window.startMs - window.rampMs) < clip.timelineEndMs &&
        (window.endMs + window.rampMs) > clip.timelineStartMs
    }
  }
}
