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

// ========== Data Classes ==========

/**
 * Represents a point charge in the 2D vector field.
 * Positive q = attractor, negative q = repeller.
 */
data class Charge(
    val pos: Vector2,
    val q: Double  // Positive = attractor, Negative = repeller
)

/**
 * All parameters for the magnetic field lines generator.
 */
data class MagneticParams(
    val seed: Long,
    val chargeCount: Int = 5,
    val lineCount: Int = 3000,
    val stepSize: Double = 2.0,
    val stepsMax: Int = 200,
    val epsilon: Double = 15.0,  // Softening parameter
    val tension: Double = 1.1,
    val paletteMode: Int = 0,  // 0 = metallic, 1 = aurora
    val shortLineSteps: Int = 30,
    val longLineSteps: Int = 200,
    val shortLineRatio: Double = 0.7,  // 70% short, 30% long
    val showArrows: Boolean = false,
    val captureRadius: Double = 8.0,
    val minFieldMagnitude: Double = 0.001,
    val wMin: Double = 0.3,
    val wMax: Double = 3.5
)

/**
 * Data for each rendered line: the Hobby curve, per-sample styling info.
 */
data class FieldLine(
    val contour: ShapeContour,
    val points: List<Vector2>,
    val avgFieldMag: Double,
    val minDistToCharge: Double,
    val isLong: Boolean
)

/**
 * Pre-computed styled segment for batch rendering.
 * Groups segments by similar stroke weight for efficient drawing.
 */
data class StyledSegment(
    val p0: Vector2,
    val p1: Vector2,
    val color: ColorRGBa,
    val weight: Double
)

/**
 * Cached grain particle for background.
 */
data class GrainParticle(
    val x: Double,
    val y: Double,
    val size: Double
)

// ========== Vector Field Computation ==========

/**
 * Computes the vector field at point p due to all charges.
 * F(p) = sum_i q_i * (p - p_i) / (|p - p_i|^3 + epsilon^3)
 * Using softening to avoid singularities.
 */
fun fieldAt(p: Vector2, charges: List<Charge>, epsilon: Double): Vector2 {
    var total = Vector2.ZERO
    for (charge in charges) {
        val delta = p - charge.pos
        val distSq = delta.squaredLength
        // Softened denominator: (dist^2 + eps^2)^(3/2) = (r^2+eps^2)^1.5
        val denom = (distSq + epsilon * epsilon).pow(1.5)
        if (denom > 1e-10) {
            total += delta * (charge.q / denom)
        }
    }
    return total
}

/**
 * Returns the distance to the nearest charge from point p.
 */
fun nearestChargeDist(p: Vector2, charges: List<Charge>): Double {
    return charges.minOfOrNull { p.distanceTo(it.pos) } ?: Double.MAX_VALUE
}

/**
 * Field magnitude at point p.
 */
fun fieldMagnitude(p: Vector2, charges: List<Charge>, epsilon: Double): Double {
    return fieldAt(p, charges, epsilon).length
}

// ========== Charge Generation ==========

/**
 * Generates charges with intentional composition:
 * - One dominant system (strong attractor-repeller pair)
 * - Smaller satellite charges
 * - Ensures negative space by avoiding crowding center
 */
fun generateCharges(params: MagneticParams, bounds: Rectangle, rng: Random): List<Charge> {
    val charges = mutableListOf<Charge>()
    val safeMargin = min(bounds.width, bounds.height) * 0.08
    val safeRect = Rectangle(
        bounds.x + safeMargin,
        bounds.y + safeMargin,
        bounds.width - 2 * safeMargin,
        bounds.height - 2 * safeMargin
    )
    
    fun randomPos(): Vector2 {
        return Vector2(
            safeRect.x + rng.nextDouble() * safeRect.width,
            safeRect.y + rng.nextDouble() * safeRect.height
        )
    }
    
    val count = params.chargeCount.coerceIn(3, 9)
    
    // Create dominant system first: strong dipole-like pair
    val dominantStrength = 8000.0 + rng.nextDouble() * 4000.0
    val angle = rng.nextDouble() * 2 * PI
    val separation = min(bounds.width, bounds.height) * (0.15 + rng.nextDouble() * 0.2)
    
    // Place dominant pair slightly off-center for asymmetry
    val centerOffset = Vector2(
        (rng.nextDouble() - 0.5) * bounds.width * 0.3,
        (rng.nextDouble() - 0.5) * bounds.height * 0.3
    )
    val dominantCenter = bounds.center + centerOffset
    
    val dir = Vector2(cos(angle), sin(angle))
    charges.add(Charge(dominantCenter - dir * separation * 0.5, dominantStrength))  // Attractor
    charges.add(Charge(dominantCenter + dir * separation * 0.5, -dominantStrength * (0.7 + rng.nextDouble() * 0.6)))  // Repeller
    
    // Add satellite charges
    val remainingCount = count - 2
    repeat(remainingCount) {
        val isAttractor = rng.nextDouble() < 0.4
        val strength = (1500.0 + rng.nextDouble() * 3000.0) * (if (isAttractor) 1.0 else -1.0)
        
        // Try to place away from existing charges for better composition
        var bestPos = randomPos()
        var bestMinDist = 0.0
        repeat(10) {
            val candidate = randomPos()
            val minDist = charges.minOfOrNull { candidate.distanceTo(it.pos) } ?: Double.MAX_VALUE
            if (minDist > bestMinDist) {
                bestMinDist = minDist
                bestPos = candidate
            }
        }
        charges.add(Charge(bestPos, strength))
    }
    
    return charges
}

