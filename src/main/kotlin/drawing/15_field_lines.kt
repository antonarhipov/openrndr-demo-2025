package drawing

import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.*
import org.openrndr.extra.noise.random
import org.openrndr.extra.shapes.hobbyCurve
import org.openrndr.math.Vector2
import org.openrndr.math.map
import org.openrndr.shape.ShapeContour
import org.openrndr.shape.Rectangle
import kotlin.math.*
import kotlin.random.Random
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// ========== Data Classes ==========

enum class FieldType {
    ATTRACTOR,
    REPULSOR,
    VORTEX,
    DIPOLE // Not strictly used as a single object type in the physics, but kept for variant logic if needed
}

data class FieldObject(
    val pos: Vector2,
    val type: FieldType,
    val strength: Double,
    val falloff: Double, // Exponent p
    val coreRadius: Double
)

data class FieldGenParams(
    val seed: Long,
    val variant: Int,
    val numObjects: Int,
    val numLines: Int,
    val stepSize: Double,
    val tension: Double,
    val decay: Double
)

// ========== Vector Field ==========

fun vectorField(x: Vector2, objects: List<FieldObject>, bounds: Map<String, Double>? = null): Vector2 {
    var totalForce = Vector2.ZERO
    val eps = 0.001

    // Global drift/wind (optional, keeping it small)
    // totalForce += Vector2(0.1, 0.0) 

    for (obj in objects) {
        val delta = x - obj.pos
        val dist = delta.length
        
        // Avoid singularities
        if (dist < obj.coreRadius) {
            // Inside core, maybe dampen or zero out? 
            // Or just let it be huge? Let's cap it effectively by using coreRadius in the denominator
            // But falloff logic suggests using the distance.
        }

        // Force magnitude: k / (r^p + eps)
        // Strength already includes sign. 
        // For Attractor, strength should be negative if we sum vectors towards center?
        // Standard gravity: F = -G * m / r^2 * r_hat.
        // If 'strength' is positive for Attractor, we need to pull.
        // Let's define: 
        // ATTRACTOR: pulls TOWARDS (v points to obj)
        // REPULSOR: pushes AWAY (v points away from obj)
        // VORTEX: tangent
        
        // Let's stick to convention: 
        // REPULSOR: direction = delta.normalized (away)
        // ATTRACTOR: direction = -delta.normalized (towards)
        
        // However, I'll handle sign in 'strength' if possible, or explicit types.
        
        val denominator = dist.pow(obj.falloff) + eps
        val magnitude = abs(obj.strength) / denominator
        
        var dir = delta.normalized
        if (dist < 0.0001) dir = Vector2.UNIT_X // fallback

        val force = when (obj.type) {
            FieldType.ATTRACTOR -> dir * -magnitude // Towards
            FieldType.REPULSOR -> dir * magnitude // Away
            FieldType.VORTEX -> dir.perpendicular() * magnitude // Rotational
            else -> Vector2.ZERO
        }
        
        totalForce += force
    }

    // Optional: Void obstacles (Variant 3)
    // Reflect or nullify if inside?
    // Handled in integration step or by adding localized repulsion here.
    
    return totalForce
}

// ========== Generation Logic ==========

fun generateObjects(rng: Random, width: Double, height: Double, variant: Int): List<FieldObject> {
    val objects = mutableListOf<FieldObject>()
    val safeW = width * 0.8
    val safeH = height * 0.8
    val marginX = (width - safeW) / 2
    val marginY = (height - safeH) / 2
    
    fun rPos(): Vector2 {
        return Vector2(
            marginX + rng.nextDouble() * safeW,
            marginY + rng.nextDouble() * safeH
        )
    }

    when (variant) {
        1 -> { // Magnetic Dipole
            // Strong attractor + Strong repulsor
            val center = Vector2(width / 2.0, height / 2.0)
            val dist = min(width, height) * 0.25
            val angle = rng.nextDouble() * 2 * PI
            val offset = Vector2(cos(angle), sin(angle)) * dist
            
            val p1 = center - offset
            val p2 = center + offset
            
            // Pole 1
            objects.add(FieldObject(p1, FieldType.ATTRACTOR, 50000.0, 2.0, 20.0))
            // Pole 2
            objects.add(FieldObject(p2, FieldType.REPULSOR, 50000.0, 2.0, 20.0))
            
            // Add faint interferers
            repeat(rng.nextInt(1, 3)) {
                objects.add(FieldObject(rPos(), FieldType.VORTEX, 5000.0 * (if (rng.nextBoolean()) 1 else -1), 2.0, 10.0))
            }
        }
        2 -> { // Vortex Garden
            val count = rng.nextInt(4, 10)
            repeat(count) { i ->
                val sign = if (i % 2 == 0) 1.0 else -1.0
                objects.add(FieldObject(
                    rPos(),
                    FieldType.VORTEX,
                    20000.0 * sign, // Strength
                    1.8, // Falloff
                    15.0 // Core
                ))
            }
        }
        3 -> { // Hidden Obstacles
            // A few attractors/repulsors
            repeat(rng.nextInt(2, 5)) {
                val type = if (rng.nextBoolean()) FieldType.ATTRACTOR else FieldType.REPULSOR
                objects.add(FieldObject(
                    rPos(),
                    type,
                    30000.0,
                    2.2,
                    30.0
                ))
            }
            // "Obstacles" are handled as regions where field is manipulated or lines die, 
            // but we can also simulate them with super strong repulsors with steep falloff.
            // Let's add one "Hero Void" (repulsor with high p)
            objects.add(FieldObject(
                rPos(),
                FieldType.REPULSOR,
                100000.0,
                3.0, // Steeper falloff looks like a solid object
                60.0
            ))
        }
    }
    return objects
}

