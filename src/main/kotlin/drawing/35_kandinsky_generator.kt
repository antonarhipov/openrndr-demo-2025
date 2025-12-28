package drawing

import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.draw.isolated
import org.openrndr.draw.isolatedWithTarget
import org.openrndr.draw.renderTarget
import org.openrndr.extra.noise.Random
import org.openrndr.extra.noise.gaussian
import org.openrndr.extra.noise.perlinQuintic
import org.openrndr.math.Vector2
import org.openrndr.shape.*
import java.io.File
import kotlin.math.*

/**
 * Kandinsky Drawing Generator
 * An OPENRNDR sketch that produces random but convincingly Kandinsky-style abstract drawings.
 */

sealed class Element(val layer: Int, val importance: Double = 0.5) {
    abstract val bbox: Rectangle
    abstract val anchors: List<Vector2>
    abstract fun draw(drawer: Drawer)
}

class PointElement(
    val position: Vector2,
    val radius: Double,
    val color: ColorRGBa,
    layer: Int
) : Element(layer) {
    override val bbox = Rectangle.fromCenter(position, radius * 2.0, radius * 2.0)
    override val anchors = listOf(position)
    override fun draw(drawer: Drawer) {
        drawer.fill = color
        drawer.stroke = null
        drawer.circle(position, radius)
    }
}

class CircleElement(
    val center: Vector2,
    val radius: Double,
    val color: ColorRGBa,
    val strokeWidth: Double,
    val isFilled: Boolean,
    val concentric: Int = 1,
    layer: Int
) : Element(layer) {
    override val bbox = Rectangle.fromCenter(center, radius * 2.0, radius * 2.0)
    override val anchors = listOf(center)
    override fun draw(drawer: Drawer) {
        drawer.fill = if (isFilled) color else null
        drawer.stroke = if (isFilled) null else color
        drawer.strokeWeight = strokeWidth
        for (i in 0 until concentric) {
            val r = radius * (1.0 - i.toDouble() / concentric)
            if (r > 0) drawer.circle(center, r)
        }
    }
}

class EllipseElement(
    val center: Vector2,
    val radiusX: Double,
    val radiusY: Double,
    val rotation: Double,
    val color: ColorRGBa,
    val strokeWidth: Double,
    layer: Int
) : Element(layer) {
    override val bbox = Rectangle.fromCenter(center, max(radiusX, radiusY) * 2.0, max(radiusX, radiusY) * 2.0)
    override val anchors = listOf(center)
    override fun draw(drawer: Drawer) {
        drawer.fill = null
        drawer.stroke = color
        drawer.strokeWeight = strokeWidth
        drawer.isolated {
            drawer.translate(center)
            drawer.rotate(rotation)
            drawer.contour(Ellipse(Vector2.ZERO, radiusX, radiusY).contour)
        }
    }
}

class PolygonElement(
    val points: List<Vector2>,
    val color: ColorRGBa,
    val isFilled: Boolean,
    layer: Int
) : Element(layer) {
    override val bbox = points.bounds
    override val anchors = points
    override fun draw(drawer: Drawer) {
        drawer.fill = if (isFilled) color else null
        drawer.stroke = if (isFilled) null else color
        drawer.strokeWeight = 1.0
        drawer.contour(contour {
            moveTo(points.first())
            for (i in 1 until points.size) {
                lineTo(points[i])
            }
            close()
        })
    }
}

class ArcElement(
    val center: Vector2,
    val radius: Double,
    val startAngle: Double,
    val sweepAngle: Double,
    val color: ColorRGBa,
    val strokeWidth: Double,
    layer: Int
) : Element(layer) {
    override val bbox = Rectangle.fromCenter(center, radius * 2.0, radius * 2.0)
    override val anchors = listOf(center)
    override fun draw(drawer: Drawer) {
        drawer.fill = null
        drawer.stroke = color
        drawer.strokeWeight = strokeWidth
        val startRad = startAngle * PI / 180.0
        val endRad = (startAngle + sweepAngle) * PI / 180.0
        drawer.contour(contour {
            moveTo(center + Vector2(cos(startRad), sin(startRad)) * radius)
            arcTo(radius, radius, sweepAngle, false, true, center + Vector2(cos(endRad), sin(endRad)) * radius)
        })
    }
}

