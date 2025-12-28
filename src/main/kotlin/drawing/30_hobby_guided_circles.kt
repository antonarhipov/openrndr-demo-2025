package drawing

import org.openrndr.application
import org.openrndr.color.ColorHSLa
import org.openrndr.color.ColorRGBa
import org.openrndr.color.rgb
import org.openrndr.draw.*
import org.openrndr.extra.shapes.hobbyCurve
import org.openrndr.math.Vector2
import org.openrndr.math.mix
import org.openrndr.shape.ShapeContour
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.*
import kotlin.random.Random

// =============================================================================
// HOBBY-GUIDED CIRCLES - Generative Art Sketch
// =============================================================================
// Generates Hobby curves as guiding paths and places circles along them with
// graded sizes and colors. Clean, poster-like, highly legible design.

// =============================================================================
// ENUMS
// =============================================================================

/**
 * Size grading mode for circles along the curve
 */
enum class SizeMode {
    MONOTONE,      // Small → large (or reverse) along arc length
    PULSE,         // Gaussian bulges at 1-3 focal locations
    CURVATURE      // Tighter curvature → smaller circles
}

/**
 * Color palette mode
 */
enum class ColorMode {
    MONO,          // Single-hue lightness ramp
    MULTI_STOP     // 2-4 colors blended along arc length
}

/**
 * Composition mode for Hobby-guided circles
 */
enum class HobbyCompositionMode {
    SINGLE,        // One main curve
    DUAL           // Two curves crossing/near-parallel
}

// =============================================================================
// DATA CLASSES
// =============================================================================

/**
 * Global parameters controlling the sketch
 */
data class HobbyCircleParams(
    val seed: Long = System.currentTimeMillis(),
    val tension: Double = 1.0,            // Hobby curve tension (0.5 - 2.0)
    val spacing: Double = 15.0,           // Arc-length spacing between circles
    val closed: Boolean = false,          // Open vs closed curve
    val sizeMode: SizeMode = SizeMode.MONOTONE,
    val colorMode: ColorMode = ColorMode.MULTI_STOP,
    val compositionMode: HobbyCompositionMode = HobbyCompositionMode.SINGLE,
    val showGuide: Boolean = false,       // Show underlying Hobby curve
    val showDebug: Boolean = false,       // Debug overlay
    val adaptiveSpacing: Boolean = false, // Curvature-adaptive spacing
    val minRadius: Double = 4.0,
    val maxRadius: Double = 22.0,
    val paletteIndex: Int = 0             // Which palette to use
)

/**
 * Sample point along a contour
 */
data class CurveSample(
    val position: Vector2,
    val tangent: Vector2,
    val normal: Vector2,
    val arcLength: Double,      // Absolute arc length from start
    val u: Double,              // Normalized parameter [0, 1]
    val curvature: Double = 0.0 // Local curvature estimate
)

// =============================================================================
// COLOR PALETTES
// =============================================================================

val HOBBY_BACKGROUND: ColorRGBa = rgb(0.96, 0.95, 0.93)  // Off-white

// Multi-stop gradient palettes
val PALETTES: List<List<ColorRGBa>> = listOf(
    // Palette 0: Navy → Cyan → Warm Orange
    listOf(
        rgb(0.12, 0.15, 0.35),   // Deep navy
        rgb(0.15, 0.55, 0.65),   // Teal
        rgb(0.35, 0.75, 0.85),   // Cyan
        rgb(0.95, 0.60, 0.30)    // Warm orange
    ),
    // Palette 1: Charcoal → Cream
    listOf(
        rgb(0.18, 0.18, 0.20),   // Charcoal
        rgb(0.45, 0.42, 0.40),   // Medium gray
        rgb(0.75, 0.72, 0.68),   // Light gray
        rgb(0.95, 0.92, 0.85)    // Cream
    ),
    // Palette 2: Forest → Gold
    listOf(
        rgb(0.10, 0.25, 0.18),   // Deep forest
        rgb(0.20, 0.45, 0.30),   // Green
        rgb(0.55, 0.65, 0.35),   // Yellow-green
        rgb(0.90, 0.75, 0.25)    // Gold
    ),
    // Palette 3: Wine → Rose
    listOf(
        rgb(0.35, 0.10, 0.15),   // Deep wine
        rgb(0.55, 0.20, 0.30),   // Burgundy
        rgb(0.80, 0.45, 0.50),   // Rose
        rgb(0.95, 0.75, 0.75)    // Light pink
    )
)

