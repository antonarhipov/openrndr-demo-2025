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
// BENT CIRCLES (DOMAIN WARP) - Generative Art Sketch
// =============================================================================
// Generates perfect circles then warps them via a smooth vector field (gradient
// or curl of 2D noise), creating flowing, bent loops that look like they're
// drifting through a fluid field. Clean, graphic, poster-like aesthetic.

// =============================================================================
// DATA CLASSES
// =============================================================================

/**
 * Field mode: how we derive the vector field from noise
 */
enum class FieldMode {
    GRADIENT,  // Push/pull: v = ∇N
    CURL       // Swirl: v = (-dN/dy, dN/dx)
}

/**
 * Composition mode
 */
enum class CompositionMode {
    HERO,   // Single large ring with subtle ghost copies
    FIELD   // Multiple rings of varying sizes
}

/**
 * Parameters controlling the bent circles generation
 */
data class BentCircleParams(
    val seed: Long = System.currentTimeMillis(),
    // Composition
    val compositionMode: CompositionMode = CompositionMode.FIELD,
    val ringCount: Int = 12,              // Number of rings in field mode
    // Warp parameters
    val beta: Double = 80.0,              // Bend strength (warp intensity)
    val noiseFreq: Double = 0.003,        // Noise frequency (spatial scale)
    val fieldMode: FieldMode = FieldMode.CURL,
    val perRingNoise: Boolean = true,     // Use unique noise offset per ring
    val multiPassWarp: Boolean = false,   // Apply warp twice for stronger effect
    val beta2: Double = 40.0,             // Second pass warp strength
    // Smoothing
    val useHobby: Boolean = true,         // Apply Hobby curve smoothing
    val hobbyTension: Double = 1.0,       // Hobby curve tension
    // Sampling
    val thetaSamples: Int = 1000,         // Samples around circle (720-2000)
    // Display
    val showDebug: Boolean = false
)

/**
 * Represents a single bent ring
 */
data class BentRing(
    val center: Vector2,
    val baseRadius: Double,
    val ringSeed: Long,
    val noiseOffset: Vector2,             // Offset for per-ring noise variation
    val zOrder: Double,                   // For back-to-front rendering
    val accentColor: ColorRGBa? = null,   // Optional warm accent color
    val strokeWeight: Double = 1.5        // Stroke weight for this ring
)

// =============================================================================
// COLOR PALETTE
// =============================================================================

val BENT_BACKGROUND: ColorRGBa = rgb(0.96, 0.95, 0.93)     // Off-white
val BENT_INK_BLUE: ColorRGBa = rgb(0.12, 0.18, 0.32)       // Ink blue / near-black
val BENT_INK_DARK: ColorRGBa = rgb(0.08, 0.10, 0.14)       // Near-black
val BENT_WARM_ACCENT: ColorRGBa = rgb(0.82, 0.42, 0.18)    // Warm orange accent

// =============================================================================
// NOISE FUNCTIONS
// =============================================================================

/**
 * 2D smooth noise N(x, y) with optional seed offset
 * Uses normalized coordinate space for stable parameters
 */
fun noiseN(x: Double, y: Double, params: BentCircleParams, seedOffset: Long = 0): Double {
    val scaledX = x * params.noiseFreq
    val scaledY = y * params.noiseFreq
    return simplex((params.seed + seedOffset).toInt(), scaledX, scaledY)
}

/**
 * Compute the vector field at point p
 * Uses finite differences to estimate partial derivatives
 * 
 * Gradient mode: v = (dN/dx, dN/dy) - push/pull
 * Curl mode: v = (-dN/dy, dN/dx) - swirl
 */