class BlobElement(
    val center: Vector2,
    val radius: Double,
    val color: ColorRGBa,
    val seed: Int,
    layer: Int
) : Element(layer) {
    override val bbox = Rectangle.fromCenter(center, radius * 2.5, radius * 2.5)
    override val anchors = listOf(center)
    override fun draw(drawer: Drawer) {
        drawer.fill = color
        drawer.stroke = null
        val points = (0 until 8).map {
            val angle = it * PI * 2.0 / 8.0
            val r = radius * (0.8 + 0.4 * perlinQuintic(seed, cos(angle), sin(angle)))
            center + Vector2(cos(angle), sin(angle)) * r
        }
        drawer.contour(ShapeContour.fromPoints(points, true))
    }
}

class ZigzagElement(
    val p1: Vector2,
    val p2: Vector2,
    val steps: Int,
    val amplitude: Double,
    val color: ColorRGBa,
    val strokeWidth: Double,
    layer: Int
) : Element(layer) {
    override val bbox = listOf(p1, p2).bounds.offsetEdges(amplitude)
    override val anchors = listOf(p1, p2)
    override fun draw(drawer: Drawer) {
        drawer.stroke = color
        drawer.strokeWeight = strokeWidth
        drawer.fill = null
        val diff = p2 - p1
        val perp = Vector2(-diff.y, diff.x).normalized
        drawer.contour(contour {
            moveTo(p1)
            for (i in 1..steps) {
                val t = i.toDouble() / steps
                val p = p1 + diff * t
                val offset = if (i % 2 == 0) perp * amplitude else perp * -amplitude
                lineTo(p + offset)
            }
        })
    }
}

class WaveElement(
    val p1: Vector2,
    val p2: Vector2,
    val cycles: Double,
    val amplitude: Double,
    val color: ColorRGBa,
    val strokeWidth: Double,
    layer: Int
) : Element(layer) {
    override val bbox = listOf(p1, p2).bounds.offsetEdges(amplitude)
    override val anchors = listOf(p1, p2)
    override fun draw(drawer: Drawer) {
        drawer.stroke = color
        drawer.strokeWeight = strokeWidth
        drawer.fill = null
        val diff = p2 - p1
        val perp = Vector2(-diff.y, diff.x).normalized
        drawer.contour(contour {
            moveTo(p1)
            val segments = 100
            for (i in 1..segments) {
                val t = i.toDouble() / segments
                val p = p1 + diff * t
                val offset = perp * sin(t * cycles * PI * 2.0) * amplitude
                lineTo(p + offset)
            }
        })
    }
}

class SpiralElement(
    val center: Vector2,
    val radius: Double,
    val turns: Double,
    val color: ColorRGBa,
    val strokeWidth: Double,
    layer: Int
) : Element(layer) {
    override val bbox = Rectangle.fromCenter(center, radius * 2.0, radius * 2.0)
    override val anchors = listOf(center)
    override fun draw(drawer: Drawer) {
        drawer.stroke = color
        drawer.strokeWeight = strokeWidth
        drawer.fill = null
        drawer.contour(contour {
            val segments = 100
            moveTo(center)
            for (i in 1..segments) {
                val t = i.toDouble() / segments
                val angle = t * turns * PI * 2.0
                val r = t * radius
                lineTo(center + Vector2(cos(angle), sin(angle)) * r)
            }
        })
    }
}

class HatchElement(
    val rect: Rectangle,
    val angle: Double,
    val spacing: Double,
    val color: ColorRGBa,
    val strokeWidth: Double,
    layer: Int
) : Element(layer) {
    override val bbox = rect
    override val anchors = listOf(rect.center)
    override fun draw(drawer: Drawer) {
        drawer.stroke = color
        drawer.strokeWeight = strokeWidth
        drawer.isolated {
            drawer.translate(rect.center)
            drawer.rotate(angle)
            val diag = sqrt(rect.width * rect.width + rect.height * rect.height)
            var x = -diag / 2.0
            while (x < diag / 2.0) {
                drawer.lineSegment(Vector2(x, -diag / 2.0), Vector2(x, diag / 2.0))
                x += spacing
            }
        }
    }
}

