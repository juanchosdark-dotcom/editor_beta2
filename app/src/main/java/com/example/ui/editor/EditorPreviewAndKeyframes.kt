package com.example.ui.editor

import androidx.annotation.OptIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.R
import com.example.core.ai.api.DeviceProfile
import com.example.core.media.playback.PreviewPlayerController
import com.example.core.media.playback.PreviewPlayerState
import com.example.core.model.BuiltInLutPresets
import com.example.core.model.TimelineClip
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
@Composable
internal fun Media3PreviewPlayerCard(
  previewController: PreviewPlayerController?,
  previewState: PreviewPlayerState,
  deviceProfile: DeviceProfile,
  activeVideoClip: TimelineClip?,
  activeAdjustmentClip: TimelineClip?,
  activeSubtitleText: String?,
  playheadMs: Long,
  onRetryPreview: () -> Unit,
  onDismissError: () -> Unit,
  onOpenMediaPicker: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val isInspection = LocalInspectionMode.current
  val lifecycleOwner = LocalLifecycleOwner.current

  // Pause immediately on route change or ON_PAUSE/ON_STOP and restore cleanly after rotation.
  DisposableEffect(lifecycleOwner, previewController) {
    val observer =
      LifecycleEventObserver { _, event ->
        when (event) {
          Lifecycle.Event.ON_PAUSE,
          Lifecycle.Event.ON_STOP, -> previewController?.pausePlayback()
          else -> Unit
        }
      }
    lifecycleOwner.lifecycle.addObserver(observer)
    previewController?.restoreIfNeeded(playheadMs)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
      previewController?.pausePlayback()
    }
  }

  val videoCfg = activeVideoClip?.videoConfig
  val adjCfg = activeAdjustmentClip?.adjustmentConfig
  val offsetInClip =
    if (activeVideoClip != null) {
      (playheadMs - activeVideoClip.timelineStartMs).coerceIn(0L, activeVideoClip.durationMs)
    } else {
      0L
    }
  val transform = videoCfg?.evaluateTransformAt(offsetInClip)
  val videoGrade = videoCfg?.let { BuiltInLutPresets.resolveEffectiveColor(it) }
  val adjGrade = adjCfg?.let { BuiltInLutPresets.resolveEffectiveColor(it) }
  val brightness = ((videoGrade?.brightness ?: 0f) + (adjGrade?.brightness ?: 0f) + ((videoGrade?.exposure ?: 0f) + (adjGrade?.exposure ?: 0f)) * 0.3f).coerceIn(-0.8f, 0.8f)
  val contrast = ((videoGrade?.contrast ?: 1f) * (adjGrade?.contrast ?: 1f)).coerceIn(0.4f, 2.0f)
  val saturation = ((videoGrade?.saturation ?: 1f) * (adjGrade?.saturation ?: 1f)).coerceIn(0.0f, 2.2f)
  val temperature = ((videoGrade?.temperature ?: 0f) + (adjGrade?.temperature ?: 0f)).coerceIn(-1f, 1f)
  val activeLutTint = adjGrade?.activeLut ?: videoGrade?.activeLut

  Column(
    modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Surface(
      color = MaterialTheme.colorScheme.surfaceContainerLowest,
      shape = RoundedCornerShape(10.dp),
      border =
        BorderStroke(
          width = 1.dp,
          color =
            if (previewState.uiError != null) {
              MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
            } else {
              MaterialTheme.colorScheme.outline
            },
        ),
      modifier = Modifier.fillMaxWidth(),
    ) {
      Box(
        modifier =
          Modifier
            .fillMaxWidth()
            .height(166.dp)
            .padding(2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest),
      ) {
        if (!isInspection && previewController != null && previewState.hasPlayableMedia) {
          AndroidView(
            factory = { context ->
              PlayerView(context).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setKeepContentOnPlayerReset(true)
                player = previewController.getOrCreatePlayer(deviceProfile)
              }
            },
            update = { view ->
              val targetPlayer = previewController.getOrCreatePlayer(deviceProfile)
              if (view.player !== targetPlayer) {
                view.player = targetPlayer
              }
            },
            onRelease = { view -> runCatching { view.player = null } },
            modifier = Modifier.fillMaxSize().testTag("media3_player_view"),
          )
        } else {
          // Precision offline/standby viewer monitor preserving transform & color preview
          val demoScale = (transform?.scale ?: 1f).coerceIn(0.5f, 2.0f)
          val demoRotation = transform?.rotationDegrees ?: 0f
          val demoAlpha = (transform?.opacity ?: 1f).coerceIn(0.1f, 1.0f)
          val lumTint =
            if (brightness >= 0f) {
              Color.White.copy(alpha = (brightness * 0.45f).coerceIn(0f, 0.35f))
            } else {
              Color.Black.copy(alpha = (-brightness * 0.65f).coerceIn(0f, 0.5f))
            }

          Box(
            modifier =
              Modifier
                .fillMaxSize()
                .padding(8.dp)
                .graphicsLayer {
                  scaleX = demoScale
                  scaleY = demoScale
                  rotationZ = demoRotation
                  alpha = demoAlpha
                }
                .clip(RoundedCornerShape(6.dp))
                .background(
                  Brush.linearGradient(
                    listOf(
                      activeLutTint?.let { Color(it.shadowColorArgb).copy(alpha = 0.85f) }
                        ?: MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                      activeLutTint?.let { Color(it.midtoneColorArgb).copy(alpha = (0.28f * saturation).coerceIn(0.08f, 0.65f)) }
                        ?: MaterialTheme.colorScheme.secondary.copy(alpha = (0.10f * saturation - temperature * 0.06f).coerceIn(0.03f, 0.36f)),
                      activeLutTint?.let { Color(it.highlightColorArgb).copy(alpha = (0.22f * contrast).coerceIn(0.08f, 0.55f)) }
                        ?: MaterialTheme.colorScheme.primary.copy(alpha = (0.12f * contrast + temperature * 0.08f).coerceIn(0.04f, 0.38f)),
                    ),
                  ),
                )
                .background(lumTint),
            contentAlignment = Alignment.Center,
          ) {
            Surface(
              color = Color.Transparent,
              shape = RoundedCornerShape(6.dp),
              border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
              modifier = Modifier.fillMaxSize(),
            ) {
              Box(modifier = Modifier.fillMaxSize()) {
                Text(
                  text = stringResource(R.string.label_viewer_no_signal),
                  style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                  color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                  modifier = Modifier.align(Alignment.TopStart).padding(horizontal = 8.dp, vertical = 6.dp),
                )

                Column(
                  modifier = Modifier.align(Alignment.Center).padding(horizontal = 16.dp),
                  horizontalAlignment = Alignment.CenterHorizontally,
                  verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                  Icon(
                    imageVector = Icons.Default.Videocam,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(24.dp),
                  )
                  Text(
                    text = previewState.statusNote ?: stringResource(R.string.label_no_preview_media),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                  )
                  AssistChip(
                    onClick = onOpenMediaPicker,
                    leadingIcon = {
                      Icon(
                        imageVector = Icons.Default.AddPhotoAlternate,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                      )
                    },
                    label = { Text(stringResource(R.string.nav_media)) },
                    modifier = Modifier.testTag("preview_open_media_chip"),
                  )
                }
              }
            }
          }
        }

        if (!activeSubtitleText.isNullOrBlank()) {
          Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.84f),
            shape = RoundedCornerShape(4.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier =
              Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 10.dp, start = 16.dp, end = 16.dp),
          ) {
            Text(
              text = activeSubtitleText,
              style = MaterialTheme.typography.labelMedium,
              fontWeight = FontWeight.Medium,
              color = MaterialTheme.colorScheme.onSurface,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
          }
        }
      }
    }

    previewState.diagnosticNotice?.let { notice ->
      Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().testTag("preview_diagnostic_notice"),
      ) {
        Text(
          text = notice.message,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.secondary,
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
      }
    }

    previewState.uiError?.let { uiError ->
      Surface(
        color = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.7f)),
        modifier = Modifier.fillMaxWidth().testTag("preview_error_banner"),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Icon(
            imageVector = Icons.Default.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp),
          )
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = uiError.title,
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.error,
            )
            Text(
              text = uiError.detail,
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
            )
          }
          if (uiError.isRecoverable) {
            TextButton(onClick = onRetryPreview) {
              Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(2.dp))
              Text("Reintentar")
            }
          }
          IconButton(onClick = onDismissError, modifier = Modifier.minimumInteractiveComponentSize()) {
            Icon(imageVector = Icons.Default.Close, contentDescription = "Ocultar error", modifier = Modifier.size(16.dp))
          }
        }
      }
    }
  }
}

