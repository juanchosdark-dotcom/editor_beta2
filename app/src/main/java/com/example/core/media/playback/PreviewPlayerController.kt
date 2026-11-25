package com.example.core.media.playback

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import com.example.core.ai.api.DeviceMemoryTier
import com.example.core.ai.api.DeviceProfile
import com.example.core.media.model.MediaUiError
import com.example.core.media.model.MediaUiErrorKind
import com.example.core.media.model.PlaybackDiagnosticNotice
import com.example.core.media.model.RenderSettings
import com.example.core.model.ClipKind
import com.example.core.model.MediaAsset
import com.example.core.model.TimelineClip
import com.example.core.model.TimelineState
import com.example.core.model.TrackType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToLong

data class PreviewPlayerState(
  val isPlaying: Boolean = false,
  val isScrubbing: Boolean = false,
  val isLoopEnabled: Boolean = false,
  val hasPlayableMedia: Boolean = false,
  val activeProxyCount: Int = 0,
  val videoRotationDegrees: Int = 0,
  val videoWidth: Int = 0,
  val videoHeight: Int = 0,
  val statusNote: String? = null,
  val diagnosticNotice: PlaybackDiagnosticNotice? = null,
  val uiError: MediaUiError? = null,
) {
  val isPortraitVideo: Boolean
    get() = videoHeight > videoWidth && videoWidth > 0
}

internal data class TimelinePlaybackSegment(
  val clipId: String,
  val windowIndex: Int,
  val timelineStartMs: Long,
  val timelineEndMs: Long,
  val sourceInMs: Long,
  val speed: Float,
  val isHdrOrHighResProtected: Boolean,
)

/**
 * Stability-hardened Media3 [ExoPlayer] controller for timeline preview.
 *
 * - Binds to [Lifecycle] via [LifecycleEventObserver] to pause on `ON_PAUSE` and release on `ON_DESTROY`.
 * - Restores compiled playback state cleanly after configuration changes / screen rotation.
 * - Switches to [SeekParameters.CLOSEST_SYNC] while scrubbing and restores exact seeking on release.
 * - Adapts buffer budgets to [DeviceProfile], applies HDR/high-res fallbacks, and surfaces typed [MediaUiError]s.
 */