// ========== Numerical Integration (RK4) ==========

/**
 * RK4 integration step.
 */
fun rk4Step(p: Vector2, charges: List<Charge>, epsilon: Double, h: Double): Vector2 {
    val k1 = fieldAt(p, charges, epsilon).normalized
    val k2 = fieldAt(p + k1 * (h / 2), charges, epsilon).normalized
    val k3 = fieldAt(p + k2 * (h / 2), charges, epsilon).normalized
    val k4 = fieldAt(p + k3 * h, charges, epsilon).normalized
    return p + (k1 + k2 * 2.0 + k3 * 2.0 + k4) * (h / 6.0)
}

/**
 * Integrates a path through the field starting at seedPoint.
 * Uses RK4 integration with various termination conditions.
 */
fun integratePath(
    seedPoint: Vector2,
    charges: List<Charge>,
    params: MagneticParams,
    bounds: Rectangle,
    maxSteps: Int,
    forward: Boolean = true
): List<Vector2> {
    val path = mutableListOf<Vector2>()
    var p = seedPoint
    path.add(p)
    
    val direction = if (forward) 1.0 else -1.0
    val h = params.stepSize * direction
    val padding = min(bounds.width, bounds.height) * 0.02
    val paddedBounds = Rectangle(
        bounds.x - padding,
        bounds.y - padding,
        bounds.width + 2 * padding,
        bounds.height + 2 * padding
    )
    
    for (step in 0 until maxSteps) {
        // Check termination conditions
        
        // 1. Out of bounds
        if (!paddedBounds.contains(p)) break
        
        // 2. Too close to a charge (captured)
        val distToNearest = nearestChargeDist(p, charges)
        if (distToNearest < params.captureRadius) break
        
        // 3. Field too weak (stagnation)
        val fieldMag = fieldMagnitude(p, charges, params.epsilon)
        if (fieldMag < params.minFieldMagnitude) break
        
        // 4. Self-intersection check (simplified: check recent points)
        if (path.size > 20) {
            var nearCount = 0
            for (i in 0 until path.size - 10) {
                if (p.distanceTo(path[i]) < params.stepSize * 2) {
                    nearCount++
                    if (nearCount > 3) break
                }
            }
            if (nearCount > 3) break
        }
        
        // RK4 step
        val pNext = rk4Step(p, charges, params.epsilon, h)
        
        // Adaptive step: skip if step is too small
        if (pNext.distanceTo(p) < 0.1) break
        
        path.add(pNext)
        p = pNext
    }
    
    return path
}

/**
 * Generates seed points for field lines using a mix of strategies.
 */
