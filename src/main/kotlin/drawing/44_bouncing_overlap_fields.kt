package drawing

import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.isolatedWithTarget
import org.openrndr.draw.renderTarget
import org.openrndr.extra.noise.Random
import org.openrndr.math.Vector2
import org.openrndr.shape.Circle
import org.openrndr.shape.intersection
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.*

/**
 * Bouncing Overlap Fields
 * A generative animation with elastic bouncing circles and distinct overlap rendering.
 *
 * Controls:
 * R: reseed & restart simulation
 * P: cycle palette
 * [ ]: decrease/increase number of circles
 * - =: adjust speed multiplier
 * O: toggle overlap rendering mode
 * T: toggle stroke mode
 * S: save current frame as PNG
 * D: debug overlay
 */

data class Ball(
    var pos: Vector2,
    var vel: Vector2,
    val r: Double,
    var baseColor: ColorRGBa
)

enum class OverlapPaletteMode {
    INK_NEON, WARM_PAPER, SYNTH_POP, OCEAN_FOG
}

data class OverlapColorScheme(
    val background: ColorRGBa,
    val circleColors: List<ColorRGBa>,
    val overlapColor: ColorRGBa
)

class BouncingOverlapFieldsParams {
    var seed: Long = (Math.random() * 1000000).toLong()
    var numCircles: Int = 20
    var rMin: Double = 25.0
    var rMax: Double = 70.0
    var vMin: Double = 0.5
    var vMax: Double = 2.5
    var globalAlpha: Double = 0.75
    var paletteMode: OverlapPaletteMode = OverlapPaletteMode.INK_NEON
    var strokeMode: Boolean = false
    var overlapMode: Boolean = true
    var speedMultiplier: Double = 1.0
    var debugMode: Boolean = false
}

