package drawing

import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.*
import org.openrndr.extra.noise.Random
import org.openrndr.math.Vector2
import org.openrndr.math.Vector3
import org.openrndr.math.Matrix44
import org.openrndr.math.transforms.transform
import org.openrndr.shape.*
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.*

enum class QuietPaletteMode { A, B, C }
enum class FigureType { PERSON, BIRD, VESSEL, TOWER, PLANT, ANIMAL }
enum class FinishMode { GRAIN, SHADOW, WOBBLE }

data class QuietParams(
    var seed: Long = (Math.random() * 1000000).toLong(),
    val width: Int = 600,
    val height: Int = 800,
    var paletteMode: QuietPaletteMode = QuietPaletteMode.A,
    var figureType: FigureType = FigureType.entries.random(),
    var numberOfSignals: Int = Random.int(1, 4),
    var numberOfFields: Int = Random.int(2, 5),
    var patternContrast: Double = 0.2, // 0.15..0.25
    var misregistrationPx: Double = 4.0, // 2..6
    var finishMode: FinishMode = FinishMode.GRAIN,
    var debugMode: Boolean = false
)

class QuietPalette(
    val background: ColorRGBa,
    val baseColors: List<ColorRGBa>,
    val neutralColors: List<ColorRGBa>,
    val accentColors: List<ColorRGBa>
)

fun getPalette(mode: QuietPaletteMode, seed: Long): QuietPalette {
    val rng = java.util.Random(seed)
    return when (mode) {
        QuietPaletteMode.A -> QuietPalette(
            background = ColorRGBa.fromHex("F5F5F0"), 
            baseColors = listOf(
                ColorRGBa.fromHex("D2D2CA"), 
                ColorRGBa.fromHex("B87D64"), 
                ColorRGBa.fromHex("7A9B9B")  
            ),
            neutralColors = listOf(ColorRGBa.fromHex("F5F5F0"), ColorRGBa.fromHex("E0E0D8")),
            accentColors = if (rng.nextBoolean()) {
                listOf(ColorRGBa.fromHex("FF7F50"), ColorRGBa.fromHex("FF4500")) 
            } else {
                listOf(ColorRGBa.fromHex("32CD32"), ColorRGBa.fromHex("ADFF2F")) 
            }
        )
        QuietPaletteMode.B -> QuietPalette(
            background = ColorRGBa.fromHex("F0F0F5"), 
            baseColors = listOf(
                ColorRGBa.fromHex("708090"), 
                ColorRGBa.fromHex("C2B280"), 
                ColorRGBa.fromHex("AF8FAF")  
            ),
            neutralColors = listOf(ColorRGBa.fromHex("F0F0F5"), ColorRGBa.fromHex("E5E5EA")),
            accentColors = listOf(ColorRGBa.fromHex("FF8C00"), ColorRGBa.fromHex("FF4500")) 
        )
        QuietPaletteMode.C -> QuietPalette(
            background = ColorRGBa.fromHex("F4ECD8"), 
            baseColors = listOf(
                ColorRGBa.fromHex("2F4F4F"), 
                ColorRGBa.fromHex("708090"), 
                ColorRGBa.fromHex("556B2F")  
            ),
            neutralColors = listOf(ColorRGBa.fromHex("F4ECD8"), ColorRGBa.fromHex("E8DFCC")),
            accentColors = listOf(ColorRGBa.fromHex("FFFF00"), ColorRGBa.fromHex("FFD700")) 
        )
    }
}

