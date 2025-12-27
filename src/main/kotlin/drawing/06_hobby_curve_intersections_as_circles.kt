package drawing

import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.extra.noise.scatter
import org.openrndr.extra.shapes.hobbyCurve
import org.openrndr.shape.intersections
import kotlin.random.Random

fun main() = application {
    configure {
        width = 800
        height = 800
    }
    program {
        // 1. scatter 100 points over the surface
        // Using a radius that ensures we get at least 100 points, then taking exactly 100
        val points = drawer.bounds.scatter(40.0, random = Random(42)).take(100)
        
        // 2. draw a hobby curve using the points
        // We create a closed hobby curve for more interesting self-intersections
        val curve = hobbyCurve(points, closed = true).contour
        
        // Find self-intersection points of the hobby curve
        val intersections = curve.intersections(curve).map { it.position }
        
        // 4. use the intersection points to draw circles with random radius
        val rng = Random(42)
        val radii = intersections.map { rng.nextDouble(2.0, 10.0) }

        extend {
            drawer.clear(ColorRGBa.fromHex("#1a1a1a"))
            
            // Draw the hobby curve (Requirement 2)
            // Using low opacity to keep the focus on intersection points (Requirement 3)
            drawer.fill = null
            drawer.stroke = ColorRGBa.GRAY.opacify(0.3)
            drawer.strokeWeight = 1.0
            drawer.contour(curve)
            
            // 3. only visualize the points that appear in the intersection points
            // 4. use the intersection points to draw circles with random radius
            drawer.fill = ColorRGBa.PINK.opacify(0.8)
            drawer.stroke = null
            drawer.circles(intersections, radii)
        }
    }
}

