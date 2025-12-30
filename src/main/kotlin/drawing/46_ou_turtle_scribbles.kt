package drawing

import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.*
import org.openrndr.extra.gui.GUI
import org.openrndr.extra.noise.Random
import org.openrndr.extra.noise.gaussian
import org.openrndr.extra.parameters.*
import org.openrndr.math.Vector2
import org.openrndr.math.map
import org.openrndr.shape.Rectangle
import org.openrndr.shape.ShapeContour
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.*

class OUTurtleParams {
    @IntParameter("Seed", 0, 1000000)
    var seed: Int = 123456

    @IntParameter("Scribble Count", 1, 6)
    var scribbleCount: Int = 1

    var n: Int = 10000

    @DoubleParameter("Length (L)", 10.0, 200.0)
    var l: Double = 60.0

    @DoubleParameter("Alpha", 0.1, 5.0)
    var alpha: Double = 0.6

    @DoubleParameter("Sigma", 0.1, 5.0)
    var sigma: Double = 1.2

    @DoubleParameter("Stroke Width", 0.5, 5.0)
    var strokeWidth: Double = 1.2

    @DoubleParameter("Stroke Alpha", 0.1, 1.0)
    var strokeAlpha: Double = 0.8

    @DoubleParameter("Gamma Y (Anisotropic)", 1.0, 3.0)
    var gammaY: Double = 1.8

    @Vector2Parameter("Drift Vector")
    var driftVector: Vector2 = Vector2(0.0, 10.0)

    @BooleanParameter("Use Drift")
    var useDrift: Boolean = false

    @BooleanParameter("Use Anisotropic Scale")
    var useAnisotropicScale: Boolean = false

    @BooleanParameter("Dark Mode")
    var darkMode: Boolean = false

    @BooleanParameter("Debug Mode")
    var debugMode: Boolean = false
    
    @BooleanParameter("Dual Pass Ink")
    var dualPass: Boolean = true

    @BooleanParameter("Regenerate")
    var regenerateTrigger: Boolean = false
}

