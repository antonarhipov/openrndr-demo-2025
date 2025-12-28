package drawing

import org.openrndr.*
import org.openrndr.color.ColorRGBa
import org.openrndr.color.ColorHSVa
import org.openrndr.draw.*
import org.openrndr.extra.noise.Random
import org.openrndr.math.Vector2
import org.openrndr.math.map
import org.openrndr.shape.*
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.*

/**
 * Simultaneity Fields
 * A generative art sketch inspired by Robert & Sonia Delaunay.
 * Features: Concentric discs, segmented arcs, overlapping translucent planes, 
 * and simultaneous contrast color rhythms.
 */

enum class SFCompositionType {
    DISC_ORCHESTRA, ARC_GRID, BAND_INTERFERENCE;
    fun next(): SFCompositionType {
        val vals = entries
        return vals[(ordinal + 1) % vals.size]
    }
}

enum class SFPaletteMode {
    PRIMARY_SECONDARY, WARM_COOL, TRIAD;
    fun next(): SFPaletteMode {
        val vals = entries
        return vals[(ordinal + 1) % vals.size]
    }
}

enum class SFOverlapMode {
    LOW, MED, HIGH;
    fun next(): SFOverlapMode {
        val vals = entries
        return vals[(ordinal + 1) % vals.size]
    }
}

data class SFParams(
    var seed: Long = Random.seed.hashCode().toLong(),
    var compType: SFCompositionType = SFCompositionType.DISC_ORCHESTRA,
    var paletteMode: SFPaletteMode = SFPaletteMode.PRIMARY_SECONDARY,
    var overlapMode: SFOverlapMode = SFOverlapMode.MED,
    var separatorMode: Boolean = false,
    var debugOverlay: Boolean = false
)

class ShapeFill(
    val contour: ShapeContour,
    val color: ColorRGBa,
    val isOpaque: Boolean,
    val clip: Rectangle? = null,
    val centers: List<Vector2> = emptyList() // For debug
)

class SFPaletteEngine(var params: SFParams) {
    fun pickHue(index: Int): Double {
        val wheel = listOf(0.0, 60.0, 240.0, 30.0, 120.0, 300.0) // R, Y, B, O, G, V
        return when (params.paletteMode) {
            SFPaletteMode.PRIMARY_SECONDARY -> wheel[index % wheel.size]
            SFPaletteMode.WARM_COOL -> {
                if (index % 2 == 0) listOf(0.0, 30.0, 60.0)[Random.int(0, 3)]
                else listOf(180.0, 240.0, 300.0)[Random.int(0, 3)]
            }
            SFPaletteMode.TRIAD -> {
                val base = (params.seed % 360).toDouble()
                (base + (index % 3) * 120.0) % 360.0
            }
        }
    }

    fun delaunayColor(role: Int, neighborHue: Double? = null): ColorRGBa {
        var hue = pickHue(role)
        
        if (neighborHue != null) {
            val diff = abs(hue - neighborHue)
            if (diff < 40.0 || diff > 320.0) {
                hue = (hue + 60.0) % 360.0
            }
        }

        val saturation = Random.double(0.75, 1.0)
        val value = Random.double(0.7, 0.95)
        
        val alpha = when (params.overlapMode) {
            SFOverlapMode.LOW -> Random.double(0.8, 0.95)
            SFOverlapMode.MED -> Random.double(0.65, 0.8)
            SFOverlapMode.HIGH -> Random.double(0.45, 0.65)
        }
        
        return ColorHSVa(hue, saturation, value).toRGBa().opacify(alpha)
    }
}

fun arcBand(center: Vector2, r0: Double, r1: Double, a0: Double, a1: Double): ShapeContour {
    return contour {
        val startRad = Math.toRadians(a0)
        val endRad = Math.toRadians(a1)
        val p0 = center + Vector2(cos(startRad), sin(startRad)) * r0
        val p1 = center + Vector2(cos(endRad), sin(endRad)) * r0
        val p2 = center + Vector2(cos(endRad), sin(endRad)) * r1
        val p3 = center + Vector2(cos(startRad), sin(startRad)) * r1
        
        moveTo(p0)
        arcTo(r0, r0, 0.0, abs(a1 - a0) > 180.0, true, p1)
        lineTo(p2)
        arcTo(r1, r1, 0.0, abs(a1 - a0) > 180.0, false, p3)
        close()
    }
}