@OptIn(UnstableApi::class)
class PreviewPlayerController(
  private val context: Context,
) {

  private var exoPlayer: ExoPlayer? = null
  private var currentSegments: List<TimelinePlaybackSegment> = emptyList()
  private var lastProfileTier: DeviceMemoryTier = DeviceMemoryTier.BALANCED
  private var boundLifecycle: Lifecycle? = null
  private var lifecycleObserver: LifecycleEventObserver? = null
  private var lastCompiledParams: LastCompileRequest? = null
  private var resumePositionMs: Long = 0L
  private var loopEnabledPreference: Boolean = false
  private var effectsDisabledFallback: Boolean = false
  private var lastScrubDispatchMs: Long = Long.MIN_VALUE
  private var lastScrubWallClockMs: Long = 0L

  private data class LastCompileRequest(
    val timeline: TimelineState,
    val assets: List<MediaAsset>,
    val projectRootDir: File,
    val renderSettings: RenderSettings,
    val deviceProfile: DeviceProfile,
  )

  private val _state = MutableStateFlow(PreviewPlayerState())
  val state: StateFlow<PreviewPlayerState> = _state.asStateFlow()

  fun getOrCreatePlayer(profile: DeviceProfile = DeviceProfile()): ExoPlayer {
    val existing = exoPlayer
    if (existing != null) return existing

    val loadControl = buildLoadControlForProfile(profile)
    val renderersFactory =
      DefaultRenderersFactory(context)
        .setEnableDecoderFallback(true)

    val audioAttributes =
      AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
        .build()

    return ExoPlayer.Builder(context, renderersFactory)
      .setLoadControl(loadControl)
      .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
      .setHandleAudioBecomingNoisy(true)
      .build()
      .also { player ->
        lastProfileTier = profile.tier
        player.setSeekParameters(SeekParameters.DEFAULT)
        player.repeatMode =
          if (loopEnabledPreference) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        player.addListener(
          object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
              _state.value = _state.value.copy(isPlaying = isPlaying)
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
              if (videoSize.width > 0 && videoSize.height > 0) {
                val unappliedRot =
                  if (videoSize.unappliedRotationDegrees != 0) {
                    ((videoSize.unappliedRotationDegrees % 360) + 360) % 360
                  } else {
                    _state.value.videoRotationDegrees
                  }
                _state.value =
                  _state.value.copy(
                    videoWidth = videoSize.width,
                    videoHeight = videoSize.height,
                    videoRotationDegrees = unappliedRot,
                    uiError = null,
                  )
              }
            }

            override fun onPlayerError(error: PlaybackException) {
              handlePlaybackException(error)
            }
          },
        )
        exoPlayer = player
      }
  }

  /**
   * Attaches a [LifecycleEventObserver] that pauses audio/video immediately on `ON_PAUSE`
   * and frees the hardware decoder on `ON_DESTROY`.
   */
  fun createLifecycleObserver(): LifecycleEventObserver =
    LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_PAUSE,
        Lifecycle.Event.ON_STOP, -> pausePlayback()
        Lifecycle.Event.ON_DESTROY -> release()
        else -> Unit
      }
    }

  fun attachToLifecycle(lifecycle: Lifecycle) {
    if (boundLifecycle === lifecycle && lifecycleObserver != null) return
    detachLifecycle()
    val observer = createLifecycleObserver()
    lifecycle.addObserver(observer)
    boundLifecycle = lifecycle
    lifecycleObserver = observer
  }

  fun detachLifecycle() {
    val observer = lifecycleObserver
    if (observer != null) {
      runCatching { boundLifecycle?.removeObserver(observer) }
    }
    lifecycleObserver = null
    boundLifecycle = null
  }

  /**
   * Restores the compiled Media3 preview after a configuration change / rotation
   * if [exoPlayer] was released during `ON_DESTROY` while the ViewModel survived.
   */
  fun restoreIfNeeded(currentPlayheadMs: Long? = null) {
    if (exoPlayer != null) return
    val saved = lastCompiledParams ?: return
    val restoredTimeline =
      currentPlayheadMs?.let { pos ->
        saved.timeline.copy(playheadMs = pos.coerceAtLeast(0L))
      } ?: saved.timeline.copy(playheadMs = resumePositionMs.coerceAtLeast(0L))

    loadTimelinePreview(
      timeline = restoredTimeline,
      assets = saved.assets,
      projectRootDir = saved.projectRootDir,
      renderSettings = saved.renderSettings,
      deviceProfile = saved.deviceProfile,
    )
  }

  fun loadTimelinePreview(
    timeline: TimelineState,
    assets: List<MediaAsset>,
    projectRootDir: File,
    renderSettings: RenderSettings,
    deviceProfile: DeviceProfile = DeviceProfile(),
  ) {
    val isSameMediaRequest =
      lastCompiledParams?.assets == assets &&
        lastCompiledParams?.renderSettings?.useProxiesForPreview == renderSettings.useProxiesForPreview
    if (!isSameMediaRequest) {
      effectsDisabledFallback = false
    }

    lastCompiledParams =
      LastCompileRequest(
        timeline = timeline,
        assets = assets,
        projectRootDir = projectRootDir,
        renderSettings = renderSettings,
        deviceProfile = deviceProfile,
      )
    resumePositionMs = timeline.playheadMs.coerceAtLeast(0L)

    if (exoPlayer != null && lastProfileTier != deviceProfile.tier) {
      // Recreate ExoPlayer with updated memory buffer budget when DeviceProfile tier shifts.
      releasePlayerOnly()
    }

    val assetById = assets.associateBy { it.assetId }
    val videoTrack =
      timeline.tracksSorted().firstOrNull { it.type == TrackType.VIDEO && it.isVisible }
    val videoClips =
      videoTrack?.let { track ->
        timeline.clipsForTrack(track.trackId).filter { it.isEnabled && it.kind == ClipKind.VIDEO }
      }.orEmpty()

    if (videoClips.isEmpty()) {
      runCatching {
        exoPlayer?.stop()
        exoPlayer?.clearMediaItems()
      }
      currentSegments = emptyList()
      _state.value =
        PreviewPlayerState(
          isLoopEnabled = loopEnabledPreference,
          hasPlayableMedia = false,
          statusNote = "Sin clips de video activos en la línea de tiempo.",
        )
      return
    }

    val mediaItems = mutableListOf<MediaItem>()
    val segments = mutableListOf<TimelinePlaybackSegment>()
    var proxyCount = 0
    var hdrOrHighResCount = 0
    var missingFiles = 0
    var primaryRotation = 0
    var primaryWidth = 0
    var primaryHeight = 0

    for (clip in videoClips) {
      val asset = clip.assetId?.let { assetById[it] }
      if (asset == null) {
        missingFiles++
        continue
      }

      val resolved = resolveSafePreviewUri(asset, projectRootDir, renderSettings, deviceProfile)
      if (resolved == null) {
        missingFiles++
        continue
      }
      if (resolved.isProxy) proxyCount++
      if (resolved.isHdrOrHighResProtected) hdrOrHighResCount++
      if (segments.isEmpty()) {
        primaryRotation = asset.fingerprint.rotationDegrees
        val (orientedW, orientedH) = asset.fingerprint.orientedResolution()
        primaryWidth = orientedW ?: 0
        primaryHeight = orientedH ?: 0
      }

      val startMs = clip.sourceInMs.coerceAtLeast(0L)
      val endMs = clip.sourceOutMs.coerceAtLeast(startMs + 100L)
      val item =
        MediaItem.Builder()
          .setMediaId(clip.clipId)
          .setUri(resolved.uri)
          .setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder()
              .setStartPositionMs(startMs)
              .setEndPositionMs(endMs)
              .build(),
          )
          .build()

      val windowIdx = mediaItems.size
      mediaItems += item
      segments +=
        TimelinePlaybackSegment(
          clipId = clip.clipId,
          windowIndex = windowIdx,
          timelineStartMs = clip.timelineStartMs,
          timelineEndMs = clip.timelineEndMs,
          sourceInMs = startMs,
          speed = clip.speed,
          isHdrOrHighResProtected = resolved.isHdrOrHighResProtected,
        )
    }

    if (mediaItems.isEmpty()) {
      runCatching {
        exoPlayer?.stop()
        exoPlayer?.clearMediaItems()
      }
      currentSegments = emptyList()
      val error =
        if (missingFiles > 0) {
          MediaUiError(
            kind = MediaUiErrorKind.FILE_UNAVAILABLE,
            title = "Archivo multimedia no disponible",
            detail = "Uno o más clips vinculados no se encontraron en la carpeta portable.",
          )
        } else {
          null
        }
      _state.value =
        PreviewPlayerState(
          isLoopEnabled = loopEnabledPreference,
          hasPlayableMedia = false,
          statusNote = error?.detail ?: "No hay medios reproducibles en este rango.",
          uiError = error,
        )
      return
    }

    try {
      val player = getOrCreatePlayer(deviceProfile)
      currentSegments = segments
      val previewEffects =
        if (effectsDisabledFallback) {
          emptyList()
        } else {
          buildLivePreviewEffects(timeline, videoClips)
        }
      val effectsFailed =
        runCatching { player.setVideoEffects(previewEffects) }
          .onFailure {
            effectsDisabledFallback = true
            runCatching { player.setVideoEffects(emptyList()) }
          }
          .isFailure
      player.setMediaItems(mediaItems, /* resetPosition = */ true)
      player.prepare()
      seekInternal(player, timeline.playheadMs, useClosestSync = false)

      val notice =
        when {
          effectsDisabledFallback || effectsFailed ->
            PlaybackDiagnosticNotice(
              message = "Compatibilidad HDR/alta res: filtros GL desactivados en vista previa para proteger el decodificador.",
              isMemoryAdjusted = deviceProfile.tier == DeviceMemoryTier.CONSERVATIVE,
              isHdrProtected = true,
            )
          hdrOrHighResCount > 0 && proxyCount > 0 ->
            PlaybackDiagnosticNotice(
              message = "HDR/alta res protegido con proxy ligero (${deviceProfile.tier.label}).",
              isMemoryAdjusted = deviceProfile.tier == DeviceMemoryTier.CONSERVATIVE,
              isHdrProtected = true,
            )
          hdrOrHighResCount > 0 ->
            PlaybackDiagnosticNotice(
              message = "Clip HDR/4K detectado: reproducción protegida con fallback de decodificador.",
              isHdrProtected = true,
            )
          deviceProfile.tier == DeviceMemoryTier.CONSERVATIVE ->
            PlaybackDiagnosticNotice(
              message = "Búfer ajustado por memoria (${deviceProfile.availableRamMb} MB libres).",
              isMemoryAdjusted = true,
            )
          else -> null
        }

      val partialMissingError =
        if (missingFiles > 0) {
          MediaUiError(
            kind = MediaUiErrorKind.FILE_UNAVAILABLE,
            title = "$missingFiles clip(s) sin archivo local",
            detail = "Se omitieron los clips ausentes para evitar bloqueos.",
          )
        } else {
          null
        }

      _state.value =
        PreviewPlayerState(
          isPlaying = player.isPlaying,
          isLoopEnabled = loopEnabledPreference,
          hasPlayableMedia = true,
          activeProxyCount = proxyCount,
          videoRotationDegrees = primaryRotation,
          videoWidth = primaryWidth,
          videoHeight = primaryHeight,
          statusNote = null,
          diagnosticNotice = notice,
          uiError = partialMissingError,
        )
    } catch (t: Throwable) {
      _state.value =
        PreviewPlayerState(
          isLoopEnabled = loopEnabledPreference,
          hasPlayableMedia = false,
          statusNote = "No se pudo iniciar la vista previa para este clip.",
          uiError = mapThrowableToUiError(t),
        )
    }
  }

  private fun buildLivePreviewEffects(
    timeline: TimelineState,
    videoClips: List<TimelineClip>,
  ): List<Effect> {
    val activeClip =
      videoClips.firstOrNull { timeline.playheadMs in it.timelineStartMs until it.timelineEndMs }
        ?: videoClips.firstOrNull()
        ?: return emptyList()

    val adjustment =
      timeline.clips
        .firstOrNull { adj ->
          adj.isEnabled &&
            adj.kind == ClipKind.ADJUSTMENT &&
            adj.timelineStartMs < activeClip.timelineEndMs &&
            adj.timelineEndMs > activeClip.timelineStartMs
        }
        ?.adjustmentConfig

    val effects = mutableListOf<Effect>()
    activeClip.videoConfig?.let { cfg ->
      if (cfg.scale != 1.0f || cfg.rotationDegrees != 0.0f) {
        runCatching {
          effects.add(
            ScaleAndRotateTransformation.Builder()
              .setScale(cfg.scale.coerceIn(0.25f, 4.0f), cfg.scale.coerceIn(0.25f, 4.0f))
              .setRotationDegrees(cfg.rotationDegrees)
              .build(),
          )
        }
      }
      if (cfg.brightness != 0.0f) {
        runCatching { effects.add(Brightness(cfg.brightness.coerceIn(-1f, 1f))) }
      }
      if (cfg.contrast != 1.0f) {
        runCatching { effects.add(Contrast((cfg.contrast - 1.0f).coerceIn(-1f, 1f))) }
      }
      if (cfg.saturation != 1.0f) {
        val satShift = ((cfg.saturation - 1.0f) * 100f).coerceIn(-100f, 100f)
        runCatching { effects.add(HslAdjustment.Builder().adjustSaturation(satShift).build()) }
      }
    }
    adjustment?.let { adj ->
      if (adj.brightness != 0.0f) {
        runCatching { effects.add(Brightness(adj.brightness.coerceIn(-1f, 1f))) }
      }
      if (adj.contrast != 1.0f) {
        runCatching { effects.add(Contrast((adj.contrast - 1.0f).coerceIn(-1f, 1f))) }
      }
      if (adj.saturation != 1.0f) {
        val satShift = ((adj.saturation - 1.0f) * 100f).coerceIn(-100f, 100f)
        runCatching { effects.add(HslAdjustment.Builder().adjustSaturation(satShift).build()) }
      }
    }
    return effects
  }

  fun beginScrubbing() {
    val player = exoPlayer ?: return
    if (_state.value.isScrubbing) return
    runCatching {
      if (player.isPlaying) player.pause()
      player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
    }.onFailure { err ->
      _state.value = _state.value.copy(uiError = mapThrowableToUiError(err))
      return
    }
    lastScrubDispatchMs = Long.MIN_VALUE
    lastScrubWallClockMs = 0L
    _state.value = _state.value.copy(isScrubbing = true, isPlaying = false)
  }

  fun scrubTo(timelinePositionMs: Long) {
    val player = exoPlayer ?: return
    if (!_state.value.isScrubbing) {
      beginScrubbing()
    }
    val profile = lastCompiledParams?.deviceProfile ?: DeviceProfile()
    val minIntervalMs =
      when (profile.tier) {
        DeviceMemoryTier.CONSERVATIVE -> 48L
        DeviceMemoryTier.BALANCED -> 24L
        DeviceMemoryTier.PERFORMANCE -> 12L
      }
    val now = System.currentTimeMillis()
    val clamped = timelinePositionMs.coerceAtLeast(0L)
    if (
      lastScrubDispatchMs != Long.MIN_VALUE &&
      abs(clamped - lastScrubDispatchMs) < 350L &&
      (now - lastScrubWallClockMs) < minIntervalMs
    ) {
      return
    }
    lastScrubDispatchMs = clamped
    lastScrubWallClockMs = now
    resumePositionMs = clamped
    runCatching {
      seekInternal(player, clamped, useClosestSync = true)
    }.onFailure { err ->
      _state.value = _state.value.copy(uiError = mapThrowableToUiError(err))
    }
  }

  fun endScrubbing(finalTimelinePositionMs: Long) {
    val player = exoPlayer ?: return
    val clamped = finalTimelinePositionMs.coerceAtLeast(0L)
    lastScrubDispatchMs = Long.MIN_VALUE
    resumePositionMs = clamped
    runCatching {
      player.setSeekParameters(SeekParameters.EXACT)
      seekInternal(player, clamped, useClosestSync = false)
    }.onFailure { err ->
      _state.value = _state.value.copy(uiError = mapThrowableToUiError(err))
    }
    _state.value = _state.value.copy(isScrubbing = false)
  }

  fun seekTo(timelinePositionMs: Long) {
    val player = exoPlayer ?: return
    val clamped = timelinePositionMs.coerceAtLeast(0L)
    resumePositionMs = clamped
    runCatching {
      seekInternal(player, clamped, useClosestSync = _state.value.isScrubbing)
    }.onFailure { err ->
      _state.value = _state.value.copy(uiError = mapThrowableToUiError(err))
    }
  }

  fun togglePlayPause() {
    val player = exoPlayer ?: return
    if (!_state.value.hasPlayableMedia) return
    runCatching {
      if (player.playbackState == Player.STATE_ENDED) {
        player.seekTo(0, 0L)
      }
      if (player.isPlaying) {
        player.pause()
      } else {
        player.setSeekParameters(SeekParameters.DEFAULT)
        player.play()
      }
    }.onFailure { err ->
      _state.value = _state.value.copy(isPlaying = false, uiError = mapThrowableToUiError(err))
    }
  }

  fun setLoopEnabled(enabled: Boolean) {
    loopEnabledPreference = enabled
    runCatching {
      exoPlayer?.repeatMode =
        if (enabled) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
    }
    _state.value = _state.value.copy(isLoopEnabled = enabled)
  }

  fun toggleLoop(): Boolean {
    val next = !loopEnabledPreference
    setLoopEnabled(next)
    return next
  }

  fun pausePlayback() {
    exoPlayer?.let { player ->
      runCatching {
        resumePositionMs = currentPositionMs()
        if (player.isPlaying) {
          player.pause()
        }
      }
    }
    _state.value = _state.value.copy(isPlaying = false, isScrubbing = false)
  }

  fun retryLastPreview() {
    val request = lastCompiledParams ?: return
    effectsDisabledFallback = false
    _state.value = _state.value.copy(uiError = null)
    loadTimelinePreview(
      timeline = request.timeline,
      assets = request.assets,
      projectRootDir = request.projectRootDir,
      renderSettings = request.renderSettings,
      deviceProfile = request.deviceProfile,
    )
  }

  fun clearError() {
    _state.value = _state.value.copy(uiError = null)
  }

  fun currentPositionMs(): Long {
    val player = exoPlayer ?: return resumePositionMs
    return runCatching {
      val seg = currentSegments.getOrNull(player.currentMediaItemIndex)
      if (seg == null) {
        player.currentPosition.coerceAtLeast(0L)
      } else {
        val timelineOffset = (player.currentPosition.coerceAtLeast(0L) / seg.speed).roundToLong()
        (seg.timelineStartMs + timelineOffset).coerceAtMost(seg.timelineEndMs)
      }
    }.getOrDefault(resumePositionMs)
  }

  /**
   * Releases ExoPlayer and hardware decoders on `ON_DESTROY` while keeping compiled metadata
   * so configuration changes / rotation can restore preview position seamlessly.
   */
  fun release() {
    resumePositionMs = currentPositionMs()
    detachLifecycle()
    releasePlayerOnly()
    currentSegments = emptyList()
    _state.value = PreviewPlayerState(isLoopEnabled = loopEnabledPreference)
  }

  /**
   * Permanent cleanup invoked when the owning [androidx.lifecycle.ViewModel] is cleared.
   */
  fun disposePermanently() {
    lastCompiledParams = null
    resumePositionMs = 0L
    loopEnabledPreference = false
    effectsDisabledFallback = false
    release()
  }

  private fun releasePlayerOnly() {
    runCatching {
      exoPlayer?.stop()
      exoPlayer?.clearVideoSurface()
      exoPlayer?.release()
    }
    exoPlayer = null
  }

  private fun seekInternal(
    player: ExoPlayer,
    timelinePositionMs: Long,
    useClosestSync: Boolean,
  ) {
    val clamped = timelinePositionMs.coerceAtLeast(0L)
    val segments = currentSegments
    if (segments.isEmpty()) {
      player.seekTo(clamped)
      return
    }
    val match =
      segments.firstOrNull { clamped in it.timelineStartMs until it.timelineEndMs }
        ?: segments.lastOrNull { clamped >= it.timelineStartMs }
        ?: segments.first()

    val offsetInClipMs =
      (clamped - match.timelineStartMs).coerceIn(0L, (match.timelineEndMs - match.timelineStartMs).coerceAtLeast(0L))
    val targetWindowPos = (offsetInClipMs * match.speed).toLong().coerceAtLeast(0L)
    if (useClosestSync) {
      player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
    }
    player.seekTo(match.windowIndex, targetWindowPos)
  }

  private fun handlePlaybackException(error: PlaybackException) {
    val uiError = mapPlaybackExceptionToUiError(error)
    val currentReq = lastCompiledParams

    // Fallback 1: If GL VideoFrameProcessor failed (common on HDR / 10-bit surfaces), retry without GL effects
    if (
      uiError.kind == MediaUiErrorKind.HDR_COMPATIBILITY_FALLBACK &&
      !effectsDisabledFallback &&
      currentReq != null
    ) {
      effectsDisabledFallback = true
      loadTimelinePreview(
        timeline = currentReq.timeline,
        assets = currentReq.assets,
        projectRootDir = currentReq.projectRootDir,
        renderSettings = currentReq.renderSettings,
        deviceProfile = currentReq.deviceProfile,
      )
      return
    }

    // Fallback 2: If HDR/high-res or decoder failed and proxies aren't forced yet, retry with proxy fallback
    if (
      uiError.kind == MediaUiErrorKind.UNSUPPORTED_FORMAT_OR_DECODER &&
      currentReq != null &&
      !currentReq.renderSettings.useProxiesForPreview
    ) {
      effectsDisabledFallback = true
      loadTimelinePreview(
        timeline = currentReq.timeline,
        assets = currentReq.assets,
        projectRootDir = currentReq.projectRootDir,
        renderSettings = currentReq.renderSettings.copy(useProxiesForPreview = true),
        deviceProfile = currentReq.deviceProfile,
      )
      _state.value =
        _state.value.copy(
          diagnosticNotice =
            PlaybackDiagnosticNotice(
              message = "Decodificador saturado: cambiado automáticamente a modo proxy compatible.",
              isMemoryAdjusted = true,
              isHdrProtected = true,
            ),
        )
      return
    }

    _state.value =
      _state.value.copy(
        isPlaying = false,
        isScrubbing = false,
        statusNote = uiError.detail,
        uiError = uiError,
      )
  }

  private data class ResolvedPreviewSource(
    val uri: Uri,
    val isProxy: Boolean,
    val isHdrOrHighResProtected: Boolean,
  )

  private fun resolveSafePreviewUri(
    asset: MediaAsset,
    projectRootDir: File,
    renderSettings: RenderSettings,
    deviceProfile: DeviceProfile,
  ): ResolvedPreviewSource? {
    val shouldPreferProxy =
      renderSettings.useProxiesForPreview ||
        (asset.fingerprint.isHdr && deviceProfile.tier != DeviceMemoryTier.PERFORMANCE) ||
        (asset.fingerprint.isHighResolution && deviceProfile.tier == DeviceMemoryTier.CONSERVATIVE)

    val isProtected = asset.fingerprint.isHdr || asset.fingerprint.isHighResolution

    if (shouldPreferProxy && !asset.proxyRelativePath.isNullOrBlank()) {
      val proxyFile = File(projectRootDir, asset.proxyRelativePath)
      if (proxyFile.exists() && proxyFile.length() > 0L) {
        return ResolvedPreviewSource(
          uri = Uri.fromFile(proxyFile),
          isProxy = true,
          isHdrOrHighResProtected = isProtected,
        )
      }
    }

    val localFile = File(projectRootDir, asset.relativePath)
    if (localFile.exists() && localFile.length() > 0L) {
      return ResolvedPreviewSource(
        uri = Uri.fromFile(localFile),
        isProxy = false,
        isHdrOrHighResProtected = isProtected,
      )
    }

    val extUri = asset.externalSourceUri
    if (!extUri.isNullOrBlank()) {
      return ResolvedPreviewSource(
        uri = Uri.parse(extUri),
        isProxy = false,
        isHdrOrHighResProtected = isProtected,
      )
    }
    return null
  }

  companion object {
    fun buildLoadControlForProfile(profile: DeviceProfile): DefaultLoadControl {
      val (minBufferMs, maxBufferMs) =
        when (profile.tier) {
          DeviceMemoryTier.CONSERVATIVE -> 1_500 to 4_000
          DeviceMemoryTier.BALANCED -> 2_500 to 8_000
          DeviceMemoryTier.PERFORMANCE -> 5_000 to 15_000
        }
      return DefaultLoadControl.Builder()
        .setBufferDurationsMs(
          minBufferMs,
          maxBufferMs,
          /* bufferForPlaybackMs = */ 500,
          /* bufferForPlaybackAfterRebufferMs = */ 1_000,
        )
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()
    }

    fun mapPlaybackExceptionToUiError(error: PlaybackException): MediaUiError =
      mapPlaybackErrorCodeToUiError(
        errorCode = error.errorCode,
        errorCodeName = error.errorCodeName,
        cause = error.cause,
      )

    fun mapPlaybackErrorCodeToUiError(
      errorCode: Int,
      errorCodeName: String = "",
      cause: Throwable? = null,
    ): MediaUiError {
      if (cause is OutOfMemoryError) {
        return MediaUiError(
          kind = MediaUiErrorKind.MEMORY_PRESSURE_ADJUSTED,
          title = "Límite de memoria alcanzado",
          detail = "Reduce la calidad de vista previa a 360p o genera proxies para liberar RAM.",
        )
      }
      return when (errorCode) {
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE, ->
          MediaUiError(
            kind = MediaUiErrorKind.FILE_UNAVAILABLE,
            title = "Archivo multimedia inaccesible",
            detail =
              if (errorCodeName.isNotBlank()) {
                "No se puede leer el clip desde almacenamiento ($errorCodeName)."
              } else {
                "No se puede leer el clip desde almacenamiento."
              },
          )

        PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED,
        PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED, ->
          MediaUiError(
            kind = MediaUiErrorKind.HDR_COMPATIBILITY_FALLBACK,
            title = "Compatibilidad HDR / GPU",
            detail = "El procesador de video no toleró los efectos sobre este formato; usa modo proxy si persiste.",
          )

        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES, ->
          MediaUiError(
            kind = MediaUiErrorKind.UNSUPPORTED_FORMAT_OR_DECODER,
            title = "Formato o decodificador no soportado",
            detail = "El decodificador de hardware rechazó este clip (prueba generar proxies 540p).",
          )

        else ->
          MediaUiError(
            kind = MediaUiErrorKind.PLAYBACK_GENERAL,
            title = "Error de reproducción",
            detail = "No se pudo reproducir el video seleccionado.",
          )
      }
    }

    fun mapThrowableToUiError(t: Throwable): MediaUiError =
      when (t) {
        is OutOfMemoryError ->
          MediaUiError(
            kind = MediaUiErrorKind.MEMORY_PRESSURE_ADJUSTED,
            title = "Límite de memoria alcanzado",
            detail = "Reduce la calidad de vista previa a 360p o genera proxies para liberar RAM.",
          )
        is SecurityException ->
          MediaUiError(
            kind = MediaUiErrorKind.FILE_UNAVAILABLE,
            title = "Acceso al archivo denegado",
            detail = "Vuelve a seleccionar el video desde Medios para renovar el acceso.",
          )
        else ->
          MediaUiError(
            kind = MediaUiErrorKind.PLAYBACK_GENERAL,
            title = "Fallo al preparar vista previa",
            detail = "No se pudo preparar la vista previa del video seleccionado.",
          )
      }
  }
}
