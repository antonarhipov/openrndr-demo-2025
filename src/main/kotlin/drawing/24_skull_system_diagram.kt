package drawing

import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.color.rgb
import org.openrndr.draw.*
import org.openrndr.extra.color.presets.*
import org.openrndr.extra.noise.uniform
import org.openrndr.extra.shapes.hobbyCurve
import org.openrndr.math.Vector2
import org.openrndr.math.map
import org.openrndr.shape.*
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.*
import kotlin.random.Random

data class SkullParams(
    val seed: Long = Random.nextLong(),
    val paletteMode: PaletteMode = PaletteMode.NAVY_WHITE,
    val showDebug: Boolean = false
)

enum class PaletteMode {
    NAVY_WHITE, DARK_BLUEPRINT
}

class SkullSystemDiagram(val params: SkullParams) {
    val rng = Random(params.seed)
    
    val bg: ColorRGBa
    val linePrimary: ColorRGBa
    val fillLight: ColorRGBa
    val fillDark: ColorRGBa
    val accentRed: ColorRGBa
    val accentOrange: ColorRGBa

    val skullContour: ShapeContour
    val traces: List<Pair<ShapeContour, ColorRGBa>>
    val modules: List<Pair<Shape, ColorRGBa>>
    val eyeSubsystems: List<Pair<Shape, ColorRGBa>>
    val teeth: List<Shape>

    init {
        if (params.paletteMode == PaletteMode.NAVY_WHITE) {
            bg = rgb("FDFCF8") // Off-white
            linePrimary = rgb("1A2B44") // Navy
            fillLight = rgb("E0E0E0")
            fillDark = rgb("808080")
            accentRed = rgb("D32F2F")
            accentOrange = rgb("FBC02D")
        } else {
            bg = rgb("0A192F") // Dark navy
            linePrimary = rgb("64FFDA") // Cyan/Teal
            fillLight = rgb("112240")
            fillDark = rgb("233554")
            accentRed = rgb("F44336")
            accentOrange = rgb("FFEB3B")
        }
        
        skullContour = buildSkullContour()
        traces = generateTraces(skullContour)
        modules = generateModules(skullContour)
        eyeSubsystems = generateEyeSubsystems(skullContour)
        teeth = generateTeeth(skullContour)
    }

    private fun buildSkullContour(): ShapeContour {
        val center = Vector2(300.0, 400.0)
        val scale = 1.3
        
        // Side profile points (facing left)
        val points = listOf(
            Vector2(30.0, -180.0),   // Top cranium
            Vector2(120.0, -100.0),  // Back upper
            Vector2(150.0, 50.0),    // Back
            Vector2(100.0, 160.0),   // Nuchal
            Vector2(40.0, 180.0),    // Jaw hinge
            Vector2(40.0, 250.0),    // Jaw back
            Vector2(-40.0, 280.0),   // Chin
            Vector2(-90.0, 240.0),   // Mouth / Teeth lower
            Vector2(-90.0, 180.0),   // Mouth / Teeth upper
            Vector2(-110.0, 140.0),  // Nose bottom
            Vector2(-100.0, 80.0),   // Nose notch
            Vector2(-80.0, 0.0),     // Brow
            Vector2(-40.0, -120.0),  // Frontal
        ).map { it * scale + center }
        
        return ShapeContour.fromPoints(points, true).hobbyCurve()
    }