fun generateSeedPoints(
    params: MagneticParams,
    charges: List<Charge>,
    bounds: Rectangle,
    rng: Random
): List<Pair<Vector2, Boolean>> {  // Pair<position, isLong>
    val seeds = mutableListOf<Pair<Vector2, Boolean>>()
    val safeMargin = min(bounds.width, bounds.height) * 0.08
    val safeRect = Rectangle(
        bounds.x + safeMargin,
        bounds.y + safeMargin,
        bounds.width - 2 * safeMargin,
        bounds.height - 2 * safeMargin
    )
    
    val totalLines = params.lineCount
    
    // Strategy 1: Uniform random seeds (majority)
    val uniformCount = (totalLines * 0.5).toInt()
    repeat(uniformCount) {
        val isLong = rng.nextDouble() > params.shortLineRatio
        val p = Vector2(
            safeRect.x + rng.nextDouble() * safeRect.width,
            safeRect.y + rng.nextDouble() * safeRect.height
        )
        // Reject if too close to any charge
        if (nearestChargeDist(p, charges) > params.captureRadius * 2) {
            seeds.add(Pair(p, isLong))
        }
    }
    
    // Strategy 2: Extra seeds near charges (interesting field regions)
    val nearChargeCount = (totalLines * 0.3).toInt()
    repeat(nearChargeCount) {
        val charge = charges[rng.nextInt(charges.size)]
        val angle = rng.nextDouble() * 2 * PI
        val radius = params.captureRadius * 2 + rng.nextDouble() * 60.0
        val p = charge.pos + Vector2(cos(angle), sin(angle)) * radius
        if (safeRect.contains(p)) {
            seeds.add(Pair(p, rng.nextDouble() > params.shortLineRatio))
        }
    }
    
    // Strategy 3: Ring seeds around charges for "filing halos"
    val ringCount = (totalLines * 0.2).toInt()
    val ringsPerCharge = ringCount / charges.size.coerceAtLeast(1)
    for (charge in charges) {
        val numRings = rng.nextInt(2, 4)
        for (ring in 0 until numRings) {
            val baseRadius = params.captureRadius * 1.5 + ring * 15.0
            val pointsOnRing = ringsPerCharge / numRings
            for (i in 0 until pointsOnRing) {
                val angle = (i.toDouble() / pointsOnRing) * 2 * PI + rng.nextDouble() * 0.3
                val radius = baseRadius + rng.nextDouble() * 8.0
                val p = charge.pos + Vector2(cos(angle), sin(angle)) * radius
                if (safeRect.contains(p)) {
                    seeds.add(Pair(p, false))  // Ring seeds are short lines
                }
            }
        }
    }
    
    return seeds.shuffled(rng).take(totalLines)
}

// ========== Path Processing ==========

/**
 * Resamples path to have roughly evenly spaced points.
 */
fun resamplePath(points: List<Vector2>, targetSpacing: Double = 8.0): List<Vector2> {
    if (points.size < 2) return points
    
    val resampled = mutableListOf<Vector2>()
    resampled.add(points.first())
    
    var accumulated = 0.0
    
    for (i in 1 until points.size) {
        val dist = points[i].distanceTo(points[i - 1])
        accumulated += dist
        
        if (accumulated >= targetSpacing) {
            resampled.add(points[i])
            accumulated = 0.0
        }
    }
    
    // Always include last point if different
    if (resampled.last().distanceTo(points.last()) > 1.0) {
        resampled.add(points.last())
    }
    
    return resampled
}

/**
 * Creates a Hobby curve from points with given tension.
 */
fun makeHobbyCurve(points: List<Vector2>, tension: Double): ShapeContour? {
    if (points.size < 3) return null
    return try {
        ShapeContour.fromPoints(points, closed = false).hobbyCurve(tension)
    } catch (e: Exception) {
        null
    }
}

// ========== Styling ==========

/**
 * Computes stroke width based on field magnitude and distance to nearest charge.
 */
fun computeWidth(
    p: Vector2,
    charges: List<Charge>,
    params: MagneticParams,
    dNear: Double = 20.0,
    dFar: Double = 200.0
): Double {
    val dist = nearestChargeDist(p, charges)
    val t = smoothstep(dFar, dNear, dist)
    return params.wMin + (params.wMax - params.wMin) * t
}

/**
 * Computes alpha based on field magnitude.
 */
fun computeAlpha(fieldMag: Double, baseAlpha: Double = 0.7): Double {
    val t = (fieldMag * 500.0).coerceIn(0.0, 1.0)
    return (baseAlpha * 0.3 + baseAlpha * 0.7 * t).coerceIn(0.1, 0.95)
}

/**
 * Metallic palette: copper/silver/gold based on angle and position.
 */
fun metallicColor(tangentAngle: Double, t: Double, fieldMag: Double): ColorRGBa {
    // Base metallic colors
    val copper = ColorRGBa.fromHex("B87333")
    val silver = ColorRGBa.fromHex("C0C0C0")
    val gold = ColorRGBa.fromHex("CFB53B")
    val bronze = ColorRGBa.fromHex("CD7F32")
    
    // Map angle to color selection
    val normalizedAngle = ((tangentAngle + PI) / (2 * PI))  // 0 to 1
    val colorT = (normalizedAngle + t * 0.3 + fieldMag * 0.2) % 1.0
    
    return when {
        colorT < 0.33 -> copper.mix(gold, (colorT / 0.33))
        colorT < 0.66 -> gold.mix(silver, ((colorT - 0.33) / 0.33))
        else -> silver.mix(bronze, ((colorT - 0.66) / 0.34))
    }
}

