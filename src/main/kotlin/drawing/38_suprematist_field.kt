package drawing

import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.*
import org.openrndr.extra.noise.Random
import org.openrndr.extra.noise.gaussian
import org.openrndr.math.Vector2
import org.openrndr.shape.Circle
import org.openrndr.shape.Rectangle
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.*

/**
 * Suprematist Field
 * Inspired by Kazimir Malevich / Suprematism.
 */

data class SupParams(
    var seed: Long = (Math.random() * 1000000).toLong(),
    val width: Int = 1200,
    val height: Int = 800,
    var paletteMode: SupPaletteMode = SupPaletteMode.CLASSIC,
    var strokeMode: Boolean = false,
    var density: Double = 0.5, // 0.0 to 1.0
    var angleSet: SupAngleSet = SupAngleSet.STRICT,
    var showGrain: Boolean = true,
    var debugMode: Boolean = false,
    var overlapChance: Double = 0.2,
    var cropChance: Double = 0.3
)

enum class SupPaletteMode {
    CLASSIC, MONOCHROME, WARM
}

enum class SupAngleSet(val angles: List<Double>) {
    STRICT(listOf(0.0, 15.0, 30.0, 45.0, 60.0, -15.0, -30.0, -45.0, -60.0)),
    VARIED(listOf(0.0, 5.0, 10.0, 15.0, 20.0, 25.0, 30.0, 45.0, 60.0, 75.0, 90.0, -5.0, -10.0, -15.0, -30.0, -45.0, -60.0))
}

sealed class SupElement(val z: Int, val elementSeed: Int) {
    abstract fun draw(drawer: Drawer, params: SupParams, palette: SuprematistPalette)
    abstract fun getPoints(): List<Vector2>
}

fun rotatePoint(p: Vector2, center: Vector2, angleDegrees: Double): Vector2 {
    val rad = Math.toRadians(angleDegrees)
    val s = sin(rad)
    val c = cos(rad)
    val relP = p - center
    val nx = relP.x * c - relP.y * s
    val ny = relP.x * s + relP.y * c
    return Vector2(nx, ny) + center
}

fun Drawer.wobblyContour(points: List<Vector2>, seed: String) {
    val prevSeed = Random.seed
    Random.seed = seed
    val jittered = points.map { it + Vector2(Random.double(-1.5, 1.5), Random.double(-1.5, 1.5)) }
    contour(org.openrndr.shape.contour {
        moveTo(jittered[0])
        for (i in 1 until jittered.size) lineTo(jittered[i])
        close()
    })
    Random.seed = prevSeed
}

class PlaneRect(
    val center: Vector2,
    val w: Double,
    val h: Double,
    val rotation: Double,
    val color: ColorRGBa,
    z: Int,
    elementSeed: Int
) : SupElement(z, elementSeed) {
    override fun draw(drawer: Drawer, params: SupParams, palette: SuprematistPalette) {
        drawer.isolated {
            drawer.fill = color
            drawer.stroke = if (params.strokeMode) ColorRGBa.BLACK.opacify(0.2) else null
            drawer.strokeWeight = 0.5
            drawer.wobblyContour(getPoints(), elementSeed.toString())
            // Subtle edge detail for weight
            if (color == palette.black && !params.strokeMode) {
                drawer.fill = null
                drawer.stroke = ColorRGBa.BLACK.opacify(0.1)
                drawer.wobblyContour(getPoints(), (elementSeed + 1).toString())
            }
        }
    }
    override fun getPoints(): List<Vector2> {
        val hw = w / 2.0
        val hh = h / 2.0
        return listOf(
            Vector2(-hw, -hh), Vector2(hw, -hh), Vector2(hw, hh), Vector2(-hw, hh)
        ).map { rotatePoint(it + center, center, rotation) }
    }
}