fun main() = application {
    configure {
        width = 600
        height = 800
        title = "OU Turtle Scribbles"
    }

    program {
        val params = OUTurtleParams()
        val gui = GUI()
        gui.add(params, "Parameters")
        
        // Storage for generated scribbles
        var scribbles = mutableListOf<List<Vector2>>()
        var lastState = ""
        fun getState() = "${params.seed}-${params.scribbleCount}-${params.l}-${params.alpha}-${params.sigma}-${params.useDrift}-${params.driftVector}-${params.useAnisotropicScale}-${params.gammaY}"

        fun generateScribbles() {
            Random.seed = params.seed.toString()
            scribbles.clear()
            
            val count = params.scribbleCount
            for (sIdx in 0 until count) {
                val points = mutableListOf<Vector2>()
                
                // Per-scribble variation
                val sAlpha = params.alpha * Random.double(0.85, 1.15)
                val sSigma = params.sigma * Random.double(0.85, 1.15)
                
                var x = 0.0
                var y = 0.0
                var theta = Random.double(0.0, PI * 2)
                var kappa = 0.0
                
                val ds = params.l / params.n
                val sqrtDs = sqrt(ds)
                
                points.add(Vector2(x, y))
                
                for (i in 0 until params.n) {
                    val xi = Random.gaussian(0.0, 1.0)
                    kappa = kappa - sAlpha * kappa * ds + sSigma * sqrtDs * xi
                    theta += kappa * ds
                    x += cos(theta) * ds
                    y += sin(theta) * ds
                    points.add(Vector2(x, y))
                }
                
                // Post-processing per scribble: Tall look
                var processedPoints = points.toList()
                if (params.useDrift) {
                    processedPoints = processedPoints.mapIndexed { i, p ->
                        val t = i.toDouble() / (params.n)
                        p + params.driftVector * t
                    }
                }
                
                if (params.useAnisotropicScale) {
                    processedPoints = processedPoints.map { p ->
                        Vector2(p.x, p.y * params.gammaY)
                    }
                }
                
                scribbles.add(processedPoints)
            }
        }

        fun fitToCanvas(allPoints: List<List<Vector2>>): List<List<Vector2>> {
            if (allPoints.isEmpty()) return emptyList()
            
            // Compute global bounding box
            var minX = Double.POSITIVE_INFINITY
            var minY = Double.POSITIVE_INFINITY
            var maxX = Double.NEGATIVE_INFINITY
            var maxY = Double.NEGATIVE_INFINITY
            
            for (scribble in allPoints) {
                for (p in scribble) {
                    if (p.x < minX) minX = p.x
                    if (p.y < minY) minY = p.y
                    if (p.x > maxX) maxX = p.x
                    if (p.y > maxY) maxY = p.y
                }
            }
            
            val bboxWidth = maxX - minX
            val bboxHeight = maxY - minY
            val centerX = (minX + maxX) / 2.0
            val centerY = (minY + maxY) / 2.0
            
            val margin = 0.8 // 80% fit
            val scale = (min(width.toDouble(), height.toDouble()) * margin) / max(bboxWidth, bboxHeight)
            
            return allPoints.map { scribble ->
                scribble.map { p ->
                    Vector2(
                        (p.x - centerX) * scale + width / 2.0,
                        (p.y - centerY) * scale + height / 2.0
                    )
                }
            }
        }

        fun drawScribbles(d: Drawer, fitted: List<List<Vector2>>) {
            d.clear(if (params.darkMode) ColorRGBa.fromHex("#1a1a1a") else ColorRGBa.fromHex("#fdfcf8"))

            for ((idx, scribble) in fitted.withIndex()) {
                val isMain = idx == 0
                val weight = if (isMain) params.strokeWidth else params.strokeWidth * 0.5
                val alpha = if (isMain) params.strokeAlpha else params.strokeAlpha * 0.4
                val color = if (params.darkMode) ColorRGBa.WHITE else ColorRGBa.BLACK

                d.stroke = color.opacify(alpha)
                d.strokeWeight = weight
                d.fill = null

                if (params.dualPass && isMain) {
                    // Faint wide under-stroke
                    d.stroke = color.opacify(alpha * 0.3)
                    d.strokeWeight = weight * 2.5
                    d.lineStrip(scribble)

                    // Sharp core
                    d.stroke = color.opacify(alpha)
                    d.strokeWeight = weight
                    d.lineStrip(scribble)
                } else {
                    d.lineStrip(scribble)
                }
            }
        }

        generateScribbles()

        keyboard.keyDown.listen {
            when (it.name) {
                "r" -> {
                    params.seed = (Math.random() * 1000000).toInt()
                    generateScribbles()
                }
                "1" -> {
                    // Lazy loops
                    params.alpha = 0.4
                    params.sigma = 0.8
                    params.l = 80.0
                    generateScribbles()
                }
                "2" -> {
                    // Balanced
                    params.alpha = 0.6
                    params.sigma = 1.2
                    params.l = 60.0
                    generateScribbles()
                }
                "3" -> {
                    // Tight wriggles
                    params.alpha = 1.2
                    params.sigma = 2.5
                    params.l = 40.0
                    generateScribbles()
                }
                "t" -> {
                    params.useAnisotropicScale = !params.useAnisotropicScale
                    params.useDrift = params.useAnisotropicScale
                    generateScribbles()
                }
                "b" -> {
                    params.darkMode = !params.darkMode
                }
                "d" -> {
                    params.debugMode = !params.debugMode
                }
                "s", "e" -> {
                    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                    val filename = "ou_scribble_${params.seed}_$timestamp.png"
                    val rt = renderTarget(600, 800) {
                        colorBuffer()
                        depthBuffer(DepthFormat.DEPTH24_STENCIL8)
                    }
                    drawer.isolatedWithTarget(rt) {
                        drawScribbles(this, fitToCanvas(scribbles))
                    }
                    rt.colorBuffer(0).saveToFile(File("images/$filename"))
                    rt.colorBuffer(0).destroy()
                    rt.destroy()
                    println("Exported to images/$filename")
                }
            }
        }

        extend(gui)
        extend {
            gui.visible = mouse.position.x < width * 0.05 || (gui.visible && mouse.position.x < 200.0)

            val currentState = getState()
            if (currentState != lastState || params.regenerateTrigger) {
                lastState = currentState
                params.regenerateTrigger = false
                generateScribbles()
            }

            val fittedScribbles = fitToCanvas(scribbles)
            drawScribbles(drawer, fittedScribbles)

            if (params.debugMode) {
                drawer.fill = if (params.darkMode) ColorRGBa.WHITE else ColorRGBa.BLACK
                drawer.stroke = null
                drawer.text("Seed: ${params.seed}", 20.0, 30.0)
                drawer.text("Alpha: ${String.format("%.2f", params.alpha)}", 20.0, 50.0)
                drawer.text("Sigma: ${String.format("%.2f", params.sigma)}", 20.0, 70.0)
                drawer.text("L: ${String.format("%.1f", params.l)}", 20.0, 90.0)
                drawer.text("R: Reseed | 1-3: Presets | T: Tall-mode | B: Background | S/E: Export | D: Debug", 20.0, 110.0)
            }
        }
    }
}
