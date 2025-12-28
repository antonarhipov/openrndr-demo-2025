package drawing

import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.*
import org.openrndr.extra.shapes.hobbyCurve
import org.openrndr.math.Polar
import org.openrndr.math.Vector2
import org.openrndr.shape.Rectangle
import org.openrndr.shape.ShapeContour
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.*
import kotlin.random.Random

// ==========================================
// Data Structures
// ==========================================

data class MoireSample(val pos: Vector2, val tangent: Vector2, val normal: Vector2, val curvature: Double)

data class MoireParams(
    val nLines: Int,
    val spacing: Double,
    val tensionA: Double,
    val tensionB: Double,
    val rotationDelta: Double, // degrees
    val translationDelta: Vector2,
    val scaleDelta: Double,
    val spacingDelta: Double, // additional spacing for B
    val seed: Long,
    val variant: Int
)

// ==========================================
// Main Program
// ==========================================

fun main() = application {
    configure {
        width = 600
        height = 800
        title = "Hobby Curve Moiré Interference"
    }

    program {
        // State
        var seed = Random.nextLong()
        var variant = 1
        var debugOverlay = false

        // Params (calculated on reseed)
        var params = generateMoireParams(seed, variant, width.toDouble(), height.toDouble())

        // Generated Data
        var basePoints = emptyList<Vector2>()
        var familyA = emptyList<ShapeContour>()
        var familyB = emptyList<ShapeContour>()
        var familyC = emptyList<ShapeContour>() // For Variant 3

        val renderTarget = renderTarget(width, height) {
            colorBuffer()
            depthBuffer()
        }
        
        // Font loading
        val font = loadFont("data/fonts/default.otf", 12.0)

        fun regenerate() {
            params = generateMoireParams(seed, variant, width.toDouble(), height.toDouble())
            
            // 1. Generate Base Points
            val safeArea = Rectangle(0.0, 0.0, width.toDouble(), height.toDouble()).offsetEdges(-(min(width, height) * 0.08))
            basePoints = generateBasePoints(params.seed, safeArea)

            // 2. Build Base Curve & Samples
            // Note: Variant 2 uses different tensions for A and B, so we might need two base curves
            // But usually they share control points.
            
            // Family A
            val contourA = hobbyContour(basePoints, params.tensionA)
            // Sampling A
            // We need a uniform step for the samples to place lines.
            // The step along the curve determines the resolution of the offset polylines, not the spacing between lines.
            // Let's pick a small step for smooth curves.
            val samplesA = sampleByArcLength(contourA, 2.0) 
            
            // Family B
            // Transform logic depends on variant
            var samplesB: List<MoireSample>
            
            if (variant == 2) {
                 // Tension Drift: Same points, different tension
                 val contourB = hobbyContour(basePoints, params.tensionB)
                 samplesB = sampleByArcLength(contourB, 2.0)
            } else {
                 // For rotation/translation, we transform the points or the samples?
                 // Prompt says "Derive Family B: another near-identical set, but slightly transformed"
                 // Variant 1: Rotated version of Family A.
                 // We can rotate the samples of A.
                 samplesB = transformSamples(samplesA, params, safeArea.center)
            }

            // 3. Build Families
            // N depends on spacing and coverage. Prompt says N: 60-400.
            // We calculate N to fill "most of safe area" or based on params.nLines
            
            val clipRect = safeArea // or full canvas? Prompt: "Keep offsets inside safe area; if ... clip"
            
            familyA = buildFamily(samplesA, params.nLines, params.spacing, clipRect)
            familyB = buildFamily(samplesB, params.nLines, params.spacing + params.spacingDelta, clipRect)
            
            if (variant == 3) {
                 // Variant 3: Multi-Layer Weave
                 // Maybe a third family or split.
                 // Let's add a Family C which is Family A but mirror/phase shifted or just a different set.
                 // "one group with larger spacing... one with tight spacing"
                 // Let's make Family C a tighter version of A
                 val paramsC = params.copy(spacing = params.spacing * 0.5, nLines = params.nLines * 2)
                 // Shift phase by half spacing
                 // To do phase shift, we offset the start d_i.
                 // implemented in buildFamily via optional phase shift? 
                 // For now, let's just generate a slightly offset C.
                 familyC = buildFamily(samplesA, params.nLines, params.spacing * 1.5, clipRect, phase = 0.5)
            } else {
                familyC = emptyList()
            }
        }

        // Initial generation
        regenerate()

        // Input
        keyboard.keyDown.listen {
            when (it.name) {
                "r" -> {
                    seed = Random.nextLong()
                    regenerate()
                }
                "1" -> { variant = 1; regenerate() }
                "2" -> { variant = 2; regenerate() }
                "3" -> { variant = 3; regenerate() }
                "d" -> { debugOverlay = !debugOverlay }
                "e" -> {
                    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                    val filename = "moire_v${variant}_s${seed}_${timestamp}.png"
                    renderTarget.colorBuffer(0).saveToFile(File("images/$filename"))
                    println("Saved to images/$filename")
                }
            }
        }

        extend {
            drawer.isolatedWithTarget(renderTarget) {
                clear(ColorRGBa.WHITE) // Will be overwritten by background
                
                // Render Background
                renderBackground(drawer, seed)
                
                // Render Families
                // Strategy: A then B. Thin strokes. Alpha blending.
                
                val rng = Random(seed)
                val baseColor = if (rng.nextDouble() < 0.2) ColorRGBa.BLACK else ColorRGBa.fromHex("0F0F15") // Deep ink
                val accentColor = if (rng.nextDouble() < 0.3) {
                     listOf(ColorRGBa.RED, ColorRGBa.BLUE, ColorRGBa.fromHex("FF4500")).random(rng)
                } else null

                // Style setup
                // Variant 3 might use accent on C
                
                renderFamily(drawer, familyA, baseColor.opacify(0.8), 1.0)
                
                val colorB = if (variant == 2) baseColor.opacify(0.5) else baseColor.opacify(0.7)
                renderFamily(drawer, familyB, colorB, 1.0)
                
                if (variant == 3 && familyC.isNotEmpty()) {
                    val colorC = accentColor?.opacify(0.6) ?: baseColor.opacify(0.4)
                    renderFamily(drawer, familyC, colorC, 0.5)
                }

                // Masking for Variant 3 (Quiet zone)
                if (variant == 3) {
                     val quietZone = rng.nextBoolean()
                     if (quietZone) {
                         drawer.isolated {
                             drawer.fill = ColorRGBa.WHITE.opacify(0.9)
                             drawer.stroke = null
                             val c = Vector2(width/2.0, height/2.0)
                             val r = min(width, height) * 0.25
                             drawer.circle(c, r)
                         }
                     }
                }
                
                // Safe area border (optional, maybe not for final art but good for structure)
                // drawer.fill = null
                // drawer.stroke = ColorRGBa.GRAY.opacify(0.2)
                // val safeArea = Rectangle(0.0, 0.0, width.toDouble(), height.toDouble()).offsetEdges(-(min(width, height) * 0.08))
                // drawer.rectangle(safeArea)

                // Render Legend
                renderLegend(drawer, font, params, seed, variant)

                // Debug Overlay
                if (debugOverlay) {
                    renderDebug(drawer, basePoints, familyA, params)
                }
            }
            
            // Draw result to screen
            drawer.image(renderTarget.colorBuffer(0))
        }
    }
}

