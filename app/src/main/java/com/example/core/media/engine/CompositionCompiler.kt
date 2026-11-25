package com.example.core.media.engine

import android.graphics.Matrix
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.example.core.media.audio.AudioTrackMixer
import com.example.core.media.audio.EnvelopeAudioProcessor
import com.example.core.media.model.MediaRenderTarget
import com.example.core.model.AdjustmentClipConfig
import com.example.core.model.BuiltInLutPresets
import com.example.core.model.ClipKind
import com.example.core.model.DuckingWindow
import com.example.core.model.MediaAsset
import com.example.core.model.MediaType
import com.example.core.model.TimelineClip
import com.example.core.model.TimelineState
import com.example.core.model.TrackType
import com.example.core.model.VideoClipConfig
import java.io.File

sealed interface CompiledCompositionResult {
  data class Ready(
    val composition: Composition,
    val totalVideoItems: Int,
    val totalAudioItems: Int,
    val usedProxyCount: Int,
    val appliedVideoEffectCount: Int = 0,
  ) : CompiledCompositionResult

  data class EmptyOrMissingMedia(val reason: String) : CompiledCompositionResult
}

/**
 * Translates our portable [TimelineState] and [MediaAsset] registry into a Media3 [Composition].
 *
 * Includes:
 * - Source trimming via [MediaItem.ClippingConfiguration] (`sourceInMs..sourceOutMs`).
 * - Static & keyframe-animated scale, position (`offsetX`/`offsetY`), rotation, and opacity.
 * - Overlapping [ClipKind.ADJUSTMENT] color grading (brightness, contrast, saturation, temperature).
 * - Separate Preview quality / proxy usage vs. full-resolution Export with HDR->SDR tone mapping.
 * - Mute/Solo resolution and volume/fade/ducking via [EnvelopeAudioProcessor].
 */
@OptIn(UnstableApi::class)
class CompositionCompiler {

  fun compile(
    timeline: TimelineState,
    assets: List<MediaAsset>,
    projectRootDir: File,
    renderTarget: MediaRenderTarget,
  ): CompiledCompositionResult {
    val assetById = assets.associateBy { it.assetId }
    val trackById = timeline.tracks.associateBy { it.trackId }
    val trackAudibility = AudioTrackMixer.resolveTrackAudibility(timeline.tracks)
    val duckingWindows = AudioTrackMixer.computeDuckingWindows(timeline)

    val activeAdjustmentClips =
      timeline.clips.filter { clip ->
        clip.isEnabled &&
          clip.kind == ClipKind.ADJUSTMENT &&
          trackById[clip.trackId]?.isVisible != false
      }

    var usedProxyCount = 0
    var appliedVideoEffectCount = 0

    // 1. Primary video track sequence
    val videoTracks =
      timeline.tracksSorted().filter { it.type == TrackType.VIDEO && it.isVisible }
    val primaryVideoClips =
      videoTracks.firstOrNull()?.let { track ->
        timeline.clipsForTrack(track.trackId).filter { it.isEnabled && it.kind == ClipKind.VIDEO }
      }.orEmpty()

    val videoEditedItems =
      primaryVideoClips.mapNotNull { clip ->
        val asset = clip.assetId?.let { assetById[it] } ?: return@mapNotNull null
        val resolved =
          resolveSourceUri(asset, projectRootDir, renderTarget) ?: return@mapNotNull null
        if (resolved.isProxy) usedProxyCount++

        val adjustmentOverlap = findOverlappingAdjustment(clip, activeAdjustmentClips)
        val isTrackAudible = trackAudibility[clip.trackId] == true
        val trackVolume = trackById[clip.trackId]?.volume ?: 1f
        val clipDuckingWindows =
          if (timeline.duckingConfig.targetTrackId == clip.trackId) {
            AudioTrackMixer.duckingWindowsForClip(clip, duckingWindows)
          } else {
            emptyList()
          }

        val (item, effectCount) =
          buildVideoEditedItem(
            clip = clip,
            asset = asset,
            sourceUri = resolved.uri,
            isTrackAudible = isTrackAudible,
            trackVolume = trackVolume,
            duckingWindows = clipDuckingWindows,
            adjustment = adjustmentOverlap,
            renderTarget = renderTarget,
          )
        appliedVideoEffectCount += effectCount
        item
      }

    // 2. Auxiliary audio tracks (respecting Solo/Mute and Ducking envelopes)
    val audioTracks =
      timeline.tracksSorted().filter { it.type == TrackType.AUDIO && trackAudibility[it.trackId] == true }
    val audioClips =
      audioTracks.flatMap { track ->
        timeline.clipsForTrack(track.trackId).filter { it.isEnabled && it.kind == ClipKind.AUDIO }
      }

    val audioEditedItems =
      audioClips.mapNotNull { clip ->
        val asset = clip.assetId?.let { assetById[it] } ?: return@mapNotNull null
        val resolved =
          resolveSourceUri(asset, projectRootDir, renderTarget) ?: return@mapNotNull null
        val trackVolume = trackById[clip.trackId]?.volume ?: 1f
        val clipDuckingWindows =
          if (timeline.duckingConfig.targetTrackId == clip.trackId) {
            AudioTrackMixer.duckingWindowsForClip(clip, duckingWindows)
          } else {
            emptyList()
          }
        buildAudioEditedItem(clip, resolved.uri, trackVolume, clipDuckingWindows)
      }

    if (videoEditedItems.isEmpty() && audioEditedItems.isEmpty()) {
      return CompiledCompositionResult.EmptyOrMissingMedia(
        "No hay clips multimedia locales en línea para procesar en este rango.",
      )
    }

    val sequences = mutableListOf<EditedMediaItemSequence>()
    if (videoEditedItems.isNotEmpty()) {
      sequences.add(EditedMediaItemSequence.withAudioAndVideoFrom(videoEditedItems))
    }
    if (audioEditedItems.isNotEmpty()) {
      sequences.add(EditedMediaItemSequence.withAudioFrom(audioEditedItems))
    }

    val compositionEffects = buildCompositionWideEffects(renderTarget)
    val composition =
      Composition.Builder(sequences)
        .setEffects(compositionEffects)
        .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
        .build()

    return CompiledCompositionResult.Ready(
      composition = composition,
      totalVideoItems = videoEditedItems.size,
      totalAudioItems = audioEditedItems.size,
      usedProxyCount = usedProxyCount,
      appliedVideoEffectCount = appliedVideoEffectCount,
    )
  }

