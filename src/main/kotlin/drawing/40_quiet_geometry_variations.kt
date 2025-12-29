package drawing

import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.draw.LineCap
import org.openrndr.draw.isolated
import org.openrndr.draw.isolatedWithTarget
import org.openrndr.draw.renderTarget
import org.openrndr.extra.noise.Random
import org.openrndr.extra.noise.simplex
import org.openrndr.math.Vector2
import org.openrndr.math.transforms.transform
import org.openrndr.shape.*
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.*

enum class VaryPaletteMode { A, B, C, D, E }
enum class VaryFigureType { PERSON, BIRD, VESSEL, TOWER, PLANT, ANIMAL, ARCHITECTURE, TOTEM, MASK }
enum class VaryFinishMode { GRAIN, SHADOW, WOBBLE, PAPER_TEXTURE }
enum class CompositionStrategy { GRID, RADIAL, DIAGONAL, CLUSTER, FLOW, SPIRAL }
enum class IntersectionRule { COLOR_SHIFT, SUBTRACTIVE, PATTERNED, DASHED_OUTLINE, MIXED }

data class VaryParams(
    var seed: Long = (Math.random() * 1000000).toLong(),
    val width: Int = 800,
    val height: Int = 800,
    var paletteMode: VaryPaletteMode = VaryPaletteMode.entries.random(),
    var figureType: VaryFigureType = VaryFigureType.entries.random(),
    var compositionStrategy: CompositionStrategy = CompositionStrategy.entries.random(),
    var intersectionRule: IntersectionRule = IntersectionRule.entries.random(),
    var numberOfSignals: Int = Random.int(3, 8),
    var numberOfFields: Int = Random.int(4, 9),
    var patternContrast: Double = 0.25,
    var misregistrationPx: Double = 5.0,
    var finishMode: VaryFinishMode = VaryFinishMode.GRAIN,
    var debugMode: Boolean = false
)

class VaryPalette(
    val background: ColorRGBa,
    val baseColors: List<ColorRGBa>,
    val neutralColors: List<ColorRGBa>,
    val accentColors: List<ColorRGBa>
)

fun getVaryPalette(mode: VaryPaletteMode, seed: Long): VaryPalette {
    val rng = java.util.Random(seed)
    return when (mode) {
        VaryPaletteMode.A -> VaryPalette(
            background = ColorRGBa.fromHex("F5F5F0"),
            baseColors = listOf(ColorRGBa.fromHex("D2D2CA"), ColorRGBa.fromHex("B87D64"), ColorRGBa.fromHex("7A9B9B")),
            neutralColors = listOf(ColorRGBa.fromHex("F5F5F0"), ColorRGBa.fromHex("E0E0D8")),
            accentColors = listOf(ColorRGBa.fromHex("FF7F50"), ColorRGBa.fromHex("FF4500"))
        )
        VaryPaletteMode.B -> VaryPalette(
            background = ColorRGBa.fromHex("F0F0F5"),
            baseColors = listOf(ColorRGBa.fromHex("708090"), ColorRGBa.fromHex("C2B280"), ColorRGBa.fromHex("AF8FAF")),
            neutralColors = listOf(ColorRGBa.fromHex("F0F0F5"), ColorRGBa.fromHex("E5E5EA")),
            accentColors = listOf(ColorRGBa.fromHex("FF8C00"), ColorRGBa.fromHex("FF4500"))
        )
        VaryPaletteMode.C -> VaryPalette(
            background = ColorRGBa.fromHex("F4ECD8"),
            baseColors = listOf(ColorRGBa.fromHex("2F4F4F"), ColorRGBa.fromHex("708090"), ColorRGBa.fromHex("556B2F")),
            neutralColors = listOf(ColorRGBa.fromHex("F4ECD8"), ColorRGBa.fromHex("E8DFCC")),
            accentColors = listOf(ColorRGBa.fromHex("FFFF00"), ColorRGBa.fromHex("FFD700"))
        )
        VaryPaletteMode.D -> VaryPalette( // Earthy/Terracotta
            background = ColorRGBa.fromHex("FAF3E0"),
            baseColors = listOf(ColorRGBa.fromHex("E2725B"), ColorRGBa.fromHex("8E443D"), ColorRGBa.fromHex("C2B280")),
            neutralColors = listOf(ColorRGBa.fromHex("FAF3E0"), ColorRGBa.fromHex("D4A373")),
            accentColors = listOf(ColorRGBa.fromHex("264653"), ColorRGBa.fromHex("2A9D8F"))
        )
        VaryPaletteMode.E -> VaryPalette( // Deep forest
            background = ColorRGBa.fromHex("1A2F1A"),
            baseColors = listOf(ColorRGBa.fromHex("2D5A27"), ColorRGBa.fromHex("4B7F52"), ColorRGBa.fromHex("1B3022")),
            neutralColors = listOf(ColorRGBa.fromHex("1A2F1A"), ColorRGBa.fromHex("0D1A0D")),
            accentColors = listOf(ColorRGBa.fromHex("FFD700"), ColorRGBa.fromHex("FF6347"))
        )
    }
}

