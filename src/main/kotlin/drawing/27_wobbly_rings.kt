package drawing

import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.color.rgb
import org.openrndr.draw.*
import org.openrndr.extra.noise.simplex
import org.openrndr.extra.shapes.hobbyCurve
import org.openrndr.math.Vector2
import org.openrndr.shape.ShapeContour
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.*
import kotlin.random.Random

// =============================================================================
// WOBBLY RINGS - Generative Art Sketch
// =============================================================================
// Renders closed, imperfect rings via polar radius perturbation and multi-stroke
// layering. The result feels like engineered-but-organic geometry: still clearly
// ring-like, but with non-convex bulges, subtle harmonics, and rich line texture.

// =============================================================================
// DATA CLASSES
// =============================================================================

/**
 * Parameters controlling the wobbly rings generation
 */
data class WobblyRingParams(
    val seed: Long = System.currentTimeMillis(),
    // Layout mode: 0 = hero (single large ring), 1 = field (multiple rings)
    val layoutMode: Int = 0,
    // Ring geometry
    val ringCount: Int = 12,              // Number of rings in field mode
    val baseRadius: Double = 200.0,       // Base radius R for hero ring
    // Wobble parameters
    val alpha: Double = 0.12,             // Wobble strength (0.08-0.15 typical)
    val noiseFreq: Double = 2.5,          // Frequency of smooth noise along theta
    // Harmonics
    val harmonics: List<Int> = listOf(2, 3, 5, 8),  // Default harmonic set K
    val harmonicScale: Double = 0.04,     // Base amplitude scale for harmonics
    // Multi-stroke rendering
    val strokePasses: Int = 22,           // Number of stroke passes (6-40)
    val strokeDeltaScale: Double = 2.5,   // Scale of delta_j offset noise
    // Width variation
    val widthMode: Int = 0,               // 0 = curvature-based, 1 = noise-based
    val baseStrokeWidth: Double = 1.2,    // Base stroke width
    val widthVariation: Double = 0.6,     // Amount of width variation
    // Sampling - reduced from 1000 to 360 for performance while maintaining smooth appearance
    val thetaSamples: Int = 360,          // Number of samples around theta
    // Display
    val showDebug: Boolean = false
)

/**
 * Represents a single ring with its computed properties
 */
data class WobblyRing(
    val center: Vector2,
    val baseRadius: Double,
    val ringSeed: Long,
    val zOrder: Double,                   // For back-to-front rendering
    val harmonicPhases: List<Double>,     // Random phases for each harmonic
    val harmonicAmps: List<Double>,       // Random amplitudes for each harmonic
    val accentColor: ColorRGBa? = null    // Optional warm accent color
)

// =============================================================================
// HARMONIC SETS
// =============================================================================

val HARMONIC_SETS = listOf(
    listOf(2, 3, 5, 8),
    listOf(1, 2, 4),
    listOf(3, 6, 9),
    listOf(2, 5, 7, 11)
)

// =============================================================================
// COLOR PALETTE
// =============================================================================

val BACKGROUND_COLOR: ColorRGBa = rgb(0.96, 0.95, 0.93)  // Off-white
val INK_COLOR: ColorRGBa = rgb(0.08, 0.10, 0.14)          // Near-black
val INK_BLUE: ColorRGBa = rgb(0.12, 0.18, 0.32)           // Ink blue
val WARM_ACCENT: ColorRGBa = rgb(0.72, 0.38, 0.22)        // Warm terracotta accent

// =============================================================================
// NOISE FUNCTIONS
// =============================================================================

/**
 * Smooth 1D noise sampled along theta using Perlin noise
 */
fun smoothNoise1D(theta: Double, seed: Long, freq: Double): Double {
    // Map theta to 2D circle for seamless looping
    val x = cos(theta) * freq
    val y = sin(theta) * freq
    return simplex(seed.toInt(), x, y)
}

/**
 * FBM (Fractal Brownian Motion) noise for richer variation
 */
fun fbmNoise1D(theta: Double, seed: Long, freq: Double, octaves: Int = 3): Double {
    var value = 0.0
    var amplitude = 1.0
    var frequency = freq
    var maxValue = 0.0
    
    for (i in 0 until octaves) {
        value += smoothNoise1D(theta, seed + i * 1000, frequency) * amplitude
        maxValue += amplitude
        amplitude *= 0.5
        frequency *= 2.0
    }
    
    return value / maxValue
}

// =============================================================================
// RADIUS FUNCTION
// =============================================================================