// Mono palette hues (HSL base hue in degrees)
val MONO_HUES: List<Double> = listOf(210.0, 180.0, 25.0, 280.0, 0.0)  // Blue, Teal, Orange, Purple, Red

// =============================================================================
// CONTROL POINT GENERATION
// =============================================================================

/**
 * Generate control points within safe margin
 */
fun generateControlPoints(params: HobbyCircleParams, bounds: org.openrndr.shape.Rectangle, curveIndex: Int = 0): List<Vector2> {
    val rng = Random(params.seed + curveIndex * 12345L)
    val margin = minOf(bounds.width, bounds.height) * 0.08
    val safeArea = bounds.offsetEdges(-margin)
    
    // Number of control points (8-30)
    val numPoints = 10 + rng.nextInt(16)
    
    val points = mutableListOf<Vector2>()
    
    if (params.compositionMode == HobbyCompositionMode.SINGLE) {
        // Single flowing gesture - generate points along a main axis with variation
        val isVertical = rng.nextBoolean()
        
        for (i in 0 until numPoints) {
            val t = i.toDouble() / (numPoints - 1)
            val baseX: Double
            val baseY: Double
            
            if (isVertical) {
                // Vertical flow with horizontal variation
                baseY = safeArea.y + safeArea.height * t
                val centerX = safeArea.center.x
                val waveAmplitude = safeArea.width * 0.35
                val wavePhase = rng.nextDouble() * 2 * PI
                baseX = centerX + sin(t * PI * 2 + wavePhase) * waveAmplitude * (0.5 + rng.nextDouble() * 0.5)
            } else {
                // Horizontal flow with vertical variation
                baseX = safeArea.x + safeArea.width * t
                val centerY = safeArea.center.y
                val waveAmplitude = safeArea.height * 0.35
                val wavePhase = rng.nextDouble() * 2 * PI
                baseY = centerY + sin(t * PI * 2 + wavePhase) * waveAmplitude * (0.5 + rng.nextDouble() * 0.5)
            }
            
            // Add jitter
            val jitterX = rng.nextDouble(-15.0, 15.0)
            val jitterY = rng.nextDouble(-15.0, 15.0)
            
            val x = (baseX + jitterX).coerceIn(safeArea.x, safeArea.x + safeArea.width)
            val y = (baseY + jitterY).coerceIn(safeArea.y, safeArea.y + safeArea.height)
            
            points.add(Vector2(x, y))
        }
    } else {
        // Dual mode - generate two separate curves
        val offset = if (curveIndex == 0) -0.15 else 0.15
        val isFirst = curveIndex == 0
        
        for (i in 0 until numPoints) {
            val t = i.toDouble() / (numPoints - 1)
            
            // Create two curves that weave near each other
            val baseY = safeArea.y + safeArea.height * t
            val centerX = safeArea.center.x + safeArea.width * offset
            val waveAmplitude = safeArea.width * 0.25
            val wavePhase = if (isFirst) 0.0 else PI
            val baseX = centerX + sin(t * PI * 2.5 + wavePhase) * waveAmplitude
            
            val jitterX = rng.nextDouble(-12.0, 12.0)
            val jitterY = rng.nextDouble(-12.0, 12.0)
            
            val x = (baseX + jitterX).coerceIn(safeArea.x, safeArea.x + safeArea.width)
            val y = (baseY + jitterY).coerceIn(safeArea.y, safeArea.y + safeArea.height)
            
            points.add(Vector2(x, y))
        }
    }
    
    return points
}

// =============================================================================
// HOBBY CURVE CONSTRUCTION
// =============================================================================

/**
 * Build a Hobby curve from control points
 * Note: OpenRNDR's hobbyCurve uses curl parameter which relates to tension
 */
fun buildHobbyCurve(points: List<Vector2>, closed: Boolean, tension: Double): ShapeContour {
    // The curl parameter in hobbyCurve affects the curve's tightness
    // Higher values create tighter curves around control points
    val curl = tension.coerceIn(0.5, 2.0)
    return hobbyCurve(points, closed = closed, curl = curl).contour
}

