package drawing

import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.color.rgb
import org.openrndr.draw.*
import org.openrndr.extra.color.presets.*
import org.openrndr.extra.noise.gaussian
import org.openrndr.extra.noise.simplex
import org.openrndr.extra.shapes.hobbyCurve
import org.openrndr.math.Vector2
import org.openrndr.math.Vector3
import org.openrndr.math.map
import org.openrndr.shape.Rectangle
import org.openrndr.shape.ShapeContour
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.*
import kotlin.random.Random

/**
 * Curtain Waves
 * An aurora-borealis generative art program.
 */

enum class CurtainPaletteMode {
    CLASSIC, MUTED
}

data class CurtainParams(
    val seed: Long = Random.nextLong(),
    var panelCount: Int = 5,
    var tension: Double = 0.8,
    var A: Double = 40.0, // Base amplitude
    var k: Double = 0.05, // Base frequency
    var omega: Double = 1.0, // Speed
    var time: Double = 0.0,
    var paletteMode: CurtainPaletteMode = CurtainPaletteMode.CLASSIC,
    var showDebug: Boolean = false,
    var paused: Boolean = false
)

data class PanelParams(
    val id: Int,
    val xRange: Pair<Double, Double>,
    val yTopBase: Double,
    val length: Double,
    val phase: Double,
    val A: Double,
    val k: Double,
    val tension: Double,
    val zIndex: Double, // 0 to 1, 1 is front
    val brightness: Double
)

class CurtainWaves(val params: CurtainParams) {
    var panels = mutableListOf<PanelParams>()
    private var stars: List<Vector2> = emptyList()
    private var starColors: List<ColorRGBa> = emptyList()
    
    init {
        generatePanels()
    }

    fun generatePanels() {
        val rng = Random(params.seed)
        panels.clear()
        val count = params.panelCount
        
        for (i in 0 until count) {
            val isHero = (i == count / 2)
            val zIndex = if (isHero) 1.0 else rng.nextDouble(0.3, 0.8)
            val brightness = if (isHero) 1.0 else rng.nextDouble(0.4, 0.7)
            
            val widthFactor = if (isHero) rng.nextDouble(0.6, 0.8) else rng.nextDouble(0.3, 0.5)
            val xStart = rng.nextDouble(0.0, 1.0 - widthFactor)
            
            panels.add(PanelParams(
                id = i,
                xRange = Pair(xStart, xStart + widthFactor),
                yTopBase = rng.nextDouble(0.15, 0.35),
                length = rng.nextDouble(0.4, 0.6),
                phase = rng.nextDouble(0.0, PI * 2),
                A = params.A * (if (isHero) 1.5 else rng.nextDouble(0.8, 1.2)),
                k = params.k * (if (isHero) 1.5 else rng.nextDouble(0.7, 1.3)),
                tension = params.tension,
                zIndex = zIndex,
                brightness = brightness
            ))
        }
        panels.sortBy { it.zIndex }
        stars = emptyList() // Reset stars
    }

    fun topControlPoints(panel: PanelParams, t: Double, bounds: Rectangle): List<Vector2> {
        val n = 16
        val points = mutableListOf<Vector2>()
        val startX = bounds.x + panel.xRange.first * bounds.width
        val endX = bounds.x + panel.xRange.second * bounds.width
        val baseY = bounds.y + panel.yTopBase * bounds.height
        
        for (i in 0 until n) {
            val s = i.toDouble() / (n - 1)
            val x0 = startX + s * (endX - startX)
            val waveX = panel.A * sin(panel.k * (x0 - bounds.x) - params.omega * t + panel.phase)
            val noise = simplex(params.seed.toInt(), x0 * 0.01, t * 0.1) * 20.0
            val y = baseY + simplex(params.seed.toInt() + 1, s * 2.0, t * 0.05) * 50.0
            points.add(Vector2(x0 + waveX + noise, y))
        }
        return points
    }

    fun hobbyTopCurve(points: List<Vector2>, tension: Double): ShapeContour {
        return ShapeContour.fromPoints(points, closed = false).hobbyCurve(tension)
    }

    fun densityFromTangent(tangent: Vector2): Double {
        return map(10.0, 800.0, 0.0, 1.0, tangent.length).coerceIn(0.0, 1.0)
    }