fun main() = application {
    val params = QuietParams()
    configure {
        width = params.width
        height = params.height
        title = "Quiet Geometry with Signal Pops"
    }

    program {
        val rt = renderTarget(params.width, params.height) {
            colorBuffer()
            depthBuffer()
        }

        fun drawScene(drawer: Drawer, params: QuietParams) {
            Random.seed = params.seed.toString()
            val palette = getPalette(params.paletteMode, params.seed)
            drawer.clear(palette.background)

            val grid = buildGrid(params.width.toDouble(), params.height.toDouble())

            val fields = makeBackgroundFields(params, palette)
            val figure = makeFigure(params, palette, grid)
            val signals = makeSignals(params, palette, grid)

            // Prepare all elements in draw order for intersection calculation
            val allElements = mutableListOf<StyledShape>()
            allElements.addAll(fields)
            allElements.addAll(figure.parts)
            val signalElements = signals.mapIndexed { i, signal ->
                val transformed = signal.shape.transform(transform {
                    translate(signal.position.x, signal.position.y)
                })
                StyledShape(transformed, signal.color, name = "Signal $i")
            }
            allElements.addAll(signalElements)

            val intersections = getOverlapIntersections(allElements, palette)

            fun drawElementIntersections(element: StyledShape) {
                intersections.filter { it.first == element }.forEach { (_, intersect, color) ->
                    drawer.isolated {
                        drawer.fill = color
                        drawer.stroke = null
                        drawer.shape(intersect)
                    }
                }
            }

            fields.forEach { field ->
                if (params.finishMode == FinishMode.SHADOW && field.hasPattern) {
                    drawSoftShadow(drawer, field.shape)
                }
                drawer.fill = field.color
                drawer.stroke = null
                drawer.shape(field.shape)
                
                if (field.hasPattern) {
                    drawPattern(drawer, field.shape, field.color, params)
                }

                drawElementIntersections(field)
            }

            drawer.isolated {
                drawer.stroke = null
                if (params.finishMode == FinishMode.SHADOW) {
                    val mainPart = figure.parts.maxByOrNull { it.shape.bounds.area }
                    if (mainPart != null) {
                        drawSoftShadow(drawer, mainPart.shape)
                    }
                }
                figure.parts.forEach { part ->
                    drawer.fill = part.color
                    drawer.shape(part.shape)
                }
                if (figure.hasPattern) {
                    val mainPart = figure.parts.maxByOrNull { it.shape.bounds.area }
                    if (mainPart != null) {
                        drawPattern(drawer, mainPart.shape, mainPart.color, params)
                    }
                }
                // Draw intersections for figure parts after pattern
                figure.parts.forEach { part ->
                    drawElementIntersections(part)
                }
            }

            signalElements.forEach { signalElement ->
                drawer.fill = signalElement.color
                drawer.stroke = null
                drawer.shape(signalElement.shape)
                drawElementIntersections(signalElement)
            }

            applyFinish(drawer, params)

            if (params.debugMode) {
                drawDebug(drawer, params, grid)
            }
        }

        fun redraw() {
            drawer.isolatedWithTarget(rt) {
                drawScene(drawer, params)
            }
        }

        redraw()

        keyboard.keyDown.listen {
            when (it.name) {
                "r" -> {
                    params.seed = (Math.random() * 1000000).toLong()
                    redraw()
                }
                "p" -> {
                    params.paletteMode = QuietPaletteMode.entries.toTypedArray()[(params.paletteMode.ordinal + 1) % QuietPaletteMode.entries.size]
                    redraw()
                }
                "f" -> {
                    params.figureType = FigureType.entries.toTypedArray()[(params.figureType.ordinal + 1) % FigureType.entries.size]
                    redraw()
                }
                "t" -> {
                    params.finishMode = FinishMode.entries.toTypedArray()[(params.finishMode.ordinal + 1) % FinishMode.entries.size]
                    redraw()
                }
                "e" -> {
                    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                    val fileName = "quiet_geometry_${params.seed}_$timestamp.png"
                    rt.colorBuffer(0).saveToFile(File(fileName))
                    println("Exported to $fileName")
                }
                "d" -> {
                    params.debugMode = !params.debugMode
                    redraw()
                }
                "[" -> {
                    params.numberOfSignals = (params.numberOfSignals - 1).coerceAtLeast(1)
                    params.numberOfFields = (params.numberOfFields - 1).coerceAtLeast(1)
                    redraw()
                }
                "]" -> {
                    params.numberOfSignals = (params.numberOfSignals + 1).coerceAtMost(10)
                    params.numberOfFields = (params.numberOfFields + 1).coerceAtMost(10)
                    redraw()
                }
            }
        }

        extend {
            drawer.image(rt.colorBuffer(0))
        }
    }
}

data class Grid(val cols: List<Double>, val rows: List<Double>, val diagonal: LineSegment)

fun buildGrid(width: Double, height: Double): Grid {
    val cols = listOf(0.0, width / 3.0, 2.0 * width / 3.0, width)
    val rows = listOf(0.0, height / 3.0, 2.0 * height / 3.0, height)
    val diagonal = LineSegment(0.0, 0.0, width, height)
    return Grid(cols, rows, diagonal)
}

