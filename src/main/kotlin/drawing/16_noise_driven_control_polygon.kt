package drawing

import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.*
import org.openrndr.extra.noise.simplex
import org.openrndr.math.Vector2
import org.openrndr.math.map
import org.openrndr.shape.ShapeContour
import org.openrndr.shape.contour
import java.io.File
import kotlin.math.*
import kotlin.random.Random

data class Params(
    val seed: Int,
    val N: Int,
    val noiseFreq: Double,
    val timeFreq: Double,
    val tension: Double,
    val layers: Int,
    val dt: Double,
    val shapeSmoothing: Double,
    val boldEvery: Int,
    val centerOffsetPct: Double,
    val baseRadiusMinPct: Double,
    val baseRadiusMaxPct: Double
)

class NoiseDrivenStorm(val width: Int, val height: Int) {
    val minDim = minOf(width, height)
    val safeMargin = minDim * 0.08
    var debugMode = false
    var debugLayer = 60

    fun basePolygon(params: Params): Pair<Vector2, List<Vector2>> {
        val rng = Random(params.seed)

        // Center slightly off-center
        val centerX = width * 0.5 + rng.nextDouble(-1.0, 1.0) * minDim * params.centerOffsetPct
        val centerY = height * 0.5 + rng.nextDouble(-1.0, 1.0) * minDim * params.centerOffsetPct
        val center = Vector2(centerX, centerY)

        // Elliptical base
        val rxPct = rng.nextDouble(params.baseRadiusMinPct, params.baseRadiusMaxPct)
        val ryPct = rng.nextDouble(params.baseRadiusMinPct, params.baseRadiusMaxPct)
        val rx = minDim * rxPct
        val ry = minDim * ryPct

        val points = (0 until params.N).map { i ->
            val theta = 2.0 * PI * i / params.N
            center + Vector2(cos(theta) * rx, sin(theta) * ry)
        }

        return Pair(center, points)
    }

    fun smoothDisplacements(displacements: List<Double>, smoothing: Double): List<Double> {
        if (smoothing < 0.01) return displacements

        val N = displacements.size
        val smoothed = MutableList(N) { 0.0 }
        val kernelSize = (smoothing * 5).toInt().coerceAtLeast(1)

        for (i in 0 until N) {
            var sum = 0.0
            var weight = 0.0
            for (k in -kernelSize..kernelSize) {
                val idx = (i + k + N) % N
                val w = exp(-k * k / (2.0 * smoothing * smoothing + 0.1))
                sum += displacements[idx] * w
                weight += w
            }
            smoothed[i] = sum / weight
        }
        return smoothed
    }

    fun displacedPolygon(baseCenter: Vector2, basePoints: List<Vector2>, t: Double, params: Params): List<Vector2> {
        val seed = params.seed
        val N = basePoints.size

        // System translation (tiny drift)
        val centerDriftX = simplex(seed + 1000, t * params.timeFreq * 0.1) * minDim * 0.02
        val centerDriftY = simplex(seed + 2000, t * params.timeFreq * 0.1) * minDim * 0.02
        val driftedCenter = baseCenter + Vector2(centerDriftX, centerDriftY)

        // Radial displacements
        val rawDisplacements = (0 until N).map { i ->
            val spatialCoord = i.toDouble() / N * params.noiseFreq
            val dr = simplex(seed, Vector2(spatialCoord, t * params.timeFreq)) * minDim * 0.15
            dr
        }

        // Angular drift
        val angularDrifts = (0 until N).map { i ->
            val spatialCoord = i.toDouble() / N * params.noiseFreq
            val dtheta = simplex(seed + 500, Vector2(spatialCoord, t * params.timeFreq)) * 0.3
            dtheta
        }

        // Smooth displacements
        val smoothedDisp = smoothDisplacements(rawDisplacements, params.shapeSmoothing)
        val smoothedAngular = smoothDisplacements(angularDrifts, params.shapeSmoothing * 0.5)

        return basePoints.indices.map { i ->
            val base = basePoints[i]
            val toCenter = base - driftedCenter
            val radius = toCenter.length
            val baseAngle = atan2(toCenter.y, toCenter.x)

            val newRadius = radius + smoothedDisp[i]
            val newAngle = baseAngle + smoothedAngular[i]

            driftedCenter + Vector2(cos(newAngle) * newRadius, sin(newAngle) * newRadius)
        }
    }

