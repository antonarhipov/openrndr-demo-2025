package drawing

import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.extra.color.presets.MEDIUM_PURPLE
import org.openrndr.extra.color.presets.PURPLE
import org.openrndr.extra.noise.scatter
import org.openrndr.extra.olive.oliveProgram
import org.openrndr.extra.shapes.hobbyCurve
import kotlin.random.Random

fun main() = application {
    configure {
        width = 500
        height = 900
    }
    oliveProgram {
        extend {
            drawer.clear(ColorRGBa.PINK)
            val points = drawer.bounds.scatter(50.0, random = Random(0))

            val curve  = hobbyCurve(points, closed = true).contour

            drawer.fill = ColorRGBa.MEDIUM_PURPLE
            drawer.circles(points, 5.0)
            drawer.stroke = ColorRGBa.PURPLE
            drawer.strokeWeight = 1.0
            drawer.fill = null
            drawer.contour(curve.sub(seconds * 0.1, seconds * 0.1 + 0.1))
        }
    }
}