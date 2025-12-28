package drawing

import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.*
import org.openrndr.extra.shapes.hobbyCurve
import org.openrndr.math.Vector2
import org.openrndr.shape.*
import org.openrndr.extra.noise.simplex
import kotlin.math.*
import kotlin.random.Random
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// ========== Helper Functions ==========

fun hsv(h: Double, s: Double, v: Double, a: Double = 1.0): ColorRGBa {
    // Simple HSV to RGB conversion
    val c = v * s
    val x = c * (1.0 - abs(((h / 60.0) % 2.0) - 1.0))
    val m = v - c
    
    var r = 0.0
    var g = 0.0
    var b = 0.0
    
    when ((h / 60.0).toInt() % 6) {
        0 -> { r = c; g = x; b = 0.0 }
        1 -> { r = x; g = c; b = 0.0 }
        2 -> { r = 0.0; g = c; b = x }
        3 -> { r = 0.0; g = x; b = c }
        4 -> { r = x; g = 0.0; b = c }
        5 -> { r = c; g = 0.0; b = x }
    }
    return ColorRGBa(r + m, g + m, b + m, a)
}

// ========== Configuration ==========

data class GenParams(
    val seed: Long,
    val variant: Int,
    val outerTension: Double,
    val holeCount: Int,
    val depthPx: Double
)

// ========== Main Application ==========

fun main() = application {
    configure {
        width = 600
        height = 800
        title = "Sculpted Negative-Space Cutouts"
    }

    program {
        var seed = Random.nextLong()
        var variant = 1
        var showDebug = false

        // Parameters
        var params = GenParams(seed, variant, 1.0, 5, 20.0)
        
        // Geometry
        var outerContour: ShapeContour = ShapeContour.fromPoints(emptyList(), true)
        var holes: List<ShapeContour> = emptyList()
        var islands: List<ShapeContour> = emptyList()

        // Mask RenderTarget
        var maskRT: RenderTarget? = null

        fun updateMask(w: Int, h: Int) {
            maskRT?.destroy()
            maskRT = renderTarget(w, h) {
                colorBuffer()
                depthBuffer(DepthFormat.DEPTH24_STENCIL8)
            }
            
            maskRT?.let { rt ->
                drawer.isolatedWithTarget(rt) {
                    drawer.clear(ColorRGBa.TRANSPARENT)
                    drawer.ortho(rt)
                    
                    // 1. Draw Outer Shape (White)
                    drawer.fill = ColorRGBa.WHITE
                    drawer.stroke = null
                    drawer.contour(outerContour)
                    
                    // 2. Draw Holes (Transparent / ERASE)
                    // Use REPLACE blending with Transparent color to erase holes from the white shape
                    drawer.shadeStyle = null
                    drawer.fill = ColorRGBa.TRANSPARENT
                    drawer.stroke = null
                    
                    drawer.drawStyle.blendMode = BlendMode.REPLACE
                    if (holes.isNotEmpty()) {
                        drawer.contours(holes)
                    }
                    
                    // 3. Draw Islands (White / OVER)
                    // Add back the islands inside holes
                    drawer.drawStyle.blendMode = BlendMode.OVER
                    drawer.fill = ColorRGBa.WHITE
                    if (islands.isNotEmpty()) {
                        drawer.contours(islands)
                    }
                }
            }
        }

        fun regenerate() {
            val rng = Random(seed)
            
            // Determine parameters
            val tension = when(variant) {
                1 -> 0.9 + rng.nextDouble() * 0.2 
                2 -> 1.0 + rng.nextDouble() * 0.3 
                3 -> 1.0 + rng.nextDouble() * 0.4 
                else -> 1.0
            }
            
            val depth = when(variant) {
                1 -> 30.0 + rng.nextDouble() * 20.0 
                2 -> 10.0 + rng.nextDouble() * 10.0 
                3 -> 20.0 + rng.nextDouble() * 15.0 
                else -> 20.0
            }

            val count = when(variant) {
                1 -> rng.nextInt(3, 9)
                2 -> rng.nextInt(25, 81)
                3 -> rng.nextInt(8, 26)
                else -> 10
            }
            
            params = GenParams(seed, variant, tension, count, depth)

            val margin = width * 0.12
            val safeBounds = Rectangle(margin, margin, width - 2 * margin, height - 2 * margin)
            
            outerContour = generateOuterContour(rng, safeBounds, params)
            
            val holeResult = generateHoles(rng, outerContour, variant, count, depth)
            holes = holeResult.first
            islands = holeResult.second

            updateMask(width, height)
        }

        // Initial generation
        regenerate()

        fun exportFrame() {
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            val filename = "sculpted_v${variant}_s${seed}_${timestamp}.png"
            val rt = renderTarget(width, height) {
                colorBuffer()
                depthBuffer(DepthFormat.DEPTH24_STENCIL8)
            }
            
            drawer.isolatedWithTarget(rt) {
                drawer.clear(ColorRGBa.WHITE)
                drawer.ortho(rt)
                renderArtwork(drawer, width.toDouble(), height.toDouble(), params, outerContour, holes, islands, maskRT, seed)
            }
            
            rt.colorBuffer(0).saveToFile(File(filename))
            rt.destroy()
            println("Exported: $filename")
        }

        keyboard.keyDown.listen {
            when (it.name) {
                "r" -> {
                    seed = Random.nextLong()
                    regenerate()
                }
                "1" -> { variant = 1; regenerate() }
                "2" -> { variant = 2; regenerate() }
                "3" -> { variant = 3; regenerate() }
                "d" -> showDebug = !showDebug
                "e" -> exportFrame()
            }
        }

        extend {
            renderArtwork(drawer, width.toDouble(), height.toDouble(), params, outerContour, holes, islands, maskRT, seed)
            
            if (showDebug) {
                drawer.fill = null
                drawer.stroke = ColorRGBa.MAGENTA
                drawer.contours(holes)
                drawer.stroke = ColorRGBa.YELLOW
                drawer.contours(islands)
                drawer.stroke = ColorRGBa.CYAN
                drawer.contour(outerContour)
            }
        }
    }
}