class CalligraphicElement(
    val points: List<Vector2>,
    val color: ColorRGBa,
    val strokeWidth: Double,
    layer: Int
) : Element(layer) {
    override val bbox = points.bounds
    override val anchors = points
    override fun draw(drawer: Drawer) {
        drawer.stroke = color
        drawer.fill = color
        drawer.strokeWeight = 0.0
        // Drawing a ribbon instead of a line
        val ribbon = points.windowed(2).flatMapIndexed { index, pair ->
            val p1 = pair[0]
            val p2 = pair[1]
            val t = index.toDouble() / points.size
            val width = strokeWidth * (0.2 + 0.8 * sin(t * PI))
            val diff = p2 - p1
            val perp = Vector2(-diff.y, diff.x).normalized * width
            listOf(p1 + perp, p1 - perp)
        }
        if (ribbon.size >= 4) {
            drawer.contour(ShapeContour.fromPoints(ribbon, true))
        }
    }
}

class WatercolorElement(
    val points: List<Vector2>,
    val color: ColorRGBa,
    val seed: Int,
    layer: Int
) : Element(layer) {
    override val bbox = points.bounds
    override val anchors = points
    override fun draw(drawer: Drawer) {
        drawer.fill = color.opacify(0.15)
        drawer.stroke = null

        // Multiple layers of irregular shapes for watercolor look
        repeat(3) { layerIdx ->
            val noisyPoints = points.mapIndexed { i, p ->
                val angle = i * PI * 2.0 / points.size
                p + Vector2(
                    perlinQuintic(seed + layerIdx, cos(angle), sin(angle)) * 40.0,
                    perlinQuintic(seed + layerIdx + 10, cos(angle), sin(angle)) * 40.0
                )
            }
            drawer.contour(ShapeContour.fromPoints(noisyPoints, true))
        }
    }
}

class TriangleElement(
    val p1: Vector2,
    val p2: Vector2,
    val p3: Vector2,
    val color: ColorRGBa,
    val isFilled: Boolean,
    layer: Int
) : Element(layer) {
    override val bbox = listOf(p1, p2, p3).bounds
    override val anchors = listOf(p1, p2, p3, (p1 + p2 + p3) / 3.0)
    override fun draw(drawer: Drawer) {
        drawer.fill = if (isFilled) color else null
        drawer.stroke = if (isFilled) null else color
        drawer.strokeWeight = 1.0
        drawer.contour(contour {
            moveTo(p1)
            lineTo(p2)
            lineTo(p3)
            close()
        })
    }
}

class RectangleElement(
    val rect: Rectangle,
    val rotation: Double,
    val color: ColorRGBa,
    val isFilled: Boolean,
    layer: Int
) : Element(layer) {
    override val bbox = rect // Simplified
    override val anchors = listOf(rect.center, rect.corner)
    override fun draw(drawer: Drawer) {
        drawer.fill = if (isFilled) color else null
        drawer.stroke = if (isFilled) null else color
        drawer.strokeWeight = 1.0
        drawer.isolated {
            drawer.translate(rect.center)
            drawer.rotate(rotation)
            drawer.rectangle(Rectangle.fromCenter(Vector2.ZERO, rect.width, rect.height))
        }
    }
}

class LineElement(
    val p1: Vector2,
    val p2: Vector2,
    val color: ColorRGBa,
    val strokeWidth: Double,
    layer: Int
) : Element(layer) {
    override val bbox = listOf(p1, p2).bounds
    override val anchors = listOf(p1, p2, (p1 + p2) / 2.0)
    override fun draw(drawer: Drawer) {
        drawer.stroke = color
        drawer.strokeWeight = strokeWidth
        drawer.lineSegment(p1, p2)
    }
}

enum class Scenario {
    MECHANICAL, CONSTELLATION, MUSICAL, BANNER, ORBIT
}