fun main() = application {
    configure {
        width = 600
        height = 800
        title = "Bouncing Overlap Fields"
    }

    program {
        val params = BouncingOverlapFieldsParams()
        var balls = mutableListOf<Ball>()

        val rt = renderTarget(width, height) {
            colorBuffer()
        }

        val palettes = mapOf(
            OverlapPaletteMode.INK_NEON to OverlapColorScheme(
                background = ColorRGBa.fromHex("#000814"), // Near-black deep navy
                circleColors = listOf(
                    ColorRGBa.fromHex("#003566"),
                    ColorRGBa.fromHex("#001d3d"),
                    ColorRGBa.fromHex("#00607a"),
                    ColorRGBa.fromHex("#007ea7")
                ),
                overlapColor = ColorRGBa.fromHex("#ff00ff") // Neon Magenta
            ),
            OverlapPaletteMode.WARM_PAPER to OverlapColorScheme(
                background = ColorRGBa.fromHex("#fdf0d5"), // Warm off-white
                circleColors = listOf(
                    ColorRGBa.fromHex("#c1121f"), // Dusty red
                    ColorRGBa.fromHex("#780000"), // Maroon
                    ColorRGBa.fromHex("#669bbc"), // Dusty blue
                    ColorRGBa.fromHex("#003049")  // Charcoal blue
                ),
                overlapColor = ColorRGBa.fromHex("#3a015c") // Deep indigo/purple
            ),
            OverlapPaletteMode.SYNTH_POP to OverlapColorScheme(
                background = ColorRGBa.BLACK,
                circleColors = listOf(
                    ColorRGBa.fromHex("#00f5d4"), // Neon cyan
                    ColorRGBa.fromHex("#00bbf9"), // Hot blue
                    ColorRGBa.fromHex("#fee440"), // Acid yellow
                    ColorRGBa.fromHex("#f15bb5")  // Hot pink
                ),
                overlapColor = ColorRGBa.fromHex("#FF8C00") // High-energy orange
            ),
            OverlapPaletteMode.OCEAN_FOG to OverlapColorScheme(
                background = ColorRGBa.fromHex("#708d81"), // Blue-grey
                circleColors = listOf(
                    ColorRGBa.fromHex("#f4d35e"), // Pale sand
                    ColorRGBa.fromHex("#ee964b"), // Muted orange
                    ColorRGBa.fromHex("#f95738"), // Seafoam-ish / red
                    ColorRGBa.fromHex("#ebebd3")  // Pale fog
                ),
                overlapColor = ColorRGBa.fromHex("#003049") // Deep Blue
            )
        )

        fun createBall(palette: OverlapColorScheme): Ball {
            val r = Random.double(params.rMin, params.rMax)
            val pos = Vector2(
                Random.double(r, width - r),
                Random.double(r, height - r)
            )
            val angle = Random.double(0.0, 2.0 * PI)
            val v = Random.double(params.vMin, params.vMax)
            val vel = Vector2(cos(angle), sin(angle)) * v
            val color = palette.circleColors[Random.int(0, palette.circleColors.size - 1)]
            return Ball(pos, vel, r, color)
        }

        fun rebuild() {
            Random.seed = params.seed.toString()
            balls.clear()
            val palette = palettes[params.paletteMode]!!
            repeat(params.numCircles) {
                balls.add(createBall(palette))
            }
        }

        rebuild()

        var exportRequested = false

        keyboard.keyDown.listen {
            when (it.name) {
                "r" -> {
                    params.seed = (Math.random() * 1000000).toLong()
                    rebuild()
                }
                "p" -> {
                    val modes = OverlapPaletteMode.entries
                    params.paletteMode = modes[(params.paletteMode.ordinal + 1) % modes.size]
                    val palette = palettes[params.paletteMode]!!
                    balls.forEach { b ->
                        b.baseColor = palette.circleColors[Random.int(0, palette.circleColors.size - 1)]
                    }
                }
                "[", "bracketleft" -> {
                    if (balls.isNotEmpty()) {
                        balls.removeAt(balls.size - 1)
                        params.numCircles = balls.size
                    }
                }
                "]", "bracketright" -> {
                    if (balls.size < 80) {
                        val palette = palettes[params.paletteMode]!!
                        balls.add(createBall(palette))
                        params.numCircles = balls.size
                    }
                }
                "-" -> {
                    params.speedMultiplier *= 0.8
                }
                "=" -> {
                    params.speedMultiplier *= 1.2
                }
                "o" -> {
                    params.overlapMode = !params.overlapMode
                }
                "t" -> {
                    params.strokeMode = !params.strokeMode
                }
                "d" -> {
                    params.debugMode = !params.debugMode
                }
                "s" -> {
                    exportRequested = true
                }
            }
        }

        fun drawScene(d: org.openrndr.draw.Drawer) {
            val palette = palettes[params.paletteMode]!!
            d.clear(palette.background)

            // 1. Draw base circles
            d.stroke = if (params.strokeMode) ColorRGBa.WHITE.opacify(0.3) else null
            balls.forEach { b ->
                d.fill = b.baseColor.opacify(params.globalAlpha)
                d.circle(b.pos, b.r)
            }

            // 2. Draw overlaps
            if (params.overlapMode) {
                d.stroke = null
                for (i in 0 until balls.size) {
                    for (j in i + 1 until balls.size) {
                        val b1 = balls[i]
                        val b2 = balls[j]
                        val distSq = (b1.pos.x - b2.pos.x).pow(2) + (b1.pos.y - b2.pos.y).pow(2)
                        val rSum = b1.r + b2.r
                        if (distSq < rSum.pow(2)) {
                            // Using intersection to draw the overlapping region
                            val s1 = Circle(b1.pos, b1.r).shape
                            val s2 = Circle(b2.pos, b2.r).shape
                            val intersect = intersection(s1, s2)
                            d.fill = palette.overlapColor.opacify(params.globalAlpha)
                            d.shape(intersect)
                        }
                    }
                }
            }

            // 3. Debug overlay
            if (params.debugMode) {
                d.fill = null
                balls.forEach { b ->
                    d.stroke = ColorRGBa.GREEN.opacify(0.5)
                    d.lineSegment(b.pos, b.pos + b.vel * 10.0 * params.speedMultiplier)
                    d.stroke = ColorRGBa.RED.opacify(0.3)
                    // Bounce boundary for the center of the ball
                    d.rectangle(b.r, b.r, width - 2*b.r, height - 2*b.r)
                }
            }
        }

        extend {
            // Update
            balls.forEach { b ->
                b.pos += b.vel * params.speedMultiplier
                
                // Boundary bouncing with correction
                if (b.pos.x - b.r < 0) {
                    b.pos = b.pos.copy(x = b.r)
                    b.vel = b.vel.copy(x = abs(b.vel.x))
                } else if (b.pos.x + b.r > width) {
                    b.pos = b.pos.copy(x = width.toDouble() - b.r)
                    b.vel = b.vel.copy(x = -abs(b.vel.x))
                }
                
                if (b.pos.y - b.r < 0) {
                    b.pos = b.pos.copy(y = b.r)
                    b.vel = b.vel.copy(y = abs(b.vel.y))
                } else if (b.pos.y + b.r > height) {
                    b.pos = b.pos.copy(y = height.toDouble() - b.r)
                    b.vel = b.vel.copy(y = -abs(b.vel.y))
                }
            }

            if (exportRequested) {
                drawer.isolatedWithTarget(rt) {
                    drawScene(this)
                }
                val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                val filename = "images/bouncing_overlap_${params.seed}_$now.png"
                val file = File(filename)
                if (!file.parentFile.exists()) file.parentFile.mkdirs()
                rt.colorBuffer(0).saveToFile(file)
                println("Saved screenshot to $filename")
                exportRequested = false
            }

            drawScene(drawer)
        }
    }
}