    fun strandColor(u: Double, density: Double, edgeFactor: Double, panel: PanelParams, palette: CurtainPaletteMode, accentNoise: Double): ColorRGBa {
        val baseGreen = if (palette == CurtainPaletteMode.CLASSIC) ColorRGBa.GREEN.mix(ColorRGBa.AQUAMARINE, 0.2) else rgb(0.3, 0.7, 0.4)
        val topMagenta = if (palette == CurtainPaletteMode.CLASSIC) ColorRGBa.MAGENTA.mix(ColorRGBa.PINK, 0.3) else rgb(0.6, 0.3, 0.5)
        val lowViolet = if (palette == CurtainPaletteMode.CLASSIC) ColorRGBa.VIOLET.mix(ColorRGBa.BLUE, 0.2) else rgb(0.2, 0.2, 0.5)
        
        var color = baseGreen
        val magentaStrength = (1.0 - u).pow(4.0) * 0.8 + edgeFactor * 0.4 + density * 0.3
        color = color.mix(topMagenta, magentaStrength.coerceIn(0.0, 1.0))
        
        if (u > 0.7) {
            val violetStrength = map(0.7, 1.0, 0.0, 0.3, u) * (accentNoise * 0.5 + 0.5)
            color = color.mix(lowViolet, violetStrength.coerceIn(0.0, 1.0))
        }
        
        val alpha = (1.0 - u).pow(1.5) * panel.brightness
        return color.opacify(alpha)
    }

    fun renderBackground(drawer: Drawer, bounds: Rectangle) {
        if (stars.isEmpty()) {
            val rng = Random(params.seed)
            stars = List(200) { Vector2(rng.nextDouble() * bounds.width + bounds.x, rng.nextDouble() * bounds.height + bounds.y) }
            starColors = List(200) { ColorRGBa.WHITE.opacify(rng.nextDouble(0.05, 0.15)) }
        }
        drawer.isolated {
            drawer.fill = ColorRGBa.BLACK
            drawer.stroke = null
            drawer.rectangle(bounds)
            for (i in stars.indices) {
                drawer.stroke = starColors[i]
                drawer.point(stars[i])
            }
        }
    }

    fun renderPanel(drawer: Drawer, panel: PanelParams, t: Double, bounds: Rectangle) {
        val points = topControlPoints(panel, t, bounds)
        val contour = hobbyTopCurve(points, panel.tension)
        
        val isHighRes = bounds.width > 2000.0
        val samplesCount = if (isHighRes) 2400 else 800
        val strands = mutableListOf<Pair<Vector2, Double>>()
        
        for (i in 0 until samplesCount) {
            val tCurve = i.toDouble() / (samplesCount - 1)
            val pos = contour.position(tCurve)
            
            val t1 = (tCurve + 0.001).coerceAtMost(1.0)
            val t0 = (tCurve - 0.001).coerceAtLeast(0.0)
            val tangent = (contour.position(t1) - contour.position(t0)) * 500.0
            
            val density = densityFromTangent(tangent)
            strands.add(pos to density)
        }
        
        val baseWidth = if (isHighRes) 12.0 else 2.5
        renderStrands(drawer, strands, panel, baseWidth * 4.0, 0.05, bounds) // Halo
        renderStrands(drawer, strands, panel, baseWidth, 0.4, bounds)  // Core
        
        if (params.showDebug) {
            drawer.stroke = ColorRGBa.RED
            drawer.strokeWeight = 2.0
            drawer.contour(contour)
            drawer.fill = ColorRGBa.WHITE
            for (p in points) drawer.circle(p, 5.0)
        }
    }