// =============================================================================
// ARC-LENGTH SAMPLING
// =============================================================================

/**
 * Sample a contour at uniform arc-length intervals
 */
fun sampleContourArcLength(contour: ShapeContour, spacing: Double, adaptiveSpacing: Boolean = false): List<CurveSample> {
    val samples = mutableListOf<CurveSample>()
    
    // First, compute total arc length and build lookup table
    val numPreSamples = 500
    val preSamples = mutableListOf<Pair<Double, Double>>() // (t, arcLength)
    var totalArcLength = 0.0
    var prevPos = contour.position(0.0)
    
    for (i in 0..numPreSamples) {
        val t = i.toDouble() / numPreSamples
        val pos = contour.position(t)
        if (i > 0) {
            totalArcLength += pos.distanceTo(prevPos)
        }
        preSamples.add(Pair(t, totalArcLength))
        prevPos = pos
    }
    
    // Compute curvature at pre-sample points
    val curvatures = computeCurvatureProxy(contour, numPreSamples)
    
    // Function to find t for a given arc length
    fun tAtArcLength(targetLength: Double): Double {
        if (targetLength <= 0) return 0.0
        if (targetLength >= totalArcLength) return 1.0
        
        for (i in 1 until preSamples.size) {
            if (preSamples[i].second >= targetLength) {
                val (t0, s0) = preSamples[i - 1]
                val (t1, s1) = preSamples[i]
                val ratio = (targetLength - s0) / (s1 - s0)
                return t0 + (t1 - t0) * ratio
            }
        }
        return 1.0
    }
    
    // Function to get curvature at a given t
    fun curvatureAt(t: Double): Double {
        val idx = (t * numPreSamples).toInt().coerceIn(0, numPreSamples)
        return curvatures[idx]
    }
    
    // Sample at uniform arc length intervals
    var currentArcLength = 0.0
    while (currentArcLength <= totalArcLength) {
        val t = tAtArcLength(currentArcLength)
        val pos = contour.position(t)
        
        // Compute tangent and normal via finite differences
        val eps = 0.001
        val t0 = (t - eps).coerceIn(0.0, 1.0)
        val t1 = (t + eps).coerceIn(0.0, 1.0)
        val p0 = contour.position(t0)
        val p1 = contour.position(t1)
        val tangent = (p1 - p0).normalized
        val normal = Vector2(-tangent.y, tangent.x)
        
        val u = if (totalArcLength > 0) currentArcLength / totalArcLength else 0.0
        val curvature = curvatureAt(t)
        
        samples.add(CurveSample(
            position = pos,
            tangent = tangent,
            normal = normal,
            arcLength = currentArcLength,
            u = u,
            curvature = curvature
        ))
        
        // Advance by spacing (optionally adaptive)
        val effectiveSpacing = if (adaptiveSpacing) {
            // Tighter curvature → smaller spacing (more circles in tight areas)
            val curvFactor = 1.0 / (1.0 + curvature * 5.0)
            spacing * (0.5 + 0.5 * curvFactor)
        } else {
            spacing
        }
        currentArcLength += effectiveSpacing
    }
    
    return samples
}

/**
 * Compute curvature proxy along the contour
 */
fun computeCurvatureProxy(contour: ShapeContour, numSamples: Int): DoubleArray {
    val curvatures = DoubleArray(numSamples + 1)
    val eps = 0.005
    
    for (i in 0..numSamples) {
        val t = i.toDouble() / numSamples
        val t0 = (t - eps).coerceIn(0.0, 1.0)
        val t1 = t
        val t2 = (t + eps).coerceIn(0.0, 1.0)
        
        val p0 = contour.position(t0)
        val p1 = contour.position(t1)
        val p2 = contour.position(t2)
        
        val v1 = p1 - p0
        val v2 = p2 - p1
        
        val angle1 = atan2(v1.y, v1.x)
        val angle2 = atan2(v2.y, v2.x)
        var angleDiff = abs(angle2 - angle1)
        if (angleDiff > PI) angleDiff = 2 * PI - angleDiff
        
        val dist = v1.length + v2.length
        curvatures[i] = if (dist > 0.001) angleDiff / dist else 0.0
    }
    
    return curvatures
}