// ==========================================
// Generation Logic
// ==========================================

fun generateMoireParams(seed: Long, variant: Int, w: Double, h: Double): MoireParams {
    val rng = Random(seed)
    
    // N: 60-400
    // Spacing: 2-12
    // If spacing is small, N is large.
    val spacing = rng.nextDouble(2.0, 8.0)
    val coverage = min(w, h) * 0.8
    val nLines = (coverage / spacing).toInt().coerceIn(40, 300)
    
    val tensionA = rng.nextDouble(0.9, 1.4)
    // Tension B differs for Variant 2
    val tensionB = if (variant == 2) {
         tensionA + rng.nextDouble(0.1, 0.4) * (if (rng.nextBoolean()) 1 else -1)
    } else tensionA
    
    val rotDelta = if (variant == 1) rng.nextDouble(0.3, 1.5) else rng.nextDouble(0.1, 0.5)
    val transDelta = Vector2(rng.nextDouble(-5.0, 5.0), rng.nextDouble(-5.0, 5.0))
    val scaleDelta = if (variant == 2) rng.nextDouble(0.005, 0.015) else rng.nextDouble(0.001, 0.01)
    val spDelta = rng.nextDouble(0.1, 0.8) * (if(rng.nextBoolean()) 1 else -1)

    return MoireParams(
        nLines = nLines,
        spacing = spacing,
        tensionA = tensionA,
        tensionB = tensionB,
        rotationDelta = rotDelta,
        translationDelta = transDelta,
        scaleDelta = scaleDelta,
        spacingDelta = spDelta,
        seed = seed,
        variant = variant
    )
}