    fun hobbyClosedContour(points: List<Vector2>, tension: Double): ShapeContour {
        if (points.size < 3) return ShapeContour.EMPTY

        // Catmull-Rom closed curve as Hobby approximation
        return contour {
            moveTo(points.last())
            for (i in points.indices) {
                val p0 = points[(i - 1 + points.size) % points.size]
                val p1 = points[i]
                val p2 = points[(i + 1) % points.size]
                val p3 = points[(i + 2) % points.size]

                // Control points with tension
                val t = 1.0 / tension.coerceIn(0.1, 3.0)
                val c1 = p1 + (p2 - p0) * t / 6.0
                val c2 = p2 - (p3 - p1) * t / 6.0

                curveTo(c1, c2, p2)
            }
            close()
        }
    }

    fun computeCurvature(contour: ShapeContour, t: Double): Double {
        val epsilon = 0.001
        val p0 = contour.position((t - epsilon).coerceAtLeast(0.0))
        val p1 = contour.position(t)
        val p2 = contour.position((t + epsilon).coerceAtMost(1.0))

        val d1 = p1 - p0
        val d2 = p2 - p1
        val cross = d1.x * d2.y - d1.y * d2.x

        return abs(cross) / (d1.length * d2.length + 0.0001)
    }

    fun strokeStyleForLayer(stepIndex: Int, params: Params, contour: ShapeContour): StrokeStyle {
        val progress = stepIndex.toDouble() / params.layers

        // Bold front line every N steps
        val isBold = stepIndex % params.boldEvery == 0

        val baseAlpha = map(0.0, 1.0, 0.12, 0.35, progress)
        val baseWidth = if (isBold) 2.5 else 1.2

        return StrokeStyle(
            width = baseWidth,
            alpha = if (isBold) 0.6 else baseAlpha
        )
    }

    fun shouldHighlightSegment(contour: ShapeContour, t: Double, stepIndex: Int, params: Params): Boolean {
        val curvature = computeCurvature(contour, t)
        val threshold = 0.15

        // Highlight high curvature regions occasionally
        val rng = Random(params.seed + stepIndex)
        return curvature > threshold && rng.nextDouble() < 0.3
    }

    fun renderBackground(drawer: Drawer) {
        // Stormy gradient
        drawer.stroke = null

        drawer.shadeStyle = shadeStyle {
            fragmentTransform = """
                vec2 uv = c_boundsPosition.xy;
                vec3 color1 = vec3(0.1, 0.12, 0.25); // indigo
                vec3 color2 = vec3(0.15, 0.15, 0.18); // charcoal

                float mix_factor = uv.y * 0.7 + 0.3;
                vec3 gradient = mix(color1, color2, mix_factor);

                // Paper grain
                float grain = fract(sin(dot(uv * 1000.0, vec2(12.9898, 78.233))) * 43758.5453);
                gradient += (grain - 0.5) * 0.03;

                // Vignette
                float vignette = 1.0 - length(uv - 0.5) * 0.8;
                gradient *= vignette;

                x_fill.rgb = gradient;
                x_fill.a = 1.0;
            """.trimIndent()
        }

        drawer.rectangle(0.0, 0.0, width.toDouble(), height.toDouble())
        drawer.shadeStyle = null
    }