class PlaneSquare(
    val center: Vector2,
    val size: Double,
    val rotation: Double,
    val color: ColorRGBa,
    z: Int,
    elementSeed: Int
) : SupElement(z, elementSeed) {
    override fun draw(drawer: Drawer, params: SupParams, palette: SuprematistPalette) {
        drawer.isolated {
            drawer.fill = color
            drawer.stroke = if (params.strokeMode) ColorRGBa.BLACK.opacify(0.2) else null
            drawer.strokeWeight = 0.5
            drawer.wobblyContour(getPoints(), elementSeed.toString())
        }
    }
    override fun getPoints(): List<Vector2> {
        val s = size / 2.0
        return listOf(
            Vector2(-s, -s), Vector2(s, -s), Vector2(s, s), Vector2(-s, s)
        ).map { rotatePoint(it + center, center, rotation) }
    }
}

class PlaneBar(
    val center: Vector2,
    val length: Double,
    val thickness: Double,
    val rotation: Double,
    val color: ColorRGBa,
    z: Int,
    elementSeed: Int
) : SupElement(z, elementSeed) {
    override fun draw(drawer: Drawer, params: SupParams, palette: SuprematistPalette) {
        drawer.isolated {
            drawer.fill = color
            drawer.stroke = if (params.strokeMode) ColorRGBa.BLACK.opacify(0.2) else null
            drawer.strokeWeight = 0.5
            drawer.wobblyContour(getPoints(), elementSeed.toString())
        }
    }
    override fun getPoints(): List<Vector2> {
        val hl = length / 2.0
        val ht = thickness / 2.0
        return listOf(
            Vector2(-hl, -ht), Vector2(hl, -ht), Vector2(hl, ht), Vector2(-hl, ht)
        ).map { rotatePoint(it + center, center, rotation) }
    }
}

class PlaneCross(
    val center: Vector2,
    val size: Double,
    val thickness: Double,
    val rotation: Double,
    val color: ColorRGBa,
    z: Int,
    elementSeed: Int
) : SupElement(z, elementSeed) {
    override fun draw(drawer: Drawer, params: SupParams, palette: SuprematistPalette) {
        drawer.isolated {
            drawer.fill = color
            drawer.stroke = if (params.strokeMode) ColorRGBa.BLACK.opacify(0.2) else null
            drawer.strokeWeight = 0.5
            drawer.wobblyContour(getPoints(), elementSeed.toString())
        }
    }
    override fun getPoints(): List<Vector2> {
        val h = thickness / 2.0
        val s = size / 2.0
        return listOf(
            Vector2(-s, -h), Vector2(-h, -h), Vector2(-h, -s), Vector2(h, -s),
            Vector2(h, -h), Vector2(s, -h), Vector2(s, h), Vector2(h, h),
            Vector2(h, s), Vector2(-h, s), Vector2(-h, h), Vector2(-s, h)
        ).map { rotatePoint(it + center, center, rotation) }
    }
}

class PlaneCircle(
    val center: Vector2,
    val radius: Double,
    val color: ColorRGBa,
    z: Int,
    elementSeed: Int
) : SupElement(z, elementSeed) {
    override fun draw(drawer: Drawer, params: SupParams, palette: SuprematistPalette) {
        drawer.isolated {
            drawer.fill = color
            drawer.stroke = if (params.strokeMode) ColorRGBa.BLACK.opacify(0.2) else null
            drawer.strokeWeight = 0.5
            drawer.wobblyContour(getPoints(), elementSeed.toString())
        }
    }
    override fun getPoints(): List<Vector2> {
        return (0 until 16).map {
            val angle = Math.toRadians(it * 360.0 / 16.0)
            center + Vector2(cos(angle), sin(angle)) * radius
        }
    }
}

class PlaneTriangle(
    val p1: Vector2,
    val p2: Vector2,
    val p3: Vector2,
    val color: ColorRGBa,
    z: Int,
    elementSeed: Int
) : SupElement(z, elementSeed) {
    override fun draw(drawer: Drawer, params: SupParams, palette: SuprematistPalette) {
        drawer.isolated {
            drawer.fill = color
            drawer.stroke = if (params.strokeMode) ColorRGBa.BLACK.opacify(0.2) else null
            drawer.strokeWeight = 0.5
            drawer.wobblyContour(getPoints(), elementSeed.toString())
        }
    }
    override fun getPoints() = listOf(p1, p2, p3)
}

