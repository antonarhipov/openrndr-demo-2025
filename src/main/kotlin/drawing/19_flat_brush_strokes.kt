package drawing

import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.*
import org.openrndr.extra.shapes.hobbyCurve
import org.openrndr.math.Vector2
import org.openrndr.math.smoothstep
import org.openrndr.shape.Rectangle
import org.openrndr.shape.ShapeContour
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.*
import kotlin.random.Random

// ==========================================
// Wide Flat Brush Strokes (平筆 - Hira-fude)
// ==========================================
// Recreates wide, flat brush strokes (like a 1–2" hake / flat shader) with:
// - Bristle streak texture aligned to stroke direction
// - Dry-brush breaks (kasure) driven by paper grain + ink load
// - Ragged/frayed edges (not smooth vector edges)
// - Variable opacity (dense black core + semi-transparent scumbled sections)
// - Pressure dynamics (thicker at start/middle, thinning at tail)
// - Stacked curved strokes with multiple offset bands

// ==========================================
// Data Structures
// ==========================================

/**
 * Parameters for flat brush rendering
 */
data class FlatBrushParams(
    val seed: Long,
    // Brush dimensions
    val brushWidth: Double = 100.0,        // 60–140 px typical for hake
    val profilePower: Double = 2.0,         // 1.5–3.5 (flatter = more blocky)
    // Bristle texture
    val bristleFreq: Double = 80.0,         // 40–120 (higher = more streaks)
    val bristleStrength: Double = 0.6,      // How visible the bristle streaks are
    // Edge treatment
    val edgeRoughness: Double = 0.05,       // 0.02–0.08 in UV space
    val edgeFrayScale: Double = 20.0,       // Noise scale for edge fray
    // Drying / ink depletion
    val dryRate: Double = 2.0,              // 1.0–3.0 (faster = more kasure)
    val initialInkLoad: Double = 0.95,      // Starting ink amount
    val drynessThreshold: Double = 0.3,     // Base threshold for kasure
    // Paper grain
    val paperGrainSmall: Double = 4.0,      // 2–8 px small grain
    val paperGrainLarge: Double = 40.0,     // 20–60 px large grain
    val paperGrainStrength: Double = 0.5,   // How much paper affects kasure
    // Stacking for curved strokes
    val stackCount: Int = 1,                // 3–5 for layered bands
    val stackOffset: Double = 6.0,          // 2–8 px offset between layers
    val stackInkVariation: Double = 0.15,   // Ink load variation per layer
    val stackDryVariation: Double = 0.2,    // Dryness variation per layer
    // Stroke path
    val strokePoints: Int = 6,              // Control points for stroke path
    val tension: Double = 1.0,              // Hobby curve tension
    // Rendering
    val stampSpacing: Double = 0.05,        // Spacing as fraction of brush width
    val useMaxBlend: Boolean = true,        // Use max blend for pigment buildup
    // Katakana mode
    val katakanaMode: Boolean = true,       // Draw Katakana characters
    val katakanaIndex: Int = 0,             // Which Katakana to draw (0-45)
    // Display
    val showDebug: Boolean = false
)

// ==========================================
// Katakana Character Definitions (カタカナ定義)
// ==========================================

/**
 * Represents a single stroke of a Katakana character.
 * Points are normalized to [0,1] coordinate space and scaled at render time.
 */
data class FlatBrushKatakanaStroke(
    val points: List<Vector2>    // Control points for Hobby curve (normalized 0-1)
)

/**
 * Represents a complete Katakana character with multiple strokes.
 */
data class FlatBrushKatakanaCharacter(
    val name: String,             // Romanization (e.g., "A", "KA", "SA")
    val unicode: Char,            // Unicode character (e.g., 'ア', 'カ', 'サ')
    val strokes: List<FlatBrushKatakanaStroke>  // Ordered list of strokes
)

/**
 * Complete Katakana alphabet definitions.
 * Each character is defined by its strokes, with control points in normalized [0,1] space.
 * The stroke order follows traditional Japanese calligraphy conventions (筆順).
 */