class SimultaneityFields(val width: Int, val height: Int) {
    var params = SFParams()
    private val shapes = mutableListOf<ShapeFill>()
    private val palette = SFPaletteEngine(params)
    private val bgTone = ColorRGBa.fromHex("FDFBF5")

    fun generate() {
        Random.seed = params.seed.toString()
        shapes.clear()
        palette.params = params

        when (params.compType) {
            SFCompositionType.DISC_ORCHESTRA -> buildDiscOrchestra()
            SFCompositionType.ARC_GRID -> buildArcGrid()
            SFCompositionType.BAND_INTERFERENCE -> buildBandInterference()
        }
    }

    private fun buildDiscOrchestra() {
        val count = Random.int(3, 7)
        repeat(count) { i ->
            val center = Vector2(Random.double(-0.1, 1.1) * width, Random.double(-0.1, 1.1) * height)
            val maxR = Random.double(width * 0.3, width * 0.8)
            var currentR = 0.0
            val thicknesses = listOf(15.0, 25.0, 40.0, 65.0)
            
            while (currentR < maxR) {
                val t = thicknesses[Random.int(0, thicknesses.size)]
                val nextR = currentR + t
                val wedges = Random.int(6, 18)
                val baseAngle = Random.double(0.0, 360.0)
                val angleStep = 360.0 / wedges
                val jitter = Random.double(0.0, angleStep * 0.2)
                
                var lastHue: Double? = null
                var lastA = baseAngle
                for (w in 0 until wedges) {
                    val nextA = baseAngle + (w + 1) * angleStep + Random.double(-jitter, jitter)
                    val color = palette.delaunayColor(w + i, lastHue)
                    lastHue = color.toHSVa().h
                    
                    val contour = arcBand(center, currentR, nextR, lastA, nextA)
                    shapes.add(ShapeFill(contour, color, Random.bool(0.6), centers = listOf(center)))
                    lastA = nextA
                }
                currentR = nextR
            }
        }
    }