data class VaryStyledShape(
    val shape: Shape,
    val color: ColorRGBa,
    val hasPattern: Boolean = false,
    val name: String = "shape",
    val ruleOverride: IntersectionRule? = null
)

fun main() = application {
    val params = VaryParams()
    configure {
        width = params.width
        height = params.height
        title = "Quiet Geometry Variations"
    }

    program {
        val rt = renderTarget(params.width, params.height) {
            colorBuffer()
            depthBuffer()
        }

        fun drawScene(drawer: Drawer, params: VaryParams) {
            Random.seed = params.seed.toString()
            val palette = getVaryPalette(params.paletteMode, params.seed)
            drawer.clear(palette.background)

            val layout = buildLayout(params)

            val fields = makeBackgroundFields(params, palette, layout)
            val figure = makeFigure(params, palette, layout)
            val signals = makeSignals(params, palette, layout)

            val allElements = mutableListOf<VaryStyledShape>()
            allElements.addAll(fields)
            allElements.addAll(figure.parts)
            val signalElements = signals.mapIndexed { i, signal ->
                val transformed = signal.shape.transform(transform {
                    translate(signal.position.x, signal.position.y)
                })
                VaryStyledShape(transformed, signal.color, name = "Signal $i")
            }
            allElements.addAll(signalElements)

            val intersections = getVaryOverlapIntersections(allElements, palette, params.intersectionRule)

            fun drawElement(element: VaryStyledShape) {
                if (params.finishMode == VaryFinishMode.SHADOW && element.hasPattern) {
                    drawVarySoftShadow(drawer, element.shape)
                }
                drawer.fill = element.color
                drawer.stroke = null
                drawer.shape(element.shape)

                if (element.hasPattern) {
                    drawPattern(drawer, element.shape, element.color, params)
                }
            }

            // Draw fields
            fields.forEach { drawElement(it) }

            // Draw figure
            drawer.isolated {
                if (params.finishMode == VaryFinishMode.SHADOW) {
                    figure.parts.maxByOrNull { it.shape.bounds.area }?.let { drawVarySoftShadow(drawer, it.shape) }
                }
                figure.parts.forEach { part ->
                    drawer.fill = part.color
                    drawer.stroke = null
                    drawer.shape(part.shape)
                }
                if (figure.hasPattern) {
                    figure.parts.maxByOrNull { it.shape.bounds.area }?.let {
                        drawPattern(drawer, it.shape, it.color, params)
                    }
                }
            }

            // Draw signals
            signalElements.forEach { drawElement(it) }

            // Draw intersections
            intersections.forEach { (shape, color, rule) ->
                drawer.isolated {
                    when (rule) {
                        IntersectionRule.COLOR_SHIFT -> {
                            drawer.fill = color
                            drawer.stroke = null
                            drawer.shape(shape)
                        }
                        IntersectionRule.SUBTRACTIVE -> {
                            drawer.fill = palette.background
                            drawer.stroke = null
                            drawer.shape(shape)
                        }
                        IntersectionRule.PATTERNED -> {
                            drawer.drawStyle.clip = shape.bounds
                            drawPattern(drawer, shape, color, params, intensity = 1.5)
                            drawer.drawStyle.clip = null
                        }
                        IntersectionRule.DASHED_OUTLINE -> {
                            drawer.fill = null
                            drawer.stroke = color
                            drawer.strokeWeight = 2.0
                            drawer.lineCap = org.openrndr.draw.LineCap.ROUND
                            drawer.shape(shape)
                        }
                        IntersectionRule.MIXED -> {
                            drawer.fill = color.opacify(0.5)
                            drawer.stroke = color
                            drawer.strokeWeight = 1.0
                            drawer.shape(shape)
                            drawer.drawStyle.clip = shape.bounds
                            drawPattern(drawer, shape, color, params, intensity = 0.5)
                            drawer.drawStyle.clip = null
                        }
                    }
                }
            }

            applyFinish(drawer, params)

            if (params.debugMode) {
                drawDebug(drawer, params, layout)
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
                "r" -> { params.seed = (Math.random() * 1000000).toLong(); redraw() }
                "p" -> { params.paletteMode = VaryPaletteMode.entries[(params.paletteMode.ordinal + 1) % VaryPaletteMode.entries.size]; redraw() }
                "f" -> { params.figureType = VaryFigureType.entries[(params.figureType.ordinal + 1) % VaryFigureType.entries.size]; redraw() }
                "c" -> { params.compositionStrategy = CompositionStrategy.entries[(params.compositionStrategy.ordinal + 1) % CompositionStrategy.entries.size]; redraw() }
                "i" -> { params.intersectionRule = IntersectionRule.entries[(params.intersectionRule.ordinal + 1) % IntersectionRule.entries.size]; redraw() }
                "t" -> { params.finishMode = VaryFinishMode.entries[(params.finishMode.ordinal + 1) % VaryFinishMode.entries.size]; redraw() }
                "e" -> {
                    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                    val fileName = "quiet_variations_${params.seed}_$timestamp.png"
                    rt.colorBuffer(0).saveToFile(File(fileName))
                    println("Exported to $fileName")
                }
                "d" -> { params.debugMode = !params.debugMode; redraw() }
                "[" -> { params.numberOfSignals = (params.numberOfSignals - 1).coerceAtLeast(1); redraw() }
                "]" -> { params.numberOfSignals = (params.numberOfSignals + 1).coerceAtMost(20); redraw() }
            }
        }

        extend {
            drawer.image(rt.colorBuffer(0))
        }
    }
}