    private fun generateTraces(skullContour: ShapeContour): List<Pair<ShapeContour, ColorRGBa>> {
        val bounds = skullContour.bounds
        val traces = mutableListOf<Pair<ShapeContour, ColorRGBa>>()
        val step = 8.0
        
        val skullShape = Shape(listOf(skullContour))
        
        repeat(1800) {
            val start = Vector2(
                rng.nextDouble(bounds.x, bounds.x + bounds.width),
                rng.nextDouble(bounds.y, bounds.y + bounds.height)
            )
            if (skullShape.contains(start)) {
                // Higher density in jaw
                val isJaw = start.y > 550
                if (!isJaw && rng.nextDouble() < 0.4) return@repeat
                
                val path = mutableListOf(start)
                var curr = start
                val length = if (isJaw) rng.nextInt(2, 8) else rng.nextInt(5, 20)
                
                var lastDir = Vector2.ZERO
                repeat(length) {
                    val dirs = listOf(Vector2.UNIT_X, -Vector2.UNIT_X, Vector2.UNIT_Y, -Vector2.UNIT_Y)
                        .filter { it != -lastDir }
                    val dir = dirs[rng.nextInt(dirs.size)]
                    val next = curr + dir * step
                    if (skullShape.contains(next)) {
                        path.add(next)
                        curr = next
                        lastDir = dir
                    }
                }
                
                if (path.size > 1) {
                    val color = if (rng.nextDouble() < 0.12) accentOrange else linePrimary
                    val segments = mutableListOf<Segment>()
                    for (i in 0 until path.size - 1) {
                        segments.add(Segment(path[i], path[i+1]))
                    }
                    traces.add(ShapeContour(segments, false) to color)
                }
            }
        }

        // External traces
        repeat(20) {
            val t = rng.nextDouble()
            val start = skullContour.position(t)
            val normal = skullContour.normal(t)
            val end = start + normal * rng.nextDouble(10.0, 25.0)
            traces.add(ShapeContour(listOf(Segment(start, end)), false) to linePrimary)
        }

        return traces
    }

    private fun generateModules(skullContour: ShapeContour): List<Pair<Shape, ColorRGBa>> {
        val bounds = skullContour.bounds
        val modules = mutableListOf<Pair<Shape, ColorRGBa>>()
        val skullShape = Shape(listOf(skullContour))
        
        repeat(120) {
            val w = rng.nextDouble(10.0, 50.0)
            val h = rng.nextDouble(5.0, 30.0)
            val pos = Vector2(
                rng.nextDouble(bounds.x, bounds.x + bounds.width),
                rng.nextDouble(bounds.y, bounds.y + bounds.height)
            )
            
            if (skullShape.contains(pos)) {
                val rect = when(rng.nextInt(4)) {
                    0 -> Rectangle.fromCenter(pos, w, h)
                    1 -> Rectangle.fromCenter(pos, w*0.3, h*2.0)
                    2 -> Rectangle.fromCenter(pos, w*2.0, h*0.3)
                    else -> Circle(pos, w*0.5).contour.bounds
                }
                
                val color = when(rng.nextInt(5)) {
                    0 -> fillLight
                    1 -> fillDark
                    2 -> ColorRGBa.BLACK.opacify(0.3)
                    3 -> accentRed.opacify(0.1)
                    else -> linePrimary.opacify(0.08)
                }
                modules.add(Shape(listOf(rect.contour)) to color)
            }
        }
        return modules
    }

    private fun generateEyeSubsystems(skullContour: ShapeContour): List<Pair<Shape, ColorRGBa>> {
        val center = Vector2(300.0, 400.0)
        
        val cx = center.x - 45.0 * 1.3
        val cy = center.y - 10.0 * 1.3
        
        val list = mutableListOf<Pair<Shape, ColorRGBa>>()
        
        // Red turbine
        val turbineCenter = Vector2(cx, cy)
        list.add(Shape(listOf(Circle(turbineCenter, 38.0).contour)) to linePrimary.opacify(0.2))
        list.add(Shape(listOf(Circle(turbineCenter, 32.0).contour)) to accentRed.opacify(0.3))
        list.add(Shape(listOf(Circle(turbineCenter, 28.0).contour)) to accentRed)
        list.add(Shape(listOf(Circle(turbineCenter, 12.0).contour)) to bg)
        
        repeat(12) { i ->
            val angle = i * PI * 2 / 12
            val p = turbineCenter + Vector2(cos(angle), sin(angle)) * 20.0
            val blade = Rectangle.fromCenter(p, 10.0, 2.0).contour
            // We should ideally rotate this, but fixed is okay for schematic
            list.add(Shape(listOf(blade)) to linePrimary)
        }

        // Sealed chamber (Eye 2)
        val chamberCenter = turbineCenter + Vector2(100.0, 10.0)
        list.add(Shape(listOf(Rectangle.fromCenter(chamberCenter, 60.0, 50.0).contour)) to fillDark)
        list.add(Shape(listOf(Circle(chamberCenter, 20.0).contour)) to fillLight)
        list.add(Shape(listOf(Circle(chamberCenter, 15.0).contour)) to linePrimary.opacify(0.3))
        
        repeat(12) { i ->
            val angle = i * PI * 2 / 12
            val p = chamberCenter + Vector2(cos(angle), sin(angle)) * 17.0
            list.add(Shape(listOf(Rectangle.fromCenter(p, 4.0, 1.0).contour)) to linePrimary)
        }
        
        return list
    }