fun fieldV(p: Vector2, params: BentCircleParams, seedOffset: Long = 0, noiseOffset: Vector2 = Vector2.ZERO): Vector2 {
    val eps = 1.0  // Epsilon for finite differences
    
    // Offset point for per-ring noise variation
    val px = p.x + noiseOffset.x
    val py = p.y + noiseOffset.y
    
    // Numerical partial derivatives using central differences
    val dNdx = (noiseN(px + eps, py, params, seedOffset) - noiseN(px - eps, py, params, seedOffset)) / (2.0 * eps)
    val dNdy = (noiseN(px, py + eps, params, seedOffset) - noiseN(px, py - eps, params, seedOffset)) / (2.0 * eps)
    
    return when (params.fieldMode) {
        FieldMode.GRADIENT -> Vector2(dNdx, dNdy)
        FieldMode.CURL -> Vector2(-dNdy, dNdx)
    }
}

// =============================================================================
// WARP FUNCTIONS
// =============================================================================

/**
 * Warp a single point using the vector field
 * p̃ = p + β * v(p)
 */
fun warpPoint(p: Vector2, params: BentCircleParams, seedOffset: Long = 0, noiseOffset: Vector2 = Vector2.ZERO): Vector2 {
    val v = fieldV(p, params, seedOffset, noiseOffset)
    var warped = p + v * params.beta
    
    // Optional second pass for stronger deformation
    if (params.multiPassWarp) {
        val v2 = fieldV(warped, params, seedOffset, noiseOffset)
        warped = warped + v2 * params.beta2
    }
    
    return warped
}

// =============================================================================
// CIRCLE POINT GENERATION
// =============================================================================

/**
 * Generate points for a perfect circle
 * p(θ) = c + (R*cos(θ), R*sin(θ))
 */
fun makeCirclePoints(center: Vector2, radius: Double, params: BentCircleParams): List<Vector2> {
    val points = mutableListOf<Vector2>()
    val samples = params.thetaSamples
    
    for (i in 0 until samples) {
        val theta = 2.0 * PI * i / samples
        val x = center.x + radius * cos(theta)
        val y = center.y + radius * sin(theta)
        points.add(Vector2(x, y))
    }
    
    return points
}

/**
 * Generate warped ring points
 * Each point on the circle is displaced by the vector field
 */
fun makeWarpedRingPoints(
    center: Vector2,
    radius: Double,
    params: BentCircleParams,
    ringSeed: Long = 0,
    noiseOffset: Vector2 = Vector2.ZERO
): List<Vector2> {
    val circlePoints = makeCirclePoints(center, radius, params)
    
    val seedOffset = if (params.perRingNoise) ringSeed else 0L
    val offset = if (params.perRingNoise) noiseOffset else Vector2.ZERO
    
    return circlePoints.map { p ->
        warpPoint(p, params, seedOffset, offset)
    }
}

/**
 * Create a smooth closed Hobby curve contour from points
 */
fun bentHobbyClosedContour(points: List<Vector2>, tension: Double = 1.0): ShapeContour {
    return ShapeContour.fromPoints(points, closed = true).hobbyCurve(tension)
}

// =============================================================================
// RING GENERATION
// =============================================================================

/**
 * Create a BentRing with random properties
 */
fun createBentRing(
    center: Vector2,
    baseRadius: Double,
    seed: Long,
    params: BentCircleParams,
    useAccent: Boolean = false,
    strokeWeight: Double = 1.5
): BentRing {
    val rng = Random(seed)
    
    // Generate random noise offset for per-ring variation
    val noiseOffset = Vector2(
        (rng.nextDouble() - 0.5) * 2000.0,
        (rng.nextDouble() - 0.5) * 2000.0
    )
    
    return BentRing(
        center = center,
        baseRadius = baseRadius,
        ringSeed = seed,
        noiseOffset = noiseOffset,
        zOrder = rng.nextDouble(),
        accentColor = if (useAccent) BENT_WARM_ACCENT else null,
        strokeWeight = strokeWeight
    )
}

/**
 * Generate rings for hero mode (single large ring with ghost copies)
 */