// =============================================================================
// SIZE GRADING
// =============================================================================

/**
 * Compute radius at a given u position along the curve
 */
fun radiusAt(u: Double, curvature: Double, params: HobbyCircleParams): Double {
    val minR = params.minRadius
    val maxR = params.maxRadius
    
    return when (params.sizeMode) {
        SizeMode.MONOTONE -> {
            // Linear ramp from small to large
            minR + (maxR - minR) * u
        }
        SizeMode.PULSE -> {
            // Gaussian bulges at 1-3 focal locations
            val numPulses = 2
            var pulseValue = 0.0
            for (i in 0 until numPulses) {
                val center = (i + 1).toDouble() / (numPulses + 1)
                val sigma = 0.15
                val gaussian = exp(-((u - center) * (u - center)) / (2 * sigma * sigma))
                pulseValue = maxOf(pulseValue, gaussian)
            }
            minR + (maxR - minR) * pulseValue
        }
        SizeMode.CURVATURE -> {
            // Base size modulated by curvature
            // Tighter curvature → smaller circles
            val baseSize = minR + (maxR - minR) * u
            val curvFactor = 1.0 / (1.0 + curvature * 3.0)
            val modulated = minR + (baseSize - minR) * curvFactor
            modulated.coerceIn(minR, maxR)
        }
    }
}

// =============================================================================
// COLOR GRADING
// =============================================================================

/**
 * Compute color at a given u position along the curve
 */
fun colorAt(u: Double, params: HobbyCircleParams, curveIndex: Int = 0): ColorRGBa {
    // Apply subtle sinusoidal variation
    val modU = u + sin(u * PI * 4) * 0.03
    val clampedU = modU.coerceIn(0.0, 1.0)
    
    return when (params.colorMode) {
        ColorMode.MONO -> {
            // Single-hue lightness ramp
            val hueIndex = (params.paletteIndex + curveIndex) % MONO_HUES.size
            val hue = MONO_HUES[hueIndex]
            
            // Vary saturation and lightness along the curve
            val saturation = 0.5 + 0.3 * (1.0 - clampedU)
            val lightness = 0.25 + 0.45 * clampedU
            
            ColorHSLa(hue, saturation, lightness).toRGBa()
        }
        ColorMode.MULTI_STOP -> {
            // Multi-stop gradient
            val paletteIdx = (params.paletteIndex + curveIndex) % PALETTES.size
            val palette = PALETTES[paletteIdx]
            
            // Find the two colors to blend between
            val numStops = palette.size
            val scaledU = clampedU * (numStops - 1)
            val idx0 = scaledU.toInt().coerceIn(0, numStops - 2)
            val idx1 = (idx0 + 1).coerceIn(0, numStops - 1)
            val blend = scaledU - idx0
            
            val c0 = palette[idx0]
            val c1 = palette[idx1]
            
            c0.mix(c1, blend)
        }
    }
}

// =============================================================================
// RENDERING
// =============================================================================

/**
 * Render circles along a curve
 */
fun renderCurveCircles(
    drawer: Drawer,
    samples: List<CurveSample>,
    params: HobbyCircleParams,
    curveIndex: Int = 0
) {
    for ((idx, sample) in samples.withIndex()) {
        val radius = radiusAt(sample.u, sample.curvature, params)
        val color = colorAt(sample.u, params, curveIndex)
        
        // Optional opacity grade for depth
        val alpha = 0.85 + 0.15 * sample.u
        
        // Optional highlight bead: every 5th circle
        val isHighlight = idx % 5 == 0
        
        // Fill
        drawer.fill = color.opacify(alpha)
        
        // Thin outline for clarity
        if (isHighlight) {
            drawer.stroke = color.shade(0.7).opacify(alpha)
            drawer.strokeWeight = 1.5
        } else {
            drawer.stroke = color.shade(0.8).opacify(alpha * 0.6)
            drawer.strokeWeight = 0.8
        }
        
        drawer.circle(sample.position, radius)
    }
}

/**
 * Render the guide curve
 */