data class StyledShape(val shape: Shape, val color: ColorRGBa, val hasPattern: Boolean = false, val name: String = "shape")

fun makeBackgroundFields(params: QuietParams, palette: QuietPalette): List<StyledShape> {
    val fields = mutableListOf<StyledShape>()
    val w = params.width.toDouble()
    val h = params.height.toDouble()
    
    val numFields = params.numberOfFields
    val baseColors = palette.baseColors.shuffled()
    
    repeat(numFields) { i ->
        val color = baseColors[i % baseColors.size]
        val typeIdx = Random.int(0, 5)
        val shape = when (typeIdx) {
            0 -> Rectangle(Random.double(0.0, w * 0.5), Random.double(0.0, h * 0.5), Random.double(w * 0.3, w * 0.7), Random.double(h * 0.3, h * 0.7)).shape
            1 -> {
                val p1 = Vector2(Random.double(0.0, w), Random.double(0.0, h))
                val p2 = Vector2(Random.double(0.0, w), Random.double(0.0, h))
                val p3 = Vector2(Random.double(0.0, w), Random.double(0.0, h))
                val p4 = Vector2(Random.double(0.0, w), Random.double(0.0, h))
                contour {
                    moveTo(p1); lineTo(p2); lineTo(p3); lineTo(p4); close()
                }.shape
            }
            2 -> Circle(Random.double(0.0, w), Random.double(0.0, h), Random.double(w * 0.2, w * 0.5)).shape
            3 -> { // Semicircle
                val r = Random.double(w * 0.2, w * 0.5)
                val x = Random.double(0.0, w)
                val y = Random.double(0.0, h)
                Circle(x, y, r).contour.sub(0.0, 0.5).shape
            }
            else -> { // Trapezoid
                val tw = Random.double(w * 0.3, w * 0.6)
                val tw2 = tw * Random.double(0.4, 0.9)
                val th = Random.double(h * 0.2, h * 0.5)
                val tx = Random.double(0.0, w - tw)
                val ty = Random.double(0.0, h - th)
                contour {
                    moveTo(tx, ty + th)
                    lineTo(tx + tw, ty + th)
                    lineTo(tx + tw - (tw - tw2) / 2.0, ty)
                    lineTo(tx + (tw - tw2) / 2.0, ty)
                    close()
                }.shape
            }
        }
        val nameStr = when (typeIdx) {
            0 -> "Rectangle Field"
            1 -> "Contour Field"
            2 -> "Circle Field"
            3 -> "Semicircle Field"
            else -> "Trapezoid Field"
        }
        fields.add(StyledShape(shape, color, i == 0, name = "$nameStr $i"))
    }
    return fields
}

class Figure(val parts: List<StyledShape>, val hasPattern: Boolean = false)