fun generateBasePoints(seed: Long, bounds: Rectangle): List<Vector2> {
    val rng = Random(seed)
    val points = mutableListOf<Vector2>()
    
    // Method: Anchor + micro-jitter
    // 5-9 anchors
    val count = rng.nextInt(5, 9)
    
    // Start somewhere on the edge or close to it
    // Ensure broad movement.
    // Let's pick random points within the safe area but biased to cover space.
    // Or a random walk.
    
    var current = bounds.position(rng.nextDouble(0.1, 0.9), rng.nextDouble(0.1, 0.9))
    points.add(current)
    
    for (i in 1 until count) {
        // Move significantly
        val angle = rng.nextDouble(0.0, 360.0)
        val dist = rng.nextDouble(100.0, 300.0)
        var next = current + Vector2.fromPolar(Polar(angle)) * dist
        
        // Constrain to bounds
        if (!bounds.contains(next)) {
            // bounce or clamp
            next = Vector2(
                next.x.coerceIn(bounds.x, bounds.x + bounds.width),
                next.y.coerceIn(bounds.y, bounds.y + bounds.height)
            )
        }
        points.add(next)
        current = next
    }
    
    return points
}

fun hobbyContour(points: List<Vector2>, tension: Double): ShapeContour {
    // openrndr.extra.shapes.hobbyCurve(points, closed, tension)
    return hobbyCurve(points, false, tension)
}

fun sampleByArcLength(contour: ShapeContour, step: Double): List<MoireSample> {
    val length = contour.length
    val samples = mutableListOf<MoireSample>()
    var d = 0.0
    // Use equidistantPositions for positions, but we need tangents.
    // So let's iterate.
    val positions = contour.equidistantPositions((length / step).toInt())
    
    if (positions.size < 2) return emptyList()

    for (i in positions.indices) {
        val pos = positions[i]
        
        // Compute tangent
        val tangent = when (i) {
            0 -> (positions[1] - positions[0]).normalized
            positions.lastIndex -> (positions[i] - positions[i-1]).normalized
            else -> (positions[i+1] - positions[i-1]).normalized
        }
        
        val normal = tangent.perpendicular() // Rotates 90 deg counter-clockwise usually (-y, x)
        
        // Curvature approximation (optional, for alpha modulation)
        // k = |T'| / |r'| approx angle change per unit length
        val curvature = 0.0 // Placeholder or compute if needed
        
        samples.add(MoireSample(pos, tangent, normal, curvature))
    }
    return samples
}

fun transformSamples(samples: List<MoireSample>, params: MoireParams, center: Vector2): List<MoireSample> {
    // Apply rotation, translation, scale to the POSITIONS of the samples.
    // Recompute tangents/normals? 
    // Yes, or just rotate vectors.
    
    return samples.map { s ->
        var p = s.pos
        // Rotate around center
        if (abs(params.rotationDelta) > 0.001) {
            p = (p - center).rotate(params.rotationDelta) + center
        }
        // Scale around center
        if (abs(params.scaleDelta) > 0.001) {
            val scale = 1.0 + params.scaleDelta
            p = (p - center) * scale + center
        }
        // Translate
        p += params.translationDelta
        
        // Vectors
        var t = s.tangent
        var n = s.normal
        if (abs(params.rotationDelta) > 0.001) {
            t = t.rotate(params.rotationDelta)
            n = n.rotate(params.rotationDelta)
        }
        
        s.copy(pos = p, tangent = t, normal = n)
    }
}

fun buildFamily(
    samples: List<MoireSample>, 
    nLines: Int, 
    spacing: Double, 
    clipBounds: Rectangle,
    phase: Double = 0.0
): List<ShapeContour> {
    val contours = mutableListOf<ShapeContour>()
    
    val halfN = (nLines - 1) / 2.0
    
    for (i in 0 until nLines) {
        val offsetDist = (i - halfN + phase) * spacing
        
        // Create offset polyline
        val offsetPoints = samples.map { s ->
            s.pos + s.normal * offsetDist
        }
        
        // Clip or check bounds.
        // Simple check: if mostly out of bounds, skip. 
        // Or construct polyline and clip it.
        // Clipping complex curves can be slow/hard.
        // Simple approach: construct polyline. If any point is out, we might keep it or trim.
        // Prompt says: "clip it (panel clip) rather than shrinking".
        
        // Let's create the polyline first.
        // We can just draw it as a polyline (ShapeContour.fromPoints(..., false))
        
        // To clip properly: convert to ShapeContour then intersection with bounds.
        // OpenRNDR has intersection logic but it can be heavy.
        // Faster: Filter points? No, that breaks the line.
        // Let's filter segments.
        
        // Optimization: check if ALL points are out.
        val boundsExpanded = clipBounds.offsetEdges(10.0)
        if (offsetPoints.all { !boundsExpanded.contains(it) }) continue
        
        // Construct contour
        val poly = ShapeContour.fromPoints(offsetPoints, false)
        
        // Clip with bounds? 
        // Using `intersection` with a rectangle contour.
        // This might be too slow for 400 lines * 2 families.
        // Alternative: Draw loop handles clipping via Scissor or simply drawing?
        // But we want to "create" the geometry.
        // Let's stick to drawing-time clipping (scissor) if possible, 
        // OR simply accept lines going out of bounds (but prompt says "clip it").
        // "Panel clip" usually means the visual result is clipped.
        // We can use `drawer.shadeStyle` or `drawer.drawStyle.clip`.
        // Or actually construct the clipped shape.
        
        // For efficiency, I will leave the shape unclipped here and rely on render-time Scissor 
        // or just letting it flow out (if the background covers it or margin handles it).
        // Actually, "Keep offsets inside safe area".
        // If I create a polyline, I can just clamp points? No, that distorts.
        // I will perform a simple segment-rect intersection if needed, 
        // but for now let's just create the full lines and use drawer.scissor in render.
        
        contours.add(poly)
    }
    
    return contours
}