val FLAT_BRUSH_KATAKANA: List<FlatBrushKatakanaCharacter> = listOf(
    // ア (A) - 2 strokes
    FlatBrushKatakanaCharacter("A", 'ア', listOf(
        FlatBrushKatakanaStroke(listOf(Vector2(0.2, 0.15), Vector2(0.5, 0.12), Vector2(0.85, 0.18))),
        FlatBrushKatakanaStroke(listOf(Vector2(0.55, 0.1), Vector2(0.5, 0.35), Vector2(0.35, 0.65), Vector2(0.15, 0.9)))
    )),
    // イ (I) - 2 strokes
    FlatBrushKatakanaCharacter("I", 'イ', listOf(
        FlatBrushKatakanaStroke(listOf(Vector2(0.65, 0.1), Vector2(0.45, 0.3), Vector2(0.25, 0.55))),
        FlatBrushKatakanaStroke(listOf(Vector2(0.6, 0.25), Vector2(0.62, 0.5), Vector2(0.58, 0.85)))
    )),
    // ウ (U) - 3 strokes
    FlatBrushKatakanaCharacter("U", 'ウ', listOf(
        FlatBrushKatakanaStroke(listOf(Vector2(0.45, 0.08), Vector2(0.55, 0.08))),
        FlatBrushKatakanaStroke(listOf(Vector2(0.15, 0.25), Vector2(0.5, 0.22), Vector2(0.85, 0.28))),
        FlatBrushKatakanaStroke(listOf(Vector2(0.5, 0.25), Vector2(0.48, 0.55), Vector2(0.5, 0.88)))
    )),
    // エ (E) - 3 strokes
    FlatBrushKatakanaCharacter("E", 'エ', listOf(
        FlatBrushKatakanaStroke(listOf(Vector2(0.2, 0.15), Vector2(0.5, 0.15), Vector2(0.8, 0.15))),
        FlatBrushKatakanaStroke(listOf(Vector2(0.5, 0.15), Vector2(0.5, 0.5), Vector2(0.5, 0.85))),
        FlatBrushKatakanaStroke(listOf(Vector2(0.15, 0.85), Vector2(0.5, 0.85), Vector2(0.85, 0.85)))
    )),
    // オ (O) - 3 strokes
    FlatBrushKatakanaCharacter("O", 'オ', listOf(
        FlatBrushKatakanaStroke(listOf(Vector2(0.2, 0.3), Vector2(0.5, 0.28), Vector2(0.8, 0.32))),
        FlatBrushKatakanaStroke(listOf(Vector2(0.5, 0.1), Vector2(0.48, 0.5), Vector2(0.5, 0.9))),
        FlatBrushKatakanaStroke(listOf(Vector2(0.5, 0.45), Vector2(0.65, 0.55), Vector2(0.85, 0.7)))
    )),
    // カ (KA) - 2 strokes
    FlatBrushKatakanaCharacter("KA", 'カ', listOf(
        FlatBrushKatakanaStroke(listOf(Vector2(0.25, 0.15), Vector2(0.55, 0.12), Vector2(0.75, 0.2), Vector2(0.65, 0.5), Vector2(0.55, 0.85))),
        FlatBrushKatakanaStroke(listOf(Vector2(0.35, 0.35), Vector2(0.32, 0.6), Vector2(0.15, 0.9)))
    )),
    // キ (KI) - 3 strokes
    FlatBrushKatakanaCharacter("KI", 'キ', listOf(
        FlatBrushKatakanaStroke(listOf(Vector2(0.2, 0.25), Vector2(0.5, 0.22), Vector2(0.8, 0.28))),
        FlatBrushKatakanaStroke(listOf(Vector2(0.25, 0.5), Vector2(0.5, 0.48), Vector2(0.75, 0.52))),
        FlatBrushKatakanaStroke(listOf(Vector2(0.5, 0.1), Vector2(0.48, 0.5), Vector2(0.5, 0.9)))
    )),
    // ク (KU) - 2 strokes
    FlatBrushKatakanaCharacter("KU", 'ク', listOf(
        FlatBrushKatakanaStroke(listOf(Vector2(0.7, 0.1), Vector2(0.55, 0.35), Vector2(0.35, 0.6))),
        FlatBrushKatakanaStroke(listOf(Vector2(0.3, 0.2), Vector2(0.5, 0.25), Vector2(0.6, 0.4), Vector2(0.55, 0.65), Vector2(0.35, 0.9)))
    )),
    // ケ (KE) - 3 strokes
    FlatBrushKatakanaCharacter("KE", 'ケ', listOf(
        FlatBrushKatakanaStroke(listOf(Vector2(0.25, 0.15), Vector2(0.55, 0.12), Vector2(0.75, 0.18))),
        FlatBrushKatakanaStroke(listOf(Vector2(0.35, 0.4), Vector2(0.55, 0.38), Vector2(0.75, 0.42))),
        FlatBrushKatakanaStroke(listOf(Vector2(0.55, 0.15), Vector2(0.5, 0.5), Vector2(0.45, 0.88)))
    )),
    // コ (KO) - 3 strokes
    FlatBrushKatakanaCharacter("KO", 'コ', listOf(
        FlatBrushKatakanaStroke(listOf(Vector2(0.2, 0.2), Vector2(0.5, 0.18), Vector2(0.8, 0.2))),
        FlatBrushKatakanaStroke(listOf(Vector2(0.8, 0.2), Vector2(0.78, 0.5), Vector2(0.8, 0.8))),
        FlatBrushKatakanaStroke(listOf(Vector2(0.2, 0.8), Vector2(0.5, 0.78), Vector2(0.8, 0.8)))
    )),
    // サ (SA) - 3 strokes
    FlatBrushKatakanaCharacter("SA", 'サ', listOf(
        FlatBrushKatakanaStroke(listOf(Vector2(0.15, 0.2), Vector2(0.5, 0.18), Vector2(0.85, 0.22))),
        FlatBrushKatakanaStroke(listOf(Vector2(0.2, 0.45), Vector2(0.5, 0.43), Vector2(0.8, 0.47))),
        FlatBrushKatakanaStroke(listOf(Vector2(0.55, 0.18), Vector2(0.45, 0.55), Vector2(0.3, 0.9)))
    )),
    // シ (SHI) - 3 strokes
    FlatBrushKatakanaCharacter("SHI", 'シ', listOf(
        FlatBrushKatakanaStroke(listOf(Vector2(0.2, 0.2), Vector2(0.28, 0.28))),
        FlatBrushKatakanaStroke(listOf(Vector2(0.3, 0.5), Vector2(0.38, 0.58))),
        FlatBrushKatakanaStroke(listOf(Vector2(0.8, 0.15), Vector2(0.65, 0.4), Vector2(0.4, 0.7), Vector2(0.15, 0.9)))
    )),
    // ス (SU) - 2 strokes
    FlatBrushKatakanaCharacter("SU", 'ス', listOf(
        FlatBrushKatakanaStroke(listOf(Vector2(0.2, 0.15), Vector2(0.5, 0.13), Vector2(0.8, 0.17))),
        FlatBrushKatakanaStroke(listOf(Vector2(0.5, 0.15), Vector2(0.55, 0.35), Vector2(0.35, 0.55), Vector2(0.6, 0.75), Vector2(0.85, 0.9)))
    )),
    // セ (SE) - 2 strokes
    FlatBrushKatakanaCharacter("SE", 'セ', listOf(
        FlatBrushKatakanaStroke(listOf(Vector2(0.15, 0.35), Vector2(0.5, 0.32), Vector2(0.85, 0.38))),
        FlatBrushKatakanaStroke(listOf(Vector2(0.55, 0.1), Vector2(0.5, 0.35), Vector2(0.45, 0.55), Vector2(0.55, 0.7), Vector2(0.75, 0.88)))
    )),
    // ソ (SO) - 2 strokes
    FlatBrushKatakanaCharacter("SO", 'ソ', listOf(
        FlatBrushKatakanaStroke(listOf(Vector2(0.25, 0.12), Vector2(0.35, 0.25), Vector2(0.4, 0.4))),
        FlatBrushKatakanaStroke(listOf(Vector2(0.75, 0.15), Vector2(0.55, 0.45), Vector2(0.25, 0.9)))
    )),
    // タ (TA) - 3 strokes
    FlatBrushKatakanaCharacter("TA", 'タ', listOf(
        FlatBrushKatakanaStroke(listOf(Vector2(0.2, 0.2), Vector2(0.5, 0.18), Vector2(0.8, 0.22))),
        FlatBrushKatakanaStroke(listOf(Vector2(0.6, 0.2), Vector2(0.5, 0.5), Vector2(0.35, 0.88))),
        FlatBrushKatakanaStroke(listOf(Vector2(0.3, 0.5), Vector2(0.5, 0.48), Vector2(0.7, 0.52)))
    )),
    // チ (CHI) - 2 strokes
    FlatBrushKatakanaCharacter("CHI", 'チ', listOf(
        FlatBrushKatakanaStroke(listOf(Vector2(0.15, 0.2), Vector2(0.5, 0.18), Vector2(0.85, 0.22))),
        FlatBrushKatakanaStroke(listOf(Vector2(0.5, 0.2), Vector2(0.48, 0.5), Vector2(0.35, 0.7), Vector2(0.5, 0.85), Vector2(0.75, 0.88)))
    )),
    // ツ (TSU) - 3 strokes
    FlatBrushKatakanaCharacter("TSU", 'ツ', listOf(
        FlatBrushKatakanaStroke(listOf(Vector2(0.18, 0.15), Vector2(0.28, 0.32))),
        FlatBrushKatakanaStroke(listOf(Vector2(0.5, 0.12), Vector2(0.55, 0.28))),
        FlatBrushKatakanaStroke(listOf(Vector2(0.82, 0.18), Vector2(0.6, 0.45), Vector2(0.25, 0.88)))
    )),
    // テ (TE) - 3 strokes
    FlatBrushKatakanaCharacter("TE", 'テ', listOf(
        FlatBrushKatakanaStroke(listOf(Vector2(0.25, 0.18), Vector2(0.5, 0.15), Vector2(0.75, 0.18))),
        FlatBrushKatakanaStroke(listOf(Vector2(0.15, 0.4), Vector2(0.5, 0.38), Vector2(0.85, 0.42))),
        FlatBrushKatakanaStroke(listOf(Vector2(0.5, 0.4), Vector2(0.48, 0.65), Vector2(0.5, 0.9)))
    )),
    // ト (TO) - 2 strokes
    FlatBrushKatakanaCharacter("TO", 'ト', listOf(
        FlatBrushKatakanaStroke(listOf(Vector2(0.4, 0.1), Vector2(0.38, 0.5), Vector2(0.4, 0.9))),
        FlatBrushKatakanaStroke(listOf(Vector2(0.4, 0.4), Vector2(0.6, 0.5), Vector2(0.8, 0.65)))
    )),
    // ナ (NA) - 2 strokes
    FlatBrushKatakanaCharacter("NA", 'ナ', listOf(
        FlatBrushKatakanaStroke(listOf(Vector2(0.15, 0.28), Vector2(0.5, 0.25), Vector2(0.85, 0.3))),
        FlatBrushKatakanaStroke(listOf(Vector2(0.55, 0.1), Vector2(0.48, 0.45), Vector2(0.35, 0.88)))
    )),
    // ニ (NI) - 2 strokes
    FlatBrushKatakanaCharacter("NI", 'ニ', listOf(
        FlatBrushKatakanaStroke(listOf(Vector2(0.25, 0.3), Vector2(0.5, 0.28), Vector2(0.75, 0.3))),
        FlatBrushKatakanaStroke(listOf(Vector2(0.15, 0.7), Vector2(0.5, 0.68), Vector2(0.85, 0.72)))
    )),
    // ヌ (NU) - 2 strokes
    FlatBrushKatakanaCharacter("NU", 'ヌ', listOf(
        FlatBrushKatakanaStroke(listOf(Vector2(0.2, 0.2), Vector2(0.5, 0.18), Vector2(0.75, 0.25), Vector2(0.6, 0.5), Vector2(0.35, 0.88))),
        FlatBrushKatakanaStroke(listOf(Vector2(0.4, 0.35), Vector2(0.55, 0.55), Vector2(0.8, 0.85)))
    )),
    // ネ (NE) - 4 strokes
    FlatBrushKatakanaCharacter("NE", 'ネ', listOf(
        FlatBrushKatakanaStroke(listOf(Vector2(0.45, 0.08), Vector2(0.55, 0.08))),
        FlatBrushKatakanaStroke(listOf(Vector2(0.5, 0.08), Vector2(0.48, 0.35), Vector2(0.5, 0.6))),
        FlatBrushKatakanaStroke(listOf(Vector2(0.15, 0.35), Vector2(0.5, 0.32), Vector2(0.85, 0.38))),
        FlatBrushKatakanaStroke(listOf(Vector2(0.5, 0.5), Vector2(0.35, 0.7), Vector2(0.5, 0.85), Vector2(0.75, 0.9)))
    )),
    // ノ (NO) - 1 stroke
    FlatBrushKatakanaCharacter("NO", 'ノ', listOf(
        FlatBrushKatakanaStroke(listOf(Vector2(0.75, 0.1), Vector2(0.55, 0.4), Vector2(0.25, 0.9)))
    )),
    // ハ (HA) - 2 strokes
    FlatBrushKatakanaCharacter("HA", 'ハ', listOf(
        FlatBrushKatakanaStroke(listOf(Vector2(0.5, 0.15), Vector2(0.35, 0.5), Vector2(0.15, 0.88))),
        FlatBrushKatakanaStroke(listOf(Vector2(0.55, 0.2), Vector2(0.7, 0.55), Vector2(0.85, 0.88)))
    )),
    // ヒ (HI) - 2 strokes
    FlatBrushKatakanaCharacter("HI", 'ヒ', listOf(
        FlatBrushKatakanaStroke(listOf(Vector2(0.35, 0.1), Vector2(0.32, 0.5), Vector2(0.35, 0.75))),
        FlatBrushKatakanaStroke(listOf(Vector2(0.35, 0.45), Vector2(0.55, 0.4), Vector2(0.75, 0.5), Vector2(0.7, 0.7), Vector2(0.45, 0.88)))
    )),
    // フ (FU) - 1 stroke
    FlatBrushKatakanaCharacter("FU", 'フ', listOf(
        FlatBrushKatakanaStroke(listOf(Vector2(0.2, 0.2), Vector2(0.5, 0.15), Vector2(0.8, 0.25), Vector2(0.65, 0.55), Vector2(0.4, 0.88)))
    )),
    // ヘ (HE) - 1 stroke
    FlatBrushKatakanaCharacter("HE", 'ヘ', listOf(
        FlatBrushKatakanaStroke(listOf(Vector2(0.15, 0.55), Vector2(0.4, 0.35), Vector2(0.5, 0.3), Vector2(0.7, 0.45), Vector2(0.88, 0.65)))
    )),
    // ホ (HO) - 4 strokes
    FlatBrushKatakanaCharacter("HO", 'ホ', listOf(
        FlatBrushKatakanaStroke(listOf(Vector2(0.5, 0.1), Vector2(0.48, 0.5), Vector2(0.5, 0.9))),
        FlatBrushKatakanaStroke(listOf(Vector2(0.15, 0.35), Vector2(0.5, 0.32), Vector2(0.85, 0.38))),
        FlatBrushKatakanaStroke(listOf(Vector2(0.5, 0.55), Vector2(0.3, 0.75), Vector2(0.15, 0.88))),
        FlatBrushKatakanaStroke(listOf(Vector2(0.5, 0.55), Vector2(0.7, 0.75), Vector2(0.85, 0.88)))
    )),
    // マ (MA) - 2 strokes
    FlatBrushKatakanaCharacter("MA", 'マ', listOf(
        FlatBrushKatakanaStroke(listOf(Vector2(0.15, 0.25), Vector2(0.5, 0.22), Vector2(0.85, 0.28))),
        FlatBrushKatakanaStroke(listOf(Vector2(0.75, 0.28), Vector2(0.55, 0.5), Vector2(0.35, 0.88)))
    )),
    // ミ (MI) - 3 strokes
    FlatBrushKatakanaCharacter("MI", 'ミ', listOf(
        FlatBrushKatakanaStroke(listOf(Vector2(0.3, 0.2), Vector2(0.55, 0.22), Vector2(0.75, 0.28))),
        FlatBrushKatakanaStroke(listOf(Vector2(0.25, 0.48), Vector2(0.5, 0.5), Vector2(0.7, 0.55))),
        FlatBrushKatakanaStroke(listOf(Vector2(0.2, 0.75), Vector2(0.45, 0.78), Vector2(0.65, 0.82)))
    )),
    // ム (MU) - 2 strokes
    FlatBrushKatakanaCharacter("MU", 'ム', listOf(
        FlatBrushKatakanaStroke(listOf(Vector2(0.5, 0.1), Vector2(0.45, 0.35), Vector2(0.2, 0.65), Vector2(0.35, 0.75), Vector2(0.75, 0.85))),
        FlatBrushKatakanaStroke(listOf(Vector2(0.35, 0.55), Vector2(0.55, 0.6), Vector2(0.7, 0.7)))
    )),
    // メ (ME) - 2 strokes
    FlatBrushKatakanaCharacter("ME", 'メ', listOf(
        FlatBrushKatakanaStroke(listOf(Vector2(0.7, 0.15), Vector2(0.45, 0.5), Vector2(0.2, 0.88))),
        FlatBrushKatakanaStroke(listOf(Vector2(0.3, 0.35), Vector2(0.55, 0.6), Vector2(0.8, 0.85)))
    )),
    // モ (MO) - 3 strokes
    FlatBrushKatakanaCharacter("MO", 'モ', listOf(
        FlatBrushKatakanaStroke(listOf(Vector2(0.2, 0.25), Vector2(0.5, 0.22), Vector2(0.8, 0.28))),
        FlatBrushKatakanaStroke(listOf(Vector2(0.25, 0.55), Vector2(0.5, 0.52), Vector2(0.75, 0.58))),
        FlatBrushKatakanaStroke(listOf(Vector2(0.5, 0.1), Vector2(0.48, 0.55), Vector2(0.5, 0.9)))
    )),
    // ヤ (YA) - 2 strokes
    FlatBrushKatakanaCharacter("YA", 'ヤ', listOf(
        FlatBrushKatakanaStroke(listOf(Vector2(0.25, 0.3), Vector2(0.5, 0.25), Vector2(0.75, 0.35), Vector2(0.5, 0.6), Vector2(0.25, 0.88))),
        FlatBrushKatakanaStroke(listOf(Vector2(0.55, 0.1), Vector2(0.52, 0.4), Vector2(0.55, 0.75)))
    )),
    // ユ (YU) - 2 strokes
    FlatBrushKatakanaCharacter("YU", 'ユ', listOf(
        FlatBrushKatakanaStroke(listOf(Vector2(0.2, 0.25), Vector2(0.22, 0.55), Vector2(0.2, 0.75))),
        FlatBrushKatakanaStroke(listOf(Vector2(0.2, 0.75), Vector2(0.5, 0.73), Vector2(0.8, 0.78)))
    )),
    // ヨ (YO) - 3 strokes
    FlatBrushKatakanaCharacter("YO", 'ヨ', listOf(
        FlatBrushKatakanaStroke(listOf(Vector2(0.3, 0.2), Vector2(0.55, 0.18), Vector2(0.75, 0.22))),
        FlatBrushKatakanaStroke(listOf(Vector2(0.35, 0.5), Vector2(0.55, 0.48), Vector2(0.72, 0.52))),
        FlatBrushKatakanaStroke(listOf(Vector2(0.75, 0.2), Vector2(0.73, 0.5), Vector2(0.75, 0.8), Vector2(0.55, 0.78), Vector2(0.3, 0.82)))
    )),
    // ラ (RA) - 2 strokes
    FlatBrushKatakanaCharacter("RA", 'ラ', listOf(
        FlatBrushKatakanaStroke(listOf(Vector2(0.2, 0.2), Vector2(0.5, 0.18), Vector2(0.8, 0.22))),
        FlatBrushKatakanaStroke(listOf(Vector2(0.55, 0.22), Vector2(0.52, 0.5), Vector2(0.35, 0.88)))
    )),
    // リ (RI) - 2 strokes
    FlatBrushKatakanaCharacter("RI", 'リ', listOf(
        FlatBrushKatakanaStroke(listOf(Vector2(0.35, 0.15), Vector2(0.32, 0.45), Vector2(0.3, 0.65))),
        FlatBrushKatakanaStroke(listOf(Vector2(0.68, 0.1), Vector2(0.65, 0.5), Vector2(0.6, 0.88)))
    )),
    // ル (RU) - 2 strokes
    FlatBrushKatakanaCharacter("RU", 'ル', listOf(
        FlatBrushKatakanaStroke(listOf(Vector2(0.3, 0.1), Vector2(0.28, 0.5), Vector2(0.3, 0.88))),
        FlatBrushKatakanaStroke(listOf(Vector2(0.6, 0.15), Vector2(0.58, 0.5), Vector2(0.55, 0.7), Vector2(0.7, 0.82), Vector2(0.85, 0.88)))
    )),
    // レ (RE) - 1 stroke
    FlatBrushKatakanaCharacter("RE", 'レ', listOf(
        FlatBrushKatakanaStroke(listOf(Vector2(0.35, 0.1), Vector2(0.32, 0.5), Vector2(0.35, 0.7), Vector2(0.55, 0.8), Vector2(0.8, 0.88)))
    )),
    // ロ (RO) - 3 strokes
    FlatBrushKatakanaCharacter("RO", 'ロ', listOf(
        FlatBrushKatakanaStroke(listOf(Vector2(0.2, 0.2), Vector2(0.5, 0.18), Vector2(0.8, 0.2))),
        FlatBrushKatakanaStroke(listOf(Vector2(0.2, 0.2), Vector2(0.22, 0.5), Vector2(0.2, 0.8))),
        FlatBrushKatakanaStroke(listOf(Vector2(0.8, 0.2), Vector2(0.78, 0.5), Vector2(0.8, 0.8), Vector2(0.5, 0.78), Vector2(0.2, 0.8)))
    )),
    // ワ (WA) - 2 strokes
    FlatBrushKatakanaCharacter("WA", 'ワ', listOf(
        FlatBrushKatakanaStroke(listOf(Vector2(0.2, 0.2), Vector2(0.5, 0.15), Vector2(0.8, 0.22), Vector2(0.75, 0.5), Vector2(0.6, 0.88))),
        FlatBrushKatakanaStroke(listOf(Vector2(0.35, 0.35), Vector2(0.32, 0.55), Vector2(0.35, 0.75)))
    )),
    // ヲ (WO) - 3 strokes
    FlatBrushKatakanaCharacter("WO", 'ヲ', listOf(
        FlatBrushKatakanaStroke(listOf(Vector2(0.2, 0.2), Vector2(0.5, 0.18), Vector2(0.8, 0.22))),
        FlatBrushKatakanaStroke(listOf(Vector2(0.25, 0.45), Vector2(0.5, 0.42), Vector2(0.75, 0.48))),
        FlatBrushKatakanaStroke(listOf(Vector2(0.65, 0.22), Vector2(0.55, 0.55), Vector2(0.35, 0.88)))
    )),
    // ン (N) - 2 strokes
    FlatBrushKatakanaCharacter("N", 'ン', listOf(
        FlatBrushKatakanaStroke(listOf(Vector2(0.2, 0.35), Vector2(0.32, 0.5))),
        FlatBrushKatakanaStroke(listOf(Vector2(0.75, 0.15), Vector2(0.55, 0.45), Vector2(0.3, 0.85)))
    ))
)