fun makeFigure(params: QuietParams, palette: QuietPalette, grid: Grid): Figure {
    val parts = mutableListOf<StyledShape>()
    val color = palette.baseColors.random()
    
    val gx = grid.cols[Random.int(1, 3)]
    val gy = grid.rows[Random.int(1, 3)]
    val center = Vector2(gx + Random.double(-20.0, 20.0), gy + Random.double(-20.0, 20.0))
    
    when (params.figureType) {
        FigureType.PERSON -> {
            val torsoW = Random.double(35.0, 45.0)
            val torsoH = Random.double(70.0, 90.0)
            val torso = roundedRectangle(Rectangle(center.x - torsoW/2, center.y - torsoH/2, torsoW, torsoH), torsoW/2).shape
            parts.add(StyledShape(torso, color, name = "Person Torso"))
            
            val headR = Random.double(12.0, 18.0)
            val head = Circle(center.x, center.y - torsoH/2 - headR - 5.0, headR).shape
            parts.add(StyledShape(head, color, name = "Person Head"))
            
            val numLimbs = Random.int(2, 5)
            repeat(numLimbs) {
                val angle = Random.double(0.0, 2 * PI)
                val len = Random.double(30.0, 50.0)
                val start = center + Vector2(Random.double(-torsoW/4, torsoW/4), Random.double(-torsoH/4, torsoH/4))
                val end = start + Vector2(cos(angle), sin(angle)) * len
                parts.add(StyledShape(LineSegment(start, end).shape, color, name = "Person Limb"))
            }
        }
        FigureType.BIRD -> {
            val bodySize = Random.double(50.0, 70.0)
            val p1 = center + Vector2(0.0, -bodySize/2)
            val p2 = center + Vector2(-bodySize/2, bodySize/2)
            val p3 = center + Vector2(bodySize/2, bodySize/2)
            val body = contour { moveTo(p1); lineTo(p2); lineTo(p3); close() }.shape
            parts.add(StyledShape(body, color, name = "Bird Body"))
            
            repeat(Random.int(1, 3)) { i ->
                val wingArc = Circle(center, bodySize * (0.6 + i * 0.2)).contour.sub(0.0, Random.double(0.15, 0.3)).shape
                parts.add(StyledShape(wingArc, color, name = "Bird Wing $i"))
            }
        }
        FigureType.VESSEL -> {
            val w1 = Random.double(50.0, 70.0)
            val w2 = w1 * Random.double(0.5, 0.8)
            val h = Random.double(60.0, 90.0)
            val p1 = center + Vector2(-w1/2, h/2)
            val p2 = center + Vector2(w1/2, h/2)
            val p3 = center + Vector2(w2/2, -h/2)
            val p4 = center + Vector2(-w2/2, -h/2)
            val body = contour { moveTo(p1); lineTo(p2); lineTo(p3); lineTo(p4); close() }.shape
            parts.add(StyledShape(body, color, name = "Vessel Body"))
            
            val rim = if (Random.bool()) Circle(center.x, center.y - h/2, w2/2).shape 
                       else Circle(center.x, center.y - h/2, w2/2).contour.sub(0.5, 1.0).shape
            parts.add(StyledShape(rim, color, name = "Vessel Rim"))
            
            if (Random.bool(0.3)) {
                val handle = Circle(center.x - w1/2, center.y, 15.0).contour.sub(0.25, 0.75).shape
                parts.add(StyledShape(handle, color, name = "Vessel Handle"))
            }
        }
        FigureType.TOWER -> {
            val baseW = Random.double(50.0, 70.0)
            val totalH = Random.double(120.0, 160.0)
            val layers = Random.int(2, 5)
            for (i in 0 until layers) {
                val layerW = baseW * (1.0 - i * 0.15)
                val layerH = totalH / layers
                val layerY = center.y + totalH / 2.0 - (i + 0.5) * layerH
                val rect = Rectangle(center.x - layerW / 2.0, layerY - layerH / 2.0, layerW, layerH).shape
                parts.add(StyledShape(rect, color, name = "Tower Layer $i"))
            }
        }
        FigureType.PLANT -> {
            val stemH = Random.double(80.0, 130.0)
            val stem = LineSegment(center.x, center.y + stemH / 2.0, center.x, center.y - stemH / 2.0).shape
            parts.add(StyledShape(stem, color, name = "Plant Stem"))
            
            val headR = Random.double(10.0, 20.0)
            val head = if (Random.bool()) Circle(center.x, center.y - stemH / 2.0 - headR, headR).shape 
                       else Circle(center.x, center.y - stemH / 2.0 - headR, headR).contour.sub(0.5, 1.0).shape
            parts.add(StyledShape(head, color, name = "Plant Head"))
            
            repeat(Random.int(1, 3)) { i ->
                val side = if (Random.bool()) -1.0 else 1.0
                val ly = center.y + Random.double(-stemH / 3.0, stemH / 3.0)
                val leaf = Circle(center.x + 15.0 * side, ly, 15.0).contour.sub(0.0, 0.25).shape
                parts.add(StyledShape(leaf, color, name = "Plant Leaf $i"))
            }
        }
        FigureType.ANIMAL -> {
            val bodyW = 60.0
            val bodyH = 35.0
            val body = roundedRectangle(Rectangle(center.x - bodyW/2.0, center.y - bodyH/2.0, bodyW, bodyH), 10.0).shape
            parts.add(StyledShape(body, color, name = "Animal Body"))
            
            repeat(4) { i ->
                val lx = center.x - bodyW/2.0 + 10.0 + i * (bodyW - 20.0)/3.0
                val leg = LineSegment(lx, center.y + bodyH/2.0, lx, center.y + bodyH/2.0 + 20.0).shape
                parts.add(StyledShape(leg, color, name = "Animal Leg $i"))
            }
            
            val headSize = 20.0
            val head = if (Random.bool()) Circle(center.x + bodyW/2.0 + 10.0, center.y - bodyH/2.0, 10.0).shape
                       else Rectangle(center.x + bodyW/2.0, center.y - bodyH/2.0 - 10.0, headSize, headSize).shape
            parts.add(StyledShape(head, color, name = "Animal Head"))
        }
    }
    return Figure(parts, true)
}

