package drawing

import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.color.mix
import org.openrndr.color.rgb
import org.openrndr.draw.*
import org.openrndr.extra.noise.simplex
import org.openrndr.extra.shapes.hobbyCurve
import org.openrndr.math.Vector2
import org.openrndr.shape.ShapeContour
import org.openrndr.shape.contour
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.*
import kotlin.random.Random

// =============================================================================
// HOBBY STRATA GRADIENTS - Generative Art Sketch
// =============================================================================
// Creates horizontal slices separated by non-intersecting Hobby curves,
// with each slice filled by a distinct gradient color field.

// =============================================================================
// ENUMS
// =============================================================================

/**
 * Gradient direction mode for slice fills
 */
enum class GradientMode {
    VERTICAL,    // Top→Bottom gradient within slice
    HORIZONTAL,  // Left→Right gradient
    DIAGONAL     // Diagonal gradient (top-left → bottom-right)
}

/**
 * Color palette mode for strata
 */
enum class StrataPaletteMode {
    ATMOSPHERIC,  // Earth tones at bottom → pale sky at top
    OCEANIC,      // Deep blues → teals → light surface greens
    DESERT,       // Warm oranges → reds → cool sky
    FOREST,       // Deep greens → browns → light leaf greens
    TWILIGHT,     // Deep purples → magentas → sunset oranges
    GLACIAL,      // Charcoal blue → icy blue → near-white highlights
    AURORA,       // Deep navy → emerald/teal → neon lime → faint lavender
    VOLCANIC,     // Basalt gray → ember red → lava orange → ash beige
    ARCTIC_NIGHT, // Black-blue → indigo → cold cyan glow → pale mist
    MONSOON,      // Deep slate → storm blue → rain teal → humid gray-green
    MOSSY_STONE,  // Dark olive → moss green → lichen yellow-green → stone gray
    ALPINE,       // Pine green → granite → snow blue-white → thin-sky cyan
    CANYON,       // Dark umber → rust → sandstone → washed sky blue
    CORAL_REEF,   // Deep blue → reef teal → coral pink/orange → sunlit aqua
    TROPICAL_LAGOON, // Deep teal → turquoise → mint → sun-bleached sand
    SUNRISE,      // Night blue → violet → hot pink → peach → pale gold
    DUSK,         // Warm gray → mauve → plum → deep navy
    NEON_CITY,    // Near-black → electric purple → cyan → acid yellow accents
    CYBERPUNK,    // Black → magenta → violet → cyan (high contrast, saturated)
    RETRO_PASTEL, // Dusty teal → faded peach → buttercream → soft lavender
    MINT_CHOCOLATE, // Espresso brown → cocoa → sage/mint → cream
    INK_AND_PAPER, // Ink black → graphite → warm gray → off-white
    SMOKE,        // Black → charcoal → cool gray → fog white
    HEATMAP,      // Deep purple → red → orange → yellow → near-white
    TOXIC_SLIME   // Dark swamp green → vivid chartreuse → sickly yellow → chalky white
}

/**
 * Boundary separation mode for visual distinction between slices
 */
enum class SeparationMode {
    OUTLINE,          // Simple stroke on each boundary curve
    GAP,              // Micro-gap between slices (inset polygons)
    DUAL_STROKE,      // Dark + light offset strokes for crisp separation
    SOFT_FADE,        // Boundary is a short cross-fade band between palettes (no hard edge)
    FEATHERED_EDGE,   // Per-pixel dither/feather along the curve for a “painted” transition
    GRADIENT_RIM,     // Thin rim gradient: dark on one side → light on the other
    INNER_GLOW,       // Glow biased inward to one region (helps readability on dark fields)
    OUTER_GLOW,       // Glow biased outward (good for “sticker” look)
    SHADOW_CUT,       // Subtle drop shadow along boundary to fake depth/stacking
    BEVEL,            // Highlight + shadow pair to simulate an embossed seam
    EMBOSS,           // Similar to BEVEL but centered on the curve (raised seam)
    DASHED_STROKE,    // Stroke is dashed; dash length can follow curvature or pattern
    DOTTED_STROKE,    // Dot markers spaced along the curve
    ZIGZAG_STITCH,    // Tiny alternating offsets (stitching / serrated edge)
    WAVE_SEAM,        // Boundary curve is perturbed into a gentle sinusoidal seam
    NOISE_JITTER,     // Small random displacement of the boundary for organic separation
    RIBBON,           // A thin band polygon centered on the boundary (constant width)
    TRIM_BAND,        // Two parallel strokes with a fill band between (like road markings)
    INSET_BORDER,     // Border drawn slightly inside each region (2 borders, no overlap)
    OVERPRINT,        // Multiply/overlay blend on a narrow band to “ink-mix” at the edge
    HALO_CUTOUT,      // Knockout band that reveals background (like a masked separator)
    NOTCHED,          // Periodic notches cut into the edge (ticket-tear vibe)
    TICK_MARKS,       // Short perpendicular ticks along the boundary (topographic feel)
    HATCH_BAND,       // Narrow band filled with hatching lines (angle/spacing configurable)
    MOSAIC_BAND,      // Separator band made of tiny tiles/rects/triangles
    GLOW_DUAL,        // Dual strokes but with a faint glow on the light stroke
    DEPTH_STACK,      // One region appears above: top edge highlight + bottom shadow
    IRREGULAR_GAP      // Like GAP, but width varies smoothly (breathing seam)
}

// =============================================================================
// DATA CLASSES
// =============================================================================

/**
 * Global parameters controlling the sketch
 */
data class StrataParams(
    val seed: Long = System.currentTimeMillis(),
    val sliceCount: Int = 7,              // Number of horizontal slices
    val controlPts: Int = 8,              // Control points per boundary curve (5-12)
    val tension: Double = 0.95,           // Hobby curve tension (0.85-1.2)
    val amplitude: Double = 25.0,         // Y-axis noise amplitude (10-40 px)
    val gradientMode: GradientMode = GradientMode.VERTICAL,
    val paletteMode: StrataPaletteMode = StrataPaletteMode.ATMOSPHERIC,
    val separationMode: SeparationMode = SeparationMode.OUTLINE,
    val showDebug: Boolean = false        // Show control points and curve indices
)

// =============================================================================
// COLOR PALETTES
// =============================================================================

/**
 * Atmospheric palette: earth tones at bottom → pale sky at top
 */
val ATMOSPHERIC_PALETTE: List<List<ColorRGBa>> = listOf(
    // Bottom layers (earth tones)
    listOf(rgb(0.25, 0.18, 0.12), rgb(0.35, 0.28, 0.22)),     // Dark earth
    listOf(rgb(0.42, 0.32, 0.24), rgb(0.55, 0.42, 0.32)),     // Brown earth
    listOf(rgb(0.58, 0.48, 0.38), rgb(0.68, 0.55, 0.45)),     // Tan
    listOf(rgb(0.72, 0.62, 0.52), rgb(0.78, 0.68, 0.58)),     // Sand
    listOf(rgb(0.80, 0.72, 0.62), rgb(0.85, 0.78, 0.70)),     // Light sand
    listOf(rgb(0.82, 0.78, 0.72), rgb(0.88, 0.85, 0.80)),     // Cream
    listOf(rgb(0.85, 0.82, 0.78), rgb(0.90, 0.88, 0.85)),     // Pale cream
    listOf(rgb(0.88, 0.86, 0.84), rgb(0.92, 0.90, 0.88)),     // Very light
    listOf(rgb(0.90, 0.88, 0.86), rgb(0.94, 0.92, 0.90)),     // Near white
    listOf(rgb(0.92, 0.90, 0.88), rgb(0.96, 0.94, 0.92))      // Pale sky
)

/**
 * Oceanic palette: deep blues → teals → light surface greens
 */
val OCEANIC_PALETTE: List<List<ColorRGBa>> = listOf(
    // Bottom layers (deep ocean)
    listOf(rgb(0.05, 0.08, 0.18), rgb(0.08, 0.12, 0.25)),     // Abyss
    listOf(rgb(0.08, 0.15, 0.28), rgb(0.12, 0.20, 0.35)),     // Deep navy
    listOf(rgb(0.12, 0.22, 0.38), rgb(0.18, 0.30, 0.48)),     // Navy blue
    listOf(rgb(0.15, 0.32, 0.48), rgb(0.22, 0.42, 0.55)),     // Ocean blue
    listOf(rgb(0.18, 0.42, 0.52), rgb(0.25, 0.52, 0.58)),     // Deep teal
    listOf(rgb(0.22, 0.52, 0.55), rgb(0.32, 0.60, 0.58)),     // Teal
    listOf(rgb(0.30, 0.58, 0.55), rgb(0.42, 0.65, 0.58)),     // Sea green
    listOf(rgb(0.45, 0.65, 0.58), rgb(0.55, 0.72, 0.62)),     // Light teal
    listOf(rgb(0.55, 0.72, 0.62), rgb(0.68, 0.78, 0.68)),     // Surface green
    listOf(rgb(0.72, 0.82, 0.75), rgb(0.85, 0.90, 0.85))      // Foam
)

/**
 * Desert palette: warm oranges -> reds -> cool sky
 */
