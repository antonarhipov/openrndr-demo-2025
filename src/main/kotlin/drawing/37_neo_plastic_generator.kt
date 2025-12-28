package drawing

import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.*
import org.openrndr.extra.noise.Random
import org.openrndr.math.Vector2
import org.openrndr.shape.LineSegment
import org.openrndr.shape.Rectangle
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.*

/**
 * Neo-Plastic Generator
 * A generative art sketch producing Piet Mondrian–style compositions.
 */

enum class ColorDensity { SPARSE, CLASSIC, BOLD }

data class NPParams(
    var seed: Long = Random.seed.toLongOrNull() ?: Random.seed.hashCode().toLong(),
    var width: Int = 800,
    var height: Int = 800,
    var lineThickness: Double = 14.0,
    var lineVariation: Double = 0.4,
    var targetRects: Int = 15,
    var minRectSize: Double = 40.0,
    var colorDensity: ColorDensity = ColorDensity.CLASSIC,
    var useGray: Boolean = false,
    var useMicroAccent: Boolean = false,
    var showGrain: Boolean = true,
    var debugMode: Boolean = false
)

data class GridLine(val segment: LineSegment, val thickness: Double)

class NeoPlasticGenerator(var params: NPParams) {
    var rects = listOf<Rectangle>()
    var coloredRects = mapOf<Rectangle, ColorRGBa>()
    var gridLines = listOf<GridLine>()

    private val primaryRed = ColorRGBa.fromHex("D32F2F")
    private val primaryBlue = ColorRGBa.fromHex("1976D2")
    private val primaryYellow = ColorRGBa.fromHex("FBC02D")
    private val offWhite = ColorRGBa.fromHex("FDFCF5")
    private val lightGray = ColorRGBa.fromHex("E0E0E0")

    fun generate() {
        Random.seed = params.seed.toString()
        rects = splitRectangles()
        coloredRects = chooseColoredRects(rects)
        gridLines = extractGridLines(rects)
    }

    private fun splitRectangles(): List<Rectangle> {
        var result = mutableListOf(Rectangle(0.0, 0.0, params.width.toDouble(), params.height.toDouble()))
        val gridStep = 20.0

        // Ensure at least one strong vertical and one strong horizontal line
        // We do this by forcing the first two splits to be different orientations
        repeat(2) {
            val rectToSplit = result.maxByOrNull { it.width * it.height } ?: return@repeat
            // Ensure at least one of each orientation for the first two splits
            val split = chooseSplit(rectToSplit, gridStep, forceOrientation = if (it == 0) "V" else "H")
            if (split != null) {
                result.remove(rectToSplit)
                if (split.vertical) {
                    result.add(Rectangle(rectToSplit.x, rectToSplit.y, split.position - rectToSplit.x, rectToSplit.height))
                    result.add(Rectangle(split.position, rectToSplit.y, rectToSplit.x + rectToSplit.width - split.position, rectToSplit.height))
                } else {
                    result.add(Rectangle(rectToSplit.x, rectToSplit.y, rectToSplit.width, split.position - rectToSplit.y))
                    result.add(Rectangle(rectToSplit.x, split.position, rectToSplit.width, rectToSplit.y + rectToSplit.height - split.position))
                }
            }
        }
        
        var iterations = 0
        while (result.size < params.targetRects && iterations < 200) {
            iterations++
            // weighted toward larger ones
            val rectToSplit = result.maxByOrNull { it.width * it.height * Random.double(0.5, 2.0) } ?: break
            
            val split = chooseSplit(rectToSplit, gridStep) ?: continue
            
            result.remove(rectToSplit)
            if (split.vertical) {
                result.add(Rectangle(rectToSplit.x, rectToSplit.y, split.position - rectToSplit.x, rectToSplit.height))
                result.add(Rectangle(split.position, rectToSplit.y, rectToSplit.x + rectToSplit.width - split.position, rectToSplit.height))
            } else {
                result.add(Rectangle(rectToSplit.x, rectToSplit.y, rectToSplit.width, split.position - rectToSplit.y))
                result.add(Rectangle(rectToSplit.x, split.position, rectToSplit.width, rectToSplit.y + rectToSplit.height - split.position))
            }
        }
        return result
    }

