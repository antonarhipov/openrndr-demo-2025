package drawing

import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.*
import org.openrndr.extra.gui.GUI
import org.openrndr.extra.parameters.*
import org.openrndr.math.Vector2
import org.openrndr.shape.ShapeContour
import org.openrndr.shape.contour
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.math.*

class FourierParams {
    @IntParameter("Seed", 0, 1000000)
    var seed: Int = (Math.random() * 1000000).toInt()

    @IntParameter("Harmonics (K)", 10, 60)
    var K: Int = 40

    @DoubleParameter("Spectral Slope (p)", 1.0, 2.5)
    var p: Double = 1.6

    @DoubleParameter("Scale (s)", 0.1, 1.5)
    var s: Double = 1.0

    @IntParameter("Points (N)", 100, 20000)
    var N: Int = 6000

    @BooleanParameter("Open Mode")
    var openMode: Boolean = false

    @DoubleParameter("Drift (D)", 0.0, 50.0)
    var driftD: Double = 0.0

    @DoubleParameter("Anisotropy (gammaY)", 1.0, 3.0)
    var gammaY: Double = 1.0

    @DoubleParameter("Stroke Width", 0.5, 5.0)
    var strokeWidth: Double = 1.5

    @DoubleParameter("Stroke Alpha", 0.1, 1.0)
    var strokeAlpha: Double = 0.8

    @BooleanParameter("Dark Mode")
    var darkMode: Boolean = false

    @BooleanParameter("Two-Pass Ink")
    var twoPassInk: Boolean = true

    @BooleanParameter("Debug Overlay")
    var debug: Boolean = false
}