fun generateEmitters(rng: Random, width: Double, height: Double, variant: Int, objects: List<FieldObject>): List<Vector2> {
    val emitters = mutableListOf<Vector2>()
    val bounds = Rectangle(0.0, 0.0, width, height)
    
    when (variant) {
        1 -> { // Perimeter Ring for Dipole
            val count = 800
            val center = bounds.center
            val radius = min(width, height) * 0.45
            for (i in 0 until count) {
                val angle = (i.toDouble() / count) * 2 * PI
                emitters.add(center + Vector2(cos(angle), sin(angle)) * radius)
            }
        }
        2 -> { // Poisson Disc for Vortex
            // Simple random rejection for now to save tokens/complexity, "Poisson-ish"
            val count = 1200
            val candidates = 10000
            val points = mutableListOf<Vector2>()
            
            for (i in 0 until candidates) {
                if (points.size >= count) break
                val p = Vector2(rng.nextDouble() * width, rng.nextDouble() * height)
                
                // Margin check
                if (p.x < width * 0.08 || p.x > width * 0.92 || p.y < height * 0.08 || p.y > height * 0.92) continue
                
                // Distance check
                var ok = true
                for (other in points) {
                    if (p.distanceTo(other) < 10.0) {
                        ok = false
                        break
                    }
                }
                if (ok) points.add(p)
            }
            emitters.addAll(points)
        }
        3 -> { // Mixed (Halos + Random)
            // Halos around objects
            objects.forEach { obj ->
                val r = obj.coreRadius * 1.5
                val steps = (r * 3).toInt().coerceIn(10, 50)
                for (i in 0 until steps) {
                    val a = (i.toDouble() / steps) * 2 * PI
                    emitters.add(obj.pos + Vector2(cos(a), sin(a)) * r)
                }
            }
            // Fill rest with random
            repeat(500) {
                emitters.add(Vector2(rng.nextDouble() * width, rng.nextDouble() * height))
            }
        }
    }
    return emitters
}

// ========== Integration & processing ==========

fun integrateStreamline(
    start: Vector2,
    objects: List<FieldObject>,
    bounds: Rectangle,
    grid: IntArray, // Occupancy grid
    gridW: Int,
    gridH: Int,
    cellSize: Double
): List<Vector2> {
    val path = mutableListOf<Vector2>()
    var current = start
    path.add(current)
    
    val maxSteps = 400
    val baseStep = 5.0
    
    for (i in 0 until maxSteps) {
        val v = vectorField(current, objects)
        val speed = v.length
        
        // Stop if stagnant
        if (speed < 0.1) break
        
        // Adaptive step
        val dt = (baseStep / (speed + 0.1)).coerceIn(0.5, 15.0)
        
        // Euler integration (sufficient for art)
        // Normalize v to separate direction from magnitude logic if needed, 
        // but here v is the force. If we treat it as velocity field:
        val velocity = v.normalized * 5.0 // Constant speed tracing or field speed?
        // Issue says: "step length scales with field magnitude" -> this implies we follow geometry, 
        // but often field lines are better traced by normalizing direction and stepping fixed amount?
        // "dt = baseStep / (|v| + small)" implies taking smaller steps when V is large? 
        // Usually: step = V * dt. If dt ~ 1/|V|, then step ~ V/|V| = direction. 
        // So we are stepping roughly constant distance in the direction of V.
        
        val next = current + v.normalized * baseStep
        
        // Bounds check
        if (!bounds.contains(next)) break
        
        // Obstacle check (Variant 3 - Void)
        // Hardcoded void logic for simplicity if needed, or rely on repulsors.
        
        // Occupancy check
        val gx = (next.x / cellSize).toInt().coerceIn(0, gridW - 1)
        val gy = (next.y / cellSize).toInt().coerceIn(0, gridH - 1)
        val idx = gy * gridW + gx
        
        if (grid[idx] > 3) { // Density threshold
            break
        }
        grid[idx]++
        
        // Tight loop check
        if (path.size > 10 && next.distanceTo(path[path.size - 5]) < baseStep * 0.5) break
        
        path.add(next)
        current = next
    }
    
    return path
}