enum class Palette {
    CLASSIC, MUTED, HIGH_CONTRAST
}

enum class Density {
    SPARSE, NORMAL, DENSE
}

enum class HatchDensity {
    LOW, MED, HIGH
}

class KandinskyGenerator(var seed: Long = Random.seed.toLongOrNull() ?: Random.seed.hashCode().toLong()) {
    var scenario = Scenario.MECHANICAL
    var palette = Palette.CLASSIC
    var density = Density.NORMAL
    var hatchDensity = HatchDensity.MED
    var showAxes = true
    var showWashes = true
    var debugMode = false

    private val elements = mutableListOf<Element>()
    private val width = 1075.0
    private val height = 1536.0

    private val hubs = mutableListOf<Vector2>()

    fun generate() {
        Random.seed = seed.toString()
        elements.clear()
        hubs.clear()

        // Step 2: Place anchor structures
        placeAnchors()

        // Step 3: Add mid-scale structures
        addMidScale()

        // Step 4: Add linework scaffolding
        addScaffolding()

        // Step 5: Add texture (washes + hatching)
        addTextures()

        // Step 6: Final accents
        addAccents()

        elements.sortBy { it.layer }
    }

    private fun getColor(type: String): ColorRGBa {
        return when (palette) {
            Palette.CLASSIC -> when (type) {
                "primary" -> Random.pick(listOf(ColorRGBa.fromHex("D32F2F"), ColorRGBa.fromHex("1976D2"), ColorRGBa.fromHex("FBC02D")))
                "secondary" -> Random.pick(listOf(ColorRGBa.fromHex("388E3C"), ColorRGBa.fromHex("F57C00"), ColorRGBa.fromHex("7B1FA2")))
                "structure" -> ColorRGBa.BLACK
                else -> ColorRGBa.BLACK
            }
            Palette.MUTED -> when (type) {
                "primary" -> Random.pick(listOf(ColorRGBa.fromHex("8D6E63"), ColorRGBa.fromHex("546E7A"), ColorRGBa.fromHex("C0CA33")))
                "secondary" -> Random.pick(listOf(ColorRGBa.fromHex("A1887F"), ColorRGBa.fromHex("90A4AE"), ColorRGBa.fromHex("DCE775")))
                "structure" -> ColorRGBa.fromHex("3E2723")
                else -> ColorRGBa.fromHex("3E2723")
            }
            Palette.HIGH_CONTRAST -> when (type) {
                "primary" -> Random.pick(listOf(ColorRGBa.WHITE, ColorRGBa.RED, ColorRGBa.BLUE))
                "secondary" -> ColorRGBa.fromHex("666666")
                "structure" -> ColorRGBa.BLACK
                else -> ColorRGBa.BLACK
            }
        }
    }