class PlaneWedge(
    val center: Vector2,
    val w1: Double,
    val w2: Double,
    val h: Double,
    val rotation: Double,
    val color: ColorRGBa,
    z: Int,
    elementSeed: Int
) : SupElement(z, elementSeed) {
    override fun draw(drawer: Drawer, params: SupParams, palette: SuprematistPalette) {
        drawer.isolated {
            drawer.fill = color
            drawer.stroke = if (params.strokeMode) ColorRGBa.BLACK.opacify(0.2) else null
            drawer.strokeWeight = 0.5
            drawer.wobblyContour(getPoints(), elementSeed.toString())
        }
    }
    override fun getPoints(): List<Vector2> {
        val hh = h / 2.0
        val hw1 = w1 / 2.0
        val hw2 = w2 / 2.0
        return listOf(
            Vector2(-hw1, -hh), Vector2(hw1, -hh), Vector2(hw2, hh), Vector2(-hw2, hh)
        ).map { rotatePoint(it + center, center, rotation) }
    }
}

class MicroMark(
    val center: Vector2,
    val size: Double,
    val rotation: Double,
    val color: ColorRGBa,
    z: Int,
    elementSeed: Int
) : SupElement(z, elementSeed) {
    override fun draw(drawer: Drawer, params: SupParams, palette: SuprematistPalette) {
        drawer.isolated {
            drawer.fill = color
            drawer.stroke = null
            drawer.wobblyContour(getPoints(), elementSeed.toString())
        }
    }
    override fun getPoints(): List<Vector2> {
        val prevSeed = Random.seed
        Random.seed = elementSeed.toString()
        val hScale = Random.double(0.2, 2.5)
        Random.seed = prevSeed
        val sw = size / 2.0
        val sh = (size * hScale) / 2.0
        return listOf(
            Vector2(-sw, -sh), Vector2(sw, -sh), Vector2(sw, sh), Vector2(-sw, sh)
        ).map { rotatePoint(it + center, center, rotation) }
    }
}

data class SuprematistPalette(
    val background: ColorRGBa,
    val black: ColorRGBa,
    val red: ColorRGBa,
    val blue: ColorRGBa,
    val yellow: ColorRGBa,
    val gray: ColorRGBa
)

fun getPalette(mode: SupPaletteMode): SuprematistPalette {
    val bg = ColorRGBa.fromHex("F5F2E9") // Warm off-white
    val black = ColorRGBa.fromHex("1A1A1A")
    val red = ColorRGBa.fromHex("BC2B1A")
    val blue = ColorRGBa.fromHex("1A3B6B")
    val yellow = ColorRGBa.fromHex("D9A520")
    val gray = ColorRGBa.fromHex("A0A0A0")

    return when (mode) {
        SupPaletteMode.CLASSIC -> SuprematistPalette(bg, black, red, blue, yellow, gray)
        SupPaletteMode.MONOCHROME -> SuprematistPalette(bg, black, red, ColorRGBa.fromHex("333333"), ColorRGBa.fromHex("444444"), gray)
        SupPaletteMode.WARM -> SuprematistPalette(bg, black, red, ColorRGBa.fromHex("8B4513"), yellow, gray)
    }
}

fun project(points: List<Vector2>, axis: Vector2): Pair<Double, Double> {
    var min = Double.POSITIVE_INFINITY
    var max = Double.NEGATIVE_INFINITY
    for (p in points) {
        val projection = p.dot(axis)
        if (projection < min) min = projection
        if (projection > max) max = projection
    }
    return min to max
}

fun rangesOverlap(r1: Pair<Double, Double>, r2: Pair<Double, Double>): Boolean {
    return r1.first < r2.second && r2.first < r1.second
}

fun getAxes(points: List<Vector2>): List<Vector2> {
    val axes = mutableListOf<Vector2>()
    for (i in points.indices) {
        val p1 = points[i]
        val p2 = points[(i + 1) % points.size]
        val edge = p2 - p1
        axes.add(Vector2(-edge.y, edge.x).normalized)
    }
    return axes
}