    private fun generateTeeth(skullContour: ShapeContour): List<Shape> {
        val center = Vector2(300.0, 400.0)
        val list = mutableListOf<Shape>()
        for (i in 0 until 7) {
            val x = center.x - 125.0 + i * 18.0
            val y = center.y + 245.0
            list.add(Shape(listOf(Rectangle(x, y, 14.0, 22.0).contour)))
            list.add(Shape(listOf(Rectangle(x, y + 26.0, 14.0, 22.0).contour)))
        }
        return list
    }

    fun render(drawer: Drawer) {
        val w = 600.0
        val h = 800.0
        
        val contentRT = renderTarget(w.toInt(), h.toInt()) {
            colorBuffer()
            depthBuffer(DepthFormat.DEPTH24_STENCIL8)
        }
        val maskRT = renderTarget(w.toInt(), h.toInt()) {
            colorBuffer()
            depthBuffer(DepthFormat.DEPTH24_STENCIL8)
        }
        
        drawer.isolatedWithTarget(maskRT) {
            clear(ColorRGBa.TRANSPARENT)
            fill = ColorRGBa.WHITE
            stroke = null
            contour(skullContour)
        }
        
        drawer.isolatedWithTarget(contentRT) {
            clear(ColorRGBa.TRANSPARENT)
            // Modules
            for ((shape, color) in modules) {
                fill = color
                stroke = linePrimary.opacify(0.5)
                strokeWeight = 0.5
                shape(shape)
            }
            
            // Traces
            fill = null
            for ((trace, color) in traces) {
                stroke = color
                strokeWeight = if (color == accentOrange) 1.5 else 1.0
                contour(trace)
                
                // Junction dots
                val dotRng = Random(params.seed + trace.hashCode())
                if (dotRng.nextDouble() < 0.1) {
                    fill = color
                    stroke = null
                    circle(trace.segments.first().start, 1.5)
                    circle(trace.segments.last().end, 1.5)
                }
            }
            
            // Eye subsystems
            for ((shape, color) in eyeSubsystems) {
                fill = color
                stroke = linePrimary
                strokeWeight = 1.0
                shape(shape)
            }
            
            // Teeth
            fill = ColorRGBa.WHITE
            stroke = linePrimary
            strokeWeight = 1.0
            for (tooth in teeth) {
                shape(tooth)
            }
        }
        
        // Draw contentRT masked by maskRT
        drawer.isolated {
            drawer.shadeStyle = shadeStyle {
                fragmentTransform = """
                    vec2 uv = c_boundsPosition.xy;
                    vec4 color = texture(p_content, vec2(uv.x, 1.0 - uv.y));
                    vec4 mask = texture(p_mask, vec2(uv.x, 1.0 - uv.y));
                    x_fill = color;
                    x_fill.a *= mask.a;
                """
                parameter("content", contentRT.colorBuffer(0))
                parameter("mask", maskRT.colorBuffer(0))
            }
            drawer.fill = ColorRGBa.WHITE
            drawer.stroke = null
            drawer.rectangle(0.0, 0.0, w, h)
        }
        
        contentRT.destroy()
        maskRT.destroy()
        
        // Silhouette
        drawer.fill = null
        drawer.stroke = linePrimary
        drawer.strokeWeight = 3.0
        drawer.contour(skullContour)
        
        // Ports along outline
        renderPorts(drawer)
    }

    private fun renderPorts(drawer: Drawer) {
        val count = 60
        for (i in 0 until count step 3) {
            val t = i.toDouble() / count
            val p = skullContour.position(t)
            val normal = skullContour.normal(t)
            val outer = p + normal * 12.0
            drawer.stroke = linePrimary
            drawer.strokeWeight = 1.0
            drawer.lineSegment(p, outer)
            drawer.fill = linePrimary
            drawer.circle(outer, 2.0)
            
            if (i % 6 == 0) {
                drawer.fill = fillLight
                drawer.rectangle(Rectangle.fromCenter(outer, 6.0, 6.0))
            }
        }
    }

