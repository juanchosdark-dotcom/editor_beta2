package com.example.core.project

import com.example.core.model.AudioDuckingConfig
import com.example.core.model.BuiltInSubtitleStyles
import com.example.core.model.ClipKind
import com.example.core.model.ProjectDocument
import com.example.core.model.TimelineClip
import com.example.core.model.TimelineSequence
import com.example.core.model.TimelineSnapshot
import com.example.core.model.TimelineState
import com.example.core.model.Track
import com.example.core.model.TrackType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectJsonSerializerTest {

  @Test
  fun encodeDecode_preservesSnapshotsSequencesDuckingAndSubtitleStyles() {
    val serializer = ProjectJsonSerializer()
    val timeline =
      TimelineState(
        playheadMs = 1_200L,
        tracks = listOf(Track("t_vid", TrackType.VIDEO, 0, "Video")),
        clips =
          listOf(
            TimelineClip("c1", "t_vid", ClipKind.VIDEO, "Intro", 0L, 3_000L, 0L, 3_000L),
          ),
        duckingConfig = AudioDuckingConfig(enabled = true, attenuationFactor = 0.3f),
      )

    val doc =
      ProjectDocument(
        projectId = "p_1",
        name = "Portable Test",
        createdAtIso = "2026-11-23T10:00:00Z",
        updatedAtIso = "2026-11-23T10:20:00Z",
        timeline = timeline,
        activeSequenceId = "seq_main",
        sequences =
          listOf(TimelineSequence("seq_main", "Principal", "2026-11-23T10:00:00Z", timeline)),
        snapshots =
          listOf(TimelineSnapshot("snap_1", "Checkpoint 1", "2026-11-23T10:15:00Z", "", timeline)),
        savedSubtitleStyles = listOf(BuiltInSubtitleStyles.HIGH_CONTRAST_AMBER),
      )

    val json = serializer.encode(doc)
    val decoded = serializer.decode(json)

    assertTrue(decoded.isSuccess)
    val value = decoded.getOrThrow()
    assertEquals(doc.projectId, value.projectId)
    assertEquals(1, value.snapshots.size)
    assertEquals(1, value.sequences.size)
    assertTrue(value.timeline.duckingConfig.enabled)
    assertEquals(0.3f, value.timeline.duckingConfig.attenuationFactor, 0.001f)
    assertEquals(1, value.savedSubtitleStyles.size)
  }
}