fun polygonsOverlap(poly1: List<Vector2>, poly2: List<Vector2>): Boolean {
    if (poly1.isEmpty() || poly2.isEmpty()) return false
    val axes = getAxes(poly1) + getAxes(poly2)
    for (axis in axes) {
        val range1 = project(poly1, axis)
        val range2 = project(poly2, axis)
        if (!rangesOverlap(range1, range2)) return false
    }
    return true
}

fun getPaddedPoints(points: List<Vector2>, padding: Double): List<Vector2> {
    if (points.isEmpty()) return emptyList()
    val center = points.reduce { a, b -> a + b } / points.size.toDouble()
    return points.map { it + (it - center).normalized * padding }
}

fun buildComposition(params: SupParams): List<SupElement> {
    Random.seed = params.seed.toString()
    val elements = mutableListOf<SupElement>()
    val palette = getPalette(params.paletteMode)
    val w = params.width.toDouble()
    val h = params.height.toDouble()

    // 1. Setup
    val axisAngle = Random.double(0.0, 90.0) * (if (Random.bool()) 1.0 else -1.0)
    var seedCounter = 0

    fun isCollision(newPoints: List<Vector2>, padding: Double = 5.0, allowedOverlapIndex: Int = -1): Boolean {
        elements.forEachIndexed { index, existing ->
            if (index == allowedOverlapIndex) return@forEachIndexed
            if (polygonsOverlap(newPoints, getPaddedPoints(existing.getPoints(), padding))) return true
        }
        return false
    }

    // 2. Dominant Plane (always present)
    var dominantPlaced = false
    repeat(100) {
        if (dominantPlaced) return@repeat
        val dw = w * Random.double(0.25, 0.6)
        val dh = dw * Random.double(0.4, 1.5)
        
        var dc = Vector2(w * Random.double(0.1, 0.9), h * Random.double(0.1, 0.9))
        val dr = axisAngle + Random.double(-10.0, 10.0)
        
        if (Random.bool(params.cropChance)) {
            val edge = Random.int(0, 4)
            dc = when(edge) {
                0 -> Vector2(Random.double(-dw * 0.2, dw * 0.2), dc.y) // left
                1 -> Vector2(Random.double(w - dw * 0.2, w + dw * 0.2), dc.y) // right
                2 -> Vector2(dc.x, Random.double(-dh * 0.2, dh * 0.2)) // top
                else -> Vector2(dc.x, Random.double(h - dh * 0.2, h + dh * 0.2)) // bottom
            }
        }
        
        val domCol = if (Random.bool(0.85)) palette.black else palette.red
        val dom = if (Random.bool(0.2)) PlaneSquare(dc, dw, dr, domCol, 10, seedCounter++)
                  else PlaneRect(dc, dw, dh, dr, domCol, 10, seedCounter++)
        elements.add(dom)
        dominantPlaced = true
    }

    // 3. Secondary Planes
    val baseSecondary = Random.int(1, 4)
    val numSecondary = (baseSecondary * (0.2 + params.density * 1.8)).toInt().coerceAtLeast(1)
    val overlapIndex = if (Random.bool(params.overlapChance)) 0 else -1
    
    repeat(numSecondary) {
        var placed = false
        repeat(200) retrySecondary@{
            if (placed) return@retrySecondary
            val sw = w * Random.double(0.1, 0.45)
            val sh = sw * Random.double(0.15, 1.2)
            val sc = Vector2(w * Random.double(0.0, 1.0), h * Random.double(0.0, 1.0))
            
            // Variation: some elements perpendicular or free-rotated
            val sr = when {
                Random.bool(0.7) -> axisAngle
                Random.bool(0.6) -> axisAngle + 90.0
                else -> Random.double(0.0, 360.0)
            }
            
            val col = when {
                it == 0 && Random.bool(0.6) -> palette.red
                Random.bool(0.3) -> palette.black
                Random.bool(0.4) -> palette.gray
                Random.bool(0.1) -> palette.blue
                else -> palette.yellow
            }
            
            val newEl = when {
                Random.bool(0.2) -> PlaneSquare(sc, sw, sr, col, 20, seedCounter++)
                Random.bool(0.15) -> {
                    val p1 = sc + Vector2(0.0, -sw / 2.0)
                    val p2 = sc + Vector2(-sw / 2.0, sw / 2.0)
                    val p3 = sc + Vector2(sw / 2.0, sw / 2.0)
                    PlaneTriangle(rotatePoint(p1, sc, sr), rotatePoint(p2, sc, sr), rotatePoint(p3, sc, sr), col, 20, seedCounter++)
                }
                else -> PlaneRect(sc, sw, sh, sr, col, 20, seedCounter++)
            }
            
            if (!isCollision(newEl.getPoints(), 5.0, overlapIndex)) {
                elements.add(newEl)
                placed = true
            }
        }
    }

    // 4. Accents
    val baseAccents = Random.int(5, 12)
    val numAccents = (baseAccents * (0.2 + params.density * 2.3)).toInt().coerceAtLeast(2)

    repeat(numAccents) {
        var placed = false
        repeat(300) retryAccent@{
            if (placed) return@retryAccent
            val ac = Vector2(w * Random.double(0.0, 1.0), h * Random.double(0.0, 1.0))
            
            // Variation: accents can be completely chaotic
            val ar = when {
                Random.bool(0.7) -> axisAngle
                Random.bool(0.2) -> Random.pick(params.angleSet.angles)
                else -> Random.double(0.0, 360.0)
            }
            
            // Higher chance for small elements to be colorful
            val col = if (Random.bool(0.75)) Random.pick(listOf(palette.red, palette.blue, palette.yellow)) 
                      else Random.pick(listOf(palette.black, palette.gray))
            
            val type = Random.double()
            val newEl: SupElement = when {
                type < 0.35 -> MicroMark(ac, w * Random.double(0.005, 0.035), ar, col, 30, seedCounter++)
                type < 0.6 -> PlaneBar(ac, w * Random.double(0.1, 0.5), w * Random.double(0.003, 0.02), ar, col, 30, seedCounter++)
                type < 0.75 -> {
                    val size = w * Random.double(0.04, 0.12)
                    val p1 = ac + Vector2(0.0, -size / 2.0)
                    val p2 = ac + Vector2(-size * 0.4, size / 2.0)
                    val p3 = ac + Vector2(size * 0.4, size / 2.0)
                    PlaneTriangle(rotatePoint(p1, ac, ar), rotatePoint(p2, ac, ar), rotatePoint(p3, ac, ar), col, 30, seedCounter++)
                }
                type < 0.85 -> PlaneWedge(ac, w * Random.double(0.05, 0.1), w * Random.double(0.01, 0.03), h * Random.double(0.03, 0.08), ar, col, 30, seedCounter++)
                type < 0.92 -> PlaneCross(ac, w * Random.double(0.05, 0.1), w * Random.double(0.005, 0.02), ar, col, 30, seedCounter++)
                else -> PlaneCircle(ac, w * Random.double(0.008, 0.03), col, 30, seedCounter++)
            }
            
            // Allow near-miss: minimal padding
            if (!isCollision(newEl.getPoints(), 1.0)) {
                elements.add(newEl)
                placed = true
            }
        }
    }

    return elements.sortedBy { it.z }
}