fun renderGuideCurve(drawer: Drawer, contour: ShapeContour, curveIndex: Int = 0) {
    drawer.fill = null
    drawer.stroke = ColorRGBa.BLACK.opacify(0.15)
    drawer.strokeWeight = 1.0
    drawer.contour(contour)
}

/**
 * Render debug overlay
 */
fun renderDebugOverlay(
    drawer: Drawer,
    controlPoints: List<Vector2>,
    samples: List<CurveSample>,
    params: HobbyCircleParams,
    bounds: org.openrndr.shape.Rectangle
) {
    // Control points
    drawer.fill = ColorRGBa.RED.opacify(0.7)
    drawer.stroke = null
    for (pt in controlPoints) {
        drawer.circle(pt, 4.0)
    }
    
    // Safe margin
    val margin = minOf(bounds.width, bounds.height) * 0.08
    drawer.fill = null
    drawer.stroke = ColorRGBa.RED.opacify(0.3)
    drawer.strokeWeight = 1.0
    drawer.rectangle(bounds.offsetEdges(-margin))
    
    // Arc-length markers
    drawer.fill = ColorRGBa.GREEN.opacify(0.6)
    drawer.stroke = null
    for (sample in samples) {
        if ((sample.u * 10).toInt() % 1 == 0) {
            drawer.circle(sample.position, 2.0)
        }
    }
    
    // Info text
    drawer.fill = ColorRGBa.BLACK.opacify(0.8)
    drawer.stroke = null
    var y = 18.0
    drawer.text("Seed: ${params.seed}", 10.0, y); y += 16.0
    drawer.text("Tension: ${String.format("%.2f", params.tension)}", 10.0, y); y += 16.0
    drawer.text("Spacing: ${String.format("%.1f", params.spacing)}", 10.0, y); y += 16.0
    drawer.text("Closed: ${params.closed}", 10.0, y); y += 16.0
    drawer.text("Size Mode: ${params.sizeMode}", 10.0, y); y += 16.0
    drawer.text("Color Mode: ${params.colorMode}", 10.0, y); y += 16.0
    drawer.text("Composition: ${params.compositionMode}", 10.0, y); y += 16.0
    drawer.text("Samples: ${samples.size}", 10.0, y)
}

/**
 * Render the complete composition
 */
fun renderHobbyCircles(
    drawer: Drawer,
    params: HobbyCircleParams,
    bounds: org.openrndr.shape.Rectangle
) {
    // Background
    drawer.clear(HOBBY_BACKGROUND)
    
    val numCurves = if (params.compositionMode == HobbyCompositionMode.DUAL) 2 else 1
    
    for (curveIndex in 0 until numCurves) {
        // Generate control points
        val controlPoints = generateControlPoints(params, bounds, curveIndex)
        
        // Build Hobby curve
        val contour = buildHobbyCurve(controlPoints, params.closed, params.tension)
        
        // Sample at arc-length intervals
        val samples = sampleContourArcLength(contour, params.spacing, params.adaptiveSpacing)
        
        // Draw guide curve if enabled
        if (params.showGuide) {
            renderGuideCurve(drawer, contour, curveIndex)
        }
        
        // Draw circles
        renderCurveCircles(drawer, samples, params, curveIndex)
        
        // Debug overlay (only for first curve to avoid clutter)
        if (params.showDebug && curveIndex == 0) {
            renderDebugOverlay(drawer, controlPoints, samples, params, bounds)
        }
    }
}

// =============================================================================
// EXPORT FUNCTION
// =============================================================================

/**
 * Export PNG at exactly 600x800
 */