/**
 * Aurora palette: green -> violet -> pink gradient.
 */
fun auroraColor(t: Double, potential: Double): ColorRGBa {
    // Aurora colors
    val green = ColorRGBa.fromHex("00FF87")
    val cyan = ColorRGBa.fromHex("00FFFF")
    val violet = ColorRGBa.fromHex("9400D3")
    val pink = ColorRGBa.fromHex("FF69B4")
    val magenta = ColorRGBa.fromHex("FF00FF")
    
    // Combine position along curve with potential influence
    val colorT = (t + potential * 0.15).coerceIn(0.0, 1.0)
    
    return when {
        colorT < 0.25 -> green.mix(cyan, colorT / 0.25)
        colorT < 0.5 -> cyan.mix(violet, (colorT - 0.25) / 0.25)
        colorT < 0.75 -> violet.mix(pink, (colorT - 0.5) / 0.25)
        else -> pink.mix(magenta, (colorT - 0.75) / 0.25)
    }
}

/**
 * Calculates potential at point p from all charges.
 */
fun potentialAt(p: Vector2, charges: List<Charge>): Double {
    return charges.sumOf { charge ->
        val dist = p.distanceTo(charge.pos).coerceAtLeast(1.0)
        charge.q / dist
    }
}

// ========== Pre-computation for Batch Rendering ==========

/**
 * Pre-computes all styled segments for a field line.
 * This moves expensive computations out of the render loop.
 */
fun precomputeLineSegments(
    line: FieldLine,
    charges: List<Charge>,
    params: MagneticParams
): List<StyledSegment> {
    val contour = line.contour
    if (contour.length < 5.0) return emptyList()
    
    val numSamples = (contour.length / 3.0).toInt().coerceIn(10, 200)
    val segments = mutableListOf<StyledSegment>()
    
    for (i in 0 until numSamples - 1) {
        val t0 = i.toDouble() / (numSamples - 1)
        val t1 = (i + 1).toDouble() / (numSamples - 1)
        
        val p0 = contour.position(t0)
        val p1 = contour.position(t1)
        
        // Compute tangent for color
        val tangent = (p1 - p0).normalized
        val angle = atan2(tangent.y, tangent.x)
        
        // Compute styling
        val fieldMag = fieldMagnitude(p0, charges, params.epsilon)
        val width = computeWidth(p0, charges, params)
        
        // Taper at ends
        val endTaper = when {
            t0 < 0.1 -> smoothstep(0.0, 0.1, t0)
            t0 > 0.9 -> smoothstep(1.0, 0.9, t0)
            else -> 1.0
        }
        val finalWidth = width * endTaper
        
        // Color based on palette mode
        val color = when (params.paletteMode) {
            0 -> metallicColor(angle, t0, fieldMag)
            else -> {
                val potential = potentialAt(p0, charges)
                val normalizedPotential = (potential / 5000.0).coerceIn(-1.0, 1.0) * 0.5 + 0.5
                auroraColor(t0, normalizedPotential)
            }
        }
        
        val alpha = computeAlpha(fieldMag) * endTaper
        
        segments.add(StyledSegment(p0, p1, color.opacify(alpha), finalWidth.coerceAtLeast(0.3)))
    }
    
    return segments
}

/**
 * Pre-computes all segments for all field lines, grouped by stroke weight bucket.
 * Returns a map from weight bucket to list of segments for batch drawing.
 */
fun precomputeAllSegments(
    fieldLines: List<FieldLine>,
    charges: List<Charge>,
    params: MagneticParams
): Map<Double, List<StyledSegment>> {
    // Sort lines by avg field magnitude for proper layering
    val sortedLines = fieldLines.sortedBy { it.avgFieldMag + if (it.isLong) 100.0 else 0.0 }
    
    val allSegments = sortedLines.flatMap { line ->
        precomputeLineSegments(line, charges, params)
    }
    
    // Group by rounded weight for batch drawing (round to nearest 0.2)
    return allSegments.groupBy { ((it.weight * 5).toInt() / 5.0).coerceIn(0.3, 4.0) }
}

/**
 * Generates grain particles for background (cached).
 */
fun generateGrainParticles(width: Int, height: Int, count: Int, rng: Random): List<GrainParticle> {
    return (0 until count).map {
        GrainParticle(
            x = rng.nextDouble() * width,
            y = rng.nextDouble() * height,
            size = rng.nextDouble() * 1.5
        )
    }
}

// ========== Background Rendering ==========