/**
 * Get the current Katakana character
 */
fun getCurrentFlatBrushKatakana(params: FlatBrushParams): FlatBrushKatakanaCharacter {
    val index = params.katakanaIndex.coerceIn(0, FLAT_BRUSH_KATAKANA.lastIndex)
    return FLAT_BRUSH_KATAKANA[index]
}

/**
 * Transform normalized Katakana stroke points to canvas coordinates.
 * Adds subtle jitter for organic hand-drawn feel.
 */
fun generateKatakanaFlatBrushPath(
    stroke: FlatBrushKatakanaStroke,
    bounds: Rectangle,
    seed: Long,
    jitterAmount: Double = 2.0
): List<Vector2> {
    val rng = Random(seed)
    
    return stroke.points.map { normalizedPoint ->
        val x = bounds.x + normalizedPoint.x * bounds.width
        val y = bounds.y + normalizedPoint.y * bounds.height
        
        val jitter = Vector2(
            rng.nextDouble(-jitterAmount, jitterAmount),
            rng.nextDouble(-jitterAmount, jitterAmount)
        )
        
        Vector2(x, y) + jitter
    }
}

/**
 * Sample point along the stroke path
 */
data class FlatBrushSample(
    val pos: Vector2,
    val tangent: Vector2,
    val normal: Vector2,
    val t: Double,           // Parameter along path [0,1]
    val speed: Double = 1.0, // Relative speed at this point
    val pressure: Double = 1.0
)