data class Layout(
    val points: List<Vector2>,
    val mainAxis: LineSegment?,
    val center: Vector2,
    val bounds: Rectangle
)

fun buildLayout(params: VaryParams): Layout {
    val w = params.width.toDouble()
    val h = params.height.toDouble()
    val points = mutableListOf<Vector2>()
    var mainAxis: LineSegment? = null
    val center = Vector2(w / 2, h / 2)

    when (params.compositionStrategy) {
        CompositionStrategy.GRID -> {
            val cols = 4
            val rows = 4
            for (i in 1 until cols) {
                for (j in 1 until rows) {
                    points.add(Vector2(i * w / cols, j * h / rows))
                }
            }
        }
        CompositionStrategy.RADIAL -> {
            val count = 8
            val radius = min(w, h) * 0.3
            for (i in 0 until count) {
                val angle = i * 2.0 * PI / count
                points.add(center + Vector2(cos(angle), sin(angle)) * radius)
            }
        }
        CompositionStrategy.DIAGONAL -> {
            mainAxis = LineSegment(w * 0.1, h * 0.1, w * 0.9, h * 0.9)
            for (i in 1..5) {
                points.add(mainAxis.position(i / 6.0))
            }
        }
        CompositionStrategy.CLUSTER -> {
            val clusterCenter = Vector2(Random.double(w * 0.3, w * 0.7), Random.double(h * 0.3, h * 0.7))
            repeat(10) {
                points.add(clusterCenter + Vector2(Random.gaussian(0.0, 100.0), Random.gaussian(0.0, 100.0)))
            }
        }
        CompositionStrategy.FLOW -> {
            var curr = Vector2(Random.double(0.0, w), Random.double(0.0, h))
            repeat(8) {
                points.add(curr)
                val angle = simplex(params.seed.toInt(), curr.x * 0.002, curr.y * 0.002) * PI * 2.0
                curr += Vector2(cos(angle), sin(angle)) * 150.0
            }
        }
        CompositionStrategy.SPIRAL -> {
            var radius = 20.0
            var angle = 0.0
            repeat(15) {
                points.add(center + Vector2(cos(angle), sin(angle)) * radius)
                radius += 30.0
                angle += 0.5
            }
        }
    }

    return Layout(points, mainAxis, center, Rectangle(0.0, 0.0, w, h))
}