fun render(drawer: Drawer, elements: List<SupElement>, params: SupParams) {
    val palette = getPalette(params.paletteMode)
    drawer.clear(palette.background)

    // 1. Uneven ground (painterly washes)
    if (params.showGrain) {
        val prevSeed = Random.seed
        Random.seed = params.seed.toString() + "ground"
        drawer.isolated {
            drawer.stroke = null
            repeat(20) {
                val center = Vector2(Random.double(0.0, params.width.toDouble()), Random.double(0.0, params.height.toDouble()))
                val radius = Random.double(params.width * 0.3, params.width * 0.8)
                drawer.fill = if (Random.bool()) ColorRGBa.BLACK.opacify(Random.double(0.002, 0.01)) 
                             else ColorRGBa.WHITE.opacify(Random.double(0.002, 0.01))
                drawer.circle(center, radius)
            }
        }
        Random.seed = prevSeed
    }

    // 2. Main elements
    elements.forEach { it.draw(drawer, params, palette) }

    // 3. Surface texture (canvas + grain)
    if (params.showGrain) {
        val prevSeed = Random.seed
        Random.seed = params.seed.toString() + "texture"
        drawer.isolated {
            drawer.strokeWeight = 0.4
            // Canvas weave
            repeat(2000) {
                val y = Random.double(0.0, params.height.toDouble())
                drawer.stroke = ColorRGBa.BLACK.opacify(Random.double(0.0, 0.008))
                drawer.lineSegment(0.0, y, params.width.toDouble(), y)
            }
            repeat(2000) {
                val x = Random.double(0.0, params.width.toDouble())
                drawer.stroke = ColorRGBa.BLACK.opacify(Random.double(0.0, 0.008))
                drawer.lineSegment(x, 0.0, x, params.height.toDouble())
            }
            // Fine dust
            repeat(8000) {
                drawer.stroke = ColorRGBa.BLACK.opacify(Random.double(0.0, 0.015))
                drawer.point(Random.double(0.0, params.width.toDouble()), Random.double(0.0, params.height.toDouble()))
            }
        }
        Random.seed = prevSeed
    }

    if (params.debugMode) {
        drawer.fill = ColorRGBa.GREEN
        drawer.text("Seed: ${params.seed}", 20.0, 30.0)
        drawer.text("Palette: ${params.paletteMode}", 20.0, 50.0)
        drawer.text("Elements: ${elements.size}", 20.0, 70.0)
        drawer.text("Density: ${String.format("%.2f", params.density)}", 20.0, 90.0)
    }
}