/**
 * Computes the radius at angle theta with wobble and harmonics
 * r(θ) = R * (1 + α*n(θ) + Σ a_k*cos(kθ + φ_k))
 */
fun radius(
    theta: Double,
    baseR: Double,
    params: WobblyRingParams,
    ring: WobblyRing
): Double {
    // Smooth noise component n(θ)
    val noise = fbmNoise1D(theta, ring.ringSeed, params.noiseFreq)
    
    // Harmonic sum
    var harmonicSum = 0.0
    for (i in params.harmonics.indices) {
        val k = params.harmonics[i]
        val amp = ring.harmonicAmps.getOrElse(i) { params.harmonicScale }
        val phase = ring.harmonicPhases.getOrElse(i) { 0.0 }
        harmonicSum += amp * cos(k * theta + phase)
    }
    
    // Clamp total perturbation to prevent unrecognizable blobs
    val perturbation = (params.alpha * noise + harmonicSum).coerceIn(-0.35, 0.35)
    
    return baseR * (1.0 + perturbation)
}

/**
 * Computes radius with additional delta offset for multi-stroke passes
 */
fun radiusWithDelta(
    theta: Double,
    baseR: Double,
    params: WobblyRingParams,
    ring: WobblyRing,
    passSeed: Long,
    deltaScale: Double
): Double {
    val baseRadius = radius(theta, baseR, params, ring)
    // Small independent noise for this pass
    val delta = smoothNoise1D(theta, passSeed, params.noiseFreq * 1.5) * deltaScale
    return baseRadius + delta
}

// =============================================================================
// RING POINT GENERATION
// =============================================================================

/**
 * Generates points for a ring contour
 */
fun makeRingPoints(
    center: Vector2,
    baseR: Double,
    params: WobblyRingParams,
    ring: WobblyRing,
    passSeed: Long? = null,
    deltaScale: Double = 0.0
): List<Vector2> {
    val points = mutableListOf<Vector2>()
    val samples = params.thetaSamples
    
    for (i in 0 until samples) {
        val theta = 2.0 * PI * i / samples
        
        val r = if (passSeed != null && deltaScale > 0.0) {
            radiusWithDelta(theta, baseR, params, ring, passSeed, deltaScale)
        } else {
            radius(theta, baseR, params, ring)
        }
        
        val x = center.x + r * cos(theta)
        val y = center.y + r * sin(theta)
        points.add(Vector2(x, y))
    }
    
    return points
}

/**
 * Creates a smooth closed Hobby curve contour from points
 */
fun hobbyClosedContour(points: List<Vector2>, tension: Double = 1.0): ShapeContour {
    // hobbyCurve with closed=true creates a closed smooth contour
    return ShapeContour.fromPoints(points, closed = true).hobbyCurve(tension)
}

// =============================================================================
// CURVATURE AND WIDTH FUNCTIONS
// =============================================================================

/**
 * Computes a curvature proxy for each point based on angle between neighbors
 */
fun curvatureProxy(points: List<Vector2>): DoubleArray {
    val n = points.size
    val curvatures = DoubleArray(n)
    
    for (i in 0 until n) {
        val prev = points[(i - 1 + n) % n]
        val curr = points[i]
        val next = points[(i + 1) % n]
        
        val v1 = (curr - prev).normalized
        val v2 = (next - curr).normalized
        
        // Cross product magnitude gives sin of angle (curvature indicator)
        val cross = abs(v1.x * v2.y - v1.y * v2.x)
        // Dot product for direction
        val dot = v1.x * v2.x + v1.y * v2.y
        
        // Angle between vectors - larger angle = higher curvature
        curvatures[i] = atan2(cross, dot)
    }
    
    return curvatures
}

/**
 * Computes derivative magnitude |dr/dθ| as alternative width mapping
 */
fun derivativeMagnitude(points: List<Vector2>): DoubleArray {
    val n = points.size
    val derivs = DoubleArray(n)
    
    for (i in 0 until n) {
        val prev = points[(i - 1 + n) % n]
        val next = points[(i + 1) % n]
        derivs[i] = (next - prev).length / 2.0
    }
    
    return derivs
}

/**
 * Computes stroke width at a given index based on curvature or noise
 * @param maxCurv Pre-computed maximum curvature to avoid repeated array scans
 */