  private fun buildVideoEditedItem(
    clip: TimelineClip,
    asset: MediaAsset,
    sourceUri: Uri,
    isTrackAudible: Boolean,
    trackVolume: Float,
    duckingWindows: List<DuckingWindow>,
    adjustment: AdjustmentClipConfig?,
    renderTarget: MediaRenderTarget,
  ): Pair<EditedMediaItem, Int> {
    val startMs = clip.sourceInMs.coerceAtLeast(0L)
    val endMs = clip.sourceOutMs.coerceAtLeast(startMs + 1L)

    val mediaItemBuilder = MediaItem.Builder().setUri(sourceUri)
    if (asset.type == MediaType.IMAGE) {
      mediaItemBuilder.setImageDurationMs(clip.durationMs.coerceAtLeast(1_000L))
    } else {
      mediaItemBuilder.setClippingConfiguration(
        MediaItem.ClippingConfiguration.Builder()
          .setStartPositionMs(startMs)
          .setEndPositionMs(endMs)
          .build(),
      )
    }
    val mediaItem = mediaItemBuilder.build()

    val videoEffects = buildVideoEffectsForClip(clip.videoConfig, adjustment, renderTarget)
    val effectCount = videoEffects.size

    val effectiveVolume = ((clip.videoConfig?.volume ?: 1f) * trackVolume).coerceIn(0f, 2f)
    val fadeInMs = clip.videoConfig?.fadeInMs ?: 0L
    val fadeOutMs = clip.videoConfig?.fadeOutMs ?: 0L
    val audioProcessors =
      buildEnvelopeProcessors(
        clip = clip,
        baseVolume = effectiveVolume,
        fadeInMs = fadeInMs,
        fadeOutMs = fadeOutMs,
        duckingWindows = duckingWindows,
      )

    val editedBuilder =
      EditedMediaItem.Builder(mediaItem)
        .setRemoveAudio(asset.type == MediaType.IMAGE || !isTrackAudible || effectiveVolume <= 0.001f)
        .setEffects(Effects(audioProcessors, videoEffects))

    if (asset.type == MediaType.IMAGE) {
      editedBuilder.setFrameRate(30)
    }

    return editedBuilder.build() to effectCount
  }

