
import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.extra.color.presets.MEDIUM_PURPLE
import org.openrndr.extra.color.presets.PURPLE
import org.openrndr.extra.noise.scatter
import org.openrndr.extra.olive.oliveProgram
import org.openrndr.extra.shapes.hobbyCurve
import org.openrndr.extra.shapes.regularPolygon
import org.openrndr.math.Vector2
import org.openrndr.shape.Circle
import org.openrndr.shape.shape
import kotlin.math.sin
import kotlin.random.Random

/**
 *  This is a template for a live program.
 *
 *  It uses oliveProgram {} instead of program {}. All code inside the
 *  oliveProgram {} can be changed while the program is running.
 */

fun main() = application {
    configure {
        width = 800
        height = 600
    }
    oliveProgram {
        val random= Random(0)
        val positions = Circle(width / 2.0, height / 2.0, 200.0).contour.equidistantPositions(400).map {
            Vector2(it.x + random.nextDouble(2.0) - 0.5, it.y + random.nextDouble(2.0) - 0.5)
        }

        extend {
            drawer.clear(ColorRGBa.BLACK)
//            Circle(width / 2.0, height / 2.0, 200.0).contour.equidistantPositions(200).forEach {
//                drawer.stroke = ColorRGBa.WHITE
//                drawer.strokeWeight = 0.5
//                drawer.fill = null
//                val newPosition = Vector2(it.x + random.nextDouble(1.0) - 0.5, it.y + random.nextDouble(1.0) - 0.5 )
//                drawer.circle(newPosition, 30.0)
//            }
            drawer.stroke = ColorRGBa.WHITE
            drawer.strokeWeight = 0.5
            drawer.fill = null
            drawer.circles(positions, 40.0)
        }
    }
}