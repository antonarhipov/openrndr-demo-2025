package drawing

import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.extra.color.presets.PURPLE
import org.openrndr.extra.olive.oliveProgram
import org.openrndr.shape.Circle

fun main() = application {
    configure {
        width = 500
        height = 900
    }
    oliveProgram {
        extend {
            drawer.clear(ColorRGBa.PINK)

            val contour = Circle(width / 2.0, height / 2.0, 150.0).contour
            val points = contour.equidistantPositions(mouse.position.x.toInt())

            drawer.fill = null
            drawer.stroke = ColorRGBa.WHITE
            drawer.contour(contour)
            drawer.stroke = ColorRGBa.PURPLE
            drawer.circles(points, 30.0)
        }
    }
}