package drawing

import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.extra.color.presets.MEDIUM_PURPLE
import org.openrndr.extra.color.presets.PURPLE
import org.openrndr.extra.noise.scatter
import org.openrndr.extra.olive.oliveProgram
import org.openrndr.extra.shapes.hobbyCurve
import org.openrndr.shape.intersections
import kotlin.random.Random

fun main() = application {
    configure {
        width = 500
        height = 900
    }
    oliveProgram {
        extend {
            drawer.clear(ColorRGBa.PINK)
            val points = drawer.bounds.scatter(66.0, random = Random(0))

            val curve  = hobbyCurve(points, closed = true).contour

            drawer.fill = ColorRGBa.MEDIUM_PURPLE
//            drawer.circles(points, 5.0)
            drawer.stroke = ColorRGBa.PURPLE
            drawer.strokeWeight = 1.0
            drawer.fill = null

            val subCurve = curve.sub(seconds * 0.1, seconds * 0.1 + 0.9)
            val intersections = subCurve.intersections(subCurve).map { it.position }

            drawer.contour(subCurve)
            drawer.circles(intersections, 7.0)


        }
    }
}