// ==========================================
// Paper Texture Generation
// ==========================================

/**
 * Generate paper grain texture for kasure effect
 * R: small-scale grain, G: large-scale grain, B: fiber direction
 */
fun generatePaperGrain(width: Int, height: Int, seed: Long): ColorBuffer {
    val buffer = colorBuffer(width, height, format = ColorFormat.RGBa, type = ColorType.FLOAT32)
    val shadow = buffer.shadow
    shadow.download()
    
    val rng = Random(seed)
    
    for (y in 0 until height) {
        for (x in 0 until width) {
            val nx = x.toDouble() / width
            val ny = y.toDouble() / height
            
            // Small grain: fine paper tooth
            val smallGrain = fbmNoise(nx * 120.0, ny * 120.0, seed, octaves = 3) * 0.5 + 0.5
            
            // Large grain: broader paper texture
            val largeGrain = fbmNoise(nx * 30.0, ny * 30.0, seed + 100, octaves = 2) * 0.5 + 0.5
            
            // Fiber direction (slightly anisotropic - more horizontal)
            val fiberAngle = fbmNoise(nx * 8.0, ny * 8.0, seed + 200, octaves = 1) * PI * 0.3
            val fiberX = cos(fiberAngle) * 0.5 + 0.5
            val fiberY = sin(fiberAngle) * 0.5 + 0.5
            
            shadow[x, y] = ColorRGBa(smallGrain, largeGrain, fiberX, fiberY)
        }
    }
    
    shadow.upload()
    return buffer
}