    fun buildZones(skullContour: ShapeContour): List<Pair<String, Shape>> {
        val bounds = skullContour.bounds
        val cx = bounds.center.x
        val cy = bounds.center.y
        
        val forehead = Shape(listOf(Rectangle(bounds.x, bounds.y, bounds.width, bounds.height * 0.4).contour))
        val eyeZone = Shape(listOf(Circle(cx - 40.0, cy - 40.0, 60.0).contour))
        val nasalZone = Shape(listOf(Circle(cx - 80.0, cy + 20.0, 40.0).contour))
        val jawZone = Shape(listOf(Rectangle(bounds.x, cy + 80.0, bounds.width, bounds.height * 0.4).contour))
        val teethZone = Shape(listOf(Rectangle(cx - 120.0, cy + 120.0, 100.0, 60.0).contour))
        
        return listOf(
            "forehead" to forehead,
            "eye" to eyeZone,
            "nasal" to nasalZone,
            "jaw" to jawZone,
            "teeth" to teethZone
        )
    }

    fun renderBackground(drawer: Drawer) {
        drawer.clear(bg)
        
        // Faint grid
        drawer.stroke = linePrimary.opacify(0.05)
        drawer.strokeWeight = 1.0
        for (x in 0..600 step 40) {
            drawer.lineSegment(x.toDouble(), 0.0, x.toDouble(), 800.0)
        }
        for (y in 0..800 step 40) {
            drawer.lineSegment(0.0, y.toDouble(), 600.0, y.toDouble())
        }
    }

    fun renderTitleBlock(drawer: Drawer) {
        drawer.fill = linePrimary
        drawer.stroke = null
        val font = loadFont("data/fonts/default.otf", 12.0)
        drawer.fontMap = font
        
        val x = 40.0
        val y = 740.0
        drawer.text("SKULL SYSTEM DIAGRAM", x, y)
        drawer.text("SEED: ${params.seed.toString(16).uppercase()}", x, y + 15)
        drawer.text("REV A / 2025", x, y + 30)
        
        // Barcode-like lines
        for (i in 0 until 10) {
            val w = rng.nextDouble(1.0, 4.0)
            drawer.rectangle(x + 150 + i * 5, y - 10, w, 20.0)
        }
    }
}

fun main() = application {
    configure {
        width = 600
        height = 800
    }
    program {
        var params = SkullParams()
        var skull = SkullSystemDiagram(params)
        
        keyboard.keyDown.listen {
            when (it.name) {
                "r" -> {
                    params = params.copy(seed = Random.nextLong())
                    skull = SkullSystemDiagram(params)
                }
                "1" -> {
                    params = params.copy(paletteMode = PaletteMode.NAVY_WHITE)
                    skull = SkullSystemDiagram(params)
                }
                "2" -> {
                    params = params.copy(paletteMode = PaletteMode.DARK_BLUEPRINT)
                    skull = SkullSystemDiagram(params)
                }
                "d" -> {
                    params = params.copy(showDebug = !params.showDebug)
                    skull = SkullSystemDiagram(params)
                }
                "e" -> {
                    val rt = renderTarget(600, 800) {
                        colorBuffer()
                        depthBuffer(DepthFormat.DEPTH24_STENCIL8)
                    }
                    drawer.isolatedWithTarget(rt) {
                        skull.renderBackground(this)
                        skull.render(this)
                        skull.renderTitleBlock(this)
                    }
                    val filename = "skull_${params.seed}_${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.png"
                    rt.colorBuffer(0).saveToFile(File(filename))
                    rt.destroy()
                    println("Exported $filename")
                }
            }
        }

        extend {
            skull.renderBackground(drawer)
            skull.render(drawer)
            
            if (params.showDebug) {
                val zones = skull.buildZones(skull.skullContour)
                drawer.strokeWeight = 1.0
                for ((name, shape) in zones) {
                    drawer.fill = ColorRGBa.RED.opacify(0.2)
                    drawer.shape(shape)
                }
            }
            
            skull.renderTitleBlock(drawer)
        }
    }
}
