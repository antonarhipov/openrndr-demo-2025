import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.extra.color.presets.CORNFLOWER_BLUE
import org.openrndr.math.Vector2
import kotlin.math.sin
import kotlin.math.cos
import kotlin.random.Random
import kotlin.math.PI

// Class representing a single bioluminescent particle
class Particle(
    var position: Vector2,
    var velocity: Vector2,
    var size: Double,
    var color: ColorRGBa,
    var phaseOffset: Double
) {
    // Update particle position based on velocity and wind simulation
    fun update(width: Double, height: Double, seconds: Double) {
        // Apply gentle wind effect using sine waves
        val windX = sin(seconds * 0.5 + position.y * 0.01) * 0.2
        val windY = cos(seconds * 0.3 + position.x * 0.01) * 0.1

        // Update velocity with wind effect
        velocity = velocity * 0.98 + Vector2(windX, windY)

        // Update position
        position += velocity

        // Wrap around screen boundaries
        if (position.x < 0) position = Vector2(width, position.y)
        if (position.x > width) position = Vector2(0.0, position.y)
        if (position.y < 0) position = Vector2(position.x, height)
        if (position.y > height) position = Vector2(position.x, 0.0)
    }

    // Calculate current brightness using sine wave
    fun brightness(seconds: Double): Double {
        return (sin(seconds * 0.8 + phaseOffset) * 0.5 + 0.5) * 0.8 + 0.2
    }

    // Draw the particle
    fun draw(drawer: org.openrndr.draw.Drawer, seconds: Double) {
        val bright = brightness(seconds)
        // Apply brightness to the alpha channel of the color
        drawer.fill = color.opacify(bright)
        drawer.circle(position, size * bright)
    }
}

fun main() = application {
    configure {
        width = 800
        height = 600
        title = "Bioluminescent Particles"
    }

    program {
        // Create a list of particles
        val particles = List(200) {
            // Randomly choose between blue and cyan colors
            val particleColor = when (Random.nextInt(3)) {
                0 -> ColorRGBa.BLUE
                1 -> ColorRGBa.CYAN
                else -> ColorRGBa.WHITE.opacify(0.7) // A bright white-blue color
            }

            Particle(
                position = Vector2(
                    Random.nextDouble() * width, 
                    Random.nextDouble() * height
                ),
                velocity = Vector2(
                    Random.nextDouble(-0.5, 0.5),
                    Random.nextDouble(-0.5, 0.5)
                ),
                size = Random.nextDouble(2.0, 6.0),
                color = particleColor,
                phaseOffset = Random.nextDouble(0.0, PI * 2)
            )
        }

        extend {
            // Clear background with a dark blue color
            drawer.clear(ColorRGBa.BLACK.opacify(0.05))

            // Update and draw all particles
            particles.forEach { particle ->
                particle.update(width.toDouble(), height.toDouble(), seconds)
                particle.draw(drawer, seconds)
            }
        }
    }
}