val DESERT_PALETTE: List<List<ColorRGBa>> = listOf(
    listOf(rgb(0.35, 0.15, 0.10), rgb(0.45, 0.20, 0.15)),     // Dark canyon
    listOf(rgb(0.55, 0.25, 0.15), rgb(0.65, 0.35, 0.20)),     // Red rock
    listOf(rgb(0.70, 0.40, 0.25), rgb(0.80, 0.50, 0.30)),     // Rust
    listOf(rgb(0.85, 0.55, 0.35), rgb(0.90, 0.65, 0.40)),     // Ochre
    listOf(rgb(0.95, 0.70, 0.45), rgb(0.98, 0.75, 0.50)),     // Sandstone
    listOf(rgb(0.98, 0.80, 0.55), rgb(1.00, 0.85, 0.60)),     // Warm sand
    listOf(rgb(1.00, 0.90, 0.70), rgb(1.00, 0.92, 0.75)),     // Pale sand
    listOf(rgb(1.00, 0.94, 0.80), rgb(1.00, 0.96, 0.85)),     // Near white
    listOf(rgb(0.95, 0.95, 0.98), rgb(0.90, 0.92, 0.98)),     // Cool sky
    listOf(rgb(0.85, 0.88, 0.98), rgb(0.80, 0.85, 0.98))      // Blue sky
)

/**
 * Forest palette: deep greens -> browns -> light leaf greens
 */
val FOREST_PALETTE: List<List<ColorRGBa>> = listOf(
    listOf(rgb(0.12, 0.15, 0.08), rgb(0.18, 0.22, 0.12)),     // Deep forest floor
    listOf(rgb(0.22, 0.28, 0.15), rgb(0.28, 0.35, 0.18)),     // Dark moss
    listOf(rgb(0.32, 0.42, 0.22), rgb(0.38, 0.48, 0.25)),     // Pine
    listOf(rgb(0.42, 0.52, 0.28), rgb(0.48, 0.58, 0.32)),     // Fern
    listOf(rgb(0.52, 0.62, 0.35), rgb(0.58, 0.68, 0.40)),     // Leaf green
    listOf(rgb(0.62, 0.72, 0.45), rgb(0.68, 0.78, 0.50)),     // Bright green
    listOf(rgb(0.72, 0.82, 0.55), rgb(0.78, 0.88, 0.60)),     // Lime
    listOf(rgb(0.85, 0.92, 0.70), rgb(0.90, 0.95, 0.75)),     // Sunlit leaf
    listOf(rgb(0.95, 0.98, 0.85), rgb(0.98, 1.00, 0.90)),     // Pale yellow
    listOf(rgb(0.90, 0.95, 0.98), rgb(0.85, 0.92, 0.96))      // Overcast sky
)

/**
 * Twilight palette: deep purples -> magentas -> sunset oranges
 */
val TWILIGHT_PALETTE: List<List<ColorRGBa>> = listOf(
    listOf(rgb(0.08, 0.05, 0.15), rgb(0.12, 0.08, 0.22)),     // Midnight
    listOf(rgb(0.15, 0.10, 0.28), rgb(0.22, 0.15, 0.35)),     // Deep indigo
    listOf(rgb(0.30, 0.15, 0.42), rgb(0.40, 0.20, 0.55)),     // Purple
    listOf(rgb(0.55, 0.20, 0.55), rgb(0.65, 0.25, 0.60)),     // Magenta
    listOf(rgb(0.75, 0.30, 0.55), rgb(0.85, 0.35, 0.50)),     // Pink dusk
    listOf(rgb(0.90, 0.45, 0.45), rgb(0.95, 0.55, 0.40)),     // Coral
    listOf(rgb(0.98, 0.65, 0.35), rgb(1.00, 0.75, 0.30)),     // Sunset orange
    listOf(rgb(1.00, 0.85, 0.40), rgb(1.00, 0.90, 0.50)),     // Golden hour
    listOf(rgb(0.95, 0.95, 0.70), rgb(0.90, 0.92, 0.80)),     // Pale glow
    listOf(rgb(0.15, 0.15, 0.25), rgb(0.10, 0.10, 0.20))      // Deep sky
)

/**
 * Glacial palette: charcoal blue → icy blue → near-white highlights
 */
val GLACIAL_PALETTE: List<List<ColorRGBa>> = listOf(
    listOf(rgb(0.08, 0.10, 0.15), rgb(0.12, 0.15, 0.22)),     // Deep charcoal
    listOf(rgb(0.15, 0.20, 0.28), rgb(0.20, 0.28, 0.38)),     // Dark arctic blue
    listOf(rgb(0.25, 0.35, 0.48), rgb(0.35, 0.45, 0.58)),     // Cold blue
    listOf(rgb(0.40, 0.55, 0.68), rgb(0.50, 0.65, 0.78)),     // Icy blue
    listOf(rgb(0.60, 0.75, 0.85), rgb(0.70, 0.85, 0.92)),     // Glacial teal
    listOf(rgb(0.75, 0.88, 0.95), rgb(0.80, 0.92, 0.98)),     // Light ice
    listOf(rgb(0.85, 0.95, 0.98), rgb(0.90, 0.97, 1.00)),     // Arctic glow
    listOf(rgb(0.92, 0.98, 1.00), rgb(0.95, 0.99, 1.00)),     // Near white
    listOf(rgb(0.96, 0.99, 1.00), rgb(0.98, 1.00, 1.00)),     // Pure white ice
    listOf(rgb(0.98, 1.00, 1.00), rgb(1.00, 1.00, 1.00))      // Brightest highlights
)

/**
 * Aurora palette: deep navy → emerald/teal → neon lime → faint lavender
 */
val AURORA_PALETTE: List<List<ColorRGBa>> = listOf(
    listOf(rgb(0.02, 0.05, 0.12), rgb(0.05, 0.08, 0.18)),     // Deep navy
    listOf(rgb(0.05, 0.15, 0.25), rgb(0.08, 0.25, 0.35)),     // Night sky
    listOf(rgb(0.00, 0.40, 0.30), rgb(0.10, 0.55, 0.45)),     // Emerald
    listOf(rgb(0.15, 0.65, 0.50), rgb(0.25, 0.80, 0.60)),     // Teal glow
    listOf(rgb(0.40, 0.90, 0.40), rgb(0.60, 1.00, 0.30)),     // Neon lime
    listOf(rgb(0.70, 1.00, 0.50), rgb(0.85, 1.00, 0.70)),     // Bright aurora
    listOf(rgb(0.75, 0.85, 0.95), rgb(0.85, 0.75, 0.98)),     // Lavender mist
    listOf(rgb(0.80, 0.65, 0.90), rgb(0.70, 0.55, 0.85)),     // Faint violet
    listOf(rgb(0.05, 0.08, 0.15), rgb(0.02, 0.05, 0.10)),     // Dark return
    listOf(rgb(0.01, 0.02, 0.05), rgb(0.00, 0.01, 0.03))      // Deepest night
)

/**
 * Volcanic palette: basalt gray → ember red → lava orange → ash beige
 */
val VOLCANIC_PALETTE: List<List<ColorRGBa>> = listOf(
    listOf(rgb(0.10, 0.10, 0.12), rgb(0.15, 0.15, 0.18)),     // Basalt
    listOf(rgb(0.20, 0.18, 0.20), rgb(0.25, 0.22, 0.25)),     // Dark rock
    listOf(rgb(0.45, 0.05, 0.05), rgb(0.65, 0.10, 0.05)),     // Ember red
    listOf(rgb(0.75, 0.15, 0.05), rgb(0.85, 0.25, 0.05)),     // Hot lava
    listOf(rgb(0.95, 0.40, 0.05), rgb(1.00, 0.55, 0.10)),     // Orange flow
    listOf(rgb(1.00, 0.70, 0.20), rgb(1.00, 0.85, 0.40)),     // Molten gold
    listOf(rgb(0.80, 0.75, 0.70), rgb(0.70, 0.65, 0.60)),     // Ash gray
    listOf(rgb(0.60, 0.55, 0.52), rgb(0.85, 0.82, 0.78)),     // Ash beige
    listOf(rgb(0.92, 0.90, 0.88), rgb(0.95, 0.94, 0.92)),     // Pale ash
    listOf(rgb(0.98, 0.98, 0.96), rgb(1.00, 1.00, 0.98))      // Steam
)

/**
 * Arctic Night palette: black-blue → indigo → cold cyan glow → pale mist
 */
val ARCTIC_NIGHT_PALETTE: List<List<ColorRGBa>> = listOf(
    listOf(rgb(0.02, 0.03, 0.08), rgb(0.04, 0.06, 0.12)),     // Black-blue
    listOf(rgb(0.06, 0.10, 0.22), rgb(0.10, 0.15, 0.32)),     // Midnight blue
    listOf(rgb(0.12, 0.20, 0.45), rgb(0.18, 0.28, 0.55)),     // Indigo
    listOf(rgb(0.15, 0.35, 0.65), rgb(0.20, 0.45, 0.75)),     // Cold cyan glow
    listOf(rgb(0.30, 0.60, 0.85), rgb(0.45, 0.75, 0.95)),     // Arctic cyan
    listOf(rgb(0.60, 0.85, 0.98), rgb(0.75, 0.92, 1.00)),     // Ice light
    listOf(rgb(0.85, 0.95, 1.00), rgb(0.92, 0.98, 1.00)),     // Pale mist
    listOf(rgb(0.95, 0.98, 1.00), rgb(0.98, 1.00, 1.00)),     // Fog
    listOf(rgb(0.10, 0.15, 0.25), rgb(0.05, 0.10, 0.20)),     // Distant horizon
    listOf(rgb(0.02, 0.04, 0.08), rgb(0.01, 0.02, 0.05))      // Deep night
)

/**
 * Monsoon palette: deep slate → storm blue → rain teal → humid gray-green
 */