/**
 * FBM noise for paper texture
 */
private fun fbmNoise(x: Double, y: Double, seed: Long, octaves: Int = 4): Double {
    var value = 0.0
    var amplitude = 1.0
    var frequency = 1.0
    var maxValue = 0.0
    
    for (i in 0 until octaves) {
        value += valueNoise(x * frequency, y * frequency, seed + i * 1000) * amplitude
        maxValue += amplitude
        amplitude *= 0.5
        frequency *= 2.0
    }
    
    return value / maxValue
}

/**
 * Simple value noise
 */
private fun valueNoise(x: Double, y: Double, seed: Long): Double {
    val xi = floor(x).toInt()
    val yi = floor(y).toInt()
    val xf = x - xi
    val yf = y - yi
    
    fun hash(ix: Int, iy: Int): Double {
        val h = ((ix * 374761393 + iy * 668265263 + seed.toInt()) xor (seed.toInt() shr 13))
        return ((h * h * h * 60493) and 0x7FFFFFFF).toDouble() / 0x7FFFFFFF.toDouble()
    }
    
    val v00 = hash(xi, yi)
    val v10 = hash(xi + 1, yi)
    val v01 = hash(xi, yi + 1)
    val v11 = hash(xi + 1, yi + 1)
    
    // Smoothstep interpolation
    val tx = xf * xf * (3 - 2 * xf)
    val ty = yf * yf * (3 - 2 * yf)
    
    val v0 = v00 * (1 - tx) + v10 * tx
    val v1 = v01 * (1 - tx) + v11 * tx
    
    return v0 * (1 - ty) + v1 * ty
}

// ==========================================
// Stroke Path Generation
// ==========================================

/**
 * Generate a stroke path - can be straight, curved, or S-shaped
 */
fun generateStrokePath(
    params: FlatBrushParams,
    bounds: Rectangle,
    strokeType: Int = 0,  // 0=horizontal, 1=vertical, 2=curved, 3=S-curve
    strokeIndex: Int = 0
): List<Vector2> {
    val rng = Random(params.seed + strokeIndex * 1000)
    val points = mutableListOf<Vector2>()
    
    val margin = params.brushWidth * 0.8
    val safeArea = bounds.offsetEdges(-margin)
    
    when (strokeType) {
        0 -> {
            // Horizontal stroke - left to right
            val y = safeArea.y + safeArea.height * (0.3 + rng.nextDouble() * 0.4)
            val startX = safeArea.x + rng.nextDouble() * safeArea.width * 0.1
            val endX = safeArea.x + safeArea.width * (0.85 + rng.nextDouble() * 0.15)
            
            val numPts = params.strokePoints
            for (i in 0 until numPts) {
                val t = i.toDouble() / (numPts - 1)
                val x = startX + (endX - startX) * t
                // Add subtle vertical wave
                val wave = sin(t * PI * 1.5) * safeArea.height * 0.02
                points.add(Vector2(x, y + wave + rng.nextDouble(-3.0, 3.0)))
            }
        }
        1 -> {
            // Vertical stroke - top to bottom
            val x = safeArea.x + safeArea.width * (0.3 + rng.nextDouble() * 0.4)
            val startY = safeArea.y + rng.nextDouble() * safeArea.height * 0.1
            val endY = safeArea.y + safeArea.height * (0.85 + rng.nextDouble() * 0.15)
            
            val numPts = params.strokePoints
            for (i in 0 until numPts) {
                val t = i.toDouble() / (numPts - 1)
                val y = startY + (endY - startY) * t
                val wave = sin(t * PI * 1.5) * safeArea.width * 0.02
                points.add(Vector2(x + wave + rng.nextDouble(-3.0, 3.0), y))
            }
        }
        2 -> {
            // Curved arc stroke
            val centerX = safeArea.x + safeArea.width * (0.3 + rng.nextDouble() * 0.4)
            val centerY = safeArea.y + safeArea.height * (0.3 + rng.nextDouble() * 0.4)
            val radius = min(safeArea.width, safeArea.height) * (0.3 + rng.nextDouble() * 0.2)
            val startAngle = rng.nextDouble() * PI * 0.5 - PI * 0.25
            val sweep = PI * (0.5 + rng.nextDouble() * 0.5)
            
            val numPts = params.strokePoints + 2
            for (i in 0 until numPts) {
                val t = i.toDouble() / (numPts - 1)
                val angle = startAngle + sweep * t
                val r = radius + rng.nextDouble(-5.0, 5.0)
                points.add(Vector2(
                    centerX + cos(angle) * r,
                    centerY + sin(angle) * r
                ))
            }
        }
        3 -> {
            // S-curve
            val startX = safeArea.x + rng.nextDouble() * safeArea.width * 0.2
            val endX = safeArea.x + safeArea.width * (0.8 + rng.nextDouble() * 0.2)
            val midY = safeArea.y + safeArea.height * 0.5
            val amplitude = safeArea.height * 0.25
            
            val numPts = params.strokePoints + 2
            for (i in 0 until numPts) {
                val t = i.toDouble() / (numPts - 1)
                val x = startX + (endX - startX) * t
                // S-curve using sin
                val y = midY + sin(t * PI * 2 - PI * 0.5) * amplitude * (0.5 + t * 0.5)
                points.add(Vector2(x + rng.nextDouble(-2.0, 2.0), y + rng.nextDouble(-2.0, 2.0)))
            }
        }
    }
    
    return points
}

/**
 * Sample a contour uniformly with tangent and normal
 */
fun sampleFlatBrushPath(contour: ShapeContour, numSamples: Int, params: FlatBrushParams, seed: Long): List<FlatBrushSample> {
    val positions = contour.equidistantPositions(numSamples)
    if (positions.size < 2) return emptyList()
    
    val rng = Random(seed)
    val samples = mutableListOf<FlatBrushSample>()
    
    // Calculate speeds (distance between consecutive points)
    val distances = mutableListOf<Double>()
    for (i in 0 until positions.size - 1) {
        distances.add((positions[i + 1] - positions[i]).length)
    }
    distances.add(distances.lastOrNull() ?: 1.0)
    val avgDist = distances.average().coerceAtLeast(0.001)
    
    for (i in positions.indices) {
        val pos = positions[i]
        val t = i.toDouble() / (positions.size - 1)
        
        // Tangent from finite differences
        val tangent = when (i) {
            0 -> (positions[1] - positions[0]).normalized
            positions.lastIndex -> (positions[i] - positions[i - 1]).normalized
            else -> (positions[i + 1] - positions[i - 1]).normalized
        }
        
        val normal = tangent.perpendicular()
        val speed = distances[i] / avgDist
        
        // Pressure: higher at start/middle, lower at end (simulates brush lift)
        val pressure = when {
            t < 0.1 -> smoothstep(0.0, 0.1, t)  // Entry
            t > 0.8 -> smoothstep(1.0, 0.8, t)  // Exit
            else -> 1.0 - (t - 0.5).absoluteValue * 0.2  // Slight dip in middle
        }
        
        samples.add(FlatBrushSample(pos, tangent, normal, t, speed, pressure))
    }
    
    return samples
}

// ==========================================
// Flat Brush Stamp Shader
// ==========================================