    private fun placeAnchors() {
        // drawing.Scenario specific anchors
        when (scenario) {
            Scenario.MECHANICAL -> {
                // Large rectangles, rods and polygons
                repeat(2) {
                    val pos = Vector2(Random.double(0.2*width, 0.8*width), Random.double(0.2*height, 0.8*height))
                    if (Random.bool()) {
                        val rect = Rectangle.fromCenter(pos, Random.double(200.0, 400.0), Random.double(100.0, 300.0))
                        elements.add(RectangleElement(rect, Random.double(0.0, 90.0), getColor("primary"), true, 3))
                    } else {
                        val points = randomPolygon(pos, Random.double(100.0, 200.0), Random.int(4, 7))
                        elements.add(PolygonElement(points, getColor("primary"), true, 3))
                    }
                    hubs.add(pos)
                }
            }
            Scenario.CONSTELLATION -> {
                // Large circles/targets
                repeat(2) {
                    val pos = Vector2(Random.double(0.2*width, 0.8*width), Random.double(0.2*height, 0.8*height))
                    elements.add(CircleElement(pos, Random.double(100.0, 200.0), getColor("primary"), 2.0, false, 3, 3))
                    hubs.add(pos)
                }
            }
            Scenario.MUSICAL -> {
                // Wedges and waves
                repeat(2) {
                    val pos = Vector2(Random.double(0.2*width, 0.8*width), Random.double(0.2*height, 0.8*height))
                    elements.add(TriangleElement(pos, pos + Vector2(200.0, 100.0), pos + Vector2(100.0, 300.0), getColor("primary"), true, 3))
                    hubs.add(pos)
                }
            }
            Scenario.BANNER -> {
                // Floating panels and flags
                repeat(3) {
                    val pos = Vector2(Random.double(0.2*width, 0.8*width), Random.double(0.2*height, 0.8*height))
                    if (Random.bool(0.7)) {
                        elements.add(RectangleElement(Rectangle.fromCenter(pos, Random.double(100.0, 300.0), Random.double(150.0, 400.0)), Random.double(-20.0, 20.0), getColor("primary"), true, 3))
                    } else {
                        val points = randomPolygon(pos, Random.double(80.0, 150.0), Random.int(3, 5))
                        elements.add(PolygonElement(points, getColor("primary"), true, 3))
                    }
                    hubs.add(pos)
                }
            }
            Scenario.ORBIT -> {
                // One huge central circle
                val pos = Vector2(width/2, height/2) + Vector2.gaussian(Vector2.ZERO, Vector2(100.0, 100.0))
                elements.add(CircleElement(pos, Random.double(200.0, 350.0), getColor("primary"), 3.0, false, 4, 3))
                hubs.add(pos)
            }
        }

        // 1-2 dominant axes
        if (showAxes) {
            val axisCount = Random.int(1, 3)
            repeat(axisCount) {
                val p1 = Vector2(Random.double(0.0, width), Random.double(0.0, height))
                val p2 = Vector2(Random.double(0.0, width), Random.double(0.0, height))
                // Make it long
                val dir = (p2 - p1).normalized
                val p1Long = p1 - dir * 1000.0
                val p2Long = p2 + dir * 1000.0
                elements.add(LineElement(p1Long, p2Long, getColor("structure"), Random.double(4.0, 10.0), 4))
                hubs.add(p1.mix(p2, Random.double(0.0, 1.0)))
            }
        }
    }

    private fun addMidScale() {
        val count = when (density) {
            Density.SPARSE -> Random.int(4, 8)
            Density.NORMAL -> Random.int(8, 14)
            Density.DENSE -> Random.int(14, 22)
        }

        repeat(count) {
            val anchor = if (hubs.isNotEmpty() && Random.bool(0.7)) Random.pick(hubs) else Vector2(Random.double(0.0, width), Random.double(0.0, height))
            val pos = anchor + Vector2.gaussian(Vector2.ZERO, Vector2(100.0, 100.0))

            val type = Random.int(0, 6)
            val el = when (type) {
                0 -> CircleElement(pos, Random.double(20.0, 80.0), getColor("secondary"), 1.0, Random.bool(), Random.int(1, 3), 5)
                1 -> EllipseElement(pos, Random.double(40.0, 100.0), Random.double(20.0, 50.0), Random.double(0.0, 360.0), getColor("secondary"), 1.0, 5)
                2 -> RectangleElement(Rectangle.fromCenter(pos, Random.double(30.0, 100.0), Random.double(10.0, 40.0)), Random.double(0.0, 360.0), getColor("secondary"), true, 5)
                3 -> ArcElement(pos, Random.double(40.0, 120.0), Random.double(0.0, 360.0), Random.double(60.0, 180.0), getColor("secondary"), 1.5, 5)
                4 -> {
                    val points = randomPolygon(pos, Random.double(30.0, 80.0), Random.int(3, 6))
                    PolygonElement(points, getColor("secondary"), Random.bool(), 5)
                }
                else -> BlobElement(pos, Random.double(30.0, 70.0), getColor("secondary"), Random.int(0, 1000), 5)
            }
            elements.add(el)
        }
    }

    private fun randomPolygon(center: Vector2, radius: Double, sides: Int): List<Vector2> {
        val angles = (0 until sides).map { Random.double(0.0, PI * 2.0) }.sorted()
        return angles.map { angle ->
            center + Vector2(cos(angle), sin(angle)) * radius * Random.double(0.5, 1.0)
        }
    }