    fun renderLayer(drawer: Drawer, contour: ShapeContour, stepIndex: Int, params: Params, isDebugSelected: Boolean) {
        val progress = stepIndex.toDouble() / params.layers
        val isBold = stepIndex % params.boldEvery == 0

        // Base color: desaturated blues/grays
        val hueShift = progress * 0.05
        val baseColor = ColorRGBa(0.5 + hueShift, 0.55 + hueShift, 0.65, 1.0)

        val style = strokeStyleForLayer(stepIndex, params, contour)

        drawer.strokeWeight = style.width
        drawer.fill = null

        // Multi-pass for atmospheric effect
        val passes = if (isBold) 4 else 2
        for (pass in 0 until passes) {
            val offset = if (pass == 0) Vector2.ZERO else {
                Vector2(
                    simplex(params.seed + pass * 100, stepIndex * 0.1) * 0.3,
                    simplex(params.seed + pass * 100 + 50, stepIndex * 0.1) * 0.3
                )
            }

            drawer.stroke = if (isBold) {
                ColorRGBa(0.6, 0.62, 0.65, style.alpha / passes)
            } else {
                baseColor.opacify(style.alpha / passes)
            }

            drawer.pushTransforms()
            drawer.translate(offset)
            drawer.contour(contour)
            drawer.popTransforms()
        }

        // Neon warning accents (high curvature)
        if (isBold || stepIndex % (params.boldEvery / 2) == 0) {
            val segments = 200
            val magentaColor = ColorRGBa(1.0, 0.2, 0.6, 0.7)

            for (i in 0 until segments) {
                val t = i.toDouble() / segments
                if (shouldHighlightSegment(contour, t, stepIndex, params)) {
                    val tEnd = ((i + 3).toDouble() / segments).coerceAtMost(1.0)
                    val segment = contour.sub(t, tEnd)

                    drawer.stroke = magentaColor
                    drawer.strokeWeight = style.width * 1.8
                    drawer.contour(segment)
                }
            }
        }

        // Debug overlay
        if (debugMode && isDebugSelected) {
            // Show control points
            val baseCenter = basePolygon(params).first
            val basePoints = basePolygon(params).second
            val t = stepIndex * params.dt
            val displaced = displacedPolygon(baseCenter, basePoints, t, params)

            drawer.fill = ColorRGBa.YELLOW.opacify(0.6)
            drawer.stroke = ColorRGBa.YELLOW.opacify(0.4)
            drawer.strokeWeight = 1.0

            for (i in displaced.indices) {
                drawer.circle(displaced[i], 3.0)
                val next = displaced[(i + 1) % displaced.size]
                drawer.lineSegment(displaced[i], next)
            }
        }
    }

    fun renderDashedFrontLine(drawer: Drawer, contour: ShapeContour, stepIndex: Int, params: Params) {
        val segments = 100
        val dashLength = 10.0
        val gapLength = 8.0

        drawer.stroke = ColorRGBa(0.7, 0.7, 0.75, 0.5)
        drawer.strokeWeight = 2.0

        var accumulated = 0.0
        for (i in 0 until segments) {
            val t0 = i.toDouble() / segments
            val t1 = (i + 1).toDouble() / segments
            val segLength = contour.sub(t0, t1).length

            val phase = (accumulated % (dashLength + gapLength))
            if (phase < dashLength) {
                drawer.lineSegment(contour.position(t0), contour.position(t1))
            }

            accumulated += segLength
        }
    }

    fun renderPoster(drawer: Drawer, params: Params) {
        renderBackground(drawer)

        val (baseCenter, basePoints) = basePolygon(params)

        // Render all layers back-to-front
        for (step in 0 until params.layers) {
            val t = step * params.dt
            val displaced = displacedPolygon(baseCenter, basePoints, t, params)
            val contour = hobbyClosedContour(displaced, params.tension)

            val isDebugSelected = debugMode && step == debugLayer
            renderLayer(drawer, contour, step, params, isDebugSelected)

            // Dashed front lines (sparse)
            if (step % params.boldEvery == 0 && step % (params.boldEvery * 3) == 0) {
                renderDashedFrontLine(drawer, contour, step, params)
            }
        }

        // Legend
        renderLegend(drawer, params)

        // Faint grid
        renderGrid(drawer)

        // Pressure label
        renderPressureLabel(drawer, baseCenter, params)
    }

    fun renderLegend(drawer: Drawer, params: Params) {
        val x = safeMargin * 1.5
        val y = height - safeMargin * 2.5

        drawer.fill = ColorRGBa.WHITE.opacify(0.15)
        drawer.stroke = ColorRGBa.WHITE.opacify(0.25)
        drawer.strokeWeight = 1.0
        drawer.rectangle(x, y, 180.0, 90.0)

        drawer.fill = ColorRGBa.WHITE.opacify(0.5)
        drawer.fontMap = loadFontOrDefault(drawer)

        val lines = listOf(
            "NOISE SYSTEM",
            "seed: ${params.seed}",
            "N: ${params.N}",
            "ωₛ: ${"%.2f".format(params.noiseFreq)}",
            "ωₜ: ${"%.2f".format(params.timeFreq)}",
            "τ: ${"%.2f".format(params.tension)}"
        )

        lines.forEachIndexed { i, line ->
            drawer.text(line, x + 8, y + 18 + i * 12)
        }
    }