data class Signal(val shape: Shape, val color: ColorRGBa, val position: Vector2)

fun makeSignals(params: QuietParams, palette: QuietPalette, grid: Grid): List<Signal> {
    val signals = mutableListOf<Signal>()
    val accentColor = palette.accentColors.random()
    
    repeat(params.numberOfSignals) {
        val shape = when (Random.int(0, 4)) {
            0 -> Circle(0.0, 0.0, Random.double(30.0, 50.0)).shape
            1 -> {
                val s = 60.0
                contour { moveTo(0.0, 0.0); lineTo(s, s/2); lineTo(0.0, s); close() }.shape
            }
            2 -> Rectangle(-40.0, -40.0, 80.0, 80.0).shape
            else -> {
                val length = Random.double(80.0, 150.0)
                LineSegment(-length/2, 0.0, length/2, 0.0).shape
            }
        }
        
        val pos = if (Random.bool()) {
            val gx = grid.cols.random()
            val gy = grid.rows.random()
            Vector2(gx, gy)
        } else {
            grid.diagonal.position(Random.double(0.1, 0.9))
        }
        
        signals.add(Signal(shape, accentColor, pos))
    }
    return signals
}

fun drawPattern(drawer: Drawer, targetShape: Shape, baseColor: ColorRGBa, params: QuietParams) {
    drawer.isolated {
        // Fallback: clip to bounds
        drawer.drawStyle.clip = targetShape.bounds
        
        val patternColor = if (baseColor.luminance > 0.5) {
            baseColor.shade(1.0 - params.patternContrast)
        } else {
            baseColor.shade(1.0 + params.patternContrast)
        }
        
        drawer.fill = patternColor
        drawer.stroke = null
        
        val bounds = targetShape.bounds
        val type = Random.int(0, 5)
        
        drawer.translate(Random.double(-1.0, 1.0) * params.misregistrationPx, Random.double(-1.0, 1.0) * params.misregistrationPx)

        when (type) {
            0 -> { // Micro-dots
                val step = 12.0
                for (x in bounds.x.toInt()..(bounds.x + bounds.width).toInt() step step.toInt()) {
                    for (y in bounds.y.toInt()..(bounds.y + bounds.height).toInt() step step.toInt()) {
                        if (Random.bool(0.4)) {
                            drawer.circle(x.toDouble(), y.toDouble(), 1.2)
                        }
                    }
                }
            }
            1 -> { // Tonal repeats
                repeat(4) {
                    drawer.isolated {
                        drawer.translate(Random.double(-8.0, 8.0), Random.double(-8.0, 8.0))
                        drawer.shape(targetShape)
                    }
                }
            }
            2 -> { // Nested echoes
                drawer.isolated {
                    drawer.translate(bounds.center)
                    repeat(3) {
                        drawer.rotate(Random.double(1.0, 3.0))
                        drawer.scale(0.98)
                        drawer.isolated {
                            drawer.translate(-bounds.center)
                            drawer.shape(targetShape)
                        }
                    }
                }
            }
            3 -> { // Dashes
                val step = 15.0
                drawer.strokeWeight = 1.0
                for (x in bounds.x.toInt()..(bounds.x + bounds.width).toInt() step step.toInt()) {
                    for (y in bounds.y.toInt()..(bounds.y + bounds.height).toInt() step step.toInt()) {
                        if (Random.bool(0.5)) {
                            drawer.lineSegment(x.toDouble(), y.toDouble(), x.toDouble() + 5.0, y.toDouble())
                        }
                    }
                }
            }
            4 -> { // Hatching
                val step = 10.0
                drawer.strokeWeight = 0.5
                for (i in -100..100) {
                    val offset = i * step
                    drawer.lineSegment(
                        bounds.x + offset, bounds.y,
                        bounds.x + offset + bounds.height * 0.5, bounds.y + bounds.height
                    )
                }
            }
        }
        drawer.drawStyle.clip = null
    }
}