fun makeBackgroundFields(params: VaryParams, palette: VaryPalette, layout: Layout): List<VaryStyledShape> {
    val fields = mutableListOf<VaryStyledShape>()
    val w = params.width.toDouble()
    val h = params.height.toDouble()
    val baseColors = palette.baseColors.shuffled()

    repeat(params.numberOfFields) { i ->
        val color = baseColors[i % baseColors.size]
        val typeIdx = Random.int(0, 6)
        val shape = when (typeIdx) {
            0 -> Rectangle(Random.double(0.0, w * 0.4), Random.double(0.0, h * 0.4), Random.double(w * 0.4, w * 0.8), Random.double(h * 0.4, h * 0.8)).shape
            1 -> {
                val center = if (layout.points.isNotEmpty()) layout.points.random() else Vector2(w/2, h/2)
                val radius = Random.double(w * 0.15, w * 0.4)
                Circle(center, radius).shape
            }
            2 -> { // Blob approximation
                val center = if (layout.points.isNotEmpty()) layout.points.random() else Vector2(w/2, h/2)
                val radius = Random.double(100.0, 250.0)
                val pts = (0 until 8).map {
                    val a = it * PI * 2.0 / 8.0
                    val r = radius * Random.double(0.6, 1.2)
                    center + Vector2(cos(a), sin(a)) * r
                }
                contour {
                    moveTo(pts.last())
                    pts.forEach { lineTo(it) } 
                    close()
                }.shape
            }
            3 -> { // Semicircle or Arc
                val center = if (layout.points.isNotEmpty()) layout.points.random() else Vector2(w/2, h/2)
                val r = Random.double(w * 0.2, w * 0.5)
                Circle(center, r).contour.sub(Random.double(0.0, 0.4), Random.double(0.6, 1.0)).shape
            }
            4 -> { // Large Triangle
                val p1 = Vector2(Random.double(0.0, w), Random.double(0.0, h))
                val p2 = Vector2(Random.double(0.0, w), Random.double(0.0, h))
                val p3 = Vector2(Random.double(0.0, w), Random.double(0.0, h))
                contour { moveTo(p1); lineTo(p2); lineTo(p3); close() }.shape
            }
            5 -> { // Ring
                val center = if (layout.points.isNotEmpty()) layout.points.random() else Vector2(w/2, h/2)
                val r1 = Random.double(w * 0.1, w * 0.2)
                val r2 = r1 + Random.double(30.0, 80.0)
                // Use difference for ring
                difference(Circle(center, r2).shape, Circle(center, r1).shape)
            }
            else -> Rectangle(0.0, 0.0, w, h).shape
        }
        fields.add(VaryStyledShape(shape, color, Random.bool(0.4), name = "Field $i"))
    }
    return fields
}

class VaryFigure(val parts: List<VaryStyledShape>, val hasPattern: Boolean = false)

fun makeFigure(params: VaryParams, palette: VaryPalette, layout: Layout): VaryFigure {
    val parts = mutableListOf<VaryStyledShape>()
    val color = palette.baseColors.random()
    val center = if (layout.points.isNotEmpty()) layout.points.random() else Vector2(params.width/2.0, params.height/2.0)

    when (params.figureType) {
        VaryFigureType.PERSON -> {
            val torsoW = Random.double(40.0, 60.0)
            val torsoH = Random.double(80.0, 120.0)
            parts.add(VaryStyledShape(Rectangle(center.x - torsoW/2, center.y - torsoH/2, torsoW, torsoH).shape, color, name = "Torso"))
            parts.add(VaryStyledShape(Circle(center.x, center.y - torsoH/2 - 25.0, 20.0).shape, color, name = "Head"))
        }
        VaryFigureType.BIRD -> {
            val body = Circle(center, 40.0).shape
            parts.add(VaryStyledShape(body, color, name = "Bird Body"))
            val wing1 = Circle(center + Vector2(-30.0, -10.0), 50.0).contour.sub(0.0, 0.2).shape
            val wing2 = Circle(center + Vector2(30.0, -10.0), 50.0).contour.sub(0.3, 0.5).shape
            parts.add(VaryStyledShape(wing1, color))
            parts.add(VaryStyledShape(wing2, color))
        }
        VaryFigureType.ARCHITECTURE -> {
            val baseW = Random.double(100.0, 200.0)
            val h1 = Random.double(50.0, 100.0)
            parts.add(VaryStyledShape(Rectangle(center.x - baseW/2, center.y, baseW, h1).shape, color))
            parts.add(VaryStyledShape(Rectangle(center.x - baseW/4, center.y - h1, baseW/2, h1).shape, color))
            parts.add(VaryStyledShape(Circle(center.x, center.y - h1 * 1.5, baseW/4).contour.sub(0.5, 1.0).shape, color))
        }
        VaryFigureType.TOTEM -> {
            repeat(4) { i ->
                val r = 30.0 - i * 5.0
                parts.add(VaryStyledShape(Circle(center.x, center.y - i * 50.0, r).shape, color))
                if (Random.bool()) {
                    parts.add(VaryStyledShape(Rectangle(center.x - r - 10, center.y - i * 50.0 - 5, (r + 10) * 2, 10.0).shape, color))
                }
            }
        }
        VaryFigureType.MASK -> {
            val face = Circle(center, 80.0).shape
            parts.add(VaryStyledShape(face, color))
            parts.add(VaryStyledShape(Circle(center + Vector2(-30.0, -20.0), 15.0).shape, palette.background))
            parts.add(VaryStyledShape(Circle(center + Vector2(30.0, -20.0), 15.0).shape, palette.background))
            parts.add(VaryStyledShape(Rectangle(center.x - 20, center.y + 30, 40.0, 10.0).shape, palette.background))
        }
        else -> {
             parts.add(VaryStyledShape(Circle(center, 50.0).shape, color))
        }
    }
    return VaryFigure(parts, true)
}