/**
 * Renders the deep-space background with vignette and grain (optimized).
 */
fun renderBackground(drawer: Drawer, width: Int, height: Int, grainParticles: List<GrainParticle>) {
    // Deep black base
    drawer.clear(ColorRGBa.fromHex("0A0A0F"))
    
    // Vignette: darker at edges
    drawer.isolated {
        drawer.shadeStyle = shadeStyle {
            fragmentTransform = """
                vec2 uv = c_boundsPosition.xy;
                vec2 center = vec2(0.5, 0.5);
                float dist = distance(uv, center) * 1.4;
                float vignette = 1.0 - smoothstep(0.3, 1.0, dist);
                x_fill.rgb *= (0.7 + 0.3 * vignette);
            """.trimIndent()
        }
        drawer.fill = ColorRGBa.fromHex("0A0A0F")
        drawer.stroke = null
        drawer.rectangle(0.0, 0.0, width.toDouble(), height.toDouble())
    }
    
    // Subtle grain - batch draw using circles with pre-computed positions
    drawer.isolated {
        drawer.fill = ColorRGBa.WHITE.opacify(0.015)
        drawer.stroke = null
        // Batch by grouping similarly sized circles
        val bySize = grainParticles.groupBy { (it.size * 2).toInt() }
        for ((_, particles) in bySize) {
            val avgSize = particles.map { it.size }.average()
            drawer.circles(particles.map { Vector2(it.x, it.y) }, avgSize)
        }
    }
}

/**
 * Renders all pre-computed segments efficiently using batch drawing.
 */
fun renderBatchedSegments(drawer: Drawer, segmentsByWeight: Map<Double, List<StyledSegment>>) {
    drawer.fill = null
    
    // Draw each weight bucket in order (thinner first, thicker last)
    for ((weight, segments) in segmentsByWeight.toSortedMap()) {
        drawer.strokeWeight = weight
        
        // For segments with similar colors, batch them
        // Group by approximate color (rounded RGBA)
        val byColor = segments.groupBy { seg ->
            val r = (seg.color.r * 10).toInt()
            val g = (seg.color.g * 10).toInt()
            val b = (seg.color.b * 10).toInt()
            val a = (seg.color.alpha * 10).toInt()
            "$r-$g-$b-$a"
        }
        
        for ((_, colorSegments) in byColor) {
            // Use average color for the batch
            val avgColor = ColorRGBa(
                colorSegments.map { it.color.r }.average(),
                colorSegments.map { it.color.g }.average(),
                colorSegments.map { it.color.b }.average(),
                colorSegments.map { it.color.alpha }.average()
            )
            drawer.stroke = avgColor
            drawer.lineSegments(colorSegments.flatMap { listOf(it.p0, it.p1) })
        }
    }
}

// ========== Line Rendering ==========

/**
 * Renders a single field line with varying width and color.
 */
fun renderLine(
    drawer: Drawer,
    line: FieldLine,
    charges: List<Charge>,
    params: MagneticParams,
    @Suppress("UNUSED_PARAMETER") rng: Random
) {
    val contour = line.contour
    if (contour.length < 5.0) return
    
    // Sample contour for rendering
    val numSamples = (contour.length / 3.0).toInt().coerceIn(10, 200)
    
    drawer.isolated {
        drawer.fill = null
        
        for (i in 0 until numSamples - 1) {
            val t0 = i.toDouble() / (numSamples - 1)
            val t1 = (i + 1).toDouble() / (numSamples - 1)
            
            val p0 = contour.position(t0)
            val p1 = contour.position(t1)
            
            // Compute tangent for color
            val tangent = (p1 - p0).normalized
            val angle = atan2(tangent.y, tangent.x)
            
            // Compute styling
            val fieldMag = fieldMagnitude(p0, charges, params.epsilon)
            val width = computeWidth(p0, charges, params)
            
            // Taper at ends
            val endTaper = when {
                t0 < 0.1 -> smoothstep(0.0, 0.1, t0)
                t0 > 0.9 -> smoothstep(1.0, 0.9, t0)
                else -> 1.0
            }
            val finalWidth = width * endTaper
            
            // Color based on palette mode
            val color = when (params.paletteMode) {
                0 -> metallicColor(angle, t0, fieldMag)
                else -> {
                    val potential = potentialAt(p0, charges)
                    val normalizedPotential = (potential / 5000.0).coerceIn(-1.0, 1.0) * 0.5 + 0.5
                    auroraColor(t0, normalizedPotential)
                }
            }
            
            val alpha = computeAlpha(fieldMag) * endTaper
            
            drawer.strokeWeight = finalWidth.coerceAtLeast(0.3)
            drawer.stroke = color.opacify(alpha)
            drawer.lineSegment(p0, p1)
        }
    }
}