    private fun addScaffolding() {
        val count = when (density) {
            Density.SPARSE -> 10
            Density.NORMAL -> 25
            Density.DENSE -> 50
        }

        repeat(count) {
            val p1 = if (hubs.isNotEmpty() && Random.bool(0.6)) Random.pick(hubs) else Vector2(Random.double(0.0, width), Random.double(0.0, height))
            val p2 = p1 + Vector2.gaussian(Vector2.ZERO, Vector2(300.0, 300.0))
            elements.add(LineElement(p1, p2, getColor("structure").opacify(0.6), 0.5, 2))
        }

        // drawing.Scenario specific linework
        when (scenario) {
            Scenario.MECHANICAL -> {
                repeat(5) {
                    val p1 = Random.pick(hubs)
                    val p2 = Random.pick(hubs)
                    elements.add(LineElement(p1, p2, getColor("structure"), 1.5, 6))
                }
            }
            Scenario.CONSTELLATION -> {
                 // Handled in accents mostly
            }
            Scenario.MUSICAL -> {
                repeat(3) {
                    val p1 = Vector2(0.0, Random.double(0.0, height))
                    val p2 = Vector2(width, Random.double(0.0, height))
                    elements.add(WaveElement(p1, p2, Random.double(2.0, 5.0), Random.double(10.0, 30.0), getColor("structure"), 1.0, 6))
                }
            }
            Scenario.BANNER -> {
                // Already added some rects
            }
            Scenario.ORBIT -> {
                repeat(5) {
                    val hub = if (hubs.isNotEmpty()) Random.pick(hubs) else Vector2(width/2, height/2)
                    elements.add(EllipseElement(hub, Random.double(200.0, 400.0), Random.double(100.0, 200.0), Random.double(0.0, 360.0), getColor("structure").opacify(0.4), 0.8, 6))
                }
            }
        }

        // At least one of: zigzag, wave, spiral, or calligraphic stroke
        val extraType = Random.int(0, 4)
        val p_base = Vector2(Random.double(0.2 * width, 0.8 * width), Random.double(0.2 * height, 0.8 * height))
        val p_end = p_base + Vector2(Random.double(100.0, 300.0), Random.double(100.0, 300.0))
        when (extraType) {
            0 -> elements.add(ZigzagElement(p_base, p_end, 10, 20.0, getColor("structure"), 1.5, 6))
            1 -> elements.add(WaveElement(p_base, p_end, 3.0, 15.0, getColor("structure"), 1.5, 6))
            2 -> elements.add(SpiralElement(p_base, 100.0, 3.0, getColor("structure"), 1.5, 6))
            else -> {
                val calliPoints = (0 until 10).map { i ->
                    p_base + Vector2(i * 30.0, sin(i * 0.5) * 50.0)
                }
                elements.add(CalligraphicElement(calliPoints, getColor("structure"), 10.0, 6))
            }
        }
    }

    private fun addTextures() {
        if (showWashes) {
            repeat(Random.int(2, 7)) {
                val center = Vector2(Random.double(0.0, width), Random.double(0.0, height))
                val points = (0 until 6).map {
                    val angle = it * PI * 2.0 / 6.0
                    center + Vector2(cos(angle), sin(angle)) * Random.double(100.0, 300.0)
                }
                elements.add(WatercolorElement(points, getColor("secondary"), Random.int(0, 1000), 1))
            }
        }

        val hatchCount = when (hatchDensity) {
            HatchDensity.LOW -> 3
            HatchDensity.MED -> 6
            HatchDensity.HIGH -> 12
        }

        repeat(hatchCount) {
            val rect = Rectangle.fromCenter(Vector2(Random.double(0.0, width), Random.double(0.0, height)), Random.double(50.0, 200.0), Random.double(50.0, 200.0))
            elements.add(HatchElement(rect, Random.double(0.0, 360.0), Random.double(3.0, 8.0), getColor("structure").opacify(0.5), 0.5, 7))
        }
    }