  private fun buildVideoEffectsForClip(
    videoCfg: VideoClipConfig?,
    adjustment: AdjustmentClipConfig?,
    renderTarget: MediaRenderTarget,
  ): List<Effect> {
    val effects = mutableListOf<Effect>()

    videoCfg?.let { cfg ->
      val hasKeyframes = cfg.keyframes.isNotEmpty()
      val hasTranslation = cfg.offsetX != 0.0f || cfg.offsetY != 0.0f
      val hasScaleOrRotate = cfg.scale != 1.0f || cfg.rotationDegrees != 0.0f

      if (hasKeyframes || hasTranslation) {
        effects.add(
          MatrixTransformation { presentationTimeUs ->
            val offsetMs = (presentationTimeUs / 1_000L).coerceAtLeast(0L)
            val evaluated = cfg.evaluateTransformAt(offsetMs)
            Matrix().apply {
              if (evaluated.rotationDegrees != 0.0f) {
                postRotate(evaluated.rotationDegrees)
              }
              if (evaluated.scale != 1.0f) {
                postScale(evaluated.scale, evaluated.scale)
              }
              if (evaluated.offsetX != 0.0f || evaluated.offsetY != 0.0f) {
                postTranslate(
                  evaluated.offsetX.coerceIn(-1.5f, 1.5f),
                  evaluated.offsetY.coerceIn(-1.5f, 1.5f),
                )
              }
            }
          },
        )
      } else if (hasScaleOrRotate) {
        effects.add(
          ScaleAndRotateTransformation.Builder()
            .setScale(cfg.scale, cfg.scale)
            .setRotationDegrees(cfg.rotationDegrees)
            .build(),
        )
      }

      val baseOpacity =
        if (hasKeyframes) {
          cfg.keyframes.map { it.opacity }.average().toFloat()
        } else {
          cfg.opacity
        }.coerceIn(0.05f, 1.0f)
      if (baseOpacity < 0.99f) {
        effects.add(
          RgbAdjustment.Builder()
            .setRedScale(baseOpacity)
            .setGreenScale(baseOpacity)
            .setBlueScale(baseOpacity)
            .build(),
        )
      }

      val effectiveVideoGrade = BuiltInLutPresets.resolveEffectiveColor(cfg)
      val netVideoBrightness = (effectiveVideoGrade.brightness + effectiveVideoGrade.exposure * 0.35f).coerceIn(-1f, 1f)
      if (kotlin.math.abs(netVideoBrightness) > 0.005f) {
        effects.add(Brightness(netVideoBrightness))
      }
      if (kotlin.math.abs(effectiveVideoGrade.contrast - 1.0f) > 0.005f) {
        effects.add(Contrast((effectiveVideoGrade.contrast - 1.0f).coerceIn(-1f, 1f)))
      }
      if (kotlin.math.abs(effectiveVideoGrade.saturation - 1.0f) > 0.005f) {
        val satShift = ((effectiveVideoGrade.saturation - 1.0f) * 100f).coerceIn(-100f, 100f)
        effects.add(HslAdjustment.Builder().adjustSaturation(satShift).build())
      }
      if (kotlin.math.abs(effectiveVideoGrade.temperature) > 0.005f) {
        val tempShift = (effectiveVideoGrade.temperature * 0.18f).coerceIn(-0.35f, 0.35f)
        effects.add(
          RgbAdjustment.Builder()
            .setRedScale((1.0f + tempShift).coerceAtLeast(0.2f))
            .setBlueScale((1.0f - tempShift).coerceAtLeast(0.2f))
            .build(),
        )
      }
    }

    adjustment?.let { adj ->
      val effectiveAdjGrade = BuiltInLutPresets.resolveEffectiveColor(adj)
      val netAdjBrightness = (effectiveAdjGrade.brightness + effectiveAdjGrade.exposure * 0.35f).coerceIn(-1f, 1f)
      if (kotlin.math.abs(netAdjBrightness) > 0.005f) {
        effects.add(Brightness(netAdjBrightness))
      }
      if (kotlin.math.abs(effectiveAdjGrade.contrast - 1.0f) > 0.005f) {
        effects.add(Contrast((effectiveAdjGrade.contrast - 1.0f).coerceIn(-1f, 1f)))
      }
      if (kotlin.math.abs(effectiveAdjGrade.saturation - 1.0f) > 0.005f) {
        val saturationShift = ((effectiveAdjGrade.saturation - 1.0f) * 100f).coerceIn(-100f, 100f)
        effects.add(HslAdjustment.Builder().adjustSaturation(saturationShift).build())
      }
      if (kotlin.math.abs(effectiveAdjGrade.temperature) > 0.005f) {
        val tempShift = (effectiveAdjGrade.temperature * 0.18f).coerceIn(-0.35f, 0.35f)
        effects.add(
          RgbAdjustment.Builder()
            .setRedScale((1.0f + tempShift).coerceAtLeast(0.2f))
            .setBlueScale((1.0f - tempShift).coerceAtLeast(0.2f))
            .build(),
        )
      }
    }

    if (renderTarget is MediaRenderTarget.Preview) {
      effects.add(Presentation.createForHeight(renderTarget.profile.targetHeight))
    }

    return effects
  }