data class VarySignal(val shape: Shape, val color: ColorRGBa, val position: Vector2)

fun makeSignals(params: VaryParams, palette: VaryPalette, layout: Layout): List<VarySignal> {
    val signals = mutableListOf<VarySignal>()
    val accentColor = palette.accentColors.random()
    repeat(params.numberOfSignals) {
        val pos = if (Random.bool(0.7) && layout.points.isNotEmpty()) layout.points.random() else Vector2(Random.double(0.0, params.width.toDouble()), Random.double(0.0, params.height.toDouble()))
        val shape = when (Random.int(0, 4)) {
            0 -> Circle(0.0, 0.0, Random.double(10.0, 30.0)).shape
            1 -> Rectangle(-15.0, -15.0, 30.0, 30.0).shape
            2 -> {
                val pts = (0 until 3).map { Vector2(cos(it * PI * 2 / 3), sin(it * PI * 2 / 3)) * 25.0 }
                contour { moveTo(pts[0]); lineTo(pts[1]); lineTo(pts[2]); close() }.shape
            }
            else -> LineSegment(-30.0, 0.0, 30.0, 0.0).shape
        }
        signals.add(VarySignal(shape, accentColor, pos))
    }
    return signals
}

fun drawPattern(drawer: Drawer, targetShape: Shape, baseColor: ColorRGBa, params: VaryParams, intensity: Double = 1.0) {
    drawer.isolated {
        drawer.drawStyle.clip = targetShape.bounds
        val patternColor = if (baseColor.luminance > 0.5) baseColor.shade(1.0 - params.patternContrast * intensity) else baseColor.shade(1.0 + params.patternContrast * intensity)
        drawer.fill = patternColor
        drawer.stroke = patternColor
        val bounds = targetShape.bounds
        val type = Random.int(0, 7)
        drawer.translate(Random.double(-1.0, 1.0) * params.misregistrationPx, Random.double(-1.0, 1.0) * params.misregistrationPx)

        when (type) {
            0 -> { // Grid dots
                val step = 15.0
                for (x in bounds.x.toInt()..(bounds.x + bounds.width).toInt() step step.toInt()) {
                    for (y in bounds.y.toInt()..(bounds.y + bounds.height).toInt() step step.toInt()) {
                        drawer.circle(x.toDouble(), y.toDouble(), 1.5)
                    }
                }
            }
            1 -> { // Waves
                val step = 10.0
                for (y in bounds.y.toInt()..(bounds.y + bounds.height).toInt() step step.toInt()) {
                    val c = contour {
                        moveTo(bounds.x, y.toDouble())
                        for (x in bounds.x.toInt()..(bounds.x + bounds.width).toInt() step 20) {
                            continueTo(x.toDouble(), y + sin(x * 0.05) * 5.0)
                        }
                    }
                    drawer.fill = null
                    drawer.strokeWeight = 1.0
                    drawer.contour(c)
                }
            }
            2 -> { // Zigzag
                val step = 15.0
                drawer.fill = null
                for (y in bounds.y.toInt()..(bounds.y + bounds.height).toInt() step step.toInt()) {
                    val c = contour {
                        moveTo(bounds.x, y.toDouble())
                        var up = true
                        for (x in bounds.x.toInt()..(bounds.x + bounds.width).toInt() step 10) {
                            lineTo(x.toDouble(), y + (if (up) -5.0 else 5.0))
                            up = !up
                        }
                    }
                    drawer.contour(c)
                }
            }
            3 -> { // Concentric circles
                drawer.fill = null
                for (r in 10..200 step 15) {
                    drawer.circle(bounds.center, r.toDouble())
                }
            }
            4 -> { // Stipple
                drawer.stroke = null
                repeat(500) {
                    drawer.circle(Random.double(bounds.x, bounds.x + bounds.width), Random.double(bounds.y, bounds.y + bounds.height), 0.8)
                }
            }
            5 -> { // Slanted lines
                drawer.fill = null
                for (i in -20..40) {
                    val x = (bounds.x) + i * 15.0
                    drawer.lineSegment(x, bounds.y, x + bounds.height, bounds.y + bounds.height)
                }
            }
            else -> { // Crosses
                val step = 30.0
                for (x in bounds.x.toInt()..(bounds.x + bounds.width).toInt() step step.toInt()) {
                    for (y in bounds.y.toInt()..(bounds.y + bounds.height).toInt() step step.toInt()) {
                        drawer.lineSegment(x - 3.0, y - 3.0, x + 3.0, y + 3.0)
                        drawer.lineSegment(x + 3.0, y - 3.0, x - 3.0, y + 3.0)
                    }
                }
            }
        }
        drawer.drawStyle.clip = null
    }
}