fun simplifyAndResample(path: List<Vector2>): List<Vector2> {
    if (path.size < 5) return emptyList()
    
    // 1. Simple Distance-based Decimation
    val simplified = mutableListOf<Vector2>()
    simplified.add(path.first())
    var last = path.first()
    
    for (i in 1 until path.size) {
        if (path[i].distanceTo(last) > 10.0) {
            simplified.add(path[i])
            last = path[i]
        }
    }
    if (path.last().distanceTo(last) > 2.0) simplified.add(path.last())
    
    if (simplified.size < 3) return emptyList()
    
    // 2. Resample to fixed count (e.g., 20) for Hobby
    // Actually, Hobby works best with 10-20 points.
    // Let's just return the simplified list, it's usually good enough.
    // Or we can use ShapeContour.fromPoints(simplified).sampleLinear(N)
    
    return simplified
}

// ========== Main Program ==========

fun main() = application {
    configure {
        width = 600
        height = 800
        title = "Field Lines Around Invisible Objects"
    }

    program {
        var seed = Random.nextLong()
        var variant = 1
        var showDebug = false
        
        // State
        var params = FieldGenParams(seed, variant, 5, 500, 5.0, 1.0, 2.0)
        var fieldObjects = emptyList<FieldObject>()
        var fieldLines = emptyList<ShapeContour>()
        // Store extra data for styling: avg strength per line
        var lineStrengths = emptyList<Double>() 
        
        fun regenerate() {
            val rng = Random(seed)
            
            // Setup Params
            val numObjects = when(variant) {
                1 -> rng.nextInt(3, 5)
                2 -> rng.nextInt(6, 12)
                3 -> rng.nextInt(4, 8)
                else -> 5
            }
            val numLines = when(variant) {
                1 -> 800
                2 -> 1200
                3 -> 900
                else -> 800
            }
            val tension = when(variant) {
                1 -> 0.6 // Tighter for scientific look
                2 -> 0.8
                3 -> 1.0 // Smoother for flow
                else -> 0.8
            }
            
            params = FieldGenParams(seed, variant, numObjects, numLines, 5.0, tension, 2.0)
            
            // 1. Objects
            fieldObjects = generateObjects(rng, width.toDouble(), height.toDouble(), variant)
            
            // 2. Emitters
            val emitters = generateEmitters(rng, width.toDouble(), height.toDouble(), variant, fieldObjects)
            
            // 3. Grid
            val cellSize = 5.0
            val gridW = ceil(width / cellSize).toInt()
            val gridH = ceil(height / cellSize).toInt()
            val grid = IntArray(gridW * gridH)
            
            val safeMargin = min(width, height) * 0.08
            val safeRect = Rectangle(safeMargin, safeMargin, width - 2 * safeMargin, height - 2 * safeMargin)
            
            // 4. Integrate
            val rawLines = emitters.mapNotNull { start ->
                if (!safeRect.contains(start)) null
                else integrateStreamline(start, fieldObjects, safeRect, grid, gridW, gridH, cellSize)
            }
            
            // 5. Convert to Hobby
            val newLines = mutableListOf<ShapeContour>()
            val newStrengths = mutableListOf<Double>()
            
            rawLines.forEach { path ->
                val simplified = simplifyAndResample(path)
                if (simplified.size >= 4) {
                    try {
                        val contour = ShapeContour.fromPoints(simplified, closed = false).hobbyCurve(params.tension)
                        newLines.add(contour)
                        
                        // Calculate avg strength
                        // Sample middle point
                        val mid = simplified[simplified.size / 2]
                        val v = vectorField(mid, fieldObjects)
                        newStrengths.add(v.length)
                    } catch (e: Exception) {
                        // Ignore failed fits
                    }
                }
            }
            
            fieldLines = newLines
            lineStrengths = newStrengths
        }
        
        regenerate()

        // Render Function
        fun drawArtwork(drawer: Drawer) {
            val rng = Random(seed)
            
            // Background
            val bgType = if (rng.nextBoolean()) "LIGHT" else "DARK"
            val bgColor = if (bgType == "LIGHT") ColorRGBa.fromHex("F7F5F0") else ColorRGBa.fromHex("1A1A1A")
            val inkColor = if (bgType == "LIGHT") ColorRGBa.BLACK.opacify(0.7) else ColorRGBa.WHITE.opacify(0.7)
            val accentColor = if (bgType == "LIGHT") ColorRGBa.fromHex("FF4400") else ColorRGBa.fromHex("00CCFF")
            
            drawer.clear(bgColor)
            
            // Haze/Grain
            drawer.isolated {
                drawer.fill = null
                drawer.stroke = inkColor.opacify(0.05)
                repeat(2000) {
                    drawer.circle(rng.nextDouble() * width, rng.nextDouble() * height, 1.0)
                }
            }
            
            // Variant 3: Void Highlight
            if (params.variant == 3) {
                fieldObjects.filter { it.strength > 80000.0 }.forEach { voidObj ->
                    drawer.isolated {
                        drawer.stroke = inkColor.opacify(0.15)
                        drawer.fill = null
                        drawer.circle(voidObj.pos, voidObj.coreRadius * 0.8)
                    }
                }
            }

            // Variant 1: Coordinate Ticks
            if (params.variant == 1) {
                drawer.isolated {
                    drawer.stroke = inkColor.opacify(0.3)
                    drawer.fill = null
                    for (i in 1 until 10) {
                        val x = width * (i / 10.0)
                        drawer.lineSegment(x, 0.0, x, 10.0)
                        drawer.lineSegment(x, height.toDouble(), x, height - 10.0)
                        
                        val y = height * (i / 10.0)
                        drawer.lineSegment(0.0, y, 10.0, y)
                        drawer.lineSegment(width.toDouble(), y, width - 10.0, y)
                    }
                }
            }

            // Main Lines
            drawer.isolated {
                drawer.fill = null
                // Sort by length or strength?
                // Drawing order: Faint -> Strong
                
                // We'll iterate manually to set style per line
                fieldLines.zip(lineStrengths).forEach { (contour, strength) ->
                    val isAccented = rng.nextDouble() < 0.05
                    val baseAlpha = map(0.0, 500.0, 0.2, 0.9, strength).coerceIn(0.1, 0.9)
                    
                    val c = if (isAccented) accentColor else inkColor
                    
                    drawer.strokeWeight = map(0.0, 500.0, 0.5, 2.5, strength).coerceIn(0.5, 3.0)
                    drawer.stroke = c.opacify(baseAlpha)
                    
                    drawer.contour(contour)
                }
            }
            
            // Legend
            drawer.isolated {
                drawer.fill = inkColor
                drawer.text("Field Lines | V${variant} | S${seed}", 20.0, height - 40.0)
                drawer.text("Objects: ${fieldObjects.size} | Lines: ${fieldLines.size}", 20.0, height - 20.0)
            }
            
            // Debug
            if (showDebug) {
                drawer.stroke = ColorRGBa.RED
                drawer.fill = ColorRGBa.RED.opacify(0.5)
                fieldObjects.forEach { obj ->
                    val r = if(obj.type == FieldType.VORTEX) 10.0 else 5.0
                    drawer.circle(obj.pos, r)
                    if (obj.coreRadius > 0) {
                        drawer.fill = null
                        drawer.circle(obj.pos, obj.coreRadius)
                    }
                }
            }
        }
        
        fun export() {
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            val filename = "fieldlines_v${variant}_s${seed}_${timestamp}.png"
            val rt = renderTarget(width, height) {
                colorBuffer()
                depthBuffer(DepthFormat.DEPTH24_STENCIL8)
            }
            drawer.isolatedWithTarget(rt) {
                drawer.ortho(rt)
                drawArtwork(drawer)
            }
            rt.colorBuffer(0).saveToFile(File(filename))
            rt.destroy()
            println("Exported $filename")
        }
        
        keyboard.keyDown.listen {
            when(it.name) {
                "r" -> { seed = Random.nextLong(); regenerate() }
                "1" -> { variant = 1; regenerate() }
                "2" -> { variant = 2; regenerate() }
                "3" -> { variant = 3; regenerate() }
                "d" -> showDebug = !showDebug
                "e" -> export()
            }
        }

        extend {
            drawArtwork(drawer)
        }
    }
}
