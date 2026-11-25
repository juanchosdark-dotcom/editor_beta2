package com.example.core.model

import com.squareup.moshi.JsonClass

/**
 * Built-in DaVinci Resolve-style color look / LUT preset with preview swatch palette
 * and parametric grading contributions scaled by [lutStrength].
 */
@JsonClass(generateAdapter = true)
data class LutPreset(
  val id: String,
  val name: String,
  val shortCode: String,
  val category: String,
  val brightness: Float = 0.0f,
  val contrast: Float = 1.0f,
  val saturation: Float = 1.0f,
  val exposure: Float = 0.0f,
  val temperature: Float = 0.0f,
  val vignette: Float = 0.0f,
  val shadowColorArgb: Long,
  val midtoneColorArgb: Long,
  val highlightColorArgb: Long,
)

data class EffectiveColorGrading(
  val brightness: Float = 0.0f,
  val contrast: Float = 1.0f,
  val saturation: Float = 1.0f,
  val exposure: Float = 0.0f,
  val temperature: Float = 0.0f,
  val vignette: Float = 0.0f,
  val activeLut: LutPreset? = null,
  val lutStrength: Float = 1.0f,
) {
  val hasColorModifications: Boolean
    get() =
      kotlin.math.abs(brightness) > 0.01f ||
        kotlin.math.abs(contrast - 1.0f) > 0.01f ||
        kotlin.math.abs(saturation - 1.0f) > 0.01f ||
        kotlin.math.abs(exposure) > 0.01f ||
        kotlin.math.abs(temperature) > 0.01f ||
        vignette > 0.01f ||
        (activeLut != null && activeLut.id != BuiltInLutPresets.ID_NONE && lutStrength > 0.01f)
}

object BuiltInLutPresets {
  const val ID_NONE = "none"

  val NONE =
    LutPreset(
      id = ID_NONE,
      name = "Original",
      shortCode = "ORIG",
      category = "Neutro",
      shadowColorArgb = 0xFF1E2430L,
      midtoneColorArgb = 0xFF475569L,
      highlightColorArgb = 0xFFCBD5E1L,
    )

  val REC709_CLEAN =
    LutPreset(
      id = "resolve_rec709",
      name = "Rec.709",
      shortCode = "709",
      category = "Técnico",
      brightness = 0.01f,
      contrast = 1.14f,
      saturation = 1.08f,
      exposure = 0.05f,
      temperature = 0.0f,
      vignette = 0.05f,
      shadowColorArgb = 0xFF0F172AL,
      midtoneColorArgb = 0xFF334155L,
      highlightColorArgb = 0xFFF1F5F9L,
    )

  val TEAL_ORANGE =
    LutPreset(
      id = "teal_orange",
      name = "Teal & Orange",
      shortCode = "T&O",
      category = "Cine",
      brightness = -0.02f,
      contrast = 1.20f,
      saturation = 1.18f,
      exposure = 0.04f,
      temperature = 0.24f,
      vignette = 0.20f,
      shadowColorArgb = 0xFF0D4F5CL,
      midtoneColorArgb = 0xFF1E7884L,
      highlightColorArgb = 0xFFF59E42L,
    )

  val FILM_2383 =
    LutPreset(
      id = "kodak_2383",
      name = "Film 2383",
      shortCode = "2383",
      category = "Fílmico",
      brightness = -0.02f,
      contrast = 1.24f,
      saturation = 1.12f,
      exposure = -0.04f,
      temperature = 0.14f,
      vignette = 0.25f,
      shadowColorArgb = 0xFF28191DL,
      midtoneColorArgb = 0xFF9E4737L,
      highlightColorArgb = 0xFFF3C677L,
    )

  val BLEACH_NOIR =
    LutPreset(
      id = "bleach_noir",
      name = "Silver Noir",
      shortCode = "NOIR",
      category = "Drama",
      brightness = -0.04f,
      contrast = 1.36f,
      saturation = 0.48f,
      exposure = -0.02f,
      temperature = -0.08f,
      vignette = 0.34f,
      shadowColorArgb = 0xFF0B0E14L,
      midtoneColorArgb = 0xFF4B5563L,
      highlightColorArgb = 0xFFE5E7EBL,
    )