fun applyFinish(drawer: Drawer, params: VaryParams) {
    drawer.isolated {
        when (params.finishMode) {
            VaryFinishMode.GRAIN -> {
                repeat(20000) {
                    drawer.fill = ColorRGBa.BLACK.opacify(Random.double(0.0, 0.02))
                    drawer.point(Random.double(0.0, params.width.toDouble()), Random.double(0.0, params.height.toDouble()))
                }
            }
            VaryFinishMode.PAPER_TEXTURE -> {
                repeat(5000) {
                    drawer.fill = ColorRGBa.WHITE.opacify(Random.double(0.0, 0.05))
                    drawer.circle(Random.double(0.0, params.width.toDouble()), Random.double(0.0, params.height.toDouble()), Random.double(0.5, 2.0))
                }
            }
            else -> {}
        }
    }
}

fun drawDebug(drawer: Drawer, params: VaryParams, layout: Layout) {
    drawer.isolated {
        drawer.stroke = ColorRGBa.CYAN.opacify(0.5)
        layout.points.forEach { drawer.circle(it, 5.0) }
        layout.mainAxis?.let { drawer.lineSegment(it) }
        drawer.fill = ColorRGBa.CYAN
        drawer.text("Strategy: ${params.compositionStrategy}", 20.0, 30.0)
        drawer.text("Rule: ${params.intersectionRule}", 20.0, 50.0)
        drawer.text("Seed: ${params.seed}", 20.0, 70.0)
    }
}

fun drawVarySoftShadow(drawer: Drawer, shape: Shape) {
    repeat(4) { i ->
        drawer.isolated {
            drawer.fill = ColorRGBa.BLACK.opacify(0.03)
            drawer.translate(3.0 * (i + 1), 3.0 * (i + 1))
            drawer.shape(shape)
        }
    }
}

fun getVaryOverlapIntersections(allElements: List<VaryStyledShape>, palette: VaryPalette, globalRule: IntersectionRule): List<Triple<Shape, ColorRGBa, IntersectionRule>> {
    val results = mutableListOf<Triple<Shape, ColorRGBa, IntersectionRule>>()
    val allColors = (palette.baseColors + palette.accentColors).distinct()

    for (i in 0 until allElements.size) {
        for (j in i + 1 until allElements.size) {
            val s1 = allElements[i]
            val s2 = allElements[j]
            if (s1.shape.bounds.intersects(s2.shape.bounds)) {
                 try {
                    val intersect = intersection(s1.shape, s2.shape)
                    if (intersect.contours.isNotEmpty()) {
                        val candidateColors = allColors.filter { it != s1.color && it != s2.color }
                        val color = if (candidateColors.isNotEmpty()) candidateColors.random() else palette.accentColors.random()
                        results.add(Triple(intersect, color, s1.ruleOverride ?: globalRule))
                    }
                } catch (e: Exception) { }
            }
        }
    }
    return results
}