val MONSOON_PALETTE: List<List<ColorRGBa>> = listOf(
    listOf(rgb(0.12, 0.15, 0.18), rgb(0.18, 0.22, 0.25)),     // Deep slate
    listOf(rgb(0.22, 0.28, 0.35), rgb(0.28, 0.35, 0.42)),     // Storm blue
    listOf(rgb(0.32, 0.42, 0.48), rgb(0.38, 0.48, 0.55)),     // Rain blue
    listOf(rgb(0.35, 0.52, 0.55), rgb(0.42, 0.58, 0.62)),     // Teal rain
    listOf(rgb(0.45, 0.62, 0.58), rgb(0.52, 0.68, 0.62)),     // Humid green
    listOf(rgb(0.55, 0.72, 0.68), rgb(0.62, 0.78, 0.72)),     // Gray-green
    listOf(rgb(0.68, 0.75, 0.72), rgb(0.75, 0.82, 0.78)),     // Wet stone
    listOf(rgb(0.78, 0.85, 0.82), rgb(0.82, 0.88, 0.85)),     // Mist
    listOf(rgb(0.85, 0.90, 0.88), rgb(0.88, 0.92, 0.90)),     // Pale sky
    listOf(rgb(0.90, 0.92, 0.92), rgb(0.92, 0.94, 0.94))      // Overcast
)

/**
 * Mossy Stone palette: dark olive → moss green → lichen yellow-green → stone gray
 */
val MOSSY_STONE_PALETTE: List<List<ColorRGBa>> = listOf(
    listOf(rgb(0.15, 0.18, 0.12), rgb(0.22, 0.25, 0.15)),     // Dark olive
    listOf(rgb(0.25, 0.32, 0.18), rgb(0.32, 0.42, 0.22)),     // Moss green
    listOf(rgb(0.38, 0.48, 0.25), rgb(0.45, 0.58, 0.32)),     // Deep lichen
    listOf(rgb(0.52, 0.65, 0.38), rgb(0.62, 0.75, 0.45)),     // Yellow-green
    listOf(rgb(0.72, 0.82, 0.55), rgb(0.82, 0.88, 0.65)),     // Bright lichen
    listOf(rgb(0.65, 0.68, 0.62), rgb(0.55, 0.58, 0.55)),     // Stone gray
    listOf(rgb(0.45, 0.48, 0.45), rgb(0.35, 0.38, 0.35)),     // Dark stone
    listOf(rgb(0.28, 0.32, 0.28), rgb(0.22, 0.25, 0.22)),     // Deep shade
    listOf(rgb(0.85, 0.88, 0.82), rgb(0.92, 0.95, 0.88)),     // Pale sunlight
    listOf(rgb(0.95, 0.98, 0.92), rgb(0.98, 1.00, 0.95))      // Diffuse light
)

/**
 * Alpine palette: pine green → granite → snow blue-white → thin-sky cyan
 */
val ALPINE_PALETTE: List<List<ColorRGBa>> = listOf(
    listOf(rgb(0.05, 0.15, 0.08), rgb(0.10, 0.25, 0.15)),     // Deep pine
    listOf(rgb(0.15, 0.32, 0.22), rgb(0.22, 0.42, 0.30)),     // Forest
    listOf(rgb(0.35, 0.38, 0.42), rgb(0.45, 0.48, 0.52)),     // Granite gray
    listOf(rgb(0.55, 0.58, 0.62), rgb(0.65, 0.68, 0.72)),     // Light rock
    listOf(rgb(0.75, 0.85, 0.92), rgb(0.85, 0.92, 0.98)),     // Snow shadow
    listOf(rgb(0.92, 0.95, 1.00), rgb(0.98, 1.00, 1.00)),     // Fresh snow
    listOf(rgb(0.85, 0.98, 1.00), rgb(0.75, 0.95, 1.00)),     // Icy cyan
    listOf(rgb(0.65, 0.90, 1.00), rgb(0.55, 0.85, 1.00)),     // Thin sky
    listOf(rgb(0.45, 0.75, 1.00), rgb(0.35, 0.65, 0.95)),     // Alpine blue
    listOf(rgb(0.25, 0.55, 0.85), rgb(0.15, 0.45, 0.75))      // High altitude
)

/**
 * Canyon palette: dark umber → rust → sandstone → washed sky blue
 */
val CANYON_PALETTE: List<List<ColorRGBa>> = listOf(
    listOf(rgb(0.18, 0.12, 0.08), rgb(0.25, 0.18, 0.12)),     // Dark umber
    listOf(rgb(0.35, 0.22, 0.15), rgb(0.45, 0.28, 0.18)),     // Deep rust
    listOf(rgb(0.55, 0.35, 0.22), rgb(0.65, 0.42, 0.28)),     // Red sandstone
    listOf(rgb(0.75, 0.52, 0.35), rgb(0.82, 0.58, 0.42)),     // Sandstone
    listOf(rgb(0.88, 0.65, 0.48), rgb(0.92, 0.72, 0.55)),     // Warm rock
    listOf(rgb(0.95, 0.82, 0.65), rgb(0.98, 0.88, 0.75)),     // Pale sand
    listOf(rgb(0.92, 0.95, 0.98), rgb(0.85, 0.92, 0.98)),     // Sky horizon
    listOf(rgb(0.75, 0.88, 0.98), rgb(0.65, 0.82, 0.95)),     // Washed sky blue
    listOf(rgb(0.55, 0.75, 0.92), rgb(0.45, 0.68, 0.88)),     // Desert blue
    listOf(rgb(0.35, 0.55, 0.82), rgb(0.25, 0.45, 0.75))      // Deep sky
)

/**
 * Coral Reef palette: deep blue → reef teal → coral pink/orange → sunlit aqua
 */
val CORAL_REEF_PALETTE: List<List<ColorRGBa>> = listOf(
    listOf(rgb(0.02, 0.08, 0.25), rgb(0.05, 0.15, 0.35)),     // Deep abyss
    listOf(rgb(0.08, 0.25, 0.45), rgb(0.12, 0.35, 0.55)),     // Ocean blue
    listOf(rgb(0.15, 0.45, 0.55), rgb(0.20, 0.55, 0.62)),     // Reef teal
    listOf(rgb(0.25, 0.65, 0.68), rgb(0.35, 0.75, 0.72)),     // Bright teal
    listOf(rgb(0.95, 0.45, 0.45), rgb(1.00, 0.55, 0.50)),     // Coral pink
    listOf(rgb(1.00, 0.65, 0.45), rgb(1.00, 0.75, 0.55)),     // Coral orange
    listOf(rgb(1.00, 0.85, 0.65), rgb(0.95, 0.95, 0.75)),     // Sunlit reef
    listOf(rgb(0.75, 0.95, 0.85), rgb(0.55, 0.92, 0.92)),     // Shallow aqua
    listOf(rgb(0.35, 0.85, 0.92), rgb(0.25, 0.75, 0.88)),     // Tropical blue
    listOf(rgb(0.15, 0.55, 0.75), rgb(0.10, 0.45, 0.65))      // Surface light
)

/**
 * Tropical Lagoon palette: deep teal → turquoise → mint → sun-bleached sand
 */
val TROPICAL_LAGOON_PALETTE: List<List<ColorRGBa>> = listOf(
    listOf(rgb(0.05, 0.25, 0.28), rgb(0.08, 0.35, 0.38)),     // Deep teal
    listOf(rgb(0.12, 0.45, 0.48), rgb(0.18, 0.55, 0.58)),     // Dark turquoise
    listOf(rgb(0.25, 0.65, 0.68), rgb(0.35, 0.75, 0.78)),     // Lagoon blue
    listOf(rgb(0.45, 0.85, 0.82), rgb(0.55, 0.92, 0.88)),     // Turquoise
    listOf(rgb(0.65, 0.95, 0.82), rgb(0.75, 0.98, 0.88)),     // Bright mint
    listOf(rgb(0.85, 1.00, 0.92), rgb(0.92, 1.00, 0.95)),     // Mint white
    listOf(rgb(0.98, 0.95, 0.85), rgb(1.00, 0.98, 0.92)),     // Sun-bleached sand
    listOf(rgb(0.95, 0.88, 0.75), rgb(0.88, 0.82, 0.68)),     // Warm sand
    listOf(rgb(0.82, 0.75, 0.62), rgb(0.75, 0.68, 0.55)),     // Deep sand
    listOf(rgb(0.65, 0.58, 0.48), rgb(0.55, 0.48, 0.38))      // Shoreline
)

/**
 * Sunrise palette: night blue → violet → hot pink → peach → pale gold
 */
val SUNRISE_PALETTE: List<List<ColorRGBa>> = listOf(
    listOf(rgb(0.02, 0.05, 0.15), rgb(0.05, 0.08, 0.25)),     // Night blue
    listOf(rgb(0.15, 0.10, 0.35), rgb(0.25, 0.15, 0.45)),     // Deep violet
    listOf(rgb(0.35, 0.20, 0.55), rgb(0.55, 0.25, 0.65)),     // Purple glow
    listOf(rgb(0.75, 0.15, 0.55), rgb(0.95, 0.20, 0.45)),     // Hot pink
    listOf(rgb(1.00, 0.40, 0.40), rgb(1.00, 0.55, 0.45)),     // Coral sunrise
    listOf(rgb(1.00, 0.65, 0.45), rgb(1.00, 0.75, 0.50)),     // Peach
    listOf(rgb(1.00, 0.85, 0.55), rgb(1.00, 0.92, 0.65)),     // Golden hour
    listOf(rgb(1.00, 0.95, 0.75), rgb(1.00, 0.98, 0.85)),     // Pale gold
    listOf(rgb(0.98, 0.98, 0.92), rgb(0.95, 0.95, 0.98)),     // Morning sky
    listOf(rgb(0.85, 0.88, 0.98), rgb(0.75, 0.82, 0.98))      // Blue horizon
)

/**
 * Dusk palette: warm gray → mauve → plum → deep navy
 */