    private fun renderStrands(drawer: Drawer, strands: List<Pair<Vector2, Double>>, panel: PanelParams, baseWidth: Double, alphaScale: Double, bounds: Rectangle) {
        val strandLengthBase = panel.length * bounds.height
        val isHighRes = bounds.width > 2000.0
        val segments = if (isHighRes) 50 else 15
        
        val accentNoiseK = List(segments) { k -> simplex(params.seed.toInt() + 2, (k.toDouble() / segments) * 10.0, panel.id.toDouble()) }
        
        // Precompute strand lengths and x-positions to avoid simplex noise in the nested loops
        val strandInfo = strands.map { (pos, density) ->
            val nStrands = (1 + density * 2).toInt()
            List(nStrands) { j ->
                val offset = if (nStrands > 1) (j.toDouble() / (nStrands - 1) - 0.5) * (baseWidth * 1.5) else 0.0
                val x = pos.x + offset
                val len = strandLengthBase * (0.8 + 0.2 * simplex(params.seed.toInt() + 3, x * 0.01, params.time * 0.1))
                x to len
            }
        }

        for (k in 0 until segments) {
            val u0 = k.toDouble() / segments
            val u1 = (k + 1).toDouble() / segments
            drawer.strokeWeight = baseWidth * (1.0 - u0 * 0.7)
            
            val colorBuckets = mutableMapOf<Int, MutableList<Vector2>>()
            
            for (i in strands.indices) {
                val (pos, density) = strands[i]
                val edgeFactor = if (i < strands.size / 10) (1.0 - i.toDouble() / (strands.size / 10)) else if (i > strands.size * 9 / 10) (i.toDouble() - strands.size * 0.9) / (strands.size / 10) else 0.0
                
                val qDensity = (density * 5).toInt()
                val qEdge = (edgeFactor * 5).toInt()
                val colorKey = qDensity * 10 + qEdge
                val bucket = colorBuckets.getOrPut(colorKey) { mutableListOf() }
                
                val infos = strandInfo[i]
                for (info in infos) {
                    val x = info.first
                    val currentLength = info.second
                    
                    val y0 = pos.y + u0 * currentLength
                    val y1 = pos.y + u1 * currentLength
                    
                    bucket.add(Vector2(x, y0))
                    bucket.add(Vector2(x, y1))
                }
            }
            
            for ((key, points) in colorBuckets) {
                val qD = (key / 10) / 5.0
                val qE = (key % 10) / 5.0
                val color = strandColor(u0, qD, qE, panel, params.paletteMode, accentNoiseK[k])
                drawer.stroke = color.opacify(color.alpha * alphaScale)
                drawer.lineSegments(points)
            }
        }
    }
}

fun main() = application {
    configure {
        width = 600
        height = 800
        title = "Curtain Waves"
    }
    program {
        var params = CurtainParams()
        var curtain = CurtainWaves(params)
        
        val fontPreview = loadFont("data/fonts/default.otf", 16.0)
        val fontExport = loadFont("data/fonts/default.otf", 120.0)

        fun renderPoster(drawer: Drawer, p: CurtainParams, export: Boolean = false) {
            val w = drawer.width.toDouble()
            val h = drawer.height.toDouble()
            val margin = min(w, h) * 0.08
            val drawBounds = Rectangle(margin, margin, w - 2 * margin, h - 2 * margin)
            
            curtain.renderBackground(drawer, Rectangle(0.0, 0.0, w, h))
            
            for (panel in curtain.panels) {
                curtain.renderPanel(drawer, panel, p.time, drawBounds)
            }
            
            // Caption
            drawer.fontMap = fontPreview
            drawer.fill = ColorRGBa.WHITE.opacify(0.8)
            val info = "Curtain Waves | Seed: ${p.seed} | Panels: ${p.panelCount} | Tension: ${"%.2f".format(p.tension)} | A: ${"%.1f".format(p.A)} | k: ${"%.3f".format(p.k)} | omega: ${"%.1f".format(p.omega)} | t: ${"%.2f".format(p.time)}"
            drawer.text(info, margin, h - margin / 2)
        }

        keyboard.keyDown.listen {
            when (it.name) {
                "r" -> {
                    params = params.copy(seed = Random.nextLong())
                    curtain = CurtainWaves(params)
                }
                "space" -> params.paused = !params.paused
                "t" -> { params.tension += 0.05; curtain.generatePanels() }
                "g" -> { params.tension -= 0.05; curtain.generatePanels() }
                "a" -> { params.A += 5.0; curtain.generatePanels() }
                "z" -> { params.A -= 5.0; curtain.generatePanels() }
                "k" -> { params.k += 0.005; curtain.generatePanels() }
                "j" -> { params.k -= 0.005; curtain.generatePanels() }
                "period" -> params.omega += 0.1
                "comma" -> params.omega -= 0.1
                "d" -> params.showDebug = !params.showDebug
                "1" -> params.paletteMode = CurtainPaletteMode.CLASSIC
                "2" -> params.paletteMode = CurtainPaletteMode.MUTED
                "e" -> {
                    val rt = renderTarget(width, height) {
                        colorBuffer()
                        depthBuffer()
                    }
                    drawer.isolatedWithTarget(rt) {
                        clear(ColorRGBa.BLACK)
                        renderPoster(this, params, true)
                    }
                    val filename = "images/curtain_s${params.seed}_t${"%.2f".format(params.time)}_A${params.A.toInt()}_k${"%.3f".format(params.k)}_T${"%.2f".format(params.tension)}_${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.png"
                    rt.colorBuffer(0).saveToFile(File(filename))
                    rt.destroy()
                    println("Exported to $filename")
                }
            }
        }

        extend {
            if (!params.paused) {
                params.time += 0.01
            }
            renderPoster(drawer, params)
        }
    }
}
