package drawing

import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.isolated
import org.openrndr.extra.color.presets.IVORY
import org.openrndr.extra.color.presets.SLATE_GRAY
import org.openrndr.extra.shapes.hobbyCurve
import org.openrndr.math.Vector2
import org.openrndr.shape.ShapeContour
import org.openrndr.shape.intersections
import kotlin.math.PI
import kotlin.math.abs
import kotlin.random.Random

fun main() = application {
    configure {
        width = 600
        height = 848 // A-series aspect ratio (approx)
    }
    program {
        val margin = minOf(width, height) * 0.08
        val safeArea = drawer.bounds.offsetEdges(-margin)
        val rng = Random(42)

        // Stage 2: Generate point sequence (Anchor points + micro-jitter)
        val anchors = listOf(
            Vector2(safeArea.center.x, safeArea.y + margin),
            Vector2(safeArea.x + safeArea.width - margin, safeArea.center.y),
            Vector2(safeArea.center.x, safeArea.y + safeArea.height - margin),
            Vector2(safeArea.x + margin, safeArea.center.y),
            Vector2(safeArea.center.x, safeArea.center.y)
        )

        val points = mutableListOf<Vector2>()
        for (i in anchors.indices) {
            points.add(anchors[i])
            if (i < anchors.size - 1) {
                val mid = (anchors[i] + anchors[i + 1]) / 2.0
                points.add(mid + Vector2(rng.nextDouble(-20.0, 20.0), rng.nextDouble(-20.0, 20.0)))
            }
        }

        // Stage 3: Build the Hobby curve
        val curve = hobbyCurve(points, closed = false).contour

        // Stage 4 & 5: Derive visual meaning and Build Ribbon
        val baseWidth = minOf(width, height) * 0.05

        fun getWidth(t: Double): Double {
            // Approximate curvature by looking at position change
            val eps = 0.005
            val t0 = (t - eps).coerceIn(0.0, 1.0)
            val tmid = t
            val t1 = (t + eps).coerceIn(0.0, 1.0)
            
            val p0 = curve.position(t0)
            val pmid = curve.position(tmid)
            val p1 = curve.position(t1)
            
            val v1 = (pmid - p0)
            val v2 = (p1 - pmid)
            
            val angle = abs(Math.atan2(v2.y, v2.x) - Math.atan2(v1.y, v1.x))
            val normalizedAngle = if (angle > PI) 2 * PI - angle else angle
            val curvature = normalizedAngle / (v1.length + v2.length)
            
            // Stroke width w(t) = base + k / (ε + curvature(t))
            // Thick on straights, thinner on tight turns
            return baseWidth * (0.4 + 1.0 / (1.0 + curvature * 2.0))
        }

        // Stage 6: Add depth at self-intersections
        val intersections = curve.intersections(curve)
        val intersectionTs = intersections.flatMap { 
            listOf(it.a.contourT, it.b.contourT)
        }.sorted()
        val allTs = (listOf(0.0) + intersectionTs + listOf(1.0)).distinct().sorted()

        data class RibbonSegment(val contour: ShapeContour, val highlight: ShapeContour, val meanT: Double, val isOver: Boolean)

        val ribbonSegments = allTs.windowed(2).map { (t0, t1) ->
            val ribbonPointsLeft = mutableListOf<Vector2>()
            val ribbonPointsRight = mutableListOf<Vector2>()
            val highlightPoints = mutableListOf<Vector2>()
            
            val localSamples = ( (t1-t0) * 1000).toInt().coerceAtLeast(10)
            for (i in 0..localSamples) {
                val t = t0 + (t1 - t0) * i / localSamples
                val p = curve.position(t)
                val n = curve.normal(t)
                val w = getWidth(t)
                
                ribbonPointsLeft.add(p + n * (w / 2.0))
                ribbonPointsRight.add(p - n * (w / 2.0))
                highlightPoints.add(p + n * (w * 0.3)) // Offset highlight
            }
            val poly = ShapeContour.fromPoints(ribbonPointsLeft + ribbonPointsRight.reversed(), closed = true)
            val highlight = ShapeContour.fromPoints(highlightPoints, closed = false)
            
            val meanT = (t0 + t1) / 2.0
            val p0 = curve.position((meanT - 0.001).coerceIn(0.0, 1.0))
            val p1 = curve.position((meanT + 0.001).coerceIn(0.0, 1.0))
            val tangent = p1 - p0
            val over = tangent.x > 0
            
            RibbonSegment(poly, highlight, meanT, over)
        }

        extend {
            drawer.clear(ColorRGBa.IVORY)
            
            // Draw background "paper" texture
            drawer.stroke = null
            drawer.fill = ColorRGBa.BLACK.opacify(0.05)
            val paperRng = Random(42)
            for (i in 0..5000) {
                drawer.circle(Vector2(paperRng.nextDouble() * width, paperRng.nextDouble() * height), 0.5)
            }

            // Draw the ribbon segments
            val sortedSegments = ribbonSegments.sortedBy { if (it.isOver) 1 else 0 }
            
            for (segment in sortedSegments) {
                if (segment.isOver) {
                    // Draw a small "gap"
                    drawer.fill = ColorRGBa.IVORY
                    drawer.stroke = ColorRGBa.IVORY
                    drawer.strokeWeight = 8.0
                    drawer.contour(segment.contour)
                }
                
                // Ink bleed (subtle jittered copies)
                drawer.stroke = null
                for (i in 0..3) {
                    drawer.fill = ColorRGBa.SLATE_GRAY.opacify(0.1)
                    drawer.isolated {
                        drawer.translate(paperRng.nextDouble(-1.0, 1.0), paperRng.nextDouble(-1.0, 1.0))
                        drawer.contour(segment.contour)
                    }
                }

                // Main ribbon body
                drawer.fill = ColorRGBa.SLATE_GRAY
                drawer.stroke = ColorRGBa.BLACK.opacify(0.3)
                drawer.strokeWeight = 1.0
                drawer.contour(segment.contour)

                // Highlight strip
                drawer.fill = null
                drawer.stroke = ColorRGBa.WHITE.opacify(0.2)
                drawer.strokeWeight = 2.0
                drawer.contour(segment.highlight)
            }
            
            // Finishing touches: Text
            drawer.fill = ColorRGBa.BLACK.opacify(0.8)
            drawer.stroke = null
            
            drawer.text("SINGLE CURVE COMPOSITION", margin, height - margin / 2.0)
            drawer.text("2025 SERIES / NO. 07", width - margin * 3.0, height - margin / 2.0)
            
            drawer.fill = ColorRGBa.BLACK.opacify(0.4)
            drawer.text("HOBBY CURVE RIBBON", margin, margin / 1.5)
        }
    }
}