val DUSK_PALETTE: List<List<ColorRGBa>> = listOf(
    listOf(rgb(0.25, 0.22, 0.20), rgb(0.35, 0.32, 0.30)),     // Warm gray
    listOf(rgb(0.45, 0.38, 0.40), rgb(0.55, 0.48, 0.52)),     // Mauve shadow
    listOf(rgb(0.62, 0.52, 0.58), rgb(0.55, 0.42, 0.52)),     // Muted plum
    listOf(rgb(0.45, 0.32, 0.45), rgb(0.35, 0.22, 0.38)),     // Deep plum
    listOf(rgb(0.28, 0.15, 0.32), rgb(0.22, 0.10, 0.28)),     // Dark violet
    listOf(rgb(0.15, 0.08, 0.22), rgb(0.10, 0.05, 0.18)),     // Nightfall
    listOf(rgb(0.08, 0.05, 0.15), rgb(0.05, 0.03, 0.12)),     // Twilight blue
    listOf(rgb(0.03, 0.02, 0.10), rgb(0.02, 0.01, 0.08)),     // Deep navy
    listOf(rgb(0.01, 0.01, 0.05), rgb(0.00, 0.00, 0.03)),     // Obsidian
    listOf(rgb(0.00, 0.00, 0.01), rgb(0.00, 0.00, 0.00))      // Absolute night
)

/**
 * Neon City palette: near-black → electric purple → cyan → acid yellow accents
 */
val NEON_CITY_PALETTE: List<List<ColorRGBa>> = listOf(
    listOf(rgb(0.02, 0.01, 0.05), rgb(0.05, 0.02, 0.10)),     // Near-black
    listOf(rgb(0.15, 0.05, 0.25), rgb(0.25, 0.08, 0.45)),     // Deep purple
    listOf(rgb(0.45, 0.10, 0.75), rgb(0.65, 0.15, 0.95)),     // Electric purple
    listOf(rgb(0.55, 0.35, 0.95), rgb(0.45, 0.55, 1.00)),     // Violet-blue
    listOf(rgb(0.15, 0.75, 1.00), rgb(0.25, 0.85, 0.95)),     // Cyan neon
    listOf(rgb(0.35, 0.95, 0.85), rgb(0.55, 1.00, 0.75)),     // Bright aqua
    listOf(rgb(0.75, 1.00, 0.45), rgb(0.85, 1.00, 0.15)),     // Acid yellow
    listOf(rgb(0.95, 1.00, 0.35), rgb(1.00, 0.95, 0.55)),     // Neon highlight
    listOf(rgb(0.10, 0.05, 0.15), rgb(0.05, 0.02, 0.08)),     // Asphalt shadows
    listOf(rgb(0.02, 0.01, 0.04), rgb(0.01, 0.00, 0.02))      // Dark alley
)

/**
 * Cyberpunk palette: black → magenta → violet → cyan (high contrast)
 */
val CYBERPUNK_PALETTE: List<List<ColorRGBa>> = listOf(
    listOf(rgb(0.0, 0.0, 0.0), rgb(0.05, 0.0, 0.1)),          // Black
    listOf(rgb(0.2, 0.0, 0.3), rgb(0.4, 0.0, 0.5)),          // Dark violet
    listOf(rgb(0.6, 0.0, 0.7), rgb(0.8, 0.0, 0.9)),          // Magenta
    listOf(rgb(1.0, 0.0, 0.8), rgb(1.0, 0.2, 0.9)),          // Hot pink
    listOf(rgb(0.8, 0.3, 1.0), rgb(0.6, 0.5, 1.0)),          // Electric violet
    listOf(rgb(0.3, 0.7, 1.0), rgb(0.0, 0.9, 1.0)),          // Cyan
    listOf(rgb(0.0, 1.0, 1.0), rgb(0.3, 1.0, 0.9)),          // Bright cyan
    listOf(rgb(0.1, 0.1, 0.2), rgb(0.0, 0.0, 0.1)),          // Deep shadow
    listOf(rgb(1.0, 0.0, 1.0), rgb(0.0, 1.0, 1.0)),          // High contrast split
    listOf(rgb(0.1, 0.0, 0.1), rgb(0.0, 0.0, 0.0))           // Grounding black
)

/**
 * Retro Pastel palette: dusty teal → faded peach → buttercream → soft lavender
 */
val RETRO_PASTEL_PALETTE: List<List<ColorRGBa>> = listOf(
    listOf(rgb(0.45, 0.62, 0.65), rgb(0.55, 0.68, 0.72)),     // Dusty teal
    listOf(rgb(0.65, 0.75, 0.78), rgb(0.75, 0.82, 0.85)),     // Muted blue
    listOf(rgb(0.92, 0.78, 0.72), rgb(0.95, 0.85, 0.78)),     // Faded peach
    listOf(rgb(0.98, 0.88, 0.82), rgb(1.00, 0.92, 0.85)),     // Soft cream
    listOf(rgb(1.00, 0.95, 0.80), rgb(1.00, 0.98, 0.85)),     // Buttercream
    listOf(rgb(0.95, 0.92, 0.88), rgb(0.92, 0.88, 0.95)),     // Pale mist
    listOf(rgb(0.88, 0.82, 0.92), rgb(0.82, 0.75, 0.88)),     // Soft lavender
    listOf(rgb(0.78, 0.72, 0.85), rgb(0.72, 0.65, 0.82)),     // Muted violet
    listOf(rgb(0.65, 0.58, 0.72), rgb(0.55, 0.48, 0.62)),     // Dusty purple
    listOf(rgb(0.45, 0.38, 0.52), rgb(0.35, 0.28, 0.42))      // Deep retro
)

/**
 * Mint Chocolate palette: espresso brown → cocoa → sage/mint → cream
 */
val MINT_CHOCOLATE_PALETTE: List<List<ColorRGBa>> = listOf(
    listOf(rgb(0.15, 0.08, 0.05), rgb(0.22, 0.12, 0.08)),     // Espresso
    listOf(rgb(0.28, 0.18, 0.12), rgb(0.35, 0.25, 0.18)),     // Dark chocolate
    listOf(rgb(0.45, 0.35, 0.28), rgb(0.55, 0.45, 0.38)),     // Cocoa
    listOf(rgb(0.65, 0.55, 0.48), rgb(0.75, 0.65, 0.58)),     // Milk chocolate
    listOf(rgb(0.55, 0.72, 0.62), rgb(0.65, 0.78, 0.68)),     // Sage
    listOf(rgb(0.75, 0.88, 0.82), rgb(0.85, 0.95, 0.88)),     // Mint
    listOf(rgb(0.92, 1.00, 0.95), rgb(0.98, 1.00, 0.98)),     // Fresh mint
    listOf(rgb(1.00, 0.98, 0.92), rgb(1.00, 0.95, 0.88)),     // Cream
    listOf(rgb(0.95, 0.90, 0.82), rgb(0.88, 0.82, 0.75)),     // Vanilla
    listOf(rgb(0.75, 0.68, 0.62), rgb(0.65, 0.58, 0.52))      // Toasted cream
)

/**
 * Ink and Paper palette: ink black → graphite → warm gray → off-white
 */
val INK_AND_PAPER_PALETTE: List<List<ColorRGBa>> = listOf(
    listOf(rgb(0.05, 0.05, 0.08), rgb(0.08, 0.08, 0.12)),     // Ink black
    listOf(rgb(0.12, 0.12, 0.15), rgb(0.18, 0.18, 0.22)),     // Deep charcoal
    listOf(rgb(0.25, 0.25, 0.28), rgb(0.35, 0.35, 0.38)),     // Graphite
    listOf(rgb(0.45, 0.45, 0.48), rgb(0.55, 0.55, 0.58)),     // Dark gray
    listOf(rgb(0.65, 0.62, 0.60), rgb(0.75, 0.72, 0.70)),     // Warm gray
    listOf(rgb(0.82, 0.80, 0.78), rgb(0.88, 0.86, 0.84)),     // Paper gray
    listOf(rgb(0.92, 0.90, 0.88), rgb(0.95, 0.94, 0.92)),     // Off-white
    listOf(rgb(0.98, 0.97, 0.95), rgb(1.00, 0.99, 0.98)),     // Pure paper
    listOf(rgb(0.95, 0.92, 0.88), rgb(0.90, 0.85, 0.80)),     // Aged paper
    listOf(rgb(0.20, 0.20, 0.25), rgb(0.10, 0.10, 0.15))      // Dried ink
)

/**
 * Smoke palette: black → charcoal → cool gray → fog white
 */
val SMOKE_PALETTE: List<List<ColorRGBa>> = listOf(
    listOf(rgb(0.02, 0.02, 0.02), rgb(0.05, 0.05, 0.05)),     // Black
    listOf(rgb(0.08, 0.08, 0.10), rgb(0.15, 0.15, 0.18)),     // Charcoal
    listOf(rgb(0.22, 0.22, 0.25), rgb(0.32, 0.32, 0.35)),     // Deep smoke
    listOf(rgb(0.42, 0.42, 0.45), rgb(0.52, 0.52, 0.55)),     // Cool gray
    listOf(rgb(0.62, 0.62, 0.65), rgb(0.72, 0.72, 0.75)),     // Smoke gray
    listOf(rgb(0.82, 0.82, 0.85), rgb(0.88, 0.88, 0.90)),     // Fog gray
    listOf(rgb(0.92, 0.92, 0.95), rgb(0.96, 0.96, 0.98)),     // Fog white
    listOf(rgb(0.98, 0.98, 1.00), rgb(1.00, 1.00, 1.00)),     // Pure white
    listOf(rgb(0.85, 0.85, 0.88), rgb(0.75, 0.75, 0.78)),     // Dissipating
    listOf(rgb(0.55, 0.55, 0.58), rgb(0.45, 0.45, 0.48))      // Ashy smoke
)

/**
 * Heatmap palette: deep purple → red → orange → yellow → near-white
 */