fun widthAt(
    idx: Int,
    curvatures: DoubleArray,
    params: WobblyRingParams,
    ring: WobblyRing,
    theta: Double,
    maxCurv: Double
): Double {
    val baseWidth = params.baseStrokeWidth
    
    return when (params.widthMode) {
        0 -> {
            // Curvature-based: thinner at high curvature, thicker on straighter arcs
            val curv = curvatures[idx]
            val normalizedCurv = if (maxCurv > 0.001) curv / maxCurv else 0.0
            // Invert: high curvature = thin
            val widthFactor = 1.0 - normalizedCurv * params.widthVariation
            baseWidth * widthFactor.coerceIn(0.3, 1.5)
        }
        1 -> {
            // Noise-based width variation (low frequency breathing)
            val noise = smoothNoise1D(theta * 0.5, ring.ringSeed + 5000, 1.0)
            val widthFactor = 1.0 + noise * params.widthVariation
            baseWidth * widthFactor.coerceIn(0.3, 1.5)
        }
        else -> baseWidth
    }
}

// =============================================================================
// RING GENERATION
// =============================================================================

/**
 * Creates a WobblyRing with random properties
 */
fun createRing(
    center: Vector2,
    baseRadius: Double,
    seed: Long,
    params: WobblyRingParams,
    useAccent: Boolean = false
): WobblyRing {
    val rng = Random(seed)
    
    // Generate random phases and amplitudes for harmonics
    val phases = params.harmonics.map { rng.nextDouble() * 2 * PI }
    val amps = params.harmonics.map { 
        params.harmonicScale * (0.5 + rng.nextDouble())
    }
    
    return WobblyRing(
        center = center,
        baseRadius = baseRadius,
        ringSeed = seed,
        zOrder = rng.nextDouble(),
        harmonicPhases = phases,
        harmonicAmps = amps,
        accentColor = if (useAccent) WARM_ACCENT else null
    )
}

/**
 * Generates rings for hero mode (single large ring)
 */
fun generateHeroRings(params: WobblyRingParams, width: Int, height: Int): List<WobblyRing> {
    val center = Vector2(width / 2.0, height / 2.0)
    val rng = Random(params.seed)
    
    return listOf(
        createRing(
            center = center,
            baseRadius = params.baseRadius,
            seed = params.seed,
            params = params,
            useAccent = false
        )
    )
}

/**
 * Generates rings for field mode (multiple overlapping rings)
 */
fun generateFieldRings(params: WobblyRingParams, width: Int, height: Int): List<WobblyRing> {
    val rng = Random(params.seed)
    val rings = mutableListOf<WobblyRing>()
    val margin = 80.0
    
    for (i in 0 until params.ringCount) {
        val cx = margin + rng.nextDouble() * (width - 2 * margin)
        val cy = margin + rng.nextDouble() * (height - 2 * margin)
        val r = 60.0 + rng.nextDouble() * 140.0
        
        // 1-2 rings get accent color
        val useAccent = i < 2 && rng.nextDouble() < 0.5
        
        rings.add(
            createRing(
                center = Vector2(cx, cy),
                baseRadius = r,
                seed = params.seed + i * 7919,  // Prime offset for variety
                params = params,
                useAccent = useAccent
            )
        )
    }
    
    // Sort by z-order for back-to-front rendering
    return rings.sortedBy { it.zOrder }
}

// =============================================================================
// RENDERING FUNCTIONS
// =============================================================================

/**
 * Renders a single ring with multi-stroke passes
 * OPTIMIZED: Pre-computes base geometry once, uses simpler contours for passes,
 * draws whole contours instead of individual segments when possible.
 */