// ========== Rendering ==========

fun renderArtwork(
    drawer: Drawer, 
    w: Double, 
    h: Double, 
    params: GenParams,
    outer: ShapeContour,
    holes: List<ShapeContour>,
    islands: List<ShapeContour>,
    maskRT: RenderTarget?,
    seed: Long
) {
    val rng = Random(seed)

    // Palette
    val hueBase = rng.nextDouble() * 360.0
    val bgBase = hsv(hueBase, 0.1, 0.95)
    val sheetColor = hsv((hueBase + 10) % 360.0, 0.15, 0.90)
    val shadowColor = hsv(hueBase, 0.4, 0.2, 0.4)
    val highlightColor = ColorRGBa.WHITE.opacify(0.6)

    // 1. Background Paper
    drawer.shadeStyle = null
    drawer.fill = bgBase
    drawer.stroke = null
    drawer.rectangle(0.0, 0.0, w, h)
    
    // Grain
    drawer.isolated {
        drawer.stroke = ColorRGBa.BLACK.opacify(0.05)
        drawer.fill = null
        val grainCount = 5000
        val grainRng = Random(seed * 2)
        val points = List(grainCount) {
             Vector2(grainRng.nextDouble() * w, grainRng.nextDouble() * h)
        }
        drawer.points(points)
    }

    // Vignette
    drawer.isolated {
        drawer.shadeStyle = shadeStyle {
            fragmentTransform = """
                vec2 uv = c_boundsPosition.xy * 2.0 - 1.0;
                float d = length(uv);
                x_fill.rgb *= (1.0 - smoothstep(0.5, 1.2, d) * 0.4);
            """
        }
        drawer.fill = ColorRGBa.WHITE 
        drawer.stroke = null
        drawer.rectangle(0.0, 0.0, w, h)
    }
    
    if (maskRT == null) return

    // 2. Drop Shadow (using Mask)
    // Draw the mask color buffer multiple times with offsets
    val shadowDir = Vector2(1.0, 1.0).normalized
    val maskCB = maskRT.colorBuffer(0)
    
    drawer.isolated {
        drawer.shadeStyle = null
        val steps = 10
        for (i in 0 until steps) {
            val spread = params.depthPx * 0.2
            val jitter = Vector2(
                (rng.nextDouble() - 0.5) * spread,
                (rng.nextDouble() - 0.5) * spread
            )
            val offset = shadowDir * (params.depthPx * (0.5 + 0.5 * (i.toDouble() / steps)))
            
            // Draw Mask colored as Shadow
            // We use shadeStyle to tint the white mask to shadow color
            drawer.translate(offset + jitter)
            
            // Simple tinting: use image with Color filter? 
            // Or shadeStyle.
            drawer.shadeStyle = shadeStyle {
                fragmentTransform = """
                    vec4 m = texture(p_mask, va_texCoord0);
                    x_fill = p_color;
                    x_fill.a *= m.a; // Use mask alpha/red
                """
                parameter("mask", maskCB)
                parameter("color", shadowColor.opacify(0.15 / steps))
            }
            drawer.fill = shadowColor // Used by shader?
            drawer.stroke = null
            drawer.rectangle(0.0, 0.0, w, h) // Draw rect covering screen, masked by shader
        }
    }

    // 3. Sheet Fill (using Mask)
    drawer.isolated {
        drawer.shadeStyle = shadeStyle {
            fragmentTransform = """
                vec4 m = texture(p_mask, va_texCoord0);
                x_fill.a *= m.a; // Mask opacity
            """
            parameter("mask", maskCB)
        }
        drawer.fill = sheetColor
        drawer.stroke = null
        drawer.rectangle(0.0, 0.0, w, h)
    }

    // 4. Rim Highlight (Outer)
    val lightDir = Vector2(-1.0, -1.0).normalized
    drawer.isolated {
        drawer.shadeStyle = null
        drawer.fill = null
        drawer.strokeWeight = 1.5
        drawer.stroke = highlightColor
        drawer.contour(outer)
    }

    // 5. Inset Shadows (Holes)
    drawer.isolated {
        drawer.shadeStyle = null
        drawer.fill = null
        drawer.strokeWeight = params.depthPx * 0.15
        
        holes.forEach { hole ->
            // Shadow
            drawer.stroke = shadowColor.opacify(0.5)
            drawer.contour(hole.transform(org.openrndr.math.transforms.transform {
                 translate(lightDir * (params.depthPx * 0.1))
            }))
            
            // Highlight
            drawer.stroke = ColorRGBa.WHITE.opacify(0.3)
            drawer.contour(hole.transform(org.openrndr.math.transforms.transform {
                 translate(lightDir * -(params.depthPx * 0.1))
            }))
        }
    }
    
    // Islands Inset Shadows?
    // Islands are positive shapes, so they have "Rim Highlight" like the outer shape.
    if (islands.isNotEmpty()) {
         drawer.isolated {
            drawer.shadeStyle = null
            drawer.fill = null
            drawer.strokeWeight = 1.5
            drawer.stroke = highlightColor
            drawer.contours(islands)
        }
    }

    // Legend
    drawLegend(drawer, params, w, h)
}