val HEATMAP_PALETTE: List<List<ColorRGBa>> = listOf(
    listOf(rgb(0.15, 0.0, 0.25), rgb(0.25, 0.0, 0.45)),       // Deep purple
    listOf(rgb(0.45, 0.0, 0.35), rgb(0.65, 0.0, 0.15)),       // Dark red
    listOf(rgb(0.85, 0.0, 0.05), rgb(1.0, 0.1, 0.0)),         // Hot red
    listOf(rgb(1.0, 0.3, 0.0), rgb(1.0, 0.5, 0.0)),           // Orange
    listOf(rgb(1.0, 0.7, 0.0), rgb(1.0, 0.85, 0.0)),          // Gold
    listOf(rgb(1.0, 0.95, 0.1), rgb(0.95, 1.0, 0.3)),         // Yellow
    listOf(rgb(0.85, 1.0, 0.6), rgb(0.95, 1.0, 0.85)),        // White-hot yellow
    listOf(rgb(0.98, 1.0, 0.95), rgb(1.0, 1.0, 1.0)),         // White-hot
    listOf(rgb(0.6, 0.0, 0.0), rgb(0.3, 0.0, 0.1)),           // Cooling red
    listOf(rgb(0.1, 0.0, 0.2), rgb(0.05, 0.0, 0.1))           // Ambient purple
)

/**
 * Toxic Slime palette: dark swamp green → vivid chartreuse → sickly yellow → chalky white
 */
val TOXIC_SLIME_PALETTE: List<List<ColorRGBa>> = listOf(
    listOf(rgb(0.05, 0.12, 0.05), rgb(0.08, 0.18, 0.08)),     // Swamp green
    listOf(rgb(0.12, 0.25, 0.10), rgb(0.18, 0.35, 0.12)),     // Dark slime
    listOf(rgb(0.25, 0.55, 0.15), rgb(0.35, 0.75, 0.18)),     // Toxic green
    listOf(rgb(0.55, 0.95, 0.20), rgb(0.75, 1.00, 0.25)),     // Vivid chartreuse
    listOf(rgb(0.85, 1.00, 0.35), rgb(0.95, 1.00, 0.55)),     // Neon slime
    listOf(rgb(1.00, 0.95, 0.65), rgb(1.00, 0.98, 0.75)),     // Sickly yellow
    listOf(rgb(0.98, 1.00, 0.85), rgb(0.95, 0.98, 0.92)),     // Pale toxic
    listOf(rgb(0.92, 0.95, 0.95), rgb(0.95, 0.98, 0.98)),     // Chalky white
    listOf(rgb(0.45, 0.65, 0.35), rgb(0.25, 0.45, 0.15)),     // Mossy decay
    listOf(rgb(0.15, 0.25, 0.05), rgb(0.05, 0.10, 0.02))      // Deep ooze
)

/**
 * Get background color based on palette mode
 * Uses the lightest color from the palette (top of strata)
 */
fun getBackgroundColor(paletteMode: StrataPaletteMode): ColorRGBa {
    return when (paletteMode) {
        StrataPaletteMode.ATMOSPHERIC -> rgb(0.96, 0.94, 0.92)  // Pale sky (from top of atmospheric palette)
        StrataPaletteMode.OCEANIC -> rgb(0.85, 0.90, 0.85)      // Foam (from top of oceanic palette)
        StrataPaletteMode.DESERT -> rgb(0.80, 0.85, 0.98)       // Blue sky
        StrataPaletteMode.FOREST -> rgb(0.85, 0.92, 0.96)       // Overcast sky
        StrataPaletteMode.TWILIGHT -> rgb(0.10, 0.10, 0.20)     // Deep sky
        StrataPaletteMode.GLACIAL -> rgb(1.00, 1.00, 1.00)
        StrataPaletteMode.AURORA -> rgb(0.01, 0.02, 0.05)
        StrataPaletteMode.VOLCANIC -> rgb(1.00, 1.00, 0.98)
        StrataPaletteMode.ARCTIC_NIGHT -> rgb(0.01, 0.02, 0.05)
        StrataPaletteMode.MONSOON -> rgb(0.92, 0.94, 0.94)
        StrataPaletteMode.MOSSY_STONE -> rgb(0.98, 1.00, 0.95)
        StrataPaletteMode.ALPINE -> rgb(0.15, 0.45, 0.75)
        StrataPaletteMode.CANYON -> rgb(0.25, 0.45, 0.75)
        StrataPaletteMode.CORAL_REEF -> rgb(0.10, 0.45, 0.65)
        StrataPaletteMode.TROPICAL_LAGOON -> rgb(0.55, 0.48, 0.38)
        StrataPaletteMode.SUNRISE -> rgb(0.75, 0.82, 0.98)
        StrataPaletteMode.DUSK -> rgb(0.00, 0.00, 0.00)
        StrataPaletteMode.NEON_CITY -> rgb(0.01, 0.00, 0.02)
        StrataPaletteMode.CYBERPUNK -> rgb(0.0, 0.0, 0.0)
        StrataPaletteMode.RETRO_PASTEL -> rgb(0.35, 0.28, 0.42)
        StrataPaletteMode.MINT_CHOCOLATE -> rgb(0.65, 0.58, 0.52)
        StrataPaletteMode.INK_AND_PAPER -> rgb(0.10, 0.10, 0.15)
        StrataPaletteMode.SMOKE -> rgb(0.45, 0.45, 0.48)
        StrataPaletteMode.HEATMAP -> rgb(0.05, 0.0, 0.1)
        StrataPaletteMode.TOXIC_SLIME -> rgb(0.05, 0.10, 0.02)
    }
}

// =============================================================================
// NOISE FUNCTIONS
// =============================================================================

/**
 * Smooth noise for boundary curve Y positions
 */
fun boundaryNoise(seed: Long, boundaryIndex: Int, pointIndex: Int, freq: Double = 0.15): Double {
    val seedInt = seed.toInt()
    val x = pointIndex * freq
    val y = boundaryIndex * 1.7  // Different seed offset per boundary
    return simplex(seedInt, x, y)
}

// =============================================================================
// BOUNDARY CURVE FUNCTIONS
// =============================================================================

/**
 * Generate control points for a single boundary curve
 * @param boundaryIndex 0 = top boundary, n = bottom boundary
 * @param params Global parameters
 * @return List of control points for the Hobby curve
 */
fun boundaryControlPoints(boundaryIndex: Int, params: StrataParams): List<Vector2> {
    val points = mutableListOf<Vector2>()
    val canvasWidth = 600.0
    val canvasHeight = 800.0
    
    // Calculate base Y position for this boundary
    // Boundaries are evenly spaced from top to bottom
    val yBase = (boundaryIndex.toDouble() / params.sliceCount) * canvasHeight
    
    // Generate control points evenly spaced across width
    for (j in 0 until params.controlPts) {
        // X positions: evenly spaced across 600 px, extending slightly beyond edges
        val t = j.toDouble() / (params.controlPts - 1)
        val x = -10.0 + t * (canvasWidth + 20.0)  // Extend beyond edges for smooth curves
        
        // Y position: base + noise
        val noise = boundaryNoise(params.seed, boundaryIndex, j) * params.amplitude
        val y = yBase + noise
        
        points.add(Vector2(x, y))
    }
    
    return points
}

/**
 * Build a Hobby curve from control points
 */
fun hobbyBoundary(points: List<Vector2>, tension: Double): ShapeContour {
    val curl = tension.coerceIn(0.5, 2.0)
    return hobbyCurve(points, closed = false, curl = curl).contour
}

/**
 * Sample a boundary contour at regular intervals for shape building
 */
fun sampleBoundary(contour: ShapeContour, samples: Int): List<Vector2> {
    val result = mutableListOf<Vector2>()
    for (i in 0..samples) {
        val t = i.toDouble() / samples
        result.add(contour.position(t))
    }
    return result
}

/**
 * Generate all boundary curves ensuring non-intersection
 * Uses vertical spacing guarantee: y_base[i+1] - y_base[i] > 2*maxAmplitude + gapPx
 */
fun generateBoundaries(params: StrataParams): List<ShapeContour> {
    val boundaries = mutableListOf<ShapeContour>()
    
    // Generate n+1 boundary curves for n slices
    for (i in 0..params.sliceCount) {
        val controlPoints = boundaryControlPoints(i, params)
        val boundary = hobbyBoundary(controlPoints, params.tension)
        boundaries.add(boundary)
    }
    
    return boundaries
}

/**
 * Get all control points for debug display
 */
fun getAllControlPoints(params: StrataParams): List<Pair<Int, List<Vector2>>> {
    val result = mutableListOf<Pair<Int, List<Vector2>>>()
    for (i in 0..params.sliceCount) {
        result.add(Pair(i, boundaryControlPoints(i, params)))
    }
    return result
}

// =============================================================================
// SLICE SHAPE FUNCTIONS
// =============================================================================

/**
 * Build a closed shape for a slice region between two boundaries
 * @param topBoundary Upper boundary contour
 * @param bottomBoundary Lower boundary contour
 * @param topInset Function providing vertical inset for top edge at parameter t [0, 1]
 * @param bottomInset Function providing vertical inset for bottom edge at parameter t [0, 1]
 */