fun applyFinish(drawer: Drawer, params: QuietParams) {
    when (params.finishMode) {
        FinishMode.GRAIN -> {
            drawer.isolated {
                drawer.stroke = null
                repeat(10000) {
                    drawer.fill = ColorRGBa.BLACK.opacify(Random.double(0.0, 0.03))
                    drawer.point(Random.double(0.0, params.width.toDouble()), Random.double(0.0, params.height.toDouble()))
                }
            }
        }
        FinishMode.SHADOW -> {
        }
        FinishMode.WOBBLE -> {
        }
    }
}

fun drawDebug(drawer: Drawer, params: QuietParams, grid: Grid) {
    drawer.isolated {
        drawer.stroke = ColorRGBa.CYAN.opacify(0.5)
        drawer.strokeWeight = 1.0
        grid.cols.forEach { drawer.lineSegment(it, 0.0, it, params.height.toDouble()) }
        grid.rows.forEach { drawer.lineSegment(0.0, it, params.width.toDouble(), it) }
        drawer.lineSegment(grid.diagonal)
        
        drawer.fill = ColorRGBa.CYAN
        drawer.stroke = null
        drawer.text("Signals: ${params.numberOfSignals}", 20.0, 30.0)
        drawer.text("Fields: ${params.numberOfFields}", 20.0, 50.0)
        drawer.text("Seed: ${params.seed}", 20.0, 70.0)
    }
}

fun drawSoftShadow(drawer: Drawer, shape: Shape) {
    repeat(5) { i ->
        drawer.isolated {
            drawer.fill = ColorRGBa.BLACK.opacify(0.02)
            drawer.stroke = null
            drawer.translate(2.0 * (i + 1), 2.0 * (i + 1))
            drawer.shape(shape)
        }
    }
}

fun roundedRectangle(rect: Rectangle, radius: Double): ShapeContour {
    val r = minOf(radius, rect.width / 2, rect.height / 2)
    val x = rect.x
    val y = rect.y
    val w = rect.width
    val h = rect.height

    return contour {
        moveTo(x + r, y)
        if (w > 2 * r) lineTo(x + w - r, y)
        arcTo(r, r, 0.0, false, true, x + w, y + r)
        if (h > 2 * r) lineTo(x + w, y + h - r)
        arcTo(r, r, 0.0, false, true, x + w - r, y + h)
        if (w > 2 * r) lineTo(x + r, y + h)
        arcTo(r, r, 0.0, false, true, x, y + h - r)
        if (h > 2 * r) lineTo(x, y + r)
        arcTo(r, r, 0.0, false, true, x + r, y)
        close()
    }
}

fun shapesOverlap(shape1: Shape, shape2: Shape): Boolean {
    if (shape1.contours.isEmpty() || shape2.contours.isEmpty()) return false
    if (!shape1.bounds.intersects(shape2.bounds)) return false

    try {
        for (c1 in shape1.contours) {
            for (c2 in shape2.contours) {
                if (c1.intersections(c2).isNotEmpty()) return true
            }
        }

        // Containment check
        for (c in shape1.contours) {
            if (shape2.contains(c.position(0.5))) return true
        }
        for (c in shape2.contours) {
            if (shape1.contains(c.position(0.5))) return true
        }
    } catch (e: Exception) {
        println("Warning: shapesOverlap failed for a pair of shapes: ${e.message}")
        return false
    }

    return false
}

fun getOverlapIntersections(allElements: List<StyledShape>, palette: QuietPalette): List<Triple<StyledShape, Shape, ColorRGBa>> {
    val intersections = mutableListOf<Triple<StyledShape, Shape, ColorRGBa>>()
    val allColors = (palette.baseColors + palette.accentColors).distinct()

    for (i in 0 until allElements.size) {
        for (j in i + 1 until allElements.size) {
            val s1 = allElements[i]
            val s2 = allElements[j]

            if (s1.color == s2.color) {
                if (shapesOverlap(s1.shape, s2.shape)) {
                    println("Overlap detected: '${s1.name}' and '${s2.name}' with color ${s1.color}")
                    try {
                        val intersect = intersection(s1.shape, s2.shape)
                        if (intersect.contours.isNotEmpty()) {
                            val candidateColors = allColors.filter { it != s1.color }
                            val intersectColor = if (candidateColors.isNotEmpty()) Random.pick(candidateColors) else Random.pick(palette.accentColors)
                            intersections.add(Triple(s2, intersect, intersectColor))
                        }
                    } catch (e: Exception) {
                        println("Failed to compute intersection for ${s1.name} and ${s2.name}")
                    }
                }
            }
        }
    }
    return intersections
}