    data class Split(val vertical: Boolean, val position: Double)

    private fun chooseSplit(rect: Rectangle, gridStep: Double, forceOrientation: String? = null): Split? {
        val vertical = when (forceOrientation) {
            "V" -> true
            "H" -> false
            else -> if (rect.width > rect.height * 1.5) true 
                    else if (rect.height > rect.width * 1.5) false
                    else Random.bool()
        }

        val minSize = params.minRectSize + params.lineThickness
        
        if (vertical) {
            val start = rect.x + minSize
            val end = rect.x + rect.width - minSize
            if (start >= end) return if (forceOrientation == null) chooseSplit(rect, gridStep, "H") else null
            
            // Snap to grid
            val possiblePositions = mutableListOf<Double>()
            var p = ceil(start / gridStep) * gridStep
            while (p <= floor(end / gridStep) * gridStep) {
                possiblePositions.add(p)
                p += gridStep
            }
            if (possiblePositions.isEmpty()) return if (forceOrientation == null) chooseSplit(rect, gridStep, "H") else null
            return Split(true, Random.pick(possiblePositions))
        } else {
            val start = rect.y + minSize
            val end = rect.y + rect.height - minSize
            if (start >= end) return if (forceOrientation == null) chooseSplit(rect, gridStep, "V") else null
            
            val possiblePositions = mutableListOf<Double>()
            var p = ceil(start / gridStep) * gridStep
            while (p <= floor(end / gridStep) * gridStep) {
                possiblePositions.add(p)
                p += gridStep
            }
            if (possiblePositions.isEmpty()) return if (forceOrientation == null) chooseSplit(rect, gridStep, "V") else null
            return Split(false, Random.pick(possiblePositions))
        }
    }

    private fun extractGridLines(rects: List<Rectangle>): List<GridLine> {
        val segments = mutableListOf<LineSegment>()
        for (rect in rects) {
            segments.add(LineSegment(Vector2(rect.x, rect.y), Vector2(rect.x + rect.width, rect.y)))
            segments.add(LineSegment(Vector2(rect.x, rect.y + rect.height), Vector2(rect.x + rect.width, rect.y + rect.height)))
            segments.add(LineSegment(Vector2(rect.x, rect.y), Vector2(rect.x, rect.y + rect.height)))
            segments.add(LineSegment(Vector2(rect.x + rect.width, rect.y), Vector2(rect.x + rect.width, rect.y + rect.height)))
        }
        
        // Merge collinear and overlapping segments
        val merged = mutableListOf<GridLine>()
        
        fun calculateThickness(line: LineSegment): Double {
            val length = if (line.start.x == line.end.x) abs(line.start.y - line.end.y) 
                        else abs(line.start.x - line.end.x)
            val maxDim = max(params.width, params.height).toDouble()
            val lengthFactor = 0.7 + 0.6 * (length / maxDim)
            val variation = Random.double(1.0 - params.lineVariation, 1.0 + params.lineVariation)
            return (params.lineThickness * lengthFactor * variation).coerceAtLeast(1.0)
        }

        // Vertical lines
        val vLines = segments.filter { it.start.x == it.end.x }.groupBy { it.start.x }
        for ((x, lines) in vLines) {
            val sorted = lines.sortedBy { it.start.y }
            if (sorted.isEmpty()) continue
            var currentStart = sorted[0].start.y
            var currentEnd = sorted[0].end.y
            for (i in 1 until sorted.size) {
                if (sorted[i].start.y <= currentEnd + 0.1) {
                    currentEnd = max(currentEnd, sorted[i].end.y)
                } else {
                    val segment = LineSegment(Vector2(x, currentStart), Vector2(x, currentEnd))
                    merged.add(GridLine(segment, calculateThickness(segment)))
                    currentStart = sorted[i].start.y
                    currentEnd = sorted[i].end.y
                }
            }
            val segment = LineSegment(Vector2(x, currentStart), Vector2(x, currentEnd))
            merged.add(GridLine(segment, calculateThickness(segment)))
        }

        // Horizontal lines
        val hLines = segments.filter { it.start.y == it.end.y }.groupBy { it.start.y }
        for ((y, lines) in hLines) {
            val sorted = lines.sortedBy { it.start.x }
            if (sorted.isEmpty()) continue
            var currentStart = sorted[0].start.x
            var currentEnd = sorted[0].end.x
            for (i in 1 until sorted.size) {
                if (sorted[i].start.x <= currentEnd + 0.1) {
                    currentEnd = max(currentEnd, sorted[i].end.x)
                } else {
                    val segment = LineSegment(Vector2(currentStart, y), Vector2(currentEnd, y))
                    merged.add(GridLine(segment, calculateThickness(segment)))
                    currentStart = sorted[i].start.x
                    currentEnd = sorted[i].end.x
                }
            }
            val segment = LineSegment(Vector2(currentStart, y), Vector2(currentEnd, y))
            merged.add(GridLine(segment, calculateThickness(segment)))
        }

        return merged
    }

