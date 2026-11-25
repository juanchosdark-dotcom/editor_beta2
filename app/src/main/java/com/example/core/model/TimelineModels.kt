package com.example.core.model

import com.squareup.moshi.JsonClass

/**
 * Complete state of the multitrack timeline, including tracks, clips, markers, and editing modes.
 */
@JsonClass(generateAdapter = true)
data class TimelineState(
  val playheadMs: Long = 0L,
  val tracks: List<Track> = emptyList(),
  val clips: List<TimelineClip> = emptyList(),
  val markers: List<TimelineMarker> = emptyList(),
  val editOptions: EditModeOptions = EditModeOptions(),
  val duckingConfig: AudioDuckingConfig = AudioDuckingConfig(),
) {
  val totalDurationMs: Long
    get() = clips.maxOfOrNull { it.timelineEndMs } ?: 0L

  fun tracksSorted(): List<Track> = tracks.sortedBy { it.order }

  fun clipsForTrack(trackId: String): List<TimelineClip> =
    clips.filter { it.trackId == trackId }.sortedBy { it.timelineStartMs }

  fun activeClipsAt(positionMs: Long): List<TimelineClip> =
    clips.filter { it.isEnabled && positionMs in it.timelineStartMs until it.timelineEndMs }
}

/**
 * Supported track categories in the multitrack timeline.
 */
enum class TrackType {
  VIDEO,
  AUDIO,
  SUBTITLE,
  ADJUSTMENT,
}

@JsonClass(generateAdapter = true)
data class Track(
  val trackId: String,
  val type: TrackType,
  val order: Int,
  val name: String,
  val isMuted: Boolean = false,
  val isSolo: Boolean = false,
  val isLocked: Boolean = false,
  val isVisible: Boolean = true,
  val volume: Float = 1.0f,
)

enum class ClipKind {
  VIDEO,
  AUDIO,
  SUBTITLE,
  ADJUSTMENT,
}

/**
 * Non-destructive timeline clip supporting Video, Audio, Subtitles, and Adjustment Layers.
 *
 * Disabled clips (`isEnabled == false`) stay in place on the timeline with all edits intact
 * but are bypassed during preview playback and final export.
 */
@JsonClass(generateAdapter = true)
data class TimelineClip(
  val clipId: String,
  val trackId: String,
  val kind: ClipKind,
  val label: String,
  val timelineStartMs: Long,
  val timelineEndMs: Long,
  val sourceInMs: Long = 0L,
  val sourceOutMs: Long = 0L,
  val speed: Float = 1.0f,
  val isEnabled: Boolean = true,
  val isLocked: Boolean = false,
  val assetId: String? = null,
  val videoConfig: VideoClipConfig? = null,
  val audioConfig: AudioClipConfig? = null,
  val subtitleConfig: SubtitleClipConfig? = null,
  val adjustmentConfig: AdjustmentClipConfig? = null,
) {
  init {
    require(timelineStartMs >= 0L) { "timelineStartMs must be >= 0" }
    require(timelineEndMs > timelineStartMs) { "timelineEndMs must be > timelineStartMs" }
    require(speed > 0f) { "speed must be > 0" }
  }

  val durationMs: Long
    get() = timelineEndMs - timelineStartMs

  val sourceSpanMs: Long
    get() = (sourceOutMs - sourceInMs).coerceAtLeast(0L)

  fun containsTimelinePosition(positionMs: Long): Boolean =
    positionMs in timelineStartMs until timelineEndMs
}

@JsonClass(generateAdapter = true)
data class ClipKeyframe(
  val keyframeId: String,
  val offsetMs: Long,
  val scale: Float = 1.0f,
  val opacity: Float = 1.0f,
  val offsetX: Float = 0.0f,
  val offsetY: Float = 0.0f,
  val rotationDegrees: Float = 0.0f,
)

