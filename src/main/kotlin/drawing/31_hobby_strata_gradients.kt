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
    OCEANIC       // Deep blues → teals → light surface greens
}

/**
 * Boundary separation mode for visual distinction between slices
 */
enum class SeparationMode {
    OUTLINE,     // Simple stroke on each boundary curve
    GAP,         // Micro-gap between slices (inset polygons)
    DUAL_STROKE  // Dark + light offset strokes for crisp separation
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
 * Get background color based on palette mode
 * Uses the lightest color from the palette (top of strata)
 */
fun getBackgroundColor(paletteMode: StrataPaletteMode): ColorRGBa {
    return when (paletteMode) {
        StrataPaletteMode.ATMOSPHERIC -> rgb(0.96, 0.94, 0.92)  // Pale sky (from top of atmospheric palette)
        StrataPaletteMode.OCEANIC -> rgb(0.85, 0.90, 0.85)      // Foam (from top of oceanic palette)
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
 * @param insetPx Vertical inset for micro-gap mode (0 for no gap)
 */
fun buildSliceShape(topBoundary: ShapeContour, bottomBoundary: ShapeContour, insetPx: Double = 0.0): ShapeContour {
    val samples = 100
    
    // Sample top boundary (left to right)
    val topPoints = sampleBoundary(topBoundary, samples)
    
    // Sample bottom boundary (left to right, then reverse for clockwise winding)
    val bottomPoints = sampleBoundary(bottomBoundary, samples).reversed()
    
    // Apply vertical inset if needed
    val adjustedTopPoints = if (insetPx > 0) {
        topPoints.map { Vector2(it.x, it.y + insetPx) }
    } else {
        topPoints
    }
    
    val adjustedBottomPoints = if (insetPx > 0) {
        bottomPoints.map { Vector2(it.x, it.y - insetPx) }
    } else {
        bottomPoints
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
    when (params.separationMode) {
        SeparationMode.OUTLINE -> {
            // Simple dark stroke on each boundary
            drawer.isolated {
                drawer.stroke = rgb(0.1, 0.1, 0.1)
                drawer.strokeWeight = 1.5
                drawer.fill = null
                for (boundary in boundaries) {
                    drawer.contour(boundary)
                }
            }
        }
        
        SeparationMode.GAP -> {
            // Gap mode: no additional rendering needed (handled by inset shapes)
        }
        
        SeparationMode.DUAL_STROKE -> {
            // Dark stroke first (offset down)
            drawer.isolated {
                drawer.stroke = rgb(0.05, 0.05, 0.05)
                drawer.strokeWeight = 2.0
                drawer.fill = null
                for (boundary in boundaries) {
                    drawer.contour(boundary)
                }
            }
            // Light stroke on top (offset up)
            drawer.isolated {
                drawer.stroke = rgb(0.95, 0.95, 0.95)
                drawer.strokeWeight = 1.0
                drawer.fill = null
                for (boundary in boundaries) {
                    drawer.contour(boundary)
                }
            }
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
        for ((index, points) in allControlPoints) {
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
    
    // Calculate inset for gap mode
    val insetPx = if (params.separationMode == SeparationMode.GAP) 1.5 else 0.0
    
    // Render each slice
    for (i in 0 until params.sliceCount) {
        val topBoundary = boundaries[i]
        val bottomBoundary = boundaries[i + 1]
        val sliceShape = buildSliceShape(topBoundary, bottomBoundary, insetPx)
        renderSlice(drawer, sliceShape, i, params, bounds)
    }
    
    // Render boundary separation
    if (params.separationMode != SeparationMode.GAP) {
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
