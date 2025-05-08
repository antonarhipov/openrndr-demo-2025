package drawing

import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.extra.color.presets.HONEYDEW
import org.openrndr.extra.color.presets.PURPLE
import org.openrndr.extra.olive.oliveProgram

fun main() = application {
    configure {
        width = 500
        height = 900
    }
    oliveProgram {
        extend {
            drawer.clear(ColorRGBa.PINK)
            drawer.fill = ColorRGBa.HONEYDEW
            drawer.stroke = ColorRGBa.PURPLE
            drawer.strokeWeight = 3.0
            drawer.circle(width / 2.0, height / 2.0, 150.0)
        }
    }
}