  val ARCTIC_COOL =
    LutPreset(
      id = "arctic_cool",
      name = "Arctic Cool",
      shortCode = "COOL",
      category = "Atmósfera",
      brightness = 0.03f,
      contrast = 1.10f,
      saturation = 0.90f,
      exposure = 0.06f,
      temperature = -0.34f,
      vignette = 0.12f,
      shadowColorArgb = 0xFF11203AL,
      midtoneColorArgb = 0xFF2563EBL,
      highlightColorArgb = 0xFFBAE6FDL,
    )

  val GOLDEN_HOUR =
    LutPreset(
      id = "golden_hour",
      name = "Golden Hour",
      shortCode = "GOLD",
      category = "Cálido",
      brightness = 0.05f,
      contrast = 1.10f,
      saturation = 1.24f,
      exposure = 0.08f,
      temperature = 0.38f,
      vignette = 0.14f,
      shadowColorArgb = 0xFF3F1D14L,
      midtoneColorArgb = 0xFFD97706L,
      highlightColorArgb = 0xFFFDE68AL,
    )

  val ALL: List<LutPreset> =
    listOf(
      NONE,
      REC709_CLEAN,
      TEAL_ORANGE,
      FILM_2383,
      BLEACH_NOIR,
      ARCTIC_COOL,
      GOLDEN_HOUR,
    )

  fun findById(id: String?): LutPreset? {
    if (id.isNullOrBlank()) return null
    return ALL.find { it.id == id }
  }

  fun resolveEffectiveColor(
    brightness: Float,
    contrast: Float,
    saturation: Float,
    exposure: Float,
    temperature: Float,
    vignette: Float,
    lutPresetId: String?,
    lutStrength: Float,
  ): EffectiveColorGrading {
    val preset = findById(lutPresetId)?.takeIf { it.id != ID_NONE }
    val strength = if (preset != null) lutStrength.coerceIn(0f, 1f) else 0f

    val lutBrightness = if (preset != null) preset.brightness * strength else 0f
    val lutContrastDelta = if (preset != null) (preset.contrast - 1.0f) * strength else 0f
    val lutSaturationDelta = if (preset != null) (preset.saturation - 1.0f) * strength else 0f
    val lutExposure = if (preset != null) preset.exposure * strength else 0f
    val lutTemperature = if (preset != null) preset.temperature * strength else 0f
    val lutVignette = if (preset != null) preset.vignette * strength else 0f

    return EffectiveColorGrading(
      brightness = (brightness + lutBrightness).coerceIn(-0.8f, 0.8f),
      contrast = (contrast + lutContrastDelta).coerceIn(0.3f, 2.0f),
      saturation = (saturation + lutSaturationDelta).coerceIn(0.0f, 2.2f),
      exposure = (exposure + lutExposure).coerceIn(-1.2f, 1.2f),
      temperature = (temperature + lutTemperature).coerceIn(-1.0f, 1.0f),
      vignette = (vignette + lutVignette).coerceIn(0.0f, 1.0f),
      activeLut = preset,
      lutStrength = strength,
    )
  }

  fun resolveEffectiveColor(cfg: VideoClipConfig): EffectiveColorGrading =
    resolveEffectiveColor(
      brightness = cfg.brightness,
      contrast = cfg.contrast,
      saturation = cfg.saturation,
      exposure = cfg.exposure,
      temperature = cfg.temperature,
      vignette = cfg.vignette,
      lutPresetId = cfg.lutPresetId,
      lutStrength = cfg.lutStrength,
    )

  fun resolveEffectiveColor(cfg: AdjustmentClipConfig): EffectiveColorGrading =
    resolveEffectiveColor(
      brightness = cfg.brightness,
      contrast = cfg.contrast,
      saturation = cfg.saturation,
      exposure = cfg.exposure,
      temperature = cfg.temperature,
      vignette = cfg.vignette,
      lutPresetId = cfg.lutPresetId,
      lutStrength = cfg.lutStrength,
    )
}