fun drawLegend(drawer: Drawer, params: GenParams, w: Double, h: Double) {
    drawer.isolated {
        drawer.shadeStyle = null
        drawer.fill = ColorRGBa.BLACK.opacify(0.7)
        try {
            drawer.fontMap = loadFont("data/fonts/default.otf", 12.0)
        } catch (e: Exception) {
            // fallback
        }
        val text = "SCULPTED CUTOUTS | V${params.variant} | S${params.seed} | Holes: ${params.holeCount} | Tension: ${String.format("%.2f", params.outerTension)} | Depth: ${String.format("%.1f", params.depthPx)}"
        drawer.text(text, 20.0, h - 20.0)
    }
}

// ========== Generation Logic ==========

fun generateOuterContour(rng: Random, bounds: Rectangle, params: GenParams): ShapeContour {
    val center = bounds.center
    val radiusBase = min(bounds.width, bounds.height) * 0.4
    val numPoints = rng.nextInt(10, 20)
    
    val points = (0 until numPoints).map { i ->
        val angle = (i.toDouble() / numPoints) * 360.0
        val angleRad = Math.toRadians(angle)
        
        val noise = simplex(params.seed.toInt(), angle * 0.01) * 0.2
        val bump = if (rng.nextDouble() < 0.2) (rng.nextDouble() - 0.5) * 0.4 else 0.0
        
        val r = radiusBase * (1.0 + noise + bump)
        
        center + Vector2(cos(angleRad), sin(angleRad)) * r
    }
    
    return hobbyCurve(points, true, params.outerTension)
}