fun renderRing(
    drawer: Drawer,
    ring: WobblyRing,
    params: WobblyRingParams
) {
    val baseColor = ring.accentColor ?: INK_BLUE
    val passes = params.strokePasses
    
    // Generate base ring points ONCE for curvature calculation
    val basePoints = makeRingPoints(ring.center, ring.baseRadius, params, ring)
    val curvatures = curvatureProxy(basePoints)
    // Pre-compute max curvature to avoid repeated scans
    val maxCurv = curvatures.maxOrNull() ?: 0.1
    
    // Pre-compute average width for this ring (used for fast uniform-width passes)
    val avgWidth = params.baseStrokeWidth
    
    drawer.fill = null
    drawer.lineCap = LineCap.ROUND
    drawer.lineJoin = LineJoin.ROUND
    
    // Use a pre-seeded random for deterministic pass variations
    val passRng = Random(ring.ringSeed)
    
    for (passIdx in 0 until passes) {
        // Pass-specific seed
        val passSeed = ring.ringSeed + passIdx * 1337
        
        // Generate points with delta offset for this pass
        // OPTIMIZATION: Skip Hobby curve, use simple closed contour from points
        val points = makeRingPoints(
            ring.center,
            ring.baseRadius,
            params,
            ring,
            passSeed,
            params.strokeDeltaScale
        )
        
        // Use simpler contour creation (no Hobby curve) for speed
        val contour = ShapeContour.fromPoints(points, closed = true)
        
        // Alpha variation: older passes fainter, newer stronger
        val progressAlpha = (passIdx + 1).toDouble() / passes
        val alpha = 0.15 + progressAlpha * 0.35  // Range: 0.15-0.50
        
        // Micro-jitter in phase to avoid banding
        val jitterPhase = passRng.nextDouble() * 0.02
        
        // Width variation per pass
        val passWidthScale = 0.8 + passRng.nextDouble() * 0.4
        
        drawer.stroke = baseColor.opacify(alpha + jitterPhase)
        
        // OPTIMIZATION: Draw entire contour with average width instead of per-segment
        // This dramatically reduces draw calls from ~360 to 1 per pass
        if (params.widthMode == 0) {
            // For curvature mode, use a representative average width
            val theta = PI  // Sample at middle
            val midIdx = points.size / 2
            val width = widthAt(midIdx, curvatures, params, ring, theta, maxCurv) * passWidthScale
            drawer.strokeWeight = width
        } else {
            // Noise mode or uniform
            drawer.strokeWeight = avgWidth * passWidthScale
        }
        drawer.contour(contour)
    }
}

/**
 * Renders intersection hints (subtle gaps or brightness)
 * This is kept minimal as the main effect is dense line texture
 */
fun renderIntersectionHints(
    drawer: Drawer,
    rings: List<WobblyRing>,
    params: WobblyRingParams
) {
    // For now, the back-to-front ordering handles overlaps naturally
    // Additional intersection effects could be added here if needed
}

/**
 * Renders the complete poster with all rings
 */
fun renderPoster(
    drawer: Drawer,
    params: WobblyRingParams,
    width: Int,
    height: Int
) {
    // Background
    drawer.clear(BACKGROUND_COLOR)
    
    // Generate rings based on layout mode
    val rings = when (params.layoutMode) {
        0 -> generateHeroRings(params, width, height)
        1 -> generateFieldRings(params, width, height)
        else -> generateHeroRings(params, width, height)
    }
    
    // Render rings back-to-front
    for (ring in rings) {
        renderRing(drawer, ring, params)
    }
}

/**
 * Renders debug overlay showing r(θ), sample points, and harmonic components
 */
fun renderDebugOverlay(
    drawer: Drawer,
    params: WobblyRingParams,
    width: Int,
    height: Int
) {
    if (!params.showDebug) return
    
    val ring = createRing(
        Vector2(width / 2.0, height / 2.0),
        params.baseRadius,
        params.seed,
        params
    )
    
    // Plot r(θ) in bottom-left corner
    val plotX = 20.0
    val plotY = height - 150.0
    val plotW = 200.0
    val plotH = 100.0
    
    // Background for plot
    drawer.fill = ColorRGBa.WHITE.opacify(0.85)
    drawer.stroke = INK_COLOR.opacify(0.3)
    drawer.strokeWeight = 1.0
    drawer.rectangle(plotX - 5, plotY - 5, plotW + 10, plotH + 10)
    
    // Plot r(θ) curve
    drawer.stroke = INK_COLOR
    drawer.strokeWeight = 1.5
    drawer.fill = null
    
    val plotPoints = mutableListOf<Vector2>()
    for (i in 0..100) {
        val theta = 2.0 * PI * i / 100
        val r = radius(theta, params.baseRadius, params, ring)
        val normalizedR = (r - params.baseRadius * 0.6) / (params.baseRadius * 0.8)
        val px = plotX + plotW * i / 100
        val py = plotY + plotH * (1.0 - normalizedR.coerceIn(0.0, 1.0))
        plotPoints.add(Vector2(px, py))
    }
    
    for (i in 0 until plotPoints.size - 1) {
        drawer.lineSegment(plotPoints[i], plotPoints[i + 1])
    }
    
    // Labels
    drawer.fill = INK_COLOR
    drawer.stroke = null
    
    // Show sample points on ring
    val samplePoints = makeRingPoints(ring.center, ring.baseRadius, params, ring)
    drawer.fill = ColorRGBa.RED.opacify(0.5)
    for (i in samplePoints.indices step 50) {
        drawer.circle(samplePoints[i], 3.0)
    }
    
    // Draw harmonic info
    drawer.fill = INK_COLOR.opacify(0.8)
    val info = "K=${params.harmonics} α=${String.format("%.2f", params.alpha)} passes=${params.strokePasses}"
    // Simple text at bottom
    drawer.text(info, 10.0, height - 10.0)
}