    private fun addAccents() {
        // Constellations
        repeat(Random.int(3, 8)) {
            val center = Vector2(Random.double(0.0, width), Random.double(0.0, height))
            repeat(Random.int(3, 7)) {
                val pos = center + Vector2.gaussian(Vector2.ZERO, Vector2(50.0, 50.0))
                elements.add(PointElement(pos, Random.double(1.0, 4.0), getColor("structure"), 8))
                if (Random.bool(0.4)) {
                    elements.add(LineElement(pos, pos + Vector2.gaussian(Vector2.ZERO, Vector2(20.0, 20.0)), getColor("structure"), 0.3, 8))
                }
            }
        }

        // Tiny primary notes
        repeat(Random.int(2, 5)) {
            val pos = Vector2(Random.double(0.0, width), Random.double(0.0, height))
            elements.add(PointElement(pos, Random.double(5.0, 12.0), getColor("primary"), 8))
        }
    }

    fun draw(drawer: Drawer) {
        elements.forEach { it.draw(drawer) }

        if (debugMode) {
            drawer.stroke = ColorRGBa.GREEN.opacify(0.5)
            drawer.fill = null
            elements.forEach {
                drawer.rectangle(it.bbox)
                it.anchors.forEach { a -> drawer.circle(a, 3.0) }
            }
        }
    }
}

fun main() = application {
    configure {
        width = 1075 / 2
        height = 1536 / 2
        title = "Kandinsky Drawing Generator"
    }

    program {
        val canvasWidth = 1075.0
        val canvasHeight = 1536.0

        val rt = renderTarget(canvasWidth.toInt(), canvasHeight.toInt()) {
            colorBuffer()
            depthBuffer()
        }

        var generator = KandinskyGenerator()
        generator.generate()

        keyboard.keyDown.listen {
            when (it.name) {
                "r" -> {
                    generator.seed = (Math.random() * 1000000).toLong()
                    generator.generate()
                }
                "1" -> { generator.scenario = Scenario.MECHANICAL; generator.generate() }
                "2" -> { generator.scenario = Scenario.CONSTELLATION; generator.generate() }
                "3" -> { generator.scenario = Scenario.MUSICAL; generator.generate() }
                "4" -> { generator.scenario = Scenario.BANNER; generator.generate() }
                "5" -> { generator.scenario = Scenario.ORBIT; generator.generate() }
                "a" -> { generator.showAxes = !generator.showAxes; generator.generate() }
                "w" -> { generator.showWashes = !generator.showWashes; generator.generate() }
                "h" -> {
                    generator.hatchDensity = HatchDensity.entries.toTypedArray()[(generator.hatchDensity.ordinal + 1) % HatchDensity.entries.size]
                    generator.generate()
                }
                "c" -> {
                    generator.palette = Palette.entries.toTypedArray()[(generator.palette.ordinal + 1) % Palette.entries.size]
                    generator.generate()
                }
                "k" -> {
                    generator.density = Density.entries.toTypedArray()[(generator.density.ordinal + 1) % Density.entries.size]
                    generator.generate()
                }
                "d" -> { generator.debugMode = !generator.debugMode }
                "e" -> {
                    // Export logic
                    rt.colorBuffer(0).saveToFile(File("kandinsky_${generator.seed}.png"))
                }
            }
        }

        extend {
            drawer.isolatedWithTarget(rt) {
                clear(ColorRGBa.fromHex("F5F5DC")) // Warm paper tone

                // Subtle grain
                Random.seed = "grain"
                drawer.strokeWeight = 1.0
                repeat(20000) {
                    drawer.stroke = ColorRGBa.BLACK.opacify(Random.double(0.0, 0.03))
                    drawer.point(Random.double(0.0, canvasWidth), Random.double(0.0, canvasHeight))
                }

                // Sparse specks
                repeat(50) {
                    drawer.stroke = ColorRGBa.BLACK.opacify(Random.double(0.0, 0.1))
                    drawer.circle(Random.double(0.0, canvasWidth), Random.double(0.0, canvasHeight), Random.double(0.5, 1.5))
                }

                generator.draw(drawer)
            }

            drawer.image(rt.colorBuffer(0), 0.0, 0.0, width.toDouble(), height.toDouble())
        }
    }
}
