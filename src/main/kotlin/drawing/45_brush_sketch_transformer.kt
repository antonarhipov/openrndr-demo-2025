package drawing

import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.color.ColorHSVa
import org.openrndr.draw.*
import org.openrndr.extra.noise.Random
import org.openrndr.extra.parameters.*
import org.openrndr.math.Vector2
import org.openrndr.math.map
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.*

class BrushSketchParams {
    @TextParameter("Input Path")
    var inputPath: String = "data/images/cheeta.jpg"

    @IntParameter("Seed", 0, 1000000)
    var seed: Int = 12345

    @DoubleParameter("Edge Threshold", 0.0, 2.0)
    var edgeThreshold: Double = 0.2

    @IntParameter("Stroke Count Edges", 100, 20000)
    var strokeCountEdges: Int = 5000

    @IntParameter("Stroke Count Tone", 100, 100000)
    var strokeCountTone: Int = 20000

    @DoubleParameter("Min Stroke Len", 1.0, 100.0)
    var minStrokeLen: Double = 5.0

    @DoubleParameter("Max Stroke Len", 1.0, 200.0)
    var maxStrokeLen: Double = 30.0

    @DoubleParameter("Min Width", 0.1, 10.0)
    var minWidth: Double = 0.5

    @DoubleParameter("Max Width", 0.1, 20.0)
    var maxWidth: Double = 3.0

    @DoubleParameter("Jitter Amount", 0.0, 10.0)
    var jitterAmount: Double = 1.0

    @DoubleParameter("Hatch Angle", 0.0, 360.0)
    var hatchAngle: Double = 45.0

    @IntParameter("Style Mode (1:Ink, 2:Charcoal, 3:Pencil)", 1, 3)
    var styleMode: Int = 1

    @BooleanParameter("Wash Enabled")
    var washEnabled: Boolean = true

    @BooleanParameter("Debug Mode")
    var debugMode: Boolean = false
}

data class SketchStroke(
    val points: List<Vector2>,
    val weights: List<Double>,
    val opacities: List<Double>,
    val color: ColorRGBa? = null
)