fun generateHoles(
    rng: Random, 
    outer: ShapeContour, 
    variant: Int, 
    count: Int, 
    depth: Double
): Pair<List<ShapeContour>, List<ShapeContour>> {
    val holes = mutableListOf<ShapeContour>()
    val islands = mutableListOf<ShapeContour>()
    
    val outerBounds = outer.bounds
    val minDimension = min(outerBounds.width, outerBounds.height)
    
    var attempts = 0
    val maxAttempts = 2000 
    
    while (holes.size < count && attempts < maxAttempts) {
        attempts++
        
        val cx = outerBounds.x + rng.nextDouble() * outerBounds.width
        val cy = outerBounds.y + rng.nextDouble() * outerBounds.height
        val center = Vector2(cx, cy)
        
        if (!outer.contains(center)) continue
        
        val sizeScale = when(variant) {
            1 -> rng.nextDouble(0.1, 0.25) * minDimension 
            2 -> rng.nextDouble(0.02, 0.08) * minDimension 
            3 -> if (rng.nextDouble() < 0.3) rng.nextDouble(0.1, 0.2) * minDimension else rng.nextDouble(0.03, 0.08) * minDimension
            else -> 0.1 * minDimension
        }
        val holeRadius = sizeScale
        
        val nearestOuter = outer.nearest(center)
        val distToOuter = center.distanceTo(nearestOuter.position)
        
        if (distToOuter < holeRadius + depth * 1.5 + 10.0) continue
        
        var overlaps = false
        for (h in holes) {
            val hCenter = h.bounds.center
            val dist = center.distanceTo(hCenter)
            val hRadiusApprox = h.bounds.width / 2.0
            
            if (dist < holeRadius + hRadiusApprox + 10.0) {
                overlaps = true
                break
            }
        }
        if (overlaps) continue
        
        val holePointsCount = rng.nextInt(6, 12)
        val holePoints = (0 until holePointsCount).map { i ->
            val angle = (i.toDouble() / holePointsCount) * 360.0
            val angleRad = Math.toRadians(angle)
            val r = holeRadius * (0.8 + rng.nextDouble() * 0.4)
            center + Vector2(cos(angleRad), sin(angleRad)) * r
        }
        val holeContour = hobbyCurve(holePoints, true, 0.9 + rng.nextDouble() * 0.3)
        
        holes.add(holeContour)
        
        if (variant == 3 && holeRadius > minDimension * 0.12 && rng.nextDouble() < 0.6) {
            val islandRadius = holeRadius * 0.4
            val islandPointsCount = rng.nextInt(5, 9)
            val islandPoints = (0 until islandPointsCount).map { i ->
                val angle = (i.toDouble() / islandPointsCount) * 360.0
                val angleRad = Math.toRadians(angle)
                val r = islandRadius * (0.8 + rng.nextDouble() * 0.4)
                center + Vector2(cos(angleRad), sin(angleRad)) * r 
            }
            val islandContour = hobbyCurve(islandPoints, true, 1.0)
            islands.add(islandContour)
        }
    }
    
    return Pair(holes, islands)
}

val GenParams.seedInt: Int get() = seed.toInt()