    fun renderGrid(drawer: Drawer) {
        drawer.stroke = ColorRGBa.WHITE.opacify(0.04)
        drawer.strokeWeight = 0.5

        drawer.pushTransforms()
        drawer.translate(width * 0.5, height * 0.5)
        drawer.rotate(3.0)
        drawer.translate(-width * 0.5, -height * 0.5)

        val spacing = minDim / 10.0
        for (i in 0..10) {
            val x = i * spacing
            drawer.lineSegment(x, 0.0, x, height.toDouble())
        }
        for (i in 0..10) {
            val y = i * spacing
            drawer.lineSegment(0.0, y, width.toDouble(), y)
        }

        drawer.popTransforms()
    }

    fun renderPressureLabel(drawer: Drawer, center: Vector2, params: Params) {
        drawer.fill = ColorRGBa.WHITE.opacify(0.3)
        drawer.fontMap = loadFontOrDefault(drawer)

        val label = if (params.seed % 2 == 0) "L" else "H"
        drawer.text(label, center.x - 5, center.y + 5)
    }

    fun loadFontOrDefault(drawer: Drawer): FontMap? {
        return try {
            loadFont("data/fonts/default.otf", 10.0)
        } catch (e: Exception) {
            null
        }
    }

    fun exportPoster(drawer: Drawer, params: Params) {
        val filename = "noise_storm_s${params.seed}_n${params.N}_nf${"%.2f".format(params.noiseFreq)}_t${"%.2f".format(params.tension)}.png"

        val rt = renderTarget(width, height) {
            colorBuffer()
            depthBuffer(DepthFormat.DEPTH24_STENCIL8)
        }

        drawer.isolatedWithTarget(rt) {
            renderPoster(drawer, params)
        }

        rt.colorBuffer(0).saveToFile(File(filename))
        rt.destroy()

        println("Exported: $filename")
    }
}

data class StrokeStyle(val width: Double, val alpha: Double)

fun main() = application {
    configure {
        width = 600
        height = 800
        title = "Noise-Driven Control Polygon"
    }

    program {
        var params = Params(
            seed = 42,
            N = 24,
            noiseFreq = 3.5,
            timeFreq = 0.8,
            tension = 1.2,
            layers = 120,
            dt = 0.05,
            shapeSmoothing = 2.5,
            boldEvery = 15,
            centerOffsetPct = 0.08,
            baseRadiusMinPct = 0.25,
            baseRadiusMaxPct = 0.35
        )

        val storm = NoiseDrivenStorm(width, height)

        keyboard.keyDown.listen { event ->
            when (event.name) {
                "r" -> {
                    params = params.copy(seed = (Math.random() * 100000).toInt())
                    println("Reseeded: ${params.seed}")
                }
                "e" -> {
                    storm.exportPoster(drawer, params)
                }
                "d" -> {
                    storm.debugMode = !storm.debugMode
                    println("Debug mode: ${storm.debugMode}")
                }
                "[" -> {
                    params = params.copy(noiseFreq = (params.noiseFreq - 0.2).coerceAtLeast(0.5))
                    println("Noise freq: ${"%.2f".format(params.noiseFreq)}")
                }
                "]" -> {
                    params = params.copy(noiseFreq = (params.noiseFreq + 0.2).coerceAtMost(10.0))
                    println("Noise freq: ${"%.2f".format(params.noiseFreq)}")
                }
                "-" -> {
                    params = params.copy(tension = (params.tension - 0.1).coerceAtLeast(0.5))
                    println("Tension: ${"%.2f".format(params.tension)}")
                }
                "=" -> {
                    params = params.copy(tension = (params.tension + 0.1).coerceAtMost(2.0))
                    println("Tension: ${"%.2f".format(params.tension)}")
                }
                "," -> {
                    storm.debugLayer = (storm.debugLayer - 1).coerceAtLeast(0)
                    println("Debug layer: ${storm.debugLayer}")
                }
                "." -> {
                    storm.debugLayer = (storm.debugLayer + 1).coerceAtMost(params.layers - 1)
                    println("Debug layer: ${storm.debugLayer}")
                }
            }
        }

        extend {
            storm.renderPoster(drawer, params)
        }
    }
}