@JsonClass(generateAdapter = true)
data class VideoClipConfig(
  val volume: Float = 1.0f,
  val fadeInMs: Long = 0L,
  val fadeOutMs: Long = 0L,
  val opacity: Float = 1.0f,
  val scale: Float = 1.0f,
  val offsetX: Float = 0.0f,
  val offsetY: Float = 0.0f,
  val rotationDegrees: Float = 0.0f,
  val brightness: Float = 0.0f,
  val contrast: Float = 1.0f,
  val saturation: Float = 1.0f,
  val exposure: Float = 0.0f,
  val temperature: Float = 0.0f,
  val vignette: Float = 0.0f,
  val lutPresetId: String? = null,
  val lutStrength: Float = 1.0f,
  val transitionIn: ClipTransition? = null,
  val transitionOut: ClipTransition? = null,
  val keyframes: List<ClipKeyframe> = emptyList(),
) {
  fun evaluateTransformAt(offsetMs: Long): ClipKeyframe {
    if (keyframes.isEmpty()) {
      return ClipKeyframe(
        keyframeId = "base",
        offsetMs = offsetMs,
        scale = scale,
        opacity = opacity,
        offsetX = offsetX,
        offsetY = offsetY,
        rotationDegrees = rotationDegrees,
      )
    }
    val sorted = keyframes.sortedBy { it.offsetMs }
    if (offsetMs <= sorted.first().offsetMs) return sorted.first()
    if (offsetMs >= sorted.last().offsetMs) return sorted.last()

    val rightIdx = sorted.indexOfFirst { it.offsetMs >= offsetMs }.coerceAtLeast(1)
    val left = sorted[rightIdx - 1]
    val right = sorted[rightIdx]
    val span = (right.offsetMs - left.offsetMs).coerceAtLeast(1L)
    val t = ((offsetMs - left.offsetMs).toFloat() / span.toFloat()).coerceIn(0f, 1f)

    return ClipKeyframe(
      keyframeId = "interp_${offsetMs}",
      offsetMs = offsetMs,
      scale = left.scale + (right.scale - left.scale) * t,
      opacity = left.opacity + (right.opacity - left.opacity) * t,
      offsetX = left.offsetX + (right.offsetX - left.offsetX) * t,
      offsetY = left.offsetY + (right.offsetY - left.offsetY) * t,
      rotationDegrees = left.rotationDegrees + (right.rotationDegrees - left.rotationDegrees) * t,
    )
  }
}

@JsonClass(generateAdapter = true)
data class AudioClipConfig(
  val volume: Float = 1.0f,
  val pan: Float = 0.0f,
  val fadeInMs: Long = 0L,
  val fadeOutMs: Long = 0L,
)

@JsonClass(generateAdapter = true)
data class SubtitleClipConfig(
  val text: String,
  val fontFamily: String = "sans-serif",
  val fontSizeSp: Float = 20f,
  val textColorHex: String = "#FFFFFF",
  val backgroundColorHex: String = "#80000000",
  val alignment: SubtitleAlignment = SubtitleAlignment.BOTTOM_CENTER,
)

enum class SubtitleAlignment {
  BOTTOM_CENTER,
  MIDDLE_CENTER,
  TOP_CENTER,
}

@JsonClass(generateAdapter = true)
data class AdjustmentClipConfig(
  val brightness: Float = 0.0f,
  val contrast: Float = 1.0f,
  val saturation: Float = 1.0f,
  val exposure: Float = 0.0f,
  val temperature: Float = 0.0f,
  val vignette: Float = 0.0f,
  val lutRelativePath: String? = null,
  val lutPresetId: String? = null,
  val lutStrength: Float = 1.0f,
)

@JsonClass(generateAdapter = true)
data class ClipTransition(
  val type: TransitionType,
  val durationMs: Long,
)

enum class TransitionType {
  CROSS_DISSOLVE,
  FADE_BLACK,
  SLIDE_LEFT,
  SLIDE_RIGHT,
}

/**
 * Editorial timeline marker anchored to a timestamp in milliseconds.
 */
@JsonClass(generateAdapter = true)
data class TimelineMarker(
  val markerId: String,
  val positionMs: Long,
  val label: String,
  val color: MarkerColor = MarkerColor.AMBER,
  val notes: String = "",
)

enum class MarkerColor {
  AMBER,
  CYAN,
  CORAL,
  EMERALD,
  VIOLET,
}

/**
 * Editing behavior toggles including Ripple Edit mode and scope.
 */
@JsonClass(generateAdapter = true)
data class EditModeOptions(
  val rippleEnabled: Boolean = false,
  val rippleScope: RippleScope = RippleScope.SAME_TRACK,
  val snapToMarkers: Boolean = true,
)

enum class RippleScope {
  /** Ripple shifts only downstream clips on the track being edited. */
  SAME_TRACK,

  /** Ripple shifts downstream clips across all unlocked tracks and moves markers accordingly. */
  ALL_TRACKS,
}