fun generateHeroBentRings(params: BentCircleParams, width: Int, height: Int): List<BentRing> {
    val cx = width / 2.0
    val cy = height / 2.0
    val rng = Random(params.seed)
    
    val rings = mutableListOf<BentRing>()
    
    // Main hero ring
    rings.add(
        createBentRing(
            center = Vector2(cx, cy),
            baseRadius = min(width, height) * 0.35,
            seed = params.seed,
            params = params,
            useAccent = false,
            strokeWeight = 2.0
        )
    )
    
    // Ghost copies (subtle, low alpha will be applied in rendering)
    for (i in 1..3) {
        val offsetX = (rng.nextDouble() - 0.5) * 30.0
        val offsetY = (rng.nextDouble() - 0.5) * 30.0
        val radiusScale = 0.85 + rng.nextDouble() * 0.3
        
        rings.add(
            createBentRing(
                center = Vector2(cx + offsetX, cy + offsetY),
                baseRadius = min(width, height) * 0.35 * radiusScale,
                seed = params.seed + i * 7919,
                params = params,
                useAccent = false,
                strokeWeight = 1.0
            )
        )
    }
    
    return rings.sortedBy { it.zOrder }
}

/**
 * Generate rings for field mode (multiple rings of varying sizes)
 */
fun generateFieldBentRings(params: BentCircleParams, width: Int, height: Int): List<BentRing> {
    val rng = Random(params.seed)
    val rings = mutableListOf<BentRing>()
    
    // Safe margin (6-8%)
    val marginX = width * 0.07
    val marginY = height * 0.07
    
    // Create negative space region (keep 20-30% clear)
    val negativeSpaceX = marginX + rng.nextDouble() * (width - 2 * marginX) * 0.6
    val negativeSpaceY = marginY + rng.nextDouble() * (height - 2 * marginY) * 0.6
    val negativeSpaceRadius = min(width, height) * 0.15
    
    var attempts = 0
    var created = 0
    
    while (created < params.ringCount && attempts < params.ringCount * 5) {
        attempts++
        
        val cx = marginX + rng.nextDouble() * (width - 2 * marginX)
        val cy = marginY + rng.nextDouble() * (height - 2 * marginY)
        val r = 40.0 + rng.nextDouble() * 120.0
        
        // Skip if too close to negative space center
        val distToNegative = sqrt((cx - negativeSpaceX).pow(2) + (cy - negativeSpaceY).pow(2))
        if (distToNegative < negativeSpaceRadius + r * 0.5) continue
        
        // 1-2 rings get accent color
        val useAccent = created < 2 && rng.nextDouble() < 0.4
        
        // Vary stroke weight
        val strokeWeight = 1.0 + rng.nextDouble() * 1.0
        
        rings.add(
            createBentRing(
                center = Vector2(cx, cy),
                baseRadius = r,
                seed = params.seed + created * 7919,
                params = params,
                useAccent = useAccent,
                strokeWeight = strokeWeight
            )
        )
        created++
    }
    
    return rings.sortedBy { it.zOrder }
}

// =============================================================================
// RENDERING FUNCTIONS
// =============================================================================

/**
 * Render a single bent ring
 */
fun renderBentRing(
    drawer: Drawer,
    ring: BentRing,
    params: BentCircleParams,
    alpha: Double = 1.0
) {
    // Generate warped points
    val warpedPoints = makeWarpedRingPoints(
        ring.center,
        ring.baseRadius,
        params,
        ring.ringSeed,
        ring.noiseOffset
    )
    
    // Create contour
    val contour = if (params.useHobby) {
        bentHobbyClosedContour(warpedPoints, params.hobbyTension)
    } else {
        ShapeContour.fromPoints(warpedPoints, closed = true)
    }
    
    // Style
    val baseColor = ring.accentColor ?: BENT_INK_BLUE
    drawer.fill = null
    drawer.stroke = baseColor.opacify(alpha)
    drawer.strokeWeight = ring.strokeWeight
    drawer.lineCap = LineCap.ROUND
    drawer.lineJoin = LineJoin.ROUND
    
    drawer.contour(contour)
}

/**
 * Render the complete poster
 */