    private fun chooseColoredRects(rects: List<Rectangle>): Map<Rectangle, ColorRGBa> {
        val result = mutableMapOf<Rectangle, ColorRGBa>()
        val count = when (params.colorDensity) {
            ColorDensity.SPARSE -> Random.int(2, 4)
            ColorDensity.CLASSIC -> Random.int(4, 7)
            ColorDensity.BOLD -> Random.int(7, 10)
        }

        val availableRects = rects.toMutableList()
        
        // Constraints:
        // At least one rectangle should stay pure white in every quadrant
        val midX = params.width / 2.0
        val midY = params.height / 2.0
        val quadrants = listOf(
            Rectangle(0.0, 0.0, midX, midY),
            Rectangle(midX, 0.0, midX, midY),
            Rectangle(0.0, midY, midX, midY),
            Rectangle(midX, midY, midX, midY)
        )

        val forbiddenRects = mutableSetOf<Rectangle>()
        for (q in quadrants) {
            val inQ = availableRects.filter { q.contains(it.center) }
            if (inQ.isNotEmpty()) {
                forbiddenRects.add(Random.pick(inQ))
            }
        }

        availableRects.removeAll(forbiddenRects)

        // Distribution: 1-3 red, 1-2 blue, 1-2 yellow
        val colors = mutableListOf<ColorRGBa>()
        val redCount = Random.int(1, 4)
        val blueCount = Random.int(1, 3)
        val yellowCount = Random.int(1, 3)
        repeat(redCount) { colors.add(primaryRed) }
        repeat(blueCount) { colors.add(primaryBlue) }
        repeat(yellowCount) { colors.add(primaryYellow) }
        if (params.useGray && Random.bool()) colors.add(lightGray)
        
        colors.shuffle()

        var currentArea = 0.0
        val totalArea = params.width * params.height.toDouble()
        val maxColoredArea = totalArea * 0.35 // 35% max

        var coloredCount = 0
        while (coloredCount < count && availableRects.isNotEmpty() && colors.isNotEmpty()) {
            // Prefer medium or large for the main ones
            val rect = availableRects.maxByOrNull { it.width * it.height * Random.double(0.1, 1.0) } ?: break
            
            // Area check
            if (currentArea + rect.width * rect.height > maxColoredArea && coloredCount >= 3) break

            val color = colors.removeAt(0)
            result[rect] = color
            availableRects.remove(rect)
            currentArea += rect.width * rect.height
            coloredCount++
        }

        // Micro accent
        if (params.useMicroAccent && availableRects.isNotEmpty()) {
            val microRect = availableRects.minByOrNull { it.width * it.height }
            if (microRect != null && microRect.width * microRect.height < totalArea * 0.05) {
                result[microRect] = Random.pick(listOf(primaryRed, primaryBlue, primaryYellow))
            }
        }

        return result
    }