fun main() = application {
    configure {
        width = 600
        height = 800
        title = "Fourier Spaghetti"
    }

    program {
        val params = FourierParams()
        val gui = GUI()
        gui.add(params, "Parameters")

        var coefficientsX = listOf<Pair<Double, Double>>()
        var coefficientsY = listOf<Pair<Double, Double>>()
        var points = listOf<Vector2>()
        var rotationAngle = 0.0

        fun generateCoefficients() {
            val rnd = java.util.Random(params.seed.toLong())
            coefficientsX = List(params.K) { k ->
                val freq = k + 1.0
                val stdDev = params.s / freq.pow(params.p)
                Pair(rnd.nextGaussian() * stdDev, rnd.nextGaussian() * stdDev)
            }
            coefficientsY = List(params.K) { k ->
                val freq = k + 1.0
                val stdDev = params.s / freq.pow(params.p)
                Pair(rnd.nextGaussian() * stdDev, rnd.nextGaussian() * stdDev)
            }
            rotationAngle = (rnd.nextDouble() - 0.5) * 0.2 // Small random rotation
        }

        fun generatePoints() {
            val newPoints = mutableListOf<Vector2>()
            val driftVector = Vector2(0.0, params.driftD)
            val freqs = DoubleArray(params.K) { (it + 1.0) * 2.0 * PI }
            
            for (i in 0 until params.N) {
                val t = i.toDouble() / (params.N - 1)
                
                var x = 0.0
                var y = 0.0
                
                for (k in 0 until params.K) {
                    val angle = freqs[k] * t
                    val (ak, bk) = coefficientsX[k]
                    val (ck, dk) = coefficientsY[k]
                    
                    x += ak * cos(angle) + bk * sin(angle)
                    y += ck * cos(angle) + dk * sin(angle)
                }
                
                var p = Vector2(x, y)
                
                if (params.openMode) {
                    val w = 0.5 * (1.0 - cos(2.0 * PI * t))
                    p *= w
                    p += driftVector * t
                }
                
                // Anisotropic scaling
                p = Vector2(p.x, p.y * params.gammaY)
                
                newPoints.add(p)
            }
            
            // Normalize
            if (newPoints.isNotEmpty()) {
                val mean = newPoints.reduce { acc, vector2 -> acc + vector2 } / newPoints.size.toDouble()
                val centered = newPoints.map { it - mean }
                
                // Rotate
                val rotated = centered.map { it.rotate(rotationAngle * 180.0 / PI) }
                
                // Scale to fit 70-85% of canvas
                var minX = Double.MAX_VALUE
                var maxX = Double.MIN_VALUE
                var minY = Double.MAX_VALUE
                var maxY = Double.MIN_VALUE
                
                for (v in rotated) {
                    minX = min(minX, v.x)
                    maxX = max(maxX, v.x)
                    minY = min(minY, v.y)
                    maxY = max(maxY, v.y)
                }
                
                val currentWidth = maxX - minX
                val currentHeight = maxY - minY
                val targetWidth = width * 0.8 * params.s
                val targetHeight = height * 0.8 * params.s
                
                val scale = min(targetWidth / currentWidth, targetHeight / currentHeight)
                
                points = rotated.map { it * scale + Vector2(width / 2.0, height / 2.0) }
            } else {
                points = emptyList()
            }
        }

        fun regenerate() {
            generateCoefficients()
            generatePoints()
        }

        var lastState = ""
        fun getState() = "${params.seed}-${params.K}-${params.p}-${params.s}-${params.N}-${params.openMode}-${params.driftD}-${params.gammaY}"

        regenerate()
        lastState = getState()

        fun saveImage() {
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            val filename = "images/fourier_spaghetti_${params.seed}_$timestamp.png"
            File("images").mkdirs()
            val rt = renderTarget(width, height) {
                colorBuffer()
                depthBuffer(DepthFormat.DEPTH24_STENCIL8)
            }
            drawer.isolatedWithTarget(rt) {
                val bg = if (params.darkMode) ColorRGBa.fromHex("111111") else ColorRGBa.fromHex("F5F5F5")
                val ink = if (params.darkMode) ColorRGBa.WHITE else ColorRGBa.BLACK
                drawer.clear(bg)
                if (points.isNotEmpty()) {
                    val c = contour {
                        moveTo(points[0])
                        for (i in 1 until points.size) {
                            lineTo(points[i])
                        }
                        if (!params.openMode) {
                            close()
                        }
                    }
                    drawer.lineCap = LineCap.ROUND
                    drawer.lineJoin = LineJoin.ROUND
                    if (params.twoPassInk) {
                        drawer.stroke = ink.opacify(0.15 * params.strokeAlpha)
                        drawer.strokeWeight = params.strokeWidth * 3.0
                        drawer.contour(c)
                    }
                    drawer.stroke = ink.opacify(params.strokeAlpha)
                    drawer.strokeWeight = params.strokeWidth
                    drawer.contour(c)
                }
            }
            rt.colorBuffer(0).saveToFile(File(filename))
            rt.destroy()
            println("Saved to $filename")
        }

        keyboard.keyDown.listen {
            when (it.name) {
                "r" -> {
                    params.seed = (Math.random() * 1000000).toInt()
                }
                "o" -> {
                    params.openMode = !params.openMode
                }
                "[" -> {
                    params.K = (params.K - 5).coerceAtLeast(10)
                }
                "]" -> {
                    params.K = (params.K + 5).coerceAtMost(60)
                }
                "-" -> {
                    params.p = (params.p - 0.1).coerceAtLeast(1.0)
                }
                "=" -> {
                    params.p = (params.p + 0.1).coerceAtMost(2.5)
                }
                "t" -> {
                    if (params.gammaY == 1.0 && params.driftD == 0.0) {
                        params.gammaY = 2.0
                        params.driftD = 12.0
                    } else {
                        params.gammaY = 1.0
                        params.driftD = 0.0
                    }
                }
                "b" -> {
                    params.darkMode = !params.darkMode
                }
                "s", "e" -> {
                    saveImage()
                }
                "d" -> {
                    params.debug = !params.debug
                }
                "1" -> {
                    params.K = 40
                    params.p = 1.6
                    params.N = 6000
                    params.openMode = true
                    params.driftD = 12.0
                    params.gammaY = 2.0
                }
            }
        }

        gui.visible = false
        extend(gui)
        extend {
            gui.visible = mouse.position.x < width * 0.05 || (gui.visible && mouse.position.x < 200.0)
            
            if (getState() != lastState) {
                regenerate()
                lastState = getState()
            }

            val bg = if (params.darkMode) ColorRGBa.fromHex("111111") else ColorRGBa.fromHex("F5F5F5")
            val ink = if (params.darkMode) ColorRGBa.WHITE else ColorRGBa.BLACK
            
            drawer.clear(bg)
            
            if (points.isNotEmpty()) {
                val c = contour {
                    moveTo(points[0])
                    for (i in 1 until points.size) {
                        lineTo(points[i])
                    }
                    if (!params.openMode) {
                        close()
                    }
                }
                
                drawer.lineCap = LineCap.ROUND
                drawer.lineJoin = LineJoin.ROUND
                
                if (params.twoPassInk) {
                    drawer.stroke = ink.opacify(0.15 * params.strokeAlpha)
                    drawer.strokeWeight = params.strokeWidth * 3.0
                    drawer.contour(c)
                }
                
                drawer.stroke = ink.opacify(params.strokeAlpha)
                drawer.strokeWeight = params.strokeWidth
                drawer.contour(c)
            }
            
            if (params.debug) {
                drawer.isolated {
                    drawer.fill = ColorRGBa.RED
                    drawer.stroke = null
                    drawer.text("Seed: ${params.seed}", 20.0, 30.0)
                    drawer.text("K: ${params.K}", 20.0, 50.0)
                    drawer.text("p: ${String.format("%.2f", params.p)}", 20.0, 70.0)
                    drawer.text("N: ${params.N}", 20.0, 90.0)
                    drawer.text("Points: ${points.size}", 20.0, 110.0)
                    drawer.text("Open: ${params.openMode}", 20.0, 130.0)
                    drawer.text("Drift: ${String.format("%.2f", params.driftD)}", 20.0, 150.0)
                    drawer.text("GammaY: ${String.format("%.2f", params.gammaY)}", 20.0, 170.0)
                }
            }
        }
    }
}