fun renderBentPoster(
    drawer: Drawer,
    params: BentCircleParams,
    width: Int,
    height: Int
) {
    // Background
    drawer.clear(BENT_BACKGROUND)
    
    // Generate rings based on composition mode
    val rings = when (params.compositionMode) {
        CompositionMode.HERO -> generateHeroBentRings(params, width, height)
        CompositionMode.FIELD -> generateFieldBentRings(params, width, height)
    }
    
    // Render rings
    for ((index, ring) in rings.withIndex()) {
        // In hero mode, ghost rings are fainter
        val alpha = if (params.compositionMode == CompositionMode.HERO && index > 0) {
            0.2 + 0.1 * index
        } else {
            0.85 + Random(ring.ringSeed).nextDouble() * 0.15
        }
        
        renderBentRing(drawer, ring, params, alpha)
    }
}

/**
 * Render debug overlay showing vector field and unwarped vs warped circle
 */
fun renderBentDebugOverlay(
    drawer: Drawer,
    params: BentCircleParams,
    width: Int,
    height: Int
) {
    if (!params.showDebug) return
    
    // Draw vector field grid
    val gridStep = 50
    drawer.stroke = ColorRGBa.RED.opacify(0.4)
    drawer.strokeWeight = 0.5
    
    for (x in gridStep until width step gridStep) {
        for (y in gridStep until height step gridStep) {
            val p = Vector2(x.toDouble(), y.toDouble())
            val v = fieldV(p, params)
            val vNorm = v.normalized * 15.0  // Scale for visibility
            drawer.lineSegment(p, p + vNorm)
            
            // Arrow head
            drawer.circle(p + vNorm, 2.0)
        }
    }
    
    // Show unwarped circle vs warped
    val cx = width / 2.0
    val cy = height / 2.0
    val r = 100.0
    
    // Unwarped circle (dashed appearance via segments)
    drawer.stroke = ColorRGBa.BLUE.opacify(0.5)
    drawer.strokeWeight = 1.0
    val circlePoints = makeCirclePoints(Vector2(cx, cy), r, params)
    for (i in circlePoints.indices step 10) {
        if (i + 5 < circlePoints.size) {
            drawer.lineSegment(circlePoints[i], circlePoints[i + 5])
        }
    }
    
    // Warped circle
    drawer.stroke = ColorRGBa.GREEN.opacify(0.7)
    drawer.strokeWeight = 1.5
    val warpedPoints = makeWarpedRingPoints(Vector2(cx, cy), r, params, params.seed)
    val warpedContour = ShapeContour.fromPoints(warpedPoints, closed = true)
    drawer.contour(warpedContour)
    
    // Info text
    drawer.fill = BENT_INK_DARK.opacify(0.9)
    drawer.stroke = null
    val fieldName = if (params.fieldMode == FieldMode.GRADIENT) "Gradient" else "Curl"
    val info = "Field: $fieldName  β: ${String.format("%.0f", params.beta)}  freq: ${String.format("%.4f", params.noiseFreq)}  rings: ${params.ringCount}"
    drawer.text(info, 10.0, height - 10.0)
}

// =============================================================================
// EXPORT FUNCTION
// =============================================================================

/**
 * Export PNG at exactly 600x800
 */