fun main() = application {
    configure {
        width = 1000
        height = 800
        title = "Brush-Sketch Transformer"
    }

    program {
        val params = BrushSketchParams()
        var inputImage: ColorBuffer? = null
        var luminanceShadow: ColorBufferShadow? = null
        var gradientShadow: Array<Vector2>? = null
        
        var edgeStrokes = listOf<SketchStroke>()
        var toneStrokes = listOf<SketchStroke>()
        
        var renderTarget: RenderTarget? = null

        fun loadAndProcessImage() {
            val file = File(params.inputPath)
            if (!file.exists()) {
                println("File not found: ${params.inputPath}")
                return
            }
            val img = loadImage(file)
            inputImage = img
            
            val shadow = img.shadow
            shadow.download()
            
            val lum = colorBuffer(img.width, img.height, type = ColorType.FLOAT32)
            val lumShadow = lum.shadow
            for (y in 0 until img.height) {
                for (x in 0 until img.width) {
                    val c = shadow[x, y]
                    val l = c.luminance
                    lumShadow[x, y] = ColorRGBa(l, l, l, 1.0)
                }
            }
            lumShadow.upload()
            luminanceShadow = lumShadow
            
            // Sobel Gradient
            val grads = Array(img.width * img.height) { Vector2.ZERO }
            for (y in 1 until img.height - 1) {
                for (x in 1 until img.width - 1) {
                    val gX = (lumShadow[x + 1, y - 1].r + 2 * lumShadow[x + 1, y].r + lumShadow[x + 1, y + 1].r) -
                             (lumShadow[x - 1, y - 1].r + 2 * lumShadow[x - 1, y].r + lumShadow[x - 1, y + 1].r)
                    val gY = (lumShadow[x - 1, y + 1].r + 2 * lumShadow[x, y + 1].r + lumShadow[x + 1, y + 1].r) -
                             (lumShadow[x - 1, y - 1].r + 2 * lumShadow[x, y - 1].r + lumShadow[x + 1, y - 1].r)
                    grads[x + y * img.width] = Vector2(gX, gY)
                }
            }
            gradientShadow = grads
            
            renderTarget = renderTarget(img.width, img.height) {
                colorBuffer()
            }
        }

        fun generateStrokes() {
            val img = inputImage ?: return
            val grads = gradientShadow ?: return
            val lum = luminanceShadow ?: return
            val shadow = img.shadow // for color sampling
            val rng = java.util.Random(params.seed.toLong())
            
            val newEdgeStrokes = mutableListOf<SketchStroke>()
            val newToneStrokes = mutableListOf<SketchStroke>()
            
            // Pass A: Contours
            repeat(params.strokeCountEdges) {
                val x = rng.nextInt(img.width)
                val y = rng.nextInt(img.height)
                val grad = grads[x + y * img.width]
                val strength = grad.length
                
                if (strength > params.edgeThreshold) {
                    val points = mutableListOf<Vector2>()
                    val weights = mutableListOf<Double>()
                    var currP = Vector2(x.toDouble(), y.toDouble())
                    
                    val maxLen = rng.nextDouble() * (params.maxStrokeLen - params.minStrokeLen) + params.minStrokeLen
                    val len = maxLen * map(0.0, 1.5, 0.5, 2.0, strength.coerceIn(0.0, 1.5))
                    val steps = 12
                    val stepSize = len / steps
                    
                    var strokeColor: ColorRGBa? = null
                    if (params.styleMode == 3) {
                        val hsv = shadow[x, y].toHSVa()
                        strokeColor = ColorHSVa(hsv.h, 0.4, 0.3).toRGBa()
                    }
                    
                    for (i in 0..steps) {
                        if (currP.x < 0 || currP.x >= img.width || currP.y < 0 || currP.y >= img.height) break
                        points.add(currP)
                        
                        val t = i.toDouble() / steps
                        val taper = sin(t * PI)
                        val w = taper * params.maxWidth * map(0.0, 1.5, 0.5, 1.5, strength.coerceIn(0.0, 1.5))
                        weights.add(max(params.minWidth, w))
                        
                        val ix = currP.x.toInt().coerceIn(0, img.width - 1)
                        val iy = currP.y.toInt().coerceIn(0, img.height - 1)
                        val g = grads[ix + iy * img.width]
                        
                        // Follow isophote (perpendicular to gradient)
                        var dir = Vector2(-g.y, g.x).normalized
                        if (dir.length < 0.1) dir = Vector2(1.0, 0.0) // Fallback
                        
                        val modeJitter = if (params.styleMode == 2) 1.5 else 1.0
                        currP += dir * stepSize + Vector2(rng.nextGaussian(), rng.nextGaussian()) * params.jitterAmount * modeJitter
                    }
                    if (points.size > 1) {
                        val strokeOpacity = 0.8
                        val opacities = points.indices.map { i ->
                            val t = i.toDouble() / (points.size - 1)
                            sin(t * PI) * strokeOpacity
                        }
                        newEdgeStrokes.add(SketchStroke(points, weights, opacities, strokeColor))
                    }
                }
            }
            
            // Pass B: Tone
            repeat(params.strokeCountTone) {
                val x = rng.nextInt(img.width)
                val y = rng.nextInt(img.height)
                val l = lum[x, y].r
                
                // Darker regions -> more strokes
                if (rng.nextDouble() > l) {
                    val points = mutableListOf<Vector2>()
                    val weights = mutableListOf<Double>()
                    var currP = Vector2(x.toDouble(), y.toDouble())
                    
                    val angle = Math.toRadians(params.hatchAngle) + rng.nextGaussian() * 0.15
                    val dir = Vector2(cos(angle), sin(angle))
                    
                    val len = (rng.nextDouble() * (params.maxStrokeLen - params.minStrokeLen) + params.minStrokeLen) * (1.1 - l)
                    val steps = 8
                    val stepSize = len / steps
                    
                    var strokeColor: ColorRGBa? = null
                    if (params.styleMode == 3) {
                        val hsv = shadow[x, y].toHSVa()
                        strokeColor = ColorHSVa(hsv.h, 0.3, 0.4).toRGBa()
                    }
                    
                    for (i in 0..steps) {
                        if (currP.x < 0 || currP.x >= img.width || currP.y < 0 || currP.y >= img.height) break
                        points.add(currP)
                        val t = i.toDouble() / steps
                        val w = sin(t * PI) * params.maxWidth * 0.4
                        weights.add(max(params.minWidth, w))
                        val modeJitter = if (params.styleMode == 2) 1.5 else 1.0
                        currP += dir * stepSize + Vector2(rng.nextGaussian(), rng.nextGaussian()) * params.jitterAmount * 0.3 * modeJitter
                    }
                    if (points.size > 1) {
                        val strokeOpacity = (1.0 - l) * 0.6
                        val opacities = points.indices.map { i ->
                            val t = i.toDouble() / (points.size - 1)
                            sin(t * PI) * strokeOpacity
                        }
                        newToneStrokes.add(SketchStroke(points, weights, opacities, strokeColor))
                    }
                }
            }
            
            edgeStrokes = newEdgeStrokes
            toneStrokes = newToneStrokes
        }

        fun drawToRT() {
            val rt = renderTarget ?: return
            val img = inputImage ?: return
            
            drawer.isolatedWithTarget(rt) {
                val bgColor = when(params.styleMode) {
                    1 -> ColorRGBa.fromHex("FDF5E6") // Old Lace (warm paper)
                    2 -> ColorRGBa.fromHex("E0E0E0") // Light charcoal gray
                    3 -> ColorRGBa.fromHex("FCFCFC") // White paper
                    else -> ColorRGBa.WHITE
                }
                
                val baseStrokeColor = when(params.styleMode) {
                    1 -> ColorRGBa.fromHex("1A1A1A") // Ink
                    2 -> ColorRGBa.fromHex("333333") // Charcoal
                    3 -> ColorRGBa.BLACK // Will be overridden if color is provided
                    else -> ColorRGBa.BLACK
                }

                clear(bgColor)
                
                // Pass C: Wash
                if (params.washEnabled) {
                    drawStyle.blendMode = BlendMode.MULTIPLY
                    drawStyle.colorMatrix = tint(ColorRGBa.WHITE.opacify(0.15))
                    image(img)
                    drawStyle.colorMatrix = tint(ColorRGBa.WHITE)
                    drawStyle.blendMode = BlendMode.BLEND
                }

                lineCap = LineCap.ROUND
                
                fun renderStroke(s: SketchStroke) {
                    val c = s.color ?: baseStrokeColor
                    fill = null
                    for (i in 0 until s.points.size - 1) {
                        stroke = c.opacify(s.opacities[i])
                        strokeWeight = s.weights[i]
                        lineSegment(s.points[i], s.points[i+1])
                    }
                }

                // Tone Pass
                toneStrokes.forEach { renderStroke(it) }
                
                // Edge Pass (slightly darker/stronger)
                edgeStrokes.forEach { 
                    val stronger = if (it.color != null) it.color.shade(0.7) else baseStrokeColor.shade(0.5)
                    renderStroke(it.copy(color = stronger)) 
                }
            }
        }

        loadAndProcessImage()
        generateStrokes()
        drawToRT()

        extend {
            val rt = renderTarget ?: return@extend
            val img = inputImage ?: return@extend

            // Draw RT to screen (preserve aspect ratio)
            val windowAspect = width.toDouble() / height.toDouble()
            val imageAspect = img.width.toDouble() / img.height.toDouble()
            
            val targetRect = if (imageAspect > windowAspect) {
                val h = width.toDouble() / imageAspect
                org.openrndr.shape.Rectangle(0.0, (height - h) / 2.0, width.toDouble(), h)
            } else {
                val w = height.toDouble() * imageAspect
                org.openrndr.shape.Rectangle((width - w) / 2.0, 0.0, w, height.toDouble())
            }
            
            drawer.image(rt.colorBuffer(0), rt.colorBuffer(0).bounds, targetRect)
            
            // UI Overlay (simple)
            drawer.fill = ColorRGBa.BLACK.opacify(0.5)
            drawer.stroke = null
            drawer.rectangle(10.0, 10.0, 350.0, 160.0)
            drawer.fill = ColorRGBa.WHITE
            drawer.text("Brush-Sketch Transformer", 20.0, 30.0)
            drawer.text("Style: ${when(params.styleMode){1->"Ink";2->"Charcoal";3->"Pencil";else->""}}", 20.0, 50.0)
            drawer.text("Seed: ${params.seed} | Strokes: ${edgeStrokes.size + toneStrokes.size}", 20.0, 70.0)
            drawer.text("R: Reseed | 1/2/3: Styles | W: Toggle Wash", 20.0, 90.0)
            drawer.text("E: Export PNG | D: Debug", 20.0, 110.0)
            drawer.text("Path: ${params.inputPath}", 20.0, 130.0)
            
            if (params.debugMode) {
                // Show original image small
                drawer.image(img, width - 210.0, 10.0, 200.0, 200.0 / imageAspect)
                
                // Show gradient field samples
                drawer.stroke = ColorRGBa.RED
                drawer.strokeWeight = 1.0
                gradientShadow?.let { grads ->
                    for (y in 0 until img.height step 40) {
                        for (x in 0 until img.width step 40) {
                            val g = grads[x + y * img.width]
                            if (g.length > 0.01) {
                                // Draw something simple to show it works
                            }
                        }
                    }
                }
                drawer.fill = ColorRGBa.RED
                drawer.text("DEBUG: Gradient field computed", 20.0, 150.0)
            }
        }
        
        keyboard.keyDown.listen {
            when (it.name) {
                "r" -> {
                    params.seed = (Math.random() * 1000000).toInt()
                    generateStrokes()
                    drawToRT()
                }
                "1" -> { params.styleMode = 1; drawToRT() }
                "2" -> { params.styleMode = 2; drawToRT() }
                "3" -> { params.styleMode = 3; drawToRT() }
                "w" -> { params.washEnabled = !params.washEnabled; drawToRT() }
                "d" -> { params.debugMode = !params.debugMode }
                "l" -> {
                    loadAndProcessImage()
                    generateStrokes()
                    drawToRT()
                }
                "e" -> {
                    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                    val filename = "brush_sketch_${params.seed}_$timestamp.png"
                    renderTarget?.colorBuffer(0)?.saveToFile(File("images/$filename"))
                    println("Exported to images/$filename")
                }
            }
        }
    }
}