fun exportHobbyCircles600x800(drawer: Drawer, params: HobbyCircleParams) {
    val rt = renderTarget(600, 800) {
        colorBuffer()
        depthBuffer(DepthFormat.DEPTH24_STENCIL8)
    }
    
    val exportBounds = org.openrndr.shape.Rectangle(0.0, 0.0, 600.0, 800.0)
    
    drawer.isolatedWithTarget(rt) {
        ortho(rt)
        renderHobbyCircles(this, params, exportBounds)
    }
    
    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val closedStr = if (params.closed) "c" else "o"
    val filename = "hobby_circles_s${params.seed}_${params.compositionMode.name.lowercase()}_${closedStr}_$timestamp.png"
    
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
        title = "Hobby-Guided Circles"
    }
    
    program {
        var params = HobbyCircleParams()
        
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
                
                // 1/2: Composition mode
                "1" -> {
                    params = params.copy(compositionMode = HobbyCompositionMode.SINGLE)
                    showStatus("Composition: Single curve")
                }
                "2" -> {
                    params = params.copy(compositionMode = HobbyCompositionMode.DUAL)
                    showStatus("Composition: Dual weave")
                }
                
                // O: Toggle open vs closed curve
                "o" -> {
                    params = params.copy(closed = !params.closed)
                    showStatus("Curve: ${if (params.closed) "Closed" else "Open"}")
                }
                
                // [ ]: Adjust Hobby tension
                "[" -> {
                    val newTension = (params.tension - 0.1).coerceIn(0.5, 2.0)
                    params = params.copy(tension = newTension)
                    showStatus("Tension: ${String.format("%.1f", newTension)}")
                }
                "]" -> {
                    val newTension = (params.tension + 0.1).coerceIn(0.5, 2.0)
                    params = params.copy(tension = newTension)
                    showStatus("Tension: ${String.format("%.1f", newTension)}")
                }
                
                // - =: Adjust circle spacing
                "-" -> {
                    val newSpacing = (params.spacing - 2.0).coerceIn(5.0, 40.0)
                    params = params.copy(spacing = newSpacing)
                    showStatus("Spacing: ${String.format("%.0f", newSpacing)}")
                }
                "=" -> {
                    val newSpacing = (params.spacing + 2.0).coerceIn(5.0, 40.0)
                    params = params.copy(spacing = newSpacing)
                    showStatus("Spacing: ${String.format("%.0f", newSpacing)}")
                }
                
                // S: Cycle size grading mode
                "s" -> {
                    val modes = SizeMode.entries
                    val nextIdx = (modes.indexOf(params.sizeMode) + 1) % modes.size
                    params = params.copy(sizeMode = modes[nextIdx])
                    showStatus("Size Mode: ${params.sizeMode}")
                }
                
                // C: Cycle color palette mode
                "c" -> {
                    val modes = ColorMode.entries
                    val nextIdx = (modes.indexOf(params.colorMode) + 1) % modes.size
                    params = params.copy(colorMode = modes[nextIdx])
                    showStatus("Color Mode: ${params.colorMode}")
                }
                
                // P: Cycle palette
                "p" -> {
                    val numPalettes = if (params.colorMode == ColorMode.MONO) MONO_HUES.size else PALETTES.size
                    params = params.copy(paletteIndex = (params.paletteIndex + 1) % numPalettes)
                    showStatus("Palette: ${params.paletteIndex}")
                }
                
                // G: Toggle showing guide curve
                "g" -> {
                    params = params.copy(showGuide = !params.showGuide)
                    showStatus("Guide curve: ${if (params.showGuide) "ON" else "OFF"}")
                }
                
                // D: Debug overlay
                "d" -> {
                    params = params.copy(showDebug = !params.showDebug)
                    showStatus("Debug: ${if (params.showDebug) "ON" else "OFF"}")
                }
                
                // A: Toggle adaptive spacing
                "a" -> {
                    params = params.copy(adaptiveSpacing = !params.adaptiveSpacing)
                    showStatus("Adaptive spacing: ${if (params.adaptiveSpacing) "ON" else "OFF"}")
                }
                
                // E: Export PNG
                "e" -> {
                    exportHobbyCircles600x800(drawer, params)
                    showStatus("Exported PNG")
                }
            }
        }
        
        extend {
            val bounds = org.openrndr.shape.Rectangle(0.0, 0.0, width.toDouble(), height.toDouble())
            renderHobbyCircles(drawer, params, bounds)
            
            // Status message
            if (statusMessage.isNotEmpty() && seconds - statusTime < 2.5) {
                drawer.fill = ColorRGBa.BLACK.opacify(0.75)
                drawer.stroke = null
                drawer.rectangle(5.0, height - 28.0, statusMessage.length * 8.0 + 10.0, 22.0)
                drawer.fill = ColorRGBa.WHITE
                drawer.text(statusMessage, 10.0, height - 12.0)
            }
        }
    }
}