fun exportBent600x800(
    drawer: Drawer,
    params: BentCircleParams
) {
    val rt = renderTarget(600, 800) {
        colorBuffer()
        depthBuffer(DepthFormat.DEPTH24_STENCIL8)
    }
    
    drawer.isolatedWithTarget(rt) {
        ortho(rt)
        renderBentPoster(this, params, 600, 800)
    }
    
    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val modeStr = if (params.compositionMode == CompositionMode.HERO) "hero" else "field"
    val fieldStr = if (params.fieldMode == FieldMode.GRADIENT) "grad" else "curl"
    val filename = "bent_circles_${modeStr}_${fieldStr}_s${params.seed}_$timestamp.png"
    
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
        title = "Bent Circles (Domain Warp)"
    }
    
    program {
        // Initialize with strong defaults: curl field, moderate β, 12 rings, Hobby on
        var params = BentCircleParams(
            compositionMode = CompositionMode.FIELD,
            ringCount = 12,
            beta = 80.0,
            noiseFreq = 0.003,
            fieldMode = FieldMode.CURL,
            perRingNoise = true,
            useHobby = true
        )
        
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
                    params = params.copy(compositionMode = CompositionMode.HERO)
                    showStatus("Mode: Hero (single ring + ghosts)")
                }
                "2" -> {
                    params = params.copy(compositionMode = CompositionMode.FIELD)
                    showStatus("Mode: Field (${params.ringCount} rings)")
                }
                
                // G: Toggle field type (gradient vs curl)
                "g" -> {
                    val newMode = if (params.fieldMode == FieldMode.GRADIENT) FieldMode.CURL else FieldMode.GRADIENT
                    params = params.copy(fieldMode = newMode)
                    val modeName = if (newMode == FieldMode.GRADIENT) "Gradient (push/pull)" else "Curl (swirl)"
                    showStatus("Field: $modeName")
                }
                
                // [ ]: Adjust bend strength β
                "[" -> {
                    val newBeta = (params.beta - 10.0).coerceIn(10.0, 200.0)
                    params = params.copy(beta = newBeta)
                    showStatus("Bend β: ${String.format("%.0f", newBeta)}")
                }
                "]" -> {
                    val newBeta = (params.beta + 10.0).coerceIn(10.0, 200.0)
                    params = params.copy(beta = newBeta)
                    showStatus("Bend β: ${String.format("%.0f", newBeta)}")
                }
                
                // - =: Adjust noise frequency
                "-" -> {
                    val newFreq = (params.noiseFreq - 0.001).coerceIn(0.001, 0.01)
                    params = params.copy(noiseFreq = newFreq)
                    showStatus("Noise freq: ${String.format("%.4f", newFreq)}")
                }
                "=" -> {
                    val newFreq = (params.noiseFreq + 0.001).coerceIn(0.001, 0.01)
                    params = params.copy(noiseFreq = newFreq)
                    showStatus("Noise freq: ${String.format("%.4f", newFreq)}")
                }
                
                // L: Toggle per-ring noise vs shared noise
                "l" -> {
                    params = params.copy(perRingNoise = !params.perRingNoise)
                    val modeName = if (params.perRingNoise) "Per-ring (varied)" else "Shared (uniform)"
                    showStatus("Noise: $modeName")
                }
                
                // H: Toggle Hobby smoothing
                "h" -> {
                    params = params.copy(useHobby = !params.useHobby)
                    showStatus("Hobby smoothing: ${if (params.useHobby) "ON" else "OFF"}")
                }
                
                // M: Toggle multi-pass warp
                "m" -> {
                    params = params.copy(multiPassWarp = !params.multiPassWarp)
                    showStatus("Multi-pass warp: ${if (params.multiPassWarp) "ON" else "OFF"}")
                }
                
                // N: Adjust ring count
                "n" -> {
                    val newCount = when {
                        params.ringCount < 8 -> 12
                        params.ringCount < 16 -> 20
                        params.ringCount < 25 -> 25
                        else -> 5
                    }
                    params = params.copy(ringCount = newCount)
                    showStatus("Ring count: $newCount")
                }
                
                // E: Export PNG
                "e" -> {
                    exportBent600x800(drawer, params)
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
            renderBentPoster(drawer, params, width, height)
            
            // Render debug overlay if enabled
            renderBentDebugOverlay(drawer, params, width, height)
            
            // Show status message for 2 seconds
            if (seconds - statusTime < 2.0 && statusMessage.isNotEmpty()) {
                drawer.fill = BENT_INK_DARK.opacify(0.9)
                drawer.fontMap = loadFont("data/fonts/default.otf", 14.0)
                drawer.text(statusMessage, 10.0, 20.0)
            }
        }
    }
}
