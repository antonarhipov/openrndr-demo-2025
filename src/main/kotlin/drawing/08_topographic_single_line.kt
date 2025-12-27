package drawing

import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.LineCap
import org.openrndr.extra.color.presets.IVORY
import org.openrndr.extra.color.presets.SLATE_GRAY
import org.openrndr.extra.shapes.hobbyCurve
import org.openrndr.math.Vector2
import org.openrndr.shape.Rectangle
import org.openrndr.shape.ShapeContour
import kotlin.math.abs
import kotlin.random.Random

fun main() = application {
    configure {
        width = 600
        height = 848
    }
    program {
        val margin = minOf(width, height) * 0.08
        val safeArea = drawer.bounds.offsetEdges(-margin)
        val rng = Random(42)

        // Stage 2: Generate point sequence (Long travel distance, avoiding center)
        val island = Rectangle.fromCenter(safeArea.center, 180.0, 180.0)
        
        val anchors = listOf(
            Vector2(safeArea.x, safeArea.y + safeArea.height * 0.2),
            Vector2(safeArea.center.x - 150.0, safeArea.center.y - 150.0),
            Vector2(safeArea.center.x + 150.0, safeArea.center.y + 150.0),
            Vector2(safeArea.x + safeArea.width, safeArea.y + safeArea.height * 0.8)
        )
        
        val points = mutableListOf<Vector2>()
        for (i in 0 until anchors.size - 1) {
            val a = anchors[i]
            val b = anchors[i+1]
            points.add(a)
            // Add some jittered intermediate points
            for (j in 1..3) {
                val t = j / 4.0
                val p = a * (1.0 - t) + b * t
                points.add(p + Vector2(rng.nextDouble(-30.0, 30.0), rng.nextDouble(-30.0, 30.0)))
            }
        }
        points.add(anchors.last())

        // Stage 3: Build the Hobby curve
        val heroCurve = hobbyCurve(points, closed = false).contour
        
        // Stage 4 & 5: Sample and generate offsets
        val numOffsets = 80
        val spacing = 4.0
        val samples = 1000
        val offsetContours = mutableListOf<List<Vector2>>()
        
        val curvePoints = (0..samples).map { heroCurve.position(it.toDouble() / samples) }
        val curveNormals = (0..samples).map { heroCurve.normal(it.toDouble() / samples) }

        for (i in 0 until numOffsets) {
            val offsetIndex = i - numOffsets / 2
            val offsetPoints = mutableListOf<Vector2>()
            for (j in 0..samples) {
                val p = curvePoints[j]
                val n = curveNormals[j]
                val offsetP = p + n * (offsetIndex * spacing)
                
                // Requirement 6: Negative space island
                // We'll skip points that are inside the island later during drawing
                // or just mark them as null
                offsetPoints.add(offsetP)
            }
            offsetContours.add(offsetPoints)
        }

        extend {
            drawer.clear(ColorRGBa.IVORY)
            
            // Background texture
            val paperRng = Random(42)
            drawer.fill = ColorRGBa.BLACK.opacify(0.03)
            drawer.stroke = null
            for (i in 0..3000) {
                drawer.circle(Vector2(paperRng.nextDouble() * width, paperRng.nextDouble() * height), 0.5)
            }

            // Draw the offsets
            for (i in offsetContours.indices) {
                val offsetIndex = i - numOffsets / 2
                val points = offsetContours[i]
                
                val isIndexContour = abs(offsetIndex) % 10 == 0
                val opacity = (1.0 - abs(offsetIndex).toDouble() / (numOffsets / 2.0)).coerceIn(0.0, 1.0)
                
                if (opacity <= 0.0) continue

                drawer.stroke = ColorRGBa.SLATE_GRAY.opacify(opacity * 0.8)
                drawer.strokeWeight = if (isIndexContour) 1.5 else 0.5
                drawer.fill = null
                drawer.lineCap = LineCap.ROUND

                val segments = mutableListOf<Vector2>()
                var breakCounter = 0
                for (j in 0 until points.size - 1) {
                    val p1 = points[j]
                    val p2 = points[j+1]
                    
                    val inIsland = island.contains(p1) || island.contains(p2)
                    
                    if (breakCounter > 0) {
                        breakCounter--
                        continue
                    }
                    
                    if (paperRng.nextDouble() < 0.002) {
                        breakCounter = paperRng.nextInt(5, 20)
                        continue
                    }
                    
                    if (!inIsland) {
                        segments.add(p1)
                        segments.add(p2)
                    }
                }
                if (segments.isNotEmpty()) {
                    drawer.lineSegments(segments)
                }
            }

            // Finishing touches
            drawer.fill = ColorRGBa.BLACK.opacify(0.7)
            drawer.stroke = null
            drawer.text("TOPOGRAPHIC SINGLE LINE", margin, height - margin / 2.0)
            drawer.text("2025 SERIES / NO. 08", width - margin * 3.0, height - margin / 2.0)
            
            drawer.fill = ColorRGBa.BLACK.opacify(0.4)
            drawer.text("HERO CURVE OFFSET CONTOURS", margin, margin / 1.5)
        }
    }
}