/**
 * Create the flat brush stamp shader
 * Implements bristle streaks, kasure, edge fray, and pressure dynamics
 */
fun createFlatBrushShader(): ShadeStyle = shadeStyle {
    fragmentTransform = """
        // Inputs from uniforms
        vec2 stampPos = p_stampPos;
        vec2 tangent = normalize(p_tangent);
        vec2 normal = normalize(p_normal);
        float brushWidth = p_brushWidth;
        float pressure = p_pressure;
        float inkLoad = p_inkLoad;
        float dryness = p_dryness;
        float profilePower = p_profilePower;
        float bristleFreq = p_bristleFreq;
        float bristleStrength = p_bristleStrength;
        float edgeRoughness = p_edgeRoughness;
        float edgeFrayScale = p_edgeFrayScale;
        float seed = p_seed;
        
        // Canvas dimensions
        vec2 canvasSize = vec2(p_width, p_height);
        vec2 fragPos = c_boundsPosition.xy * canvasSize;
        
        // Transform to brush-local coordinates
        // u: across brush width (-1 to 1)
        // v: along stroke direction
        vec2 toFrag = fragPos - stampPos;
        float u = dot(toFrag, normal) / (brushWidth * 0.5);
        float v = dot(toFrag, tangent) / (brushWidth * 0.25);  // Stamp is shorter along stroke
        
        // === EDGE FRAY (ragged perimeter) ===
        // Perturb the edge with noise
        float edgeNoiseX = sin(v * edgeFrayScale + seed) * cos(v * edgeFrayScale * 1.7 + seed * 2.0);
        float edgeNoiseY = sin(u * edgeFrayScale * 0.8 + seed * 3.0);
        float edgePerturbation = (edgeNoiseX + edgeNoiseY * 0.5) * edgeRoughness;
        float perturbedU = u + edgePerturbation * sign(u);
        
        // === BASE PROFILE (flat brush shape) ===
        // Flat brush has a blocky profile - sharp at edges
        float absU = abs(perturbedU);
        float profile = 1.0 - pow(absU, profilePower);
        profile = clamp(profile, 0.0, 1.0);
        
        // Soft falloff at very edges for anti-aliasing
        profile *= smoothstep(1.0, 0.95, absU);
        
        // Vertical extent (along stroke) - narrower stamp
        float vFalloff = 1.0 - smoothstep(0.7, 1.0, abs(v));
        profile *= vFalloff;
        
        // === BRISTLE STREAKS ===
        // Create long streaks aligned with stroke direction
        // Use u position to create parallel bristle lines
        float bristleU = u * bristleFreq + seed * 0.1;
        float bristle = sin(bristleU * 3.14159);
        // Make streaks more pronounced with threshold
        bristle = smoothstep(0.2, 0.8, bristle * 0.5 + 0.5);
        // Add variation along v (stroke direction) for longer streaks
        float bristleVar = sin(v * 2.0 + bristleU * 0.5) * 0.3 + 0.7;
        bristle *= bristleVar;
        
        // Secondary finer bristle detail
        float fineBristle = sin(u * bristleFreq * 3.0 + seed * 5.0) * 0.5 + 0.5;
        fineBristle = smoothstep(0.4, 0.6, fineBristle);
        bristle = mix(bristle, bristle * fineBristle, 0.4);
        
        // Bristle strength modulated by position
        float bristleEffect = mix(1.0, bristle, bristleStrength * (0.7 + absU * 0.3));
        
        // === PAPER GRAIN / KASURE ===
        // Sample paper texture
        vec2 paperUV = c_boundsPosition.xy;
        vec4 paper = texture(p_paper, paperUV);
        float smallGrain = paper.r;
        float largeGrain = paper.g;
        
        // Combine grains
        float combinedGrain = smallGrain * 0.6 + largeGrain * 0.4;
        
        // Kasure threshold increases with dryness
        float kasureThresh = dryness + p_drynessThreshold;
        float kasureMask = smoothstep(kasureThresh - 0.1, kasureThresh + 0.1, combinedGrain);
        
        // More kasure at edges
        kasureMask *= (1.0 + absU * 0.5);
        
        // Kasure creates gaps in the ink
        float kasureEffect = 1.0 - kasureMask * p_paperGrainStrength;
        kasureEffect = clamp(kasureEffect, 0.0, 1.0);
        
        // === FINAL PIGMENT CALCULATION ===
        float alpha = profile * bristleEffect * kasureEffect * inkLoad * pressure;
        
        // Darker core - raise pigment where profile is highest
        float coreIntensity = smoothstep(0.3, 0.8, profile);
        float pigment = mix(0.7, 1.0, coreIntensity) * alpha;
        
        // Output
        // Using RGB for pigment density, A for coverage
        x_fill = vec4(vec3(pigment), alpha);
    """.trimIndent()
}

// ==========================================
// Flat Brush Rendering
// ==========================================

/**
 * Render a single flat brush stroke with stamping
 */
fun renderFlatBrushStroke(
    drawer: Drawer,
    samples: List<FlatBrushSample>,
    params: FlatBrushParams,
    paperTexture: ColorBuffer,
    brushShader: ShadeStyle,
    offsetNormal: Double = 0.0,  // For stacked strokes
    inkLoadMod: Double = 1.0,
    drynessMod: Double = 0.0
) {
    if (samples.isEmpty()) return
    
    val rng = Random(params.seed)
    
    // Calculate stamp spacing based on brush width and speed
    val baseSpacing = params.brushWidth * params.stampSpacing
    
    // Track ink depletion along stroke
    var currentInkLoad = params.initialInkLoad * inkLoadMod
    var currentDryness = drynessMod
    
    for ((idx, sample) in samples.withIndex()) {
        // Skip some stamps when moving fast
        val spacing = baseSpacing / sample.speed.coerceIn(0.5, 2.0)
        if (idx > 0 && idx % max(1, (spacing / baseSpacing).toInt()) != 0) {
            // Still update ink/dryness
            val t = sample.t
            currentInkLoad = (params.initialInkLoad * inkLoadMod) * exp(-t * params.dryRate)
            currentDryness = drynessMod + t * params.dryRate * 0.3
            continue
        }
        
        // Apply normal offset for stacked strokes
        val offsetPos = sample.pos + sample.normal * offsetNormal
        
        // Update ink load (depletes along stroke)
        val t = sample.t
        currentInkLoad = (params.initialInkLoad * inkLoadMod) * exp(-t * params.dryRate)
        currentDryness = drynessMod + t * params.dryRate * 0.3
        
        // Skip if too dry
        if (currentInkLoad < 0.05) continue
        
        // Draw stamp
        drawer.isolated {
            drawer.shadeStyle = brushShader.apply {
                parameter("stampPos", offsetPos)
                parameter("tangent", sample.tangent)
                parameter("normal", sample.normal)
                parameter("brushWidth", params.brushWidth * sample.pressure)
                parameter("pressure", sample.pressure)
                parameter("inkLoad", currentInkLoad)
                parameter("dryness", currentDryness)
                parameter("profilePower", params.profilePower)
                parameter("bristleFreq", params.bristleFreq)
                parameter("bristleStrength", params.bristleStrength)
                parameter("edgeRoughness", params.edgeRoughness)
                parameter("edgeFrayScale", params.edgeFrayScale)
                parameter("drynessThreshold", params.drynessThreshold)
                parameter("paperGrainStrength", params.paperGrainStrength)
                parameter("seed", (params.seed + idx).toDouble())
                parameter("width", drawer.width.toDouble())
                parameter("height", drawer.height.toDouble())
                parameter("paper", paperTexture)
            }
            
            // Draw a rectangle covering the stamp area
            val stampRadius = params.brushWidth * 1.5
            drawer.fill = ColorRGBa.BLACK
            drawer.stroke = null
            drawer.rectangle(
                offsetPos.x - stampRadius,
                offsetPos.y - stampRadius,
                stampRadius * 2,
                stampRadius * 2
            )
        }
    }
}