fun exportExact(rt: RenderTarget, params: SupParams) {
    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val fileName = "suprematist_field_${params.seed}_$timestamp.png"
    rt.colorBuffer(0).saveToFile(File(fileName))
    println("Exported to $fileName")
}

fun main() = application {
    val params = SupParams()
    configure {
        width = params.width
        height = params.height
        title = "Suprematist Field"
    }

    program {
        val rt = renderTarget(params.width, params.height) {
            colorBuffer()
            depthBuffer()
        }

        var elements = buildComposition(params)

        fun redraw() {
            drawer.isolatedWithTarget(rt) {
                render(drawer, elements, params)
            }
        }

        redraw()

        keyboard.keyDown.listen {
            when (it.name) {
                "r" -> {
                    params.seed = (Math.random() * 1000000).toLong()
                    elements = buildComposition(params)
                    redraw()
                }
                "1" -> {
                    params.paletteMode = SupPaletteMode.CLASSIC
                    elements = buildComposition(params)
                    redraw()
                }
                "2" -> {
                    params.paletteMode = SupPaletteMode.MONOCHROME
                    elements = buildComposition(params)
                    redraw()
                }
                "3" -> {
                    params.paletteMode = SupPaletteMode.WARM
                    elements = buildComposition(params)
                    redraw()
                }
                "s" -> {
                    params.strokeMode = !params.strokeMode
                    redraw()
                }
                "g" -> {
                    params.showGrain = !params.showGrain
                    redraw()
                }
                "[" -> {
                    params.density = max(0.0, params.density - 0.1)
                    elements = buildComposition(params)
                    redraw()
                }
                "]" -> {
                    params.density = min(1.0, params.density + 0.1)
                    elements = buildComposition(params)
                    redraw()
                }
                "a" -> {
                    params.angleSet = SupAngleSet.entries[(params.angleSet.ordinal + 1) % SupAngleSet.entries.size]
                    elements = buildComposition(params)
                    redraw()
                }
                "d" -> {
                    params.debugMode = !params.debugMode
                    redraw()
                }
                "e" -> {
                    exportExact(rt, params)
                }
            }
        }

        extend {
            drawer.image(rt.colorBuffer(0), 0.0, 0.0, width.toDouble(), height.toDouble())
        }
    }
}