fun buildSliceShape(
    topBoundary: ShapeContour,
    bottomBoundary: ShapeContour,
    topInset: (Double) -> Double = { 0.0 },
    bottomInset: (Double) -> Double = { 0.0 }
): ShapeContour {
    val samples = 100
    
    // Sample top boundary (left to right)
    val topPoints = sampleBoundary(topBoundary, samples)
    
    // Sample bottom boundary (left to right, then reverse for clockwise winding)
    val bottomPoints = sampleBoundary(bottomBoundary, samples).reversed()
    
    // Apply vertical insets
    val adjustedTopPoints = topPoints.mapIndexed { i, p ->
        val t = i.toDouble() / (topPoints.size - 1)
        Vector2(p.x, p.y + topInset(t))
    }
    
    val adjustedBottomPoints = bottomPoints.mapIndexed { i, p ->
        val t = (bottomPoints.size - 1 - i).toDouble() / (bottomPoints.size - 1)
        Vector2(p.x, p.y - bottomInset(t))
    }
    
    // Build closed contour: top + right edge + bottom (reversed) + left edge
    return contour {
        moveTo(adjustedTopPoints.first())
        for (i in 1 until adjustedTopPoints.size) {
            lineTo(adjustedTopPoints[i])
        }
        // Right edge (connect top-right to bottom-right)
        lineTo(adjustedBottomPoints.first())
        for (i in 1 until adjustedBottomPoints.size) {
            lineTo(adjustedBottomPoints[i])
        }
        // Left edge implicitly closed
        close()
    }
}

// =============================================================================
// GRADIENT COLOR FUNCTIONS
// =============================================================================

/**
 * Get palette colors for a specific slice
 */
fun getSliceColors(sliceIndex: Int, totalSlices: Int, paletteMode: StrataPaletteMode): List<ColorRGBa> {
    val palette = when (paletteMode) {
        StrataPaletteMode.ATMOSPHERIC -> ATMOSPHERIC_PALETTE
        StrataPaletteMode.OCEANIC -> OCEANIC_PALETTE
        StrataPaletteMode.DESERT -> DESERT_PALETTE
        StrataPaletteMode.FOREST -> FOREST_PALETTE
        StrataPaletteMode.TWILIGHT -> TWILIGHT_PALETTE
        StrataPaletteMode.GLACIAL -> GLACIAL_PALETTE
        StrataPaletteMode.AURORA -> AURORA_PALETTE
        StrataPaletteMode.VOLCANIC -> VOLCANIC_PALETTE
        StrataPaletteMode.ARCTIC_NIGHT -> ARCTIC_NIGHT_PALETTE
        StrataPaletteMode.MONSOON -> MONSOON_PALETTE
        StrataPaletteMode.MOSSY_STONE -> MOSSY_STONE_PALETTE
        StrataPaletteMode.ALPINE -> ALPINE_PALETTE
        StrataPaletteMode.CANYON -> CANYON_PALETTE
        StrataPaletteMode.CORAL_REEF -> CORAL_REEF_PALETTE
        StrataPaletteMode.TROPICAL_LAGOON -> TROPICAL_LAGOON_PALETTE
        StrataPaletteMode.SUNRISE -> SUNRISE_PALETTE
        StrataPaletteMode.DUSK -> DUSK_PALETTE
        StrataPaletteMode.NEON_CITY -> NEON_CITY_PALETTE
        StrataPaletteMode.CYBERPUNK -> CYBERPUNK_PALETTE
        StrataPaletteMode.RETRO_PASTEL -> RETRO_PASTEL_PALETTE
        StrataPaletteMode.MINT_CHOCOLATE -> MINT_CHOCOLATE_PALETTE
        StrataPaletteMode.INK_AND_PAPER -> INK_AND_PAPER_PALETTE
        StrataPaletteMode.SMOKE -> SMOKE_PALETTE
        StrataPaletteMode.HEATMAP -> HEATMAP_PALETTE
        StrataPaletteMode.TOXIC_SLIME -> TOXIC_SLIME_PALETTE
    }
    
    // Map slice index to palette (bottom slices = lower palette indices for earth/ocean depth)
    val paletteIndex = ((totalSlices - 1 - sliceIndex).toDouble() / (totalSlices - 1) * (palette.size - 1)).toInt()
        .coerceIn(0, palette.size - 1)
    
    return palette[paletteIndex]
}

/**
 * Compute gradient color for a point within a slice
 * @param sliceIndex Index of the slice (0 = top)
 * @param u Normalized horizontal position [0, 1]
 * @param v Normalized vertical position within slice [0, 1]
 * @param params Global parameters
 */
fun gradientColor(sliceIndex: Int, u: Double, v: Double, params: StrataParams): ColorRGBa {
    val colors = getSliceColors(sliceIndex, params.sliceCount, params.paletteMode)
    
    val t = when (params.gradientMode) {
        GradientMode.VERTICAL -> v
        GradientMode.HORIZONTAL -> u
        GradientMode.DIAGONAL -> (u + v) / 2.0
    }
    
    return if (colors.size >= 2) {
        mix(colors[0], colors[1], t.coerceIn(0.0, 1.0))
    } else {
        colors.firstOrNull() ?: ColorRGBa.GRAY
    }
}

// =============================================================================
// RENDERING FUNCTIONS
// =============================================================================

/**
 * Create shader style for gradient fill
 */
fun createGradientShadeStyle(
    sliceIndex: Int,
    params: StrataParams,
    bounds: org.openrndr.shape.Rectangle
): ShadeStyle {
    val colors = getSliceColors(sliceIndex, params.sliceCount, params.paletteMode)
    val color0 = colors.getOrElse(0) { ColorRGBa.GRAY }
    val color1 = colors.getOrElse(1) { color0 }
    
    return shadeStyle {
        fragmentTransform = """
            vec2 screenPos = c_boundsPosition.xy;
            float t = 0.0;
            int mode = p_gradientMode;
            if (mode == 0) {
                t = screenPos.y;
            } else if (mode == 1) {
                t = screenPos.x;
            } else {
                t = (screenPos.x + screenPos.y) / 2.0;
            }
            t = clamp(t, 0.0, 1.0);
            vec4 c0 = p_color0;
            vec4 c1 = p_color1;
            x_fill = mix(c0, c1, t);
        """
        parameter("gradientMode", params.gradientMode.ordinal)
        parameter("color0", color0)
        parameter("color1", color1)
    }
}

/**
 * Render a single slice with gradient fill
 */
fun renderSlice(drawer: Drawer, sliceShape: ShapeContour, sliceIndex: Int, params: StrataParams, bounds: org.openrndr.shape.Rectangle) {
    val style = createGradientShadeStyle(sliceIndex, params, bounds)
    
    drawer.isolated {
        // Use clip path to constrain gradient to slice shape
        drawer.shadeStyle = style
        drawer.fill = ColorRGBa.WHITE  // Base color, will be overridden by shader
        drawer.stroke = null
        drawer.contour(sliceShape)
    }
}

/**
 * Render boundary curves for separation
 */