/**
 * Render stacked curved strokes (multiple parallel bands)
 */
fun renderStackedStrokes(
    drawer: Drawer,
    samples: List<FlatBrushSample>,
    params: FlatBrushParams,
    paperTexture: ColorBuffer,
    brushShader: ShadeStyle
) {
    val rng = Random(params.seed + 500)
    
    // Calculate offsets for each layer
    val totalWidth = (params.stackCount - 1) * params.stackOffset
    val startOffset = -totalWidth / 2.0
    
    for (layer in 0 until params.stackCount) {
        val offset = startOffset + layer * params.stackOffset
        
        // Vary ink load and dryness per layer
        val inkMod = 1.0 - (rng.nextDouble() - 0.5) * params.stackInkVariation * 2
        val dryMod = rng.nextDouble() * params.stackDryVariation
        
        renderFlatBrushStroke(
            drawer, samples, params, paperTexture, brushShader,
            offsetNormal = offset,
            inkLoadMod = inkMod,
            drynessMod = dryMod
        )
    }
}

// ==========================================
// Main Composition
// ==========================================

/**
 * Render paper background
 */
fun renderFlatBrushPaper(drawer: Drawer, seed: Long) {
    // Warm paper color
    val paperColor = ColorRGBa.fromHex("FDF8F0")
    drawer.clear(paperColor)
    
    // Add subtle grain via shader
    drawer.isolated {
        drawer.shadeStyle = shadeStyle {
            fragmentTransform = """
                vec2 uv = c_boundsPosition.xy;
                float noise = fract(sin(dot(uv * 500.0, vec2(12.9898, 78.233))) * 43758.5453);
                float vign = 1.0 - length(uv - 0.5) * 0.2;
                vec3 color = x_fill.rgb;
                color += (noise - 0.5) * 0.02;
                color *= vign;
                x_fill.rgb = clamp(color, 0.0, 1.0);
            """.trimIndent()
        }
        drawer.fill = paperColor
        drawer.stroke = null
        drawer.rectangle(0.0, 0.0, drawer.width.toDouble(), drawer.height.toDouble())
    }
}

/**
 * Export PNG - includes Katakana info if in Katakana mode
 */
fun exportFlatBrushPNG(renderTarget: RenderTarget, params: FlatBrushParams) {
    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    
    val filename = if (params.katakanaMode) {
        val katakana = getCurrentFlatBrushKatakana(params)
        "flatbrush_kata_${katakana.name}_${katakana.unicode}_s${params.seed}_w${params.brushWidth.toInt()}_$timestamp.png"
    } else {
        "flatbrush_s${params.seed}_w${params.brushWidth.toInt()}_$timestamp.png"
    }
    
    File("images").mkdirs()
    renderTarget.colorBuffer(0).saveToFile(File("images/$filename"))
    println("Exported: images/$filename")
}

// ==========================================
// Main Program
// ==========================================