  private fun buildAudioEditedItem(
    clip: TimelineClip,
    sourceUri: Uri,
    trackVolume: Float,
    duckingWindows: List<DuckingWindow>,
  ): EditedMediaItem {
    val mediaItem =
      MediaItem.Builder()
        .setUri(sourceUri)
        .setClippingConfiguration(
          MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(clip.sourceInMs.coerceAtLeast(0L))
            .setEndPositionMs(clip.sourceOutMs.coerceAtLeast(clip.sourceInMs + 1L))
            .build(),
        )
        .build()

    val effectiveVolume = ((clip.audioConfig?.volume ?: 1f) * trackVolume).coerceIn(0f, 2f)
    val fadeInMs = clip.audioConfig?.fadeInMs ?: 0L
    val fadeOutMs = clip.audioConfig?.fadeOutMs ?: 0L
    val audioProcessors =
      buildEnvelopeProcessors(
        clip = clip,
        baseVolume = effectiveVolume,
        fadeInMs = fadeInMs,
        fadeOutMs = fadeOutMs,
        duckingWindows = duckingWindows,
      )

    return EditedMediaItem.Builder(mediaItem)
      .setRemoveVideo(true)
      .setEffects(Effects(audioProcessors, emptyList()))
      .build()
  }

  private fun buildCompositionWideEffects(renderTarget: MediaRenderTarget): Effects {
    val presentation =
      when (renderTarget) {
        is MediaRenderTarget.Preview ->
          Presentation.createForHeight(renderTarget.profile.targetHeight)
        is MediaRenderTarget.Export ->
          Presentation.createForWidthAndHeight(
            renderTarget.config.width,
            renderTarget.config.height,
            Presentation.LAYOUT_SCALE_TO_FIT,
          )
      }
    return Effects(emptyList(), listOf(presentation))
  }

  private fun buildEnvelopeProcessors(
    clip: TimelineClip,
    baseVolume: Float,
    fadeInMs: Long,
    fadeOutMs: Long,
    duckingWindows: List<DuckingWindow>,
  ): List<AudioProcessor> {
    val needsProcessing =
      baseVolume !in 0.99f..1.01f ||
        fadeInMs > 0L ||
        fadeOutMs > 0L ||
        duckingWindows.isNotEmpty()
    if (!needsProcessing) return emptyList()

    return listOf(
      EnvelopeAudioProcessor(
        baseVolume = baseVolume,
        fadeInMs = fadeInMs,
        fadeOutMs = fadeOutMs,
        clipStartOnTimelineMs = clip.timelineStartMs,
        clipDurationMs = clip.durationMs,
        duckingWindows = duckingWindows,
      ),
    )
  }

  private fun findOverlappingAdjustment(
    videoClip: TimelineClip,
    adjustments: List<TimelineClip>,
  ): AdjustmentClipConfig? {
    return adjustments
      .firstOrNull { adj ->
        adj.timelineStartMs < videoClip.timelineEndMs && adj.timelineEndMs > videoClip.timelineStartMs
      }
      ?.adjustmentConfig
  }

  private data class ResolvedMediaSource(val uri: Uri, val isProxy: Boolean)

  private fun resolveSourceUri(
    asset: MediaAsset,
    projectRootDir: File,
    renderTarget: MediaRenderTarget,
  ): ResolvedMediaSource? {
    if (renderTarget is MediaRenderTarget.Preview && renderTarget.useProxies) {
      val proxyRel = asset.proxyRelativePath
      if (!proxyRel.isNullOrBlank()) {
        val proxyFile = File(projectRootDir, proxyRel)
        if (proxyFile.exists() && proxyFile.length() > 0L) {
          return ResolvedMediaSource(Uri.fromFile(proxyFile), isProxy = true)
        }
      }
    }

    val relativeFile = File(projectRootDir, asset.relativePath)
    if (relativeFile.exists() && relativeFile.length() > 0L) {
      return ResolvedMediaSource(Uri.fromFile(relativeFile), isProxy = false)
    }

    val externalUri = asset.externalSourceUri
    if (!externalUri.isNullOrBlank()) {
      return ResolvedMediaSource(Uri.parse(externalUri), isProxy = false)
    }

    return null
  }
}