/**
 * Renders arrowhead at end of line.
 */
fun renderArrowhead(drawer: Drawer, contour: ShapeContour, @Suppress("UNUSED_PARAMETER") params: MagneticParams, color: ColorRGBa) {
    if (contour.length < 20.0) return
    
    val endT = 0.95
    val endPos = contour.position(endT)
    val prePos = contour.position(endT - 0.05)
    val dir = (endPos - prePos).normalized
    val perp = dir.perpendicular()
    
    val arrowSize = 4.0
    val tip = endPos + dir * arrowSize
    val left = endPos - dir * arrowSize * 0.5 + perp * arrowSize * 0.4
    val right = endPos - dir * arrowSize * 0.5 - perp * arrowSize * 0.4
    
    drawer.isolated {
        drawer.fill = color.opacify(0.6)
        drawer.stroke = null
        drawer.contour(ShapeContour.fromPoints(listOf(tip, left, right), closed = true))
    }
}

// ========== Caption Block ==========

/**
 * Renders the caption block with metadata.
 */
fun renderCaption(
    drawer: Drawer,
    params: MagneticParams,
    lineCount: Int,
    charges: List<Charge>,
    font: FontMap?
) {
    drawer.isolated {
        drawer.fontMap = font
        drawer.fill = ColorRGBa.WHITE.opacify(0.6)
        
        val x = 20.0
        var y = drawer.height - 80.0
        val lineHeight = 12.0
        
        drawer.text("MAGNETIC FIELD LINES", x, y)
        y += lineHeight
        
        drawer.fill = ColorRGBa.WHITE.opacify(0.4)
        drawer.text("Seed: ${params.seed}", x, y)
        y += lineHeight
        drawer.text("Charges: ${charges.size} | Lines: $lineCount", x, y)
        y += lineHeight
        drawer.text("Step: ${String.format("%.1f", params.stepSize)} | Tension: ${String.format("%.2f", params.tension)}", x, y)
        y += lineHeight
        val paletteName = if (params.paletteMode == 0) "Metallic" else "Aurora"
        drawer.text("Palette: $paletteName", x, y)
    }
}

// ========== Debug Overlay ==========

/**
 * Renders debug information: charges, field grid, raw vs Hobby.
 */
fun renderDebug(
    drawer: Drawer,
    charges: List<Charge>,
    params: MagneticParams,
    sampleRawLines: List<List<Vector2>>,
    bounds: Rectangle
) {
    drawer.isolated {
        // Draw charges with signs
        charges.forEach { charge ->
            val color = if (charge.q > 0) ColorRGBa.RED.opacify(0.8) else ColorRGBa.BLUE.opacify(0.8)
            drawer.fill = color
            drawer.stroke = ColorRGBa.WHITE.opacify(0.5)
            drawer.strokeWeight = 1.0
            drawer.circle(charge.pos, 8.0)
            
            // Draw sign
            drawer.fill = ColorRGBa.WHITE
            drawer.stroke = null
            val sign = if (charge.q > 0) "+" else "−"
            drawer.text(sign, charge.pos.x - 3.0, charge.pos.y + 4.0)
            
            // Draw halo
            drawer.fill = null
            drawer.stroke = color.opacify(0.2)
            drawer.strokeWeight = 1.0
            drawer.circle(charge.pos, params.captureRadius)
        }
        
        // Draw coarse vector field grid
        drawer.stroke = ColorRGBa.YELLOW.opacify(0.3)
        drawer.strokeWeight = 0.5
        val gridSpacing = 40.0
        var x = bounds.x + gridSpacing
        while (x < bounds.x + bounds.width) {
            var y = bounds.y + gridSpacing
            while (y < bounds.y + bounds.height) {
                val p = Vector2(x, y)
                val field = fieldAt(p, charges, params.epsilon)
                val mag = field.length
                if (mag > 0.0001) {
                    val dir = field.normalized
                    val arrowLen = (mag * 3000.0).coerceIn(5.0, 20.0)
                    drawer.lineSegment(p, p + dir * arrowLen)
                }
                y += gridSpacing
            }
            x += gridSpacing
        }
        
        // Draw sample raw polylines vs Hobby
        drawer.strokeWeight = 1.0
        sampleRawLines.take(5).forEachIndexed { _, raw ->
            // Raw polyline in red
            drawer.stroke = ColorRGBa.RED.opacify(0.5)
            if (raw.size >= 2) {
                drawer.lineStrip(raw)
            }
            
            // Hobby curve in green
            val hobby = makeHobbyCurve(resamplePath(raw), params.tension)
            if (hobby != null) {
                drawer.stroke = ColorRGBa.GREEN.opacify(0.7)
                drawer.contour(hobby)
            }
        }
    }
}