fun main() = application {
    configure {
        width = 600
        height = 800  // Portrait orientation for Katakana
        title = "Flat Brush Katakana (平筆カタカナ)"
    }
    
    program {
        var seed = Random.nextLong()
        var params = FlatBrushParams(seed = seed, brushWidth = 60.0)  // Smaller brush for characters
        var strokeType = 0  // 0=horizontal, 1=vertical, 2=curved, 3=S-curve (for non-Katakana mode)
        var useStacking = false
        
        // Generate paper texture
        var paperTexture = generatePaperGrain(width, height, seed)
        
        // Create brush shader
        val brushShader = createFlatBrushShader()
        
        // Render target for composition
        val compositeTarget = renderTarget(width, height) {
            colorBuffer(ColorFormat.RGBa, ColorType.FLOAT32)
            depthBuffer(DepthFormat.DEPTH24_STENCIL8)
        }
        
        // Export render target
        val exportTarget = renderTarget(width, height) {
            colorBuffer()
            depthBuffer(DepthFormat.DEPTH24_STENCIL8)
        }
        
        // Font for UI
        val font = loadFont("data/fonts/default.otf", 12.0)
        val largeFont = loadFont("data/fonts/default.otf", 24.0)
        
        fun regenerate() {
            params = params.copy(seed = seed)
            paperTexture.destroy()
            paperTexture = generatePaperGrain(width, height, seed)
        }
        
        fun render(drawer: Drawer) {
            // Render paper background
            renderFlatBrushPaper(drawer, seed)
            
            val bounds = Rectangle(0.0, 0.0, width.toDouble(), height.toDouble())
            
            if (params.katakanaMode) {
                // =====================
                // KATAKANA MODE
                // =====================
                val katakana = getCurrentFlatBrushKatakana(params)
                val margin = min(width, height) * 0.1
                val safeArea = bounds.offsetEdges(-margin)
                
                // Calculate character bounds - square, centered
                val charSize = min(safeArea.width, safeArea.height) * 0.85
                val charBounds = Rectangle(
                    safeArea.x + (safeArea.width - charSize) / 2,
                    safeArea.y + (safeArea.height - charSize) / 2,
                    charSize,
                    charSize
                )
                
                // Collect all stroke control points for debug
                val allStrokePoints = mutableListOf<List<Vector2>>()
                
                // Render each stroke of the Katakana character in stroke order (筆順)
                for ((strokeIdx, stroke) in katakana.strokes.withIndex()) {
                    // Generate control points from Katakana stroke definition
                    val pathPoints = generateKatakanaFlatBrushPath(
                        stroke,
                        charBounds,
                        seed + strokeIdx * 1000,
                        jitterAmount = 1.5
                    )
                    
                    allStrokePoints.add(pathPoints)
                    
                    if (pathPoints.size >= 2) {
                        // Build Hobby curve
                        val contour = hobbyCurve(pathPoints, false, params.tension)
                        
                        // Sample path - adjust sampling density based on stroke length
                        val numSamples = (contour.length / (params.brushWidth * params.stampSpacing)).toInt()
                            .coerceIn(20, 400)
                        val samples = sampleFlatBrushPath(contour, numSamples, params, seed + strokeIdx)
                        
                        // Render stroke with flat brush
                        if (useStacking && params.stackCount > 1) {
                            renderStackedStrokes(drawer, samples, params, paperTexture, brushShader)
                        } else {
                            renderFlatBrushStroke(drawer, samples, params, paperTexture, brushShader)
                        }
                    }
                }
                
                // Debug: draw control points for all strokes
                if (params.showDebug) {
                    val colors = listOf(ColorRGBa.RED, ColorRGBa.BLUE, ColorRGBa.GREEN, ColorRGBa.MAGENTA)
                    for ((idx, pts) in allStrokePoints.withIndex()) {
                        val color = colors[idx % colors.size]
                        drawer.isolated {
                            drawer.fill = color.opacify(0.5)
                            drawer.stroke = color.opacify(0.3)
                            drawer.strokeWeight = 1.0
                            for (pt in pts) {
                                drawer.circle(pt, 4.0)
                            }
                            drawer.lineStrip(pts)
                        }
                    }
                }
                
                // Display character info at top
                drawer.isolated {
                    drawer.fontMap = largeFont
                    drawer.fill = ColorRGBa.fromHex("4A4A4A").opacify(0.7)
                    drawer.text("${katakana.unicode} (${katakana.name})", 20.0, 40.0)
                    
                    drawer.fontMap = font
                    drawer.text("[${params.katakanaIndex + 1}/${FLAT_BRUSH_KATAKANA.size}]", 20.0, 58.0)
                }
                
            } else {
                // =====================
                // RANDOM STROKE MODE (original behavior)
                // =====================
                val pathPoints = generateStrokePath(params, bounds, strokeType, 0)
                
                if (pathPoints.size >= 2) {
                    val contour = hobbyCurve(pathPoints, false, params.tension)
                    val numSamples = (contour.length / (params.brushWidth * params.stampSpacing)).toInt()
                        .coerceIn(20, 500)
                    val samples = sampleFlatBrushPath(contour, numSamples, params, seed)
                    
                    if (useStacking) {
                        renderStackedStrokes(drawer, samples, params, paperTexture, brushShader)
                    } else {
                        renderFlatBrushStroke(drawer, samples, params, paperTexture, brushShader)
                    }
                }
                
                // Debug: draw control points
                if (params.showDebug) {
                    drawer.isolated {
                        drawer.fill = ColorRGBa.RED.opacify(0.5)
                        drawer.stroke = ColorRGBa.RED.opacify(0.3)
                        drawer.strokeWeight = 1.0
                        for (pt in pathPoints) {
                            drawer.circle(pt, 5.0)
                        }
                        drawer.lineStrip(pathPoints)
                    }
                }
            }
            
            // UI Legend
            drawer.isolated {
                drawer.fontMap = font
                drawer.fill = ColorRGBa.fromHex("4A4A4A").opacify(0.8)
                
                val y = height - 100.0
                val mode = if (params.katakanaMode) "Katakana" else "Random Stroke"
                val stackStatus = if (useStacking) "ON (${params.stackCount} layers)" else "OFF"
                
                if (params.katakanaMode) {
                    val katakana = getCurrentFlatBrushKatakana(params)
                    drawer.text("Flat Brush Katakana (平筆カタカナ) | Char: ${katakana.unicode} (${katakana.name})", 15.0, y)
                } else {
                    val strokeName = when (strokeType) {
                        0 -> "Horizontal"
                        1 -> "Vertical"
                        2 -> "Curved"
                        3 -> "S-curve"
                        else -> "Unknown"
                    }
                    drawer.text("Flat Brush (平筆) | Mode: $mode | Type: $strokeName", 15.0, y)
                }
                
                drawer.text("Seed: $seed | Width: ${params.brushWidth.toInt()}px | Profile: ${String.format("%.1f", params.profilePower)}", 15.0, y + 15.0)
                drawer.text("Bristles: ${params.bristleFreq.toInt()} | Dry: ${String.format("%.1f", params.dryRate)} | Edge: ${String.format("%.2f", params.edgeRoughness)} | Stack: $stackStatus", 15.0, y + 30.0)
                
                if (params.katakanaMode) {
                    drawer.text("←/→=char ↑/↓=±10 Space=random R=reseed M=mode T=stack D=debug E=export", 15.0, y + 45.0)
                } else {
                    drawer.text("R=reseed M=mode 1-4=stroke T=stack D=debug E=export", 15.0, y + 45.0)
                }
                drawer.text("[/]=width -/+=profile K/J=bristles W/S=dry Q/A=edge", 15.0, y + 60.0)
            }
        }
        
        // Keyboard controls
        keyboard.keyDown.listen { event ->
            when (event.name) {
                "r" -> {
                    seed = Random.nextLong()
                    regenerate()
                }
                "m" -> {
                    // Toggle Katakana mode
                    params = params.copy(katakanaMode = !params.katakanaMode)
                }
                "1" -> if (!params.katakanaMode) strokeType = 0  // Horizontal
                "2" -> if (!params.katakanaMode) strokeType = 1  // Vertical
                "3" -> if (!params.katakanaMode) strokeType = 2  // Curved
                "4" -> if (!params.katakanaMode) strokeType = 3  // S-curve
                "t" -> {
                    useStacking = !useStacking
                    if (useStacking && params.stackCount < 3) {
                        params = params.copy(stackCount = 3)
                    }
                }
                "d" -> params = params.copy(showDebug = !params.showDebug)
                "[" -> params = params.copy(brushWidth = (params.brushWidth - 5).coerceIn(20.0, 150.0))
                "]" -> params = params.copy(brushWidth = (params.brushWidth + 5).coerceIn(20.0, 150.0))
                "-" -> params = params.copy(profilePower = (params.profilePower - 0.2).coerceIn(1.0, 5.0))
                "=" -> params = params.copy(profilePower = (params.profilePower + 0.2).coerceIn(1.0, 5.0))
                "k" -> params = params.copy(bristleFreq = (params.bristleFreq + 10).coerceIn(20.0, 150.0))
                "j" -> params = params.copy(bristleFreq = (params.bristleFreq - 10).coerceIn(20.0, 150.0))
                "w" -> params = params.copy(dryRate = (params.dryRate + 0.2).coerceIn(0.5, 5.0))
                "s" -> params = params.copy(dryRate = (params.dryRate - 0.2).coerceIn(0.5, 5.0))
                "q" -> params = params.copy(edgeRoughness = (params.edgeRoughness + 0.01).coerceIn(0.0, 0.15))
                "a" -> params = params.copy(edgeRoughness = (params.edgeRoughness - 0.01).coerceIn(0.0, 0.15))
                
                // Katakana navigation (arrow keys)
                "arrow-right" -> {
                    if (params.katakanaMode) {
                        val newIndex = (params.katakanaIndex + 1) % FLAT_BRUSH_KATAKANA.size
                        params = params.copy(katakanaIndex = newIndex)
                    } else {
                        params = params.copy(stackCount = (params.stackCount + 1).coerceIn(1, 8))
                    }
                }
                "arrow-left" -> {
                    if (params.katakanaMode) {
                        val newIndex = if (params.katakanaIndex <= 0) 
                            FLAT_BRUSH_KATAKANA.lastIndex 
                        else 
                            params.katakanaIndex - 1
                        params = params.copy(katakanaIndex = newIndex)
                    } else {
                        params = params.copy(stackCount = (params.stackCount - 1).coerceIn(1, 8))
                    }
                }
                "arrow-up" -> {
                    if (params.katakanaMode) {
                        // Jump forward 10 characters
                        val newIndex = (params.katakanaIndex + 10) % FLAT_BRUSH_KATAKANA.size
                        params = params.copy(katakanaIndex = newIndex)
                    } else {
                        params = params.copy(stackCount = (params.stackCount + 1).coerceIn(1, 8))
                    }
                }
                "arrow-down" -> {
                    if (params.katakanaMode) {
                        // Jump backward 10 characters
                        val newIndex = if (params.katakanaIndex < 10) 
                            FLAT_BRUSH_KATAKANA.size - (10 - params.katakanaIndex)
                        else 
                            params.katakanaIndex - 10
                        params = params.copy(katakanaIndex = newIndex.coerceIn(0, FLAT_BRUSH_KATAKANA.lastIndex))
                    } else {
                        params = params.copy(stackCount = (params.stackCount - 1).coerceIn(1, 8))
                    }
                }
                " " -> {
                    if (params.katakanaMode) {
                        // Random character + reseed
                        val rng = Random(System.currentTimeMillis())
                        params = params.copy(katakanaIndex = rng.nextInt(FLAT_BRUSH_KATAKANA.size))
                        seed = Random.nextLong()
                        regenerate()
                    }
                }
                
                "e" -> {
                    drawer.isolatedWithTarget(exportTarget) {
                        render(drawer)
                    }
                    exportFlatBrushPNG(exportTarget, params)
                }
            }
        }
        
        extend {
            render(drawer)
        }
    }
}