// =============================================================================
// EXPORT FUNCTION
// =============================================================================

/**
 * Exports the current render to PNG at exactly 600x800
 */
fun export600x800(
    drawer: Drawer,
    params: WobblyRingParams
) {
    val rt = renderTarget(600, 800) {
        colorBuffer()
        depthBuffer(DepthFormat.DEPTH24_STENCIL8)
    }
    
    drawer.isolatedWithTarget(rt) {
        ortho(rt)
        renderPoster(this, params, 600, 800)
    }
    
    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val modeStr = if (params.layoutMode == 0) "hero" else "field"
    val filename = "wobbly_rings_${modeStr}_s${params.seed}_$timestamp.png"
    
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
        title = "Wobbly Rings"
    }
    
    program {
        var params = WobblyRingParams()
        var harmonicSetIndex = 0
        
        // Track which parameters changed for display
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
                
                // 1/2: Layout mode
                "1" -> {
                    params = params.copy(layoutMode = 0)
                    showStatus("Layout: Hero (single ring)")
                }
                "2" -> {
                    params = params.copy(layoutMode = 1)
                    showStatus("Layout: Field (${params.ringCount} rings)")
                }
                
                // [ ]: Adjust wobble strength α
                "[" -> {
                    val newAlpha = (params.alpha - 0.02).coerceIn(0.02, 0.30)
                    params = params.copy(alpha = newAlpha)
                    showStatus("Wobble α: ${String.format("%.2f", newAlpha)}")
                }
                "]" -> {
                    val newAlpha = (params.alpha + 0.02).coerceIn(0.02, 0.30)
                    params = params.copy(alpha = newAlpha)
                    showStatus("Wobble α: ${String.format("%.2f", newAlpha)}")
                }
                
                // - =: Adjust harmonic amplitudes scale
                "-" -> {
                    val newScale = (params.harmonicScale - 0.01).coerceIn(0.01, 0.15)
                    params = params.copy(harmonicScale = newScale)
                    showStatus("Harmonic scale: ${String.format("%.2f", newScale)}")
                }
                "=" -> {
                    val newScale = (params.harmonicScale + 0.01).coerceIn(0.01, 0.15)
                    params = params.copy(harmonicScale = newScale)
                    showStatus("Harmonic scale: ${String.format("%.2f", newScale)}")
                }
                
                // K: Cycle harmonic sets
                "k" -> {
                    harmonicSetIndex = (harmonicSetIndex + 1) % HARMONIC_SETS.size
                    params = params.copy(harmonics = HARMONIC_SETS[harmonicSetIndex])
                    showStatus("Harmonics K: ${params.harmonics}")
                }
                
                // S: Adjust stroke pass count
                "s" -> {
                    val newPasses = when {
                        params.strokePasses < 12 -> 18
                        params.strokePasses < 24 -> 28
                        params.strokePasses < 35 -> 40
                        else -> 6
                    }
                    params = params.copy(strokePasses = newPasses)
                    showStatus("Stroke passes: $newPasses")
                }
                
                // W: Toggle width mapping mode
                "w" -> {
                    val newMode = (params.widthMode + 1) % 2
                    params = params.copy(widthMode = newMode)
                    val modeName = if (newMode == 0) "curvature" else "noise"
                    showStatus("Width mode: $modeName")
                }
                
                // E: Export PNG
                "e" -> {
                    export600x800(drawer, params)
                    showStatus("Exported PNG")
                }
                
                // D: Debug overlay toggle
                "d" -> {
                    params = params.copy(showDebug = !params.showDebug)
                    showStatus("Debug: ${if (params.showDebug) "ON" else "OFF"}")
                }
            }
        }
        
        extend {
            // Render the poster
            renderPoster(drawer, params, width, height)
            
            // Render debug overlay if enabled
            renderDebugOverlay(drawer, params, width, height)
            
            // Show status message for 2 seconds
            if (seconds - statusTime < 2.0 && statusMessage.isNotEmpty()) {
                drawer.fill = INK_COLOR.opacify(0.9)
                drawer.fontMap = loadFont("data/fonts/default.otf", 14.0)
                drawer.text(statusMessage, 10.0, 20.0)
            }
        }
    }
}