// ========== Main Render Function ==========

/**
 * Generates and returns all field lines.
 */
fun generateFieldLines(
    params: MagneticParams,
    charges: List<Charge>,
    bounds: Rectangle,
    rng: Random
): List<FieldLine> {
    val seeds = generateSeedPoints(params, charges, bounds, rng)
    val lines = mutableListOf<FieldLine>()
    
    for ((seedPoint, isLong) in seeds) {
        val maxSteps = if (isLong) params.longLineSteps else params.shortLineSteps
        
        // Integrate forward
        val forwardPath = integratePath(seedPoint, charges, params, bounds, maxSteps, forward = true)
        
        // Optionally integrate backward for symmetric lines
        val backwardPath = if (isLong && rng.nextDouble() < 0.5) {
            integratePath(seedPoint, charges, params, bounds, maxSteps / 2, forward = false)
        } else {
            emptyList()
        }
        
        // Combine paths (backward reversed + forward)
        val fullPath = if (backwardPath.size > 1) {
            backwardPath.reversed() + forwardPath.drop(1)
        } else {
            forwardPath
        }
        
        if (fullPath.size < 4) continue
        
        // Resample and create Hobby curve
        val resampled = resamplePath(fullPath)
        if (resampled.size < 3) continue
        
        val contour = makeHobbyCurve(resampled, params.tension) ?: continue
        
        // Compute average field magnitude and min distance
        val avgMag = resampled.map { fieldMagnitude(it, charges, params.epsilon) }.average()
        val minDist = resampled.minOf { nearestChargeDist(it, charges) }
        
        lines.add(FieldLine(contour, resampled, avgMag, minDist, isLong))
    }
    
    return lines
}

// ========== Main Program ==========