fun renderBoundaries(drawer: Drawer, boundaries: List<ShapeContour>, params: StrataParams) {
    // Skip first and last boundaries if they are at the edges of the canvas
    val activeBoundaries = boundaries.subList(1, boundaries.size - 1)
    
    when (params.separationMode) {
        SeparationMode.OUTLINE -> {
            drawer.isolated {
                drawer.stroke = rgb(0.1, 0.1, 0.1)
                drawer.strokeWeight = 1.5
                drawer.fill = null
                for (boundary in activeBoundaries) {
                    drawer.contour(boundary)
                }
            }
        }
        
        SeparationMode.GAP -> {
            // Handled in renderStrata via buildSliceShape inset
        }
        
        SeparationMode.DUAL_STROKE -> {
            for (boundary in activeBoundaries) {
                drawer.isolated {
                    drawer.stroke = rgb(0.05, 0.05, 0.05)
                    drawer.strokeWeight = 2.0
                    drawer.fill = null
                    drawer.contour(boundary)
                }
                drawer.isolated {
                    drawer.stroke = rgb(0.95, 0.95, 0.95)
                    drawer.strokeWeight = 1.0
                    drawer.fill = null
                    drawer.contour(boundary)
                }
            }
        }

        SeparationMode.SOFT_FADE -> {
            drawer.isolated {
                drawer.fill = null
                for (boundary in activeBoundaries) {
                    for (i in 0..8) {
                        drawer.stroke = rgb(0.1, 0.1, 0.1).copy(alpha = 0.03)
                        drawer.strokeWeight = 2.0 + i * 3.0
                        drawer.contour(boundary)
                    }
                }
            }
        }

        SeparationMode.FEATHERED_EDGE -> {
            drawer.isolated {
                drawer.stroke = null
                val rng = Random(params.seed)
                for (boundary in activeBoundaries) {
                    val points = sampleBoundary(boundary, 1000)
                    for (pt in points) {
                        drawer.fill = rgb(0.1, 0.1, 0.1).copy(alpha = rng.nextDouble() * 0.5)
                        val offset = Vector2(rng.nextDouble() - 0.5, rng.nextDouble() - 0.5) * 4.0
                        drawer.circle(pt + offset, rng.nextDouble() * 1.5)
                    }
                }
            }
        }

        SeparationMode.GRADIENT_RIM -> {
            for (boundary in activeBoundaries) {
                val samples = 200
                for (i in 0 until samples) {
                    val t = i.toDouble() / samples
                    val pos = boundary.position(t)
                    val normal = boundary.normal(t)
                    drawer.isolated {
                        drawer.stroke = null
                        val rimLength = 10.0
                        for (j in 0..5) {
                            val jt = j.toDouble() / 5.0
                            drawer.fill = rgb(0.0, 0.0, 0.0).copy(alpha = 0.2 * (1.0 - jt))
                            drawer.circle(pos + normal * (jt * rimLength), 1.0)
                            drawer.fill = rgb(1.0, 1.0, 1.0).copy(alpha = 0.2 * (1.0 - jt))
                            drawer.circle(pos - normal * (jt * rimLength), 1.0)
                        }
                    }
                }
            }
        }

        SeparationMode.INNER_GLOW -> {
            drawer.isolated {
                drawer.fill = null
                for (boundary in activeBoundaries) {
                    for (i in 0..10) {
                        drawer.stroke = rgb(1.0, 1.0, 1.0).copy(alpha = 0.05)
                        drawer.strokeWeight = i.toDouble() * 2.0
                        // Offset inward (downwards mostly for these horizontal slices)
                        drawer.drawStyle.clip = null // No easy way to clip to "inner" here without complex shapes
                        drawer.contour(boundary)
                    }
                }
            }
        }

        SeparationMode.OUTER_GLOW -> {
            drawer.isolated {
                drawer.fill = null
                for (boundary in activeBoundaries) {
                    for (i in 0..10) {
                        drawer.stroke = rgb(0.0, 0.0, 0.0).copy(alpha = 0.05)
                        drawer.strokeWeight = i.toDouble() * 3.0
                        drawer.contour(boundary)
                    }
                }
            }
        }

        SeparationMode.SHADOW_CUT -> {
            drawer.isolated {
                drawer.fill = null
                for (boundary in activeBoundaries) {
                    drawer.stroke = rgb(0.0, 0.0, 0.0).copy(alpha = 0.3)
                    drawer.strokeWeight = 2.0
                    drawer.translate(0.0, 2.0)
                    drawer.contour(boundary)
                }
            }
        }

        SeparationMode.BEVEL -> {
            for (boundary in activeBoundaries) {
                drawer.isolated {
                    drawer.stroke = rgb(1.0, 1.0, 1.0).copy(alpha = 0.6)
                    drawer.strokeWeight = 1.0
                    drawer.translate(0.0, -1.5)
                    drawer.contour(boundary)
                }
                drawer.isolated {
                    drawer.stroke = rgb(0.0, 0.0, 0.0).copy(alpha = 0.4)
                    drawer.strokeWeight = 1.0
                    drawer.translate(0.0, 1.5)
                    drawer.contour(boundary)
                }
            }
        }

        SeparationMode.EMBOSS -> {
            for (boundary in activeBoundaries) {
                drawer.isolated {
                    drawer.stroke = rgb(1.0, 1.0, 1.0).copy(alpha = 0.5)
                    drawer.strokeWeight = 3.0
                    drawer.contour(boundary)
                }
                drawer.isolated {
                    drawer.stroke = rgb(0.1, 0.1, 0.1)
                    drawer.strokeWeight = 1.0
                    drawer.contour(boundary)
                }
            }
        }

        SeparationMode.DASHED_STROKE -> {
            drawer.isolated {
                drawer.stroke = rgb(0.1, 0.1, 0.1)
                drawer.strokeWeight = 2.0
                for (boundary in activeBoundaries) {
                    val len = boundary.length
                    var d = 0.0
                    while (d < len) {
                        val t0 = d / len
                        val t1 = (d + 10.0).coerceAtMost(len) / len
                        drawer.contour(boundary.sub(t0, t1))
                        d += 20.0
                    }
                }
            }
        }

        SeparationMode.DOTTED_STROKE -> {
            drawer.isolated {
                drawer.fill = rgb(0.1, 0.1, 0.1)
                drawer.stroke = null
                for (boundary in activeBoundaries) {
                    val len = boundary.length
                    var d = 0.0
                    while (d < len) {
                        drawer.circle(boundary.position(d / len), 2.0)
                        d += 8.0
                    }
                }
            }
        }

        SeparationMode.ZIGZAG_STITCH -> {
            drawer.isolated {
                drawer.stroke = rgb(0.2, 0.2, 0.2)
                drawer.strokeWeight = 1.0
                for (boundary in activeBoundaries) {
                    val len = boundary.length
                    var d = 0.0
                    var side = 1.0
                    drawer.contour(contour {
                        moveTo(boundary.position(0.0) + boundary.normal(0.0) * 3.0)
                        while (d < len) {
                            val t = d / len
                            val p = boundary.position(t)
                            val n = boundary.normal(t)
                            lineTo(p + n * (3.0 * side))
                            side *= -1.0
                            d += 5.0
                        }
                    })
                }
            }
        }

        SeparationMode.WAVE_SEAM -> {
            drawer.isolated {
                drawer.stroke = rgb(0.1, 0.1, 0.1)
                drawer.strokeWeight = 1.5
                for (boundary in activeBoundaries) {
                    drawer.contour(contour {
                        moveTo(boundary.position(0.0))
                        for (i in 0..200) {
                            val t = i.toDouble() / 200.0
                            val p = boundary.position(t)
                            val n = boundary.normal(t)
                            val offset = sin(t * PI * 40.0) * 3.0
                            lineTo(p + n * offset)
                        }
                    })
                }
            }
        }

        SeparationMode.NOISE_JITTER -> {
            drawer.isolated {
                drawer.stroke = rgb(0.1, 0.1, 0.1)
                drawer.strokeWeight = 1.0
                val rng = Random(params.seed)
                for (boundary in activeBoundaries) {
                    drawer.contour(contour {
                        moveTo(boundary.position(0.0))
                        for (i in 0..300) {
                            val t = i.toDouble() / 300.0
                            val p = boundary.position(t)
                            val offset = Vector2(rng.nextDouble() - 0.5, rng.nextDouble() - 0.5) * 4.0
                            lineTo(p + offset)
                        }
                    })
                }
            }
        }

        SeparationMode.RIBBON -> {
            drawer.isolated {
                drawer.fill = rgb(0.1, 0.1, 0.1).copy(alpha = 0.3)
                drawer.stroke = rgb(0.1, 0.1, 0.1)
                drawer.strokeWeight = 0.5
                for (boundary in activeBoundaries) {
                    val samples = 100
                    val pts = mutableListOf<Vector2>()
                    for (i in 0..samples) {
                        val t = i.toDouble() / samples
                        pts.add(boundary.position(t) + boundary.normal(t) * 4.0)
                    }
                    for (i in samples downTo 0) {
                        val t = i.toDouble() / samples
                        pts.add(boundary.position(t) - boundary.normal(t) * 4.0)
                    }
                    drawer.contour(ShapeContour.fromPoints(pts, true))
                }
            }
        }

        SeparationMode.TRIM_BAND -> {
            for (boundary in activeBoundaries) {
                drawer.isolated {
                    drawer.stroke = rgb(0.1, 0.1, 0.1)
                    drawer.strokeWeight = 1.0
                    drawer.translate(0.0, -3.0)
                    drawer.contour(boundary)
                    drawer.translate(0.0, 6.0)
                    drawer.contour(boundary)
                }
                drawer.isolated {
                    drawer.stroke = rgb(1.0, 0.9, 0.0).copy(alpha = 0.5)
                    drawer.strokeWeight = 2.0
                    drawer.contour(boundary)
                }
            }
        }

        SeparationMode.INSET_BORDER -> {
            for (boundary in activeBoundaries) {
                drawer.isolated {
                    drawer.stroke = rgb(0.0, 0.0, 0.0).copy(alpha = 0.4)
                    drawer.strokeWeight = 1.0
                    drawer.translate(0.0, -4.0)
                    drawer.contour(boundary)
                    drawer.translate(0.0, 8.0)
                    drawer.contour(boundary)
                }
            }
        }

        SeparationMode.OVERPRINT -> {
            drawer.isolated {
                drawer.drawStyle.blendMode = BlendMode.BLEND
                drawer.fill = null
                for (boundary in activeBoundaries) {
                    drawer.stroke = rgb(0.1, 0.1, 0.1).copy(alpha = 0.3)
                    drawer.strokeWeight = 6.0
                    drawer.contour(boundary)
                }
            }
        }

        SeparationMode.HALO_CUTOUT -> {
            // Handled in renderStrata (similar to GAP but wider)
        }

        SeparationMode.NOTCHED -> {
            drawer.isolated {
                drawer.stroke = rgb(0.1, 0.1, 0.1)
                drawer.strokeWeight = 1.5
                for (boundary in activeBoundaries) {
                    val len = boundary.length
                    var d = 0.0
                    while (d < len) {
                        val t0 = d / len
                        val t1 = (d + 15.0).coerceAtMost(len) / len
                        drawer.contour(boundary.sub(t0, t1))
                        
                        // Draw a "notch" or gap
                        d += 25.0
                    }
                }
            }
        }

        SeparationMode.TICK_MARKS -> {
            drawer.isolated {
                drawer.stroke = rgb(0.1, 0.1, 0.1)
                drawer.strokeWeight = 1.0
                for (boundary in activeBoundaries) {
                    val len = boundary.length
                    var d = 0.0
                    while (d < len) {
                        val t = d / len
                        val p = boundary.position(t)
                        val n = boundary.normal(t)
                        drawer.lineSegment(p - n * 4.0, p + n * 4.0)
                        d += 10.0
                    }
                    drawer.contour(boundary)
                }
            }
        }

        SeparationMode.HATCH_BAND -> {
            drawer.isolated {
                drawer.stroke = rgb(0.1, 0.1, 0.1).copy(alpha = 0.6)
                drawer.strokeWeight = 1.0
                for (boundary in activeBoundaries) {
                    val len = boundary.length
                    var d = 0.0
                    while (d < len) {
                        val t = d / len
                        val p = boundary.position(t)
                        val n = boundary.normal(t)
                        // Diagonal hatch
                        val tangent = Vector2(-n.y, n.x)
                        val dir = (n + tangent).normalized
                        drawer.lineSegment(p - dir * 5.0, p + dir * 5.0)
                        d += 4.0
                    }
                }
            }
        }

        SeparationMode.MOSAIC_BAND -> {
            drawer.isolated {
                drawer.stroke = null
                val rng = Random(params.seed)
                for (boundary in activeBoundaries) {
                    val len = boundary.length
                    var d = 0.0
                    while (d < len) {
                        val t = d / len
                        val p = boundary.position(t)
                        drawer.fill = if (rng.nextBoolean()) rgb(0.1, 0.1, 0.1) else rgb(0.4, 0.4, 0.4)
                        drawer.isolated {
                            drawer.translate(p)
                            drawer.rotate(rng.nextDouble() * 360.0)
                            drawer.rectangle(-2.0, -2.0, 4.0, 4.0)
                        }
                        d += 6.0
                    }
                }
            }
        }

        SeparationMode.GLOW_DUAL -> {
            for (boundary in activeBoundaries) {
                // Glow
                drawer.isolated {
                    drawer.fill = null
                    for (i in 0..6) {
                        drawer.stroke = rgb(1.0, 1.0, 1.0).copy(alpha = 0.05)
                        drawer.strokeWeight = 2.0 + i * 2.0
                        drawer.contour(boundary)
                    }
                }
                // Strokes
                drawer.isolated {
                    drawer.stroke = rgb(0.1, 0.1, 0.1)
                    drawer.strokeWeight = 2.0
                    drawer.translate(0.0, 1.0)
                    drawer.contour(boundary)
                    drawer.stroke = rgb(1.0, 1.0, 1.0)
                    drawer.strokeWeight = 1.0
                    drawer.translate(0.0, -1.0)
                    drawer.contour(boundary)
                }
            }
        }

        SeparationMode.DEPTH_STACK -> {
            for (boundary in activeBoundaries) {
                // Bottom shadow
                drawer.isolated {
                    drawer.fill = null
                    drawer.stroke = rgb(0.0, 0.0, 0.0).copy(alpha = 0.2)
                    drawer.strokeWeight = 4.0
                    drawer.translate(0.0, 3.0)
                    drawer.contour(boundary)
                }
                // Top highlight
                drawer.isolated {
                    drawer.fill = null
                    drawer.stroke = rgb(1.0, 1.0, 1.0).copy(alpha = 0.4)
                    drawer.strokeWeight = 1.0
                    drawer.translate(0.0, -1.0)
                    drawer.contour(boundary)
                }
            }
        }

        SeparationMode.IRREGULAR_GAP -> {
            // Handled in renderStrata
        }
    }
}

