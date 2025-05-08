package drawing

import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.extra.color.presets.HONEYDEW
import org.openrndr.extra.color.presets.PURPLE
import org.openrndr.math.Vector2
import org.openrndr.extra.olive.oliveProgram
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

fun main() = application {
    configure {
        width = 800
        height = 600
    }
    oliveProgram {
        val radius = 200
        val positions = mutableListOf<Vector2>()

        for (angle in 0..359 step 1) {
            val rad = Math.toRadians(angle.toDouble())
            val offsetX = (radius * cos(rad) + Random.nextDouble(1.9)).toFloat()
            val offsetY = (radius * sin(rad) + Random.nextDouble(1.9)).toFloat()

            positions.add(Vector2(width / 2.0 + offsetX, height / 2.0 + offsetY))
        }
        
        
        extend {
            drawer.clear(ColorRGBa.BLACK)
            drawer.stroke = ColorRGBa.WHITE
            drawer.strokeWeight = 0.5
            drawer.fill = null
            drawer.circles(positions, 40.0)
        }

    }
}