    fun draw(drawer: Drawer) {
        drawer.stroke = null
        
        // Fill white/off-white background for all rects first
        drawer.fill = offWhite
        for (rect in rects) {
            drawer.rectangle(rect)
        }

        // Fill colored rects
        for ((rect, color) in coloredRects) {
            drawer.fill = color
            drawer.rectangle(rect)
        }

        // Draw black lines
        drawer.stroke = ColorRGBa.BLACK
        drawer.lineCap = LineCap.SQUARE
        for (line in gridLines) {
            drawer.strokeWeight = line.thickness
            drawer.lineSegment(line.segment)
        }

        if (params.showGrain) {
            drawGrain(drawer)
        }

        if (params.debugMode) {
            drawDebug(drawer)
        }
    }

    private fun drawGrain(drawer: Drawer) {
        drawer.isolated {
            drawer.strokeWeight = 1.0
            Random.seed = params.seed.toString() + "grain"
            repeat(15000) {
                drawer.stroke = ColorRGBa.BLACK.opacify(Random.double(0.0, 0.04))
                drawer.point(Random.double(0.0, params.width.toDouble()), Random.double(0.0, params.height.toDouble()))
            }
        }
    }

    private fun drawDebug(drawer: Drawer) {
        drawer.isolated {
            drawer.fill = ColorRGBa.GREEN
            drawer.stroke = null
            rects.forEachIndexed { index, rect ->
                drawer.text("ID: $index", rect.center)
                drawer.text(String.format("%.1f%%", (rect.width * rect.height) / (params.width * params.height) * 100), rect.center + Vector2(0.0, 20.0))
            }
        }
    }
}

fun main() = application {
    val params = NPParams()
    
    configure {
        width = 800
        height = 800
        title = "Neo-Plastic Generator"
    }

    program {
        val rt = renderTarget(800, 800) {
            colorBuffer()
            depthBuffer()
        }

        val generator = NeoPlasticGenerator(params)
        generator.generate()

        fun redraw() {
            drawer.isolatedWithTarget(rt) {
                clear(ColorRGBa.WHITE)
                generator.draw(drawer)
            }
        }

        redraw()

        keyboard.keyDown.listen {
            when (it.name) {
                "r" -> {
                    params.seed = (Math.random() * 1000000).toLong()
                    generator.generate()
                    redraw()
                }
                "[" -> {
                    params.targetRects = max(5, params.targetRects - 1)
                    generator.generate()
                    redraw()
                }
                "]" -> {
                    params.targetRects = min(50, params.targetRects + 1)
                    generator.generate()
                    redraw()
                }
                "-" -> {
                    params.lineThickness = max(2.0, params.lineThickness - 2.0)
                    generator.generate()
                    redraw()
                }
                "=" -> {
                    params.lineThickness = min(40.0, params.lineThickness + 2.0)
                    generator.generate()
                    redraw()
                }
                "c" -> {
                    params.colorDensity = ColorDensity.entries[(params.colorDensity.ordinal + 1) % ColorDensity.entries.size]
                    generator.generate()
                    redraw()
                }
                "g" -> {
                    params.useGray = !params.useGray
                    generator.generate()
                    redraw()
                }
                "m" -> {
                    params.useMicroAccent = !params.useMicroAccent
                    generator.generate()
                    redraw()
                }
                "p" -> {
                    params.showGrain = !params.showGrain
                    redraw()
                }
                "v" -> {
                    params.lineVariation = (params.lineVariation + 0.2) % 0.8
                    generator.generate()
                    redraw()
                }
                "d" -> {
                    params.debugMode = !params.debugMode
                    redraw()
                }
                "e" -> {
                    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                    val fileName = "neo_plastic_${params.seed}_$timestamp.png"
                    rt.colorBuffer(0).saveToFile(File(fileName))
                    println("Exported to $fileName")
                }
            }
        }

        extend {
            drawer.image(rt.colorBuffer(0), 0.0, 0.0, width.toDouble(), height.toDouble())
        }
    }
}