/**
 * Render debug overlay showing control points and curve indices
 */
fun renderDebugOverlay(drawer: Drawer, params: StrataParams) {
    val allControlPoints = getAllControlPoints(params)
    
    drawer.isolated {
        // Draw control points
        drawer.fill = ColorRGBa.RED
        drawer.stroke = null
        for ((_, points) in allControlPoints) {
            for (pt in points) {
                drawer.circle(pt, 4.0)
            }
        }
        
        // Draw curve indices
        drawer.fill = ColorRGBa.BLACK
        for ((index, points) in allControlPoints) {
            val labelPos = points.first() + Vector2(15.0, 0.0)
            drawer.text("B$index", labelPos)
        }
    }
}

/**
 * Main rendering function for the strata visualization
 */
fun renderStrata(drawer: Drawer, params: StrataParams, bounds: org.openrndr.shape.Rectangle) {
    // Generate boundaries
    val boundaries = generateBoundaries(params)
    
    // Render each slice
    for (i in 0 until params.sliceCount) {
        val topBoundary = boundaries[i]
        val bottomBoundary = boundaries[i + 1]

        val topInset: (Double) -> Double = { t ->
            if (i == 0) 0.0 else when (params.separationMode) {
                SeparationMode.GAP -> 1.5
                SeparationMode.HALO_CUTOUT -> 4.0
                SeparationMode.IRREGULAR_GAP -> 1.0 + sin(t * PI * 4.0 + i) * 1.5 + 1.5
                else -> 0.0
            }
        }

        val bottomInset: (Double) -> Double = { t ->
            if (i == params.sliceCount - 1) 0.0 else when (params.separationMode) {
                SeparationMode.GAP -> 1.5
                SeparationMode.HALO_CUTOUT -> 4.0
                SeparationMode.IRREGULAR_GAP -> 1.0 + sin(t * PI * 4.0 + i + 1) * 1.5 + 1.5
                else -> 0.0
            }
        }
        
        val sliceShape = buildSliceShape(topBoundary, bottomBoundary, topInset, bottomInset)
        renderSlice(drawer, sliceShape, i, params, bounds)
    }
    
    // Render boundary separation
    // Skip rendering boundary strokes for modes that only affect slice shape (gaps)
    if (params.separationMode != SeparationMode.GAP && 
        params.separationMode != SeparationMode.HALO_CUTOUT &&
        params.separationMode != SeparationMode.IRREGULAR_GAP) {
        renderBoundaries(drawer, boundaries, params)
    }
    
    // Debug overlay
    if (params.showDebug) {
        renderDebugOverlay(drawer, params)
    }
}

// =============================================================================
// EXPORT FUNCTION
// =============================================================================

/**
 * Export PNG at exactly 600x800
 */
fun export600x800(drawer: Drawer, params: StrataParams) {
    val rt = renderTarget(600, 800) {
        colorBuffer()
        depthBuffer(DepthFormat.DEPTH24_STENCIL8)
    }
    
    val exportBounds = org.openrndr.shape.Rectangle(0.0, 0.0, 600.0, 800.0)
    
    drawer.isolatedWithTarget(rt) {
        ortho(rt)
        clear(getBackgroundColor(params.paletteMode))
        renderStrata(this, params, exportBounds)
    }
    
    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val filename = "strata_s${params.seed}_n${params.sliceCount}_${params.paletteMode.name.lowercase()}_${params.gradientMode.name.lowercase()}_$timestamp.png"
    
    File("images").mkdirs()
    rt.colorBuffer(0).saveToFile(File("images/$filename"))
    rt.destroy()
    
    println("Exported: images/$filename")
}

// =============================================================================
// MAIN APPLICATION
// =============================================================================

fun main() = application {
    configure {
        width = 600
        height = 800
        title = "Hobby Strata Gradients"
    }
    
    program {
        var params = StrataParams()
        
        var statusMessage = ""
        var statusTime = 0.0
        
        fun showStatus(msg: String) {
            statusMessage = msg
            statusTime = seconds
        }
        
        keyboard.keyDown.listen { event ->
            when (event.name) {
                // R: Reseed
                "r" -> {
                    params = params.copy(seed = Random.nextLong())
                    showStatus("Reseeded: ${params.seed}")
                }

                // Q / W: Adjust control points
                "q" -> {
                    val newCount = (params.controlPts + 1).coerceIn(2, 20)
                    params = params.copy(controlPts = newCount)
                    showStatus("Control Points: $newCount")
                }
                "w" -> {
                    val newCount = (params.controlPts - 1).coerceIn(2, 20)
                    params = params.copy(controlPts = newCount)
                    showStatus("Control Points: $newCount")
                }
                
                // [ ]: Adjust number of slices
                "[" -> {
                    val newCount = (params.sliceCount - 1).coerceIn(3, 15)
                    params = params.copy(sliceCount = newCount)
                    showStatus("Slices: $newCount")
                }
                "]" -> {
                    val newCount = (params.sliceCount + 1).coerceIn(3, 15)
                    params = params.copy(sliceCount = newCount)
                    showStatus("Slices: $newCount")
                }
                
                // - =: Adjust Hobby tension
                "-" -> {
                    val newTension = (params.tension - 0.05).coerceIn(0.85, 1.2)
                    params = params.copy(tension = newTension)
                    showStatus("Tension: ${String.format("%.2f", newTension)}")
                }
                "=" -> {
                    val newTension = (params.tension + 0.05).coerceIn(0.85, 1.2)
                    params = params.copy(tension = newTension)
                    showStatus("Tension: ${String.format("%.2f", newTension)}")
                }
                
                // A/Z: Adjust amplitude
                "a" -> {
                    val newAmplitude = (params.amplitude + 5.0).coerceIn(10.0, 40.0)
                    params = params.copy(amplitude = newAmplitude)
                    showStatus("Amplitude: ${String.format("%.0f", newAmplitude)}")
                }
                "z" -> {
                    val newAmplitude = (params.amplitude - 5.0).coerceIn(10.0, 40.0)
                    params = params.copy(amplitude = newAmplitude)
                    showStatus("Amplitude: ${String.format("%.0f", newAmplitude)}")
                }
                
                // G: Cycle gradient style
                "g" -> {
                    val modes = GradientMode.entries
                    val nextIdx = (modes.indexOf(params.gradientMode) + 1) % modes.size
                    params = params.copy(gradientMode = modes[nextIdx])
                    showStatus("Gradient: ${params.gradientMode}")
                }
                
                // P: Cycle palette mode
                "p" -> {
                    val modes = StrataPaletteMode.entries
                    val nextIdx = (modes.indexOf(params.paletteMode) + 1) % modes.size
                    params = params.copy(paletteMode = modes[nextIdx])
                    showStatus("Palette: ${params.paletteMode}")
                }
                
                // B: Toggle boundary separation mode
                "b" -> {
                    val modes = SeparationMode.entries
                    val nextIdx = (modes.indexOf(params.separationMode) + 1) % modes.size
                    params = params.copy(separationMode = modes[nextIdx])
                    showStatus("Separation: ${params.separationMode}")
                }
                
                // D: Debug overlay
                "d" -> {
                    params = params.copy(showDebug = !params.showDebug)
                    showStatus("Debug: ${if (params.showDebug) "ON" else "OFF"}")
                }
                
                // E: Export PNG
                "e" -> {
                    export600x800(drawer, params)
                    showStatus("Exported PNG")
                }
            }
        }
        
        extend {
            val bounds = org.openrndr.shape.Rectangle(0.0, 0.0, width.toDouble(), height.toDouble())
            
            // Clear background with palette-appropriate color
            drawer.clear(getBackgroundColor(params.paletteMode))
            
            // Render strata
            renderStrata(drawer, params, bounds)
            
            // Display status message
            if (seconds - statusTime < 2.0 && statusMessage.isNotEmpty()) {
                drawer.isolated {
                    drawer.fill = ColorRGBa.BLACK
                    drawer.text(statusMessage, 20.0, height - 20.0)
                }
            }
        }
    }
}
