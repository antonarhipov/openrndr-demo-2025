package drawing

import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.DepthFormat
import org.openrndr.draw.Drawer
import org.openrndr.draw.LineCap
import org.openrndr.draw.isolated
import org.openrndr.draw.renderTarget
import org.openrndr.extra.color.presets.*
import org.openrndr.extra.noise.simplex
import org.openrndr.extra.shapes.hobbyCurve
import org.openrndr.math.Vector2
import org.openrndr.math.map
import org.openrndr.shape.Rectangle
import org.openrndr.shape.Shape
import org.openrndr.shape.ShapeContour
import org.openrndr.shape.contour
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.*
import kotlin.random.Random

/**
 * Layer Stacks / Parallax Ribbons
 * Generative art program that produces a print-ready poster where depth is implied
 * using many layers derived from Hobby curves.
 */

data class Sample(val t: Double, val p: Vector2, val tangent: Vector2, val normal: Vector2)

data class LayerStyle(
    val stroke: ColorRGBa?,
    val fill: ColorRGBa?,
    val strokeWeight: Double,
    val alpha: Double
)

fun main() = application {
    configure {
        width = 1000
        height = 1000
    }

    program {
        var seed = Random.nextInt()
        var variant = 1
        var showDebug = false
        var exportRequested = false

        val margin = minOf(width.toDouble(), height.toDouble()) * 0.08
        val safeArea = drawer.bounds.offsetEdges(-margin)

        // Palette definitions
        val palettes = listOf(
            listOf(ColorRGBa.fromHex("#264653"), ColorRGBa.fromHex("#2a9d8f"), ColorRGBa.fromHex("#e9c46a"), ColorRGBa.fromHex("#f4a261"), ColorRGBa.fromHex("#e76f51")),
            listOf(ColorRGBa.fromHex("#1d3557"), ColorRGBa.fromHex("#457b9d"), ColorRGBa.fromHex("#a8dadc"), ColorRGBa.fromHex("#f1faee"), ColorRGBa.fromHex("#e63946")),
            listOf(ColorRGBa.fromHex("#2b2d42"), ColorRGBa.fromHex("#8d99ae"), ColorRGBa.fromHex("#edf2f4"), ColorRGBa.fromHex("#ef233c"), ColorRGBa.fromHex("#d90429")),
            listOf(ColorRGBa.fromHex("#003049"), ColorRGBa.fromHex("#d62828"), ColorRGBa.fromHex("#f77f00"), ColorRGBa.fromHex("#fcbf49"), ColorRGBa.fromHex("#eae2b7"))
        )

        fun getPalette(s: Int) = palettes[abs(s) % palettes.size]

        fun generateBasePoints(s: Int, bounds: Rectangle, method: Int): List<Vector2> {
            val rng = Random(s)
            val jRng = java.util.Random(s.toLong())
            val points = mutableListOf<Vector2>()
            when (method) {
                0 -> { // Anchor + micro-jitter
                    val count = rng.nextInt(8, 15)
                    for (i in 0 until count) {
                        points.add(Vector2(
                            rng.nextDouble(bounds.x, bounds.x + bounds.width),
                            rng.nextDouble(bounds.y, bounds.y + bounds.height)
                        ))
                    }
                }
                1 -> { // Inertial random walk
                    var p = Vector2(bounds.center.x, bounds.center.y)
                    var angle = rng.nextDouble(PI * 2)
                    val count = rng.nextInt(20, 30)
                    val step = 40.0
                    for (i in 0 until count) {
                        points.add(p)
                        angle += jRng.nextGaussian() * 0.5
                        val nextP = p + Vector2(cos(angle), sin(angle)) * step
                        if (!bounds.contains(nextP)) {
                            angle += PI * 0.5
                        }
                        p += Vector2(cos(angle), sin(angle)) * step
                    }
                }
                else -> { // Spiral
                    val count = 25
                    for (i in 0 until count) {
                        val angle = i * 0.4 + rng.nextDouble() * 0.1
                        val r = i * 15.0
                        points.add(bounds.center + Vector2(cos(angle), sin(angle)) * r)
                    }
                }
            }
            return points
        }

        fun sampleContour(contour: ShapeContour, step: Double): List<Sample> {
            val length = contour.length
            val count = (length / step).toInt().coerceAtLeast(10)
            return (0..count).map { i ->
                val t = i.toDouble() / count
                val p = contour.position(t)
                val t1 = (t + 0.001).coerceAtMost(1.0)
                val t0 = (t - 0.001).coerceAtLeast(0.0)
                val tangent = (contour.position(t1) - contour.position(t0)).normalized
                val normal = Vector2(-tangent.y, tangent.x)
                Sample(t, p, tangent, normal)
            }
        }

        fun styleForDepth(z: Double, palette: List<ColorRGBa>): LayerStyle {
            val gamma = 1.2
            val alpha = map(0.0, 1.0, 0.2, 0.9, z.pow(gamma))
            val colorIndex = (z * (palette.size - 1)).toInt().coerceIn(0, palette.size - 1)
            val color = palette[colorIndex]
            
            return LayerStyle(
                stroke = color.opacify(alpha),
                fill = color.opacify(alpha),
                strokeWeight = map(0.0, 1.0, 0.5, 4.0, z),
                alpha = alpha
            )
        }

        fun drawBackground(drawer: Drawer, s: Int) {
            val rng = Random(s)
            drawer.clear(ColorRGBa.fromHex("#fdfcf0")) // Soft paper
            
            drawer.stroke = null
            drawer.fill = ColorRGBa.BLACK.opacify(0.02)
            for (i in 0..20000) {
                drawer.circle(rng.nextDouble() * width, rng.nextDouble() * height, 0.5)
            }
        }

        val rt = renderTarget(width, height) {
            colorBuffer()
            depthBuffer(DepthFormat.DEPTH24_STENCIL8)
        }

        extend {
            drawer.isolated {
                drawer.withTarget(rt) {
                    val palette = getPalette(seed)
                    drawBackground(drawer, seed)

                    when (variant) {
                        1 -> { // Contour Ribbons
                            val points = generateBasePoints(seed, safeArea, 0)
                            val baseCurve = hobbyCurve(points, false)
                            val samples = sampleContour(baseCurve, 2.0)
                            val n = 150
                            val spacing = 4.0
                            val island = Rectangle.fromCenter(safeArea.center, 300.0, 300.0)

                            for (i in 0 until n) {
                                val offsetDist = (i - n / 2.0) * spacing
                                val z = 1.0 - abs(i - n / 2.0) / (n / 2.0)
                                val style = styleForDepth(z, palette)
                                val offsetPoints = samples.map { it.p + it.normal * offsetDist }
                                
                                drawer.stroke = style.stroke
                                drawer.strokeWeight = if (i % 10 == 0) style.strokeWeight * 2.0 else style.strokeWeight
                                drawer.lineCap = LineCap.ROUND
                                
                                val segments = mutableListOf<Vector2>()
                                for (j in 0 until offsetPoints.size - 1) {
                                    val p1 = offsetPoints[j]
                                    val p2 = offsetPoints[j+1]
                                    if (!island.contains(p1) && !island.contains(p2)) {
                                        if (Random(seed + i * 1000 + j).nextDouble() > 0.01) {
                                            segments.add(p1)
                                            segments.add(p2)
                                        }
                                    }
                                }
                                drawer.lineSegments(segments)
                            }
                        }
                        2 -> { // Parallax Mountain Planes
                            val numLayers = 20
                            for (i in 0 until numLayers) {
                                val z = i.toDouble() / (numLayers - 1)
                                val layerSeed = seed + i
                                val points = generateBasePoints(layerSeed, safeArea, 1)
                                val curve = hobbyCurve(points, false)
                                val style = styleForDepth(z, palette)
                                
                                val parallax = (z - 0.5) * width * 0.05
                                val verticalOffset = map(0.0, 1.0, -height * 0.2, height * 0.2, z)
                                
                                drawer.isolated {
                                    drawer.translate(parallax, verticalOffset)
                                    drawer.fill = style.fill
                                    drawer.stroke = style.stroke?.shade(0.7)
                                    drawer.strokeWeight = 0.5
                                    
                                    val samples = sampleContour(curve, 5.0)
                                    val ridgePoints = samples.map { it.p }
                                    val shapePoints = mutableListOf<Vector2>()
                                    shapePoints.addAll(ridgePoints)
                                    shapePoints.add(Vector2(ridgePoints.last().x, height * 1.5))
                                    shapePoints.add(Vector2(ridgePoints.first().x, height * 1.5))
                                    
                                    if (shapePoints.size >= 3) {
                                        drawer.contour(ShapeContour.fromPoints(shapePoints, true))
                                    }
                                }
                            }
                        }
                        3 -> { // Echo Trail / Motion Stack
                            val passes = 300
                            val points = generateBasePoints(seed, safeArea, 2)
                            val jRng = java.util.Random(seed.toLong())
                            
                            for (i in 0 until passes) {
                                val z = i.toDouble() / (passes - 1)
                                val jitteredPoints = points.map { 
                                    it + Vector2(jRng.nextGaussian() * 8.0, jRng.nextGaussian() * 8.0)
                                }
                                val curve = hobbyCurve(jitteredPoints, false)
                                val style = styleForDepth(z, palette)
                                
                                drawer.stroke = style.stroke?.opacify(0.05)
                                drawer.strokeWeight = style.strokeWeight * 0.5
                                drawer.fill = null
                                drawer.contour(curve)
                            }
                        }
                    }

                    // Info text
                    drawer.fill = ColorRGBa.BLACK.opacify(0.7)
                    drawer.text("VARIANT $variant / SEED $seed", margin, height - margin / 2.0)
                    drawer.text("LAYERS: ${if (variant == 1) 150 else if (variant == 2) 20 else 300}", margin, height - margin / 2.0 + 15.0)
                    
                    if (showDebug) {
                        drawer.fill = null
                        drawer.stroke = ColorRGBa.RED
                        drawer.rectangle(safeArea)
                    }
                }
            }

            drawer.image(rt.colorBuffer(0))

            if (exportRequested) {
                val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                val filename = "ribbons_v${variant}_s${seed}_${timestamp}.png"
                rt.colorBuffer(0).saveToFile(File(filename))
                println("Exported $filename")
                exportRequested = false
            }
        }

        keyboard.keyDown.listen {
            when (it.name) {
                "r" -> seed = Random.nextInt()
                "1" -> variant = 1
                "2" -> variant = 2
                "3" -> variant = 3
                "d" -> showDebug = !showDebug
                "e" -> exportRequested = true
            }
        }
    }
}