    private fun buildArcGrid() {
        val rows = Random.int(3, 6)
        val cols = Random.int(2, 5)
        val cellW = width.toDouble() / cols
        val cellH = height.toDouble() / rows
        
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val rect = Rectangle(c * cellW, r * cellH, cellW, cellH)
                val gridCenter = rect.center
                
                repeat(Random.int(2, 5)) { i ->
                    val center = gridCenter + Vector2(Random.double(-cellW, cellW), Random.double(-cellH, cellH))
                    val radius = Random.double(cellW * 0.4, cellW * 2.0)
                    val thickness = Random.double(15.0, cellW * 0.6)
                    val a0 = Random.double(0.0, 360.0)
                    val a1 = a0 + Random.double(60.0, 240.0)
                    
                    val color = palette.delaunayColor(r * cols + c + i)
                    val contour = arcBand(center, radius, radius + thickness, a0, a1)
                    shapes.add(ShapeFill(contour, color, Random.bool(0.5), rect, centers = listOf(center)))
                }
            }
        }
    }

    private fun buildBandInterference() {
        val count = Random.int(10, 25)
        repeat(count) { i ->
            val center = Vector2(Random.double(-0.5, 1.5) * width, Random.double(-0.5, 1.5) * height)
            val radius = Random.double(width * 0.2, width * 1.5)
            val thickness = Random.double(30.0, 120.0)
            val a0 = Random.double(0.0, 360.0)
            val a1 = a0 + Random.double(30.0, 150.0)
            
            val color = palette.delaunayColor(i)
            val contour = arcBand(center, radius, radius + thickness, a0, a1)
            shapes.add(ShapeFill(contour, color, Random.bool(0.6), centers = listOf(center)))
        }
        
        // Occasional panels
        repeat(Random.int(3, 8)) {
            val w = Random.double(100.0, 300.0)
            val h = Random.double(100.0, 300.0)
            val rect = Rectangle(Random.double(0.0, width - w), Random.double(0.0, height - h), w, h)
            val color = palette.delaunayColor(Random.int(0, 100))
            shapes.add(ShapeFill(rect.contour, color, Random.bool(0.4)))
        }
    }

    fun draw(drawer: Drawer) {
        drawer.clear(bgTone)
        
        drawer.strokeWeight = 0.5
        for (sf in shapes) {
            drawer.isolated {
                if (sf.clip != null) {
                    drawer.drawStyle.clip = sf.clip
                }
                drawer.fill = if (sf.isOpaque) sf.color.opacify(1.0) else sf.color
                drawer.stroke = if (params.separatorMode) bgTone.opacify(0.4) else null
                drawer.contour(sf.contour)
            }
        }
        
        drawGrain(drawer)
        
        if (params.debugOverlay) {
            drawDebug(drawer)
        }
    }

    private fun drawGrain(drawer: Drawer) {
        drawer.isolated {
            Random.seed = params.seed.toString() + "grain"
            val points = List(20000) {
                Vector2(Random.double(0.0, width.toDouble()), Random.double(0.0, height.toDouble()))
            }
            drawer.stroke = ColorRGBa.BLACK.opacify(0.03)
            drawer.points(points)
            
            val points2 = List(20000) {
                Vector2(Random.double(0.0, width.toDouble()), Random.double(0.0, height.toDouble()))
            }
            drawer.stroke = ColorRGBa.WHITE.opacify(0.02)
            drawer.points(points2)
        }
    }

    private fun drawDebug(drawer: Drawer) {
        drawer.isolated {
            drawer.fill = null
            drawer.stroke = ColorRGBa.RED
            drawer.strokeWeight = 1.0
            for (sf in shapes) {
                for (c in sf.centers) {
                    drawer.circle(c, 5.0)
                    drawer.lineSegment(c - Vector2(10.0, 0.0), c + Vector2(10.0, 0.0))
                    drawer.lineSegment(c - Vector2(0.0, 10.0), c + Vector2(0.0, 10.0))
                }
                if (sf.clip != null) {
                    drawer.stroke = ColorRGBa.GREEN.opacify(0.3)
                    drawer.rectangle(sf.clip)
                }
            }
        }
    }

    fun export(drawer: Drawer) {
        val rt = renderTarget(width, height) {
            colorBuffer()
            depthBuffer(DepthFormat.DEPTH24_STENCIL8)
        }
        
        drawer.isolatedWithTarget(rt) {
            draw(drawer)
        }
        
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val folder = File("images")
        if (!folder.exists()) folder.mkdirs()
        
        val filename = "images/simultaneity_${params.seed}_${params.compType}_$timestamp.png"
        rt.colorBuffer(0).saveToFile(File(filename))
        rt.destroy()
        println("Exported: $filename")
    }
}

fun main() = application {
    configure {
        width = 800
        height = 800
        title = "Simultaneity Fields"
    }

    program {
        val sf = SimultaneityFields(width, height)
        sf.generate()

        keyboard.keyDown.listen {
            when (it.name) {
                "r" -> {
                    sf.params.seed = (Math.random() * 1e12).toLong()
                    sf.generate()
                }
                "1" -> {
                    sf.params.compType = SFCompositionType.DISC_ORCHESTRA
                    sf.generate()
                }
                "2" -> {
                    sf.params.compType = SFCompositionType.ARC_GRID
                    sf.generate()
                }
                "3" -> {
                    sf.params.compType = SFCompositionType.BAND_INTERFERENCE
                    sf.generate()
                }
                "p" -> {
                    sf.params.paletteMode = sf.params.paletteMode.next()
                    sf.generate()
                }
                "o" -> {
                    sf.params.overlapMode = sf.params.overlapMode.next()
                    sf.generate()
                }
                "g" -> {
                    sf.params.separatorMode = !sf.params.separatorMode
                }
                "d" -> {
                    sf.params.debugOverlay = !sf.params.debugOverlay
                }
                "e" -> {
                    sf.export(drawer)
                }
            }
        }

        extend {
            sf.draw(drawer)
        }
    }
}