fun main() = application {
    configure {
        width = 600
        height = 800
        title = "Magnetic Field Lines"
    }
    
    program {
        var seed = Random.nextLong()
        var params = MagneticParams(seed)
        var charges = emptyList<Charge>()
        var fieldLines = emptyList<FieldLine>()
        var sampleRawLines = emptyList<List<Vector2>>()
        var showDebug = false
        
        // Cached render data (pre-computed during regenerate)
        var cachedSegments: Map<Double, List<StyledSegment>> = emptyMap()
        var cachedGrainParticles: List<GrainParticle> = emptyList()
        var cachedArrowLines: List<FieldLine> = emptyList()
        
        val bounds = Rectangle(0.0, 0.0, width.toDouble(), height.toDouble())
        
        // Load font
        val font = loadFont("data/fonts/default.otf", 10.0)
        
        fun regenerate() {
            val startTime = System.currentTimeMillis()
            val rng = Random(seed)
            params = params.copy(seed = seed)
            
            // Generate charges
            charges = generateCharges(params, bounds, rng)
            
            // Generate field lines
            fieldLines = generateFieldLines(params, charges, bounds, rng)
            
            // Pre-compute all segments for batch rendering (this is the expensive part, but done once)
            cachedSegments = precomputeAllSegments(fieldLines, charges, params)
            
            // Pre-compute grain particles (only needs seed, not palette)
            val grainRng = Random(seed + 100)
            cachedGrainParticles = generateGrainParticles(width, height, 4000, grainRng)
            
            // Pre-compute arrow lines selection
            val sortedLines = fieldLines.sortedBy { it.avgFieldMag + if (it.isLong) 100.0 else 0.0 }
            val heroLines = sortedLines.filter { it.isLong && it.contour.length > 100.0 }
            val arrowCount = (heroLines.size * 0.1).toInt().coerceAtLeast(3)
            cachedArrowLines = heroLines.shuffled(rng).take(arrowCount)
            
            // Store some raw lines for debug
            val debugRng = Random(seed + 1)
            val debugSeeds = generateSeedPoints(params.copy(lineCount = 10), charges, bounds, debugRng)
            sampleRawLines = debugSeeds.take(5).map { (seedPoint, isLong) ->
                integratePath(seedPoint, charges, params, bounds, if (isLong) params.longLineSteps else params.shortLineSteps, true)
            }
            
            val elapsed = System.currentTimeMillis() - startTime
            println("Generated ${charges.size} charges, ${fieldLines.size} lines (seed=$seed) in ${elapsed}ms")
        }
        
        // Recompute only the visual segments (when palette changes but field lines stay the same)
        fun recomputeSegments() {
            val startTime = System.currentTimeMillis()
            cachedSegments = precomputeAllSegments(fieldLines, charges, params)
            val elapsed = System.currentTimeMillis() - startTime
            println("Recomputed segments in ${elapsed}ms")
        }
        
        fun drawArtwork(drawer: Drawer, font: FontMap?) {
            // Background with cached grain
            renderBackground(drawer, width, height, cachedGrainParticles)
            
            // Render all lines using batch drawing (fast!)
            renderBatchedSegments(drawer, cachedSegments)
            
            // Render arrowheads on cached hero lines
            if (params.showArrows) {
                val color = if (params.paletteMode == 0) {
                    ColorRGBa.fromHex("CFB53B")  // Gold
                } else {
                    ColorRGBa.fromHex("00FF87")  // Green
                }
                cachedArrowLines.forEach { line ->
                    renderArrowhead(drawer, line.contour, params, color)
                }
            }
            
            // Subtle charge halos (very low alpha)
            drawer.isolated {
                charges.forEach { charge ->
                    val color = if (charge.q > 0) {
                        ColorRGBa.fromHex("FF6B6B").opacify(0.05)
                    } else {
                        ColorRGBa.fromHex("4ECDC4").opacify(0.05)
                    }
                    drawer.fill = null
                    drawer.stroke = color
                    drawer.strokeWeight = 1.0
                    repeat(3) { i ->
                        drawer.circle(charge.pos, params.captureRadius * (1.5 + i * 0.8))
                    }
                }
            }
            
            // Caption
            renderCaption(drawer, params, fieldLines.size, charges, font)
            
            // Debug overlay
            if (showDebug) {
                renderDebug(drawer, charges, params, sampleRawLines, bounds)
            }
        }
        
        fun export() {
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            val paletteName = if (params.paletteMode == 0) "metal" else "aurora"
            val filename = "images/magnetic_s${seed}_c${charges.size}_l${fieldLines.size}_${paletteName}_$timestamp.png"
            
            val rt = renderTarget(width, height) {
                colorBuffer()
                depthBuffer(DepthFormat.DEPTH24_STENCIL8)
            }
            
            drawer.isolatedWithTarget(rt) {
                drawer.ortho(rt)
                drawArtwork(drawer, font)
            }
            
            File("images").mkdirs()
            rt.colorBuffer(0).saveToFile(File(filename))
            rt.destroy()
            println("Exported $filename")
        }
        
        regenerate()
        
        // Keyboard controls
        keyboard.keyDown.listen { event ->
            when (event.name) {
                "r" -> {
                    seed = Random.nextLong()
                    regenerate()
                }
                "1" -> {
                    params = params.copy(paletteMode = 0)  // Metallic
                    recomputeSegments()  // Recompute colors without regenerating field lines
                }
                "2" -> {
                    params = params.copy(paletteMode = 1)  // Aurora
                    recomputeSegments()  // Recompute colors without regenerating field lines
                }
                "bracketleft", "[" -> {
                    params = params.copy(chargeCount = (params.chargeCount - 1).coerceIn(3, 9))
                    regenerate()
                }
                "bracketright", "]" -> {
                    params = params.copy(chargeCount = (params.chargeCount + 1).coerceIn(3, 9))
                    regenerate()
                }
                "minus", "-" -> {
                    params = params.copy(tension = (params.tension - 0.1).coerceIn(0.3, 2.0))
                    regenerate()
                }
                "equal", "=" -> {
                    params = params.copy(tension = (params.tension + 0.1).coerceIn(0.3, 2.0))
                    regenerate()
                }
                "h" -> {
                    params = params.copy(stepSize = (params.stepSize - 0.5).coerceIn(0.5, 5.0))
                    regenerate()
                }
                "j" -> {
                    params = params.copy(stepSize = (params.stepSize + 0.5).coerceIn(0.5, 5.0))
                    regenerate()
                }
                "l" -> {
                    params = params.copy(lineCount = (params.lineCount + 500).coerceAtMost(20000))
                    regenerate()
                }
                "k" -> {
                    params = params.copy(lineCount = (params.lineCount - 500).coerceAtLeast(500))
                    regenerate()
                }
                "a" -> {
                    params = params.copy(showArrows = !params.showArrows)
                }
                "d" -> {
                    showDebug = !showDebug
                }
                "e" -> {
                    export()
                }
            }
        }
        
        extend {
            drawArtwork(drawer, font)
        }
    }
}