// ==========================================
// Rendering Logic
// ==========================================

fun renderBackground(drawer: Drawer, seed: Long) {
    val rng = Random(seed)
    // Clean paper tone or deep near-black
    val isDark = rng.nextDouble() < 0.2
    
    if (isDark) {
        drawer.clear(ColorRGBa.fromHex("0F0F15"))
        // subtle grain could be added via filter or noise rect
    } else {
        drawer.clear(ColorRGBa.fromHex("FDFBF7")) // Warm paper
    }
    
    // Procedural vignette/grain
    drawer.isolated {
        drawer.shadeStyle = shadeStyle {
            fragmentTransform = """
                vec2 uv = c_boundsPosition.xy;
                float noise = fract(sin(dot(uv * 123.45, vec2(12.9898, 78.233))) * 43758.5453);
                float vign = 1.0 - length(uv - 0.5) * 0.5;
                vec3 color = x_fill.rgb;
                color += (noise - 0.5) * 0.03;
                color *= vign;
                x_fill.rgb = color;
            """.trimIndent()
        }
        drawer.fill = if (isDark) ColorRGBa.fromHex("0F0F15") else ColorRGBa.fromHex("FDFBF7")
        drawer.rectangle(0.0, 0.0, drawer.width.toDouble(), drawer.height.toDouble())
    }
}

fun renderFamily(drawer: Drawer, family: List<ShapeContour>, color: ColorRGBa, widthBase: Double) {
    drawer.strokeWeight = widthBase
    
    // Apply clipping to safe area?
    // drawer.scissor(safeArea) // We can calculate safe area again or pass it.
    // Let's use the bounds from 8% margin.
    val safeArea = Rectangle(0.0, 0.0, drawer.width.toDouble(), drawer.height.toDouble()).offsetEdges(-(min(drawer.width, drawer.height) * 0.08))
    
    drawer.drawStyle.clip = safeArea
    
    family.forEachIndexed { i, contour ->
        // Modulate alpha/width
        // "Index lines": every 10th or 20th slightly thicker
        val isIndex = i % 20 == 0
        drawer.stroke = color.opacify(if (isIndex) 1.0 else 0.8)
        drawer.strokeWeight = if (isIndex) widthBase * 1.5 else widthBase
        
        // Micro-jitter: "tiny jitter in line position per line"
        // Done at drawing time?
        // We can just draw the contour.
        
        drawer.contour(contour)
    }
    
    drawer.drawStyle.clip = null
}

fun renderLegend(drawer: Drawer, font: FontImageMap, params: MoireParams, seed: Long, variant: Int) {
    val text = """
        HOBBY CURVE MOIRÉ INTERFERENCE
        Variant: $variant
        Seed: $seed
        Params: N=${params.nLines} Sp=${String.format("%.1f", params.spacing)} TA=${String.format("%.2f", params.tensionA)} TB=${String.format("%.2f", params.tensionB)} Rot=${String.format("%.1f", params.rotationDelta)}°
    """.trimIndent()
    
    drawer.fill = ColorRGBa.BLACK.opacify(0.7)
    drawer.fontMap = font
    
    var y = drawer.height - 80.0
    text.lines().forEach { line ->
        drawer.text(line, 20.0, y)
        y += 15.0
    }
}

fun renderDebug(drawer: Drawer, basePoints: List<Vector2>, familyA: List<ShapeContour>, params: MoireParams) {
    drawer.isolated {
        drawer.stroke = ColorRGBa.MAGENTA
        drawer.fill = null
        // Base Curve points
        basePoints.forEach { 
            drawer.circle(it, 4.0)
        }
        // Connect them
        drawer.strokeWeight = 1.0
        drawer.lineStrip(basePoints)
        
        // Normals?
        // Visualizing normals on the first line of Family A
        if (familyA.isNotEmpty()) {
            val c = familyA[params.nLines / 2]
            val samples = c.equidistantPositions(20)
            drawer.stroke = ColorRGBa.CYAN
            samples.forEach { p ->
                // This is just position, to show normal we need the data.
                // Recomputing for debug is fine.
            }
        }
    }
}