@kotlin.OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun KeyframeInspectorStrip(
  selectedClip: TimelineClip,
  playheadMs: Long,
  onAddKeyframe: () -> Unit,
  onSeekToMs: (Long) -> Unit,
  onRemoveKeyframe: (String) -> Unit,
  onBeginEdit: () -> Unit,
  onUpdateTransform: (scale: Float, opacity: Float, rotationDegrees: Float, fromSliderDrag: Boolean) -> Unit,
  onEndEdit: () -> Unit,
  onClose: () -> Unit,
) {
  val videoCfg = selectedClip.videoConfig ?: return
  val offsetMs = (playheadMs - selectedClip.timelineStartMs).coerceIn(0L, selectedClip.durationMs)
  val currentTransform = videoCfg.evaluateTransformAt(offsetMs)

  Surface(
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 12.dp, bottomEnd = 12.dp),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("keyframes_subpanel"),
  ) {
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          text =
            "${stringResource(R.string.editor_keyframes_title)} · Escala ${"%.2f".format(currentTransform.scale)}x · Opac ${(currentTransform.opacity * 100f).roundToInt()}%",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.weight(1f),
        )
        FilledTonalButton(
          onClick = onAddKeyframe,
          modifier = Modifier.minimumInteractiveComponentSize().testTag("add_keyframe_button"),
        ) {
          Icon(imageVector = Icons.Default.Diamond, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(4.dp))
          Text(stringResource(R.string.action_add_keyframe))
        }
        IconButton(
          onClick = onClose,
          modifier = Modifier.minimumInteractiveComponentSize().testTag("close_subpanel_button"),
        ) {
          Icon(imageVector = Icons.Default.Close, contentDescription = stringResource(R.string.cd_close_subpanel))
        }
      }

      if (videoCfg.keyframes.isNotEmpty()) {
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          modifier = Modifier.fillMaxWidth(),
        ) {
          videoCfg.keyframes.forEach { kf ->
            InputChip(
              selected = abs(kf.offsetMs - offsetMs) <= 40L,
              onClick = { onSeekToMs(selectedClip.timelineStartMs + kf.offsetMs) },
              label = { Text("${formatTimecode(kf.offsetMs)} (${"%.1f".format(kf.scale)}x)") },
              trailingIcon = {
                Icon(
                  imageVector = Icons.Default.Close,
                  contentDescription = "Quitar keyframe",
                  modifier =
                    Modifier
                      .size(16.dp)
                      .testTag("remove_kf_${kf.keyframeId}"),
                )
              },
              modifier = Modifier.testTag("keyframe_chip_${kf.keyframeId}"),
            )
          }
        }
        videoCfg.keyframes.find { abs(it.offsetMs - offsetMs) <= 40L }?.let { activeKf ->
          AssistChip(
            onClick = { onRemoveKeyframe(activeKf.keyframeId) },
            label = { Text("Eliminar keyframe en ${formatTimecode(activeKf.offsetMs)}") },
            modifier = Modifier.testTag("delete_active_keyframe_chip"),
          )
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text("Escala", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          Slider(
            value = currentTransform.scale.coerceIn(0.5f, 2.0f),
            onValueChange = { nextScale ->
              onBeginEdit()
              onUpdateTransform(nextScale, currentTransform.opacity, currentTransform.rotationDegrees, true)
            },
            onValueChangeFinished = onEndEdit,
            valueRange = 0.5f..2.0f,
            modifier = Modifier.testTag("keyframe_scale_slider"),
          )
        }
        Column(modifier = Modifier.weight(1f)) {
          Text("Opacidad", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          Slider(
            value = currentTransform.opacity.coerceIn(0.1f, 1.0f),
            onValueChange = { nextOpacity ->
              onBeginEdit()
              onUpdateTransform(currentTransform.scale, nextOpacity, currentTransform.rotationDegrees, true)
            },
            onValueChangeFinished = onEndEdit,
            valueRange = 0.1f..1.0f,
            modifier = Modifier.testTag("keyframe_opacity_slider"),
          )
        }
      }
    }
  }
}
