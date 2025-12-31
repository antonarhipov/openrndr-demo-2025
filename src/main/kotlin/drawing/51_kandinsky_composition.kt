package drawing

import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.draw.isolated
import org.openrndr.extensions.Screenshots
import org.openrndr.math.Vector2
import org.openrndr.shape.ShapeContour
import kotlin.math.*
import java.util.Random

/**
 * A Kandinsky-inspired geometric composition with interactivity.
 * 
 * Interactivity:
 * - 'e': Export image to file
 * - 'r': Regenerate with new random seed (resets to original composition)
 * - '[': Remove random element
 * - ']': Add random element
 */

sealed class KandinskyElement(val layer: Int, val seedOffset: Int) {
    abstract fun draw(drawer: Drawer, globalSeed: Int)
}

class KLineElement(val p1: Vector2, val p2: Vector2, val color: ColorRGBa, val weight: Double, layer: Int, seedOffset: Int) : KandinskyElement(layer, seedOffset) {
    override fun draw(drawer: Drawer, globalSeed: Int) {
        val rng = Random((globalSeed + seedOffset).toLong())
        val j1 = Vector2(rng.nextDouble() - 0.5, rng.nextDouble() - 0.5) * 20.0
        val j2 = Vector2(rng.nextDouble() - 0.5, rng.nextDouble() - 0.5) * 20.0
        drawer.stroke = color
        drawer.strokeWeight = weight * (0.9 + rng.nextDouble() * 0.2)
        drawer.fill = null
        drawer.lineSegment(p1 + j1, p2 + j2)
    }
}

class KRectElement(val center: Vector2, val w: Double, val h: Double, val rotDeg: Double, val fillColor: ColorRGBa, layer: Int, seedOffset: Int) : KandinskyElement(layer, seedOffset) {
    override fun draw(drawer: Drawer, globalSeed: Int) {
        val rng = Random((globalSeed + seedOffset).toLong())
        val jPos = Vector2(rng.nextDouble() - 0.5, rng.nextDouble() - 0.5) * 20.0
        val jRot = (rng.nextDouble() - 0.5) * 10.0
        val jW = w * (0.95 + rng.nextDouble() * 0.1)
        val jH = h * (0.95 + rng.nextDouble() * 0.1)
        drawer.drawRotatedRect(center + jPos, jW, jH, rotDeg + jRot, fillColor)
    }
}

class KPolygonElement(val center: Vector2, val w: Double, val h: Double, val sides: Int, val rotDeg: Double, val fillColor: ColorRGBa, layer: Int, seedOffset: Int) : KandinskyElement(layer, seedOffset) {
    override fun draw(drawer: Drawer, globalSeed: Int) {
        val rng = Random((globalSeed + seedOffset).toLong())
        val jPos = Vector2(rng.nextDouble() - 0.5, rng.nextDouble() - 0.5) * 20.0
        val jRot = (rng.nextDouble() - 0.5) * 10.0
        drawer.drawRegularishPolygon(center + jPos, w, h, sides, rotDeg + jRot, fillColor, globalSeed + seedOffset)
    }
}

class KHatchedElement(val center: Vector2, val w: Double, val h: Double, val rotDeg: Double, val hatchAngle: Double, val spacing: Double, val strokeColor: ColorRGBa, layer: Int, seedOffset: Int) : KandinskyElement(layer, seedOffset) {
    override fun draw(drawer: Drawer, globalSeed: Int) {
        val rng = Random((globalSeed + seedOffset).toLong())
        val jPos = Vector2(rng.nextDouble() - 0.5, rng.nextDouble() - 0.5) * 15.0
        val jRot = (rng.nextDouble() - 0.5) * 5.0
        drawer.drawHatchedParallelogram(center + jPos, w, h, rotDeg + jRot, hatchAngle, spacing, strokeColor)
    }
}

class KCircleElement(val center: Vector2, val radius: Double, val color: ColorRGBa, val weight: Double, val filled: Boolean, layer: Int, seedOffset: Int) : KandinskyElement(layer, seedOffset) {
    override fun draw(drawer: Drawer, globalSeed: Int) {
        val rng = Random((globalSeed + seedOffset).toLong())
        val jPos = Vector2(rng.nextDouble() - 0.5, rng.nextDouble() - 0.5) * 20.0
        val jRadius = radius * (0.9 + rng.nextDouble() * 0.2)
        drawer.stroke = if (filled) null else color
        drawer.strokeWeight = if (filled) 0.0 else weight * (0.9 + rng.nextDouble() * 0.2)
        drawer.fill = if (filled) color else null
        drawer.circle(center + jPos, jRadius)
    }
}

class KDotElement(val pos: Vector2, val radius: Double, val color: ColorRGBa, layer: Int, seedOffset: Int) : KandinskyElement(layer, seedOffset) {
    override fun draw(drawer: Drawer, globalSeed: Int) {
        val rng = Random((globalSeed + seedOffset).toLong())
        val jPos = Vector2(rng.nextDouble() - 0.5, rng.nextDouble() - 0.5) * 10.0
        drawer.fill = color
        drawer.stroke = null
        drawer.circle(pos + jPos, radius)
    }
}

// Helper Extensions
fun Drawer.drawRotatedRect(center: Vector2, w: Double, h: Double, rotDeg: Double, fillColor: ColorRGBa) {
    isolated {
        translate(center)
        rotate(rotDeg)
        fill = fillColor
        stroke = null
        rectangle(-w / 2.0, -h / 2.0, w, h)
    }
}

fun Drawer.drawRegularishPolygon(center: Vector2, w: Double, h: Double, sides: Int, rotDeg: Double, fillColor: ColorRGBa, polygonSeed: Int) {
    val rng = Random(polygonSeed.toLong())
    val points = mutableListOf<Vector2>()
    for (i in 0 until sides) {
        val angle = (i.toDouble() / sides) * 2.0 * PI
        val r = 0.85 + rng.nextDouble() * 0.15
        val x = cos(angle) * (w / 2.0) * r
        val y = sin(angle) * (h / 2.0) * r
        points.add(Vector2(x, y))
    }
    isolated {
        translate(center)
        rotate(rotDeg)
        fill = fillColor
        stroke = null
        contour(ShapeContour.fromPoints(points, closed = true))
    }
}

fun Drawer.drawHatchedParallelogram(center: Vector2, w: Double, h: Double, rotDeg: Double, hatchAngleDeg: Double, spacing: Double, strokeColor: ColorRGBa) {
    isolated {
        translate(center)
        rotate(rotDeg)
        stroke = strokeColor
        strokeWeight = 2.0
        fill = null
        val angleRad = Math.toRadians(hatchAngleDeg)
        val cosA = cos(angleRad)
        val sinA = sin(angleRad)
        val diagonal = sqrt(w * w + h * h)
        val halfDiag = diagonal / 2.0
        var d = -halfDiag
        while (d <= halfDiag) {
            val intersections = mutableListOf<Vector2>()
            if (abs(sinA) > 1e-6) {
                val y1 = (d - (w / 2.0) * cosA) / sinA
                if (y1 >= -h / 2.0 && y1 <= h / 2.0) intersections.add(Vector2(w / 2.0, y1))
                val y2 = (d - (-w / 2.0) * cosA) / sinA
                if (y2 >= -h / 2.0 && y2 <= h / 2.0) intersections.add(Vector2(-w / 2.0, y2))
            }
            if (abs(cosA) > 1e-6) {
                val x1 = (d - (h / 2.0) * sinA) / cosA
                if (x1 >= -w / 2.0 && x1 <= w / 2.0) intersections.add(Vector2(x1, h / 2.0))
                val x2 = (d - (-h / 2.0) * sinA) / cosA
                if (x2 >= -w / 2.0 && x2 <= w / 2.0) intersections.add(Vector2(x2, -h / 2.0))
            }
            val unique = intersections.distinctBy { (it.x * 1000).toInt() to (it.y * 1000).toInt() }
            if (unique.size >= 2) {
                lineSegment(unique[0], unique[1])
            }
            d += spacing
        }
    }
}

fun main() = application {
    configure {
        width = 1200
        height = 800
        title = "Kandinsky Composition"
    }

    program {
        var seed = kotlin.random.Random.nextInt()
        val activeElements = mutableListOf<KandinskyElement>()

        // Colors
        val black = ColorRGBa.fromHex("#0B0B0B")
        val graphite = ColorRGBa.fromHex("#4A4A4A")
        val satBlue = ColorRGBa.fromHex("#1877F2")
        val satRed = ColorRGBa.fromHex("#D92B2B")
        val satYellow = ColorRGBa.fromHex("#F2C400")
        val mutedGreen = ColorRGBa.fromHex("#8DBB86")
        val mutedViolet = ColorRGBa.fromHex("#8E6AB8")
        val palette = listOf(black, satBlue, satRed, satYellow, mutedGreen, mutedViolet)

        fun createOriginalElements() {
            activeElements.clear()
            var offset = 100
            
            // Layer 2: Construction lines
            val constructionPoints = listOf(
                Vector2(260.0, 430.0) to Vector2(1710.0, 650.0),
                Vector2(420.0, 110.0) to Vector2(1440.0, 1030.0),
                Vector2(210.0, 820.0) to Vector2(1550.0, 320.0),
                Vector2(1050.0, 160.0) to Vector2(1250.0, 980.0),
                Vector2(770.0, 380.0) to Vector2(1780.0, 270.0),
                Vector2(470.0, 960.0) to Vector2(1350.0, 470.0),
                Vector2(960.0, 600.0) to Vector2(1820.0, 880.0)
            )
            constructionPoints.forEach { (p1, p2) -> activeElements.add(KLineElement(p1, p2, graphite.opacify(0.35), 2.0, 2, offset++)) }

            // Layer 3: Hatched parallelograms
            activeElements.add(KHatchedElement(Vector2(430.0, 590.0), 420.0, 170.0, -20.0, 18.0, 6.0, graphite.opacify(0.35), 3, offset++))
            activeElements.add(KHatchedElement(Vector2(1140.0, 830.0), 500.0, 190.0, 22.0, 18.0, 6.0, graphite.opacify(0.35), 3, offset++))

            // Layer 4 & 5: Polygons
            activeElements.add(KPolygonElement(Vector2(560.0, 740.0), 920.0, 360.0, 7, -12.0, mutedGreen.opacify(0.92), 4, offset++))
            activeElements.add(KPolygonElement(Vector2(1240.0, 660.0), 610.0, 280.0, 6, 18.0, mutedViolet.opacify(0.92), 5, offset++))

            // Layer 6: Blue rings
            val ringCenter = Vector2(1420.0, 740.0)
            activeElements.add(KCircleElement(ringCenter, 65.0, satBlue, 6.0, false, 6, offset++))
            activeElements.add(KCircleElement(ringCenter, 130.0, satBlue, 6.0, false, 6, offset++))
            activeElements.add(KCircleElement(ringCenter, 195.0, satBlue, 6.0, false, 6, offset++))

            // Layer 7: Square + red dot
            val blueSqCenter = Vector2(1540.0, 600.0)
            activeElements.add(KRectElement(blueSqCenter, 130.0, 130.0, 10.0, satBlue, 7, offset++))
            activeElements.add(KCircleElement(blueSqCenter, 24.0, satRed, 0.0, true, 7, offset++))

            // Layer 8: Yellow spark
            activeElements.add(KCircleElement(Vector2(1700.0, 210.0), 19.0, satYellow, 0.0, true, 8, offset++))

            // Layer 9: Small rectangles
            activeElements.add(KRectElement(Vector2(1180.0, 140.0), 45.0, 80.0, 8.0, mutedViolet.shade(0.85), 9, offset++))
            activeElements.add(KRectElement(Vector2(1460.0, 840.0), 55.0, 90.0, -22.0, mutedGreen.shade(0.85), 9, offset++))

            // Layer 10: Thick lines
            activeElements.add(KLineElement(Vector2(220.0, 170.0), Vector2(1680.0, 780.0), black, 20.0, 10, offset++))
            activeElements.add(KLineElement(Vector2(1160.0, 70.0), Vector2(920.0, 1010.0), black, 10.0, 10, offset++))

            // Layer 11: Dots
            val dotData = listOf(
                Triple(1020.0, 430.0, 4.0), Triple(1080.0, 455.0, 3.0), Triple(1145.0, 478.0, 5.0),
                Triple(1200.0, 505.0, 3.0), Triple(1255.0, 528.0, 4.0), Triple(1295.0, 545.0, 3.0),
                Triple(1328.0, 560.0, 5.0), Triple(1360.0, 575.0, 3.0),
                Triple(330.0, 235.0, 5.0), Triple(360.0, 255.0, 3.0),
                Triple(300.0, 260.0, 4.0), Triple(380.0, 220.0, 3.0)
            )
            dotData.forEach { (dx, dy, dr) -> activeElements.add(KDotElement(Vector2(dx, dy), dr, black, 11, offset++)) }
            
            activeElements.sortBy { it.layer }
        }

        fun generateRandomElement() {
            val rng = kotlin.random.Random.Default
            val type = rng.nextInt(6)
            val layer = rng.nextInt(2, 12)
            val color = palette.random()
            val offset = rng.nextInt(10000)
            
            val el = when(type) {
                0 -> KLineElement(Vector2(rng.nextDouble()*1920, rng.nextDouble()*1080), Vector2(rng.nextDouble()*1920, rng.nextDouble()*1080), color, if(rng.nextBoolean()) 2.0 else 10.0, layer, offset)
                1 -> KRectElement(Vector2(rng.nextDouble()*1920, rng.nextDouble()*1080), rng.nextDouble()*300+50, rng.nextDouble()*300+50, rng.nextDouble()*360, color, layer, offset)
                2 -> KPolygonElement(Vector2(rng.nextDouble()*1920, rng.nextDouble()*1080), rng.nextDouble()*400+100, rng.nextDouble()*400+100, rng.nextInt(3, 9), rng.nextDouble()*360, color.opacify(0.9), layer, offset)
                3 -> KCircleElement(Vector2(rng.nextDouble()*1920, rng.nextDouble()*1080), rng.nextDouble()*150+20, color, 2.0, rng.nextBoolean(), layer, offset)
                4 -> KHatchedElement(Vector2(rng.nextDouble()*1920, rng.nextDouble()*1080), rng.nextDouble()*400+100, rng.nextDouble()*200+50, rng.nextDouble()*360, 18.0, 6.0, graphite.opacify(0.35), layer, offset)
                else -> KDotElement(Vector2(rng.nextDouble()*1920, rng.nextDouble()*1080), rng.nextDouble()*6+2, black, layer, offset)
            }
            activeElements.add(el)
            activeElements.sortBy { it.layer }
        }

        createOriginalElements()

        extend(Screenshots()) {
            key = "e"
        }

        keyboard.keyDown.listen {
            when (it.name) {
                "r" -> {
                    seed = kotlin.random.Random.nextInt()
                    createOriginalElements()
                }
                "[" -> {
                    if (activeElements.isNotEmpty()) {
                        activeElements.removeAt(kotlin.random.Random.nextInt(activeElements.size))
                    }
                }
                "]" -> {
                    generateRandomElement()
                }
            }
        }

        extend {
            drawer.clear(ColorRGBa.fromHex("#F3EEDB"))
            drawer.isolated {
                scale(width.toDouble() / 1920.0, height.toDouble() / 1080.0)

                // Grain (procedural)
                val grainRng = Random(seed.toLong())
                drawer.stroke = null
                for (i in 0 until 2500) {
                    val x = grainRng.nextDouble() * 1920.0
                    val y = grainRng.nextDouble() * 1080.0
                    drawer.fill = ColorRGBa.fromHex("#0B0B0B").opacify(0.04)
                    drawer.circle(x, y, 0.5 + grainRng.nextDouble() * 0.8)
                }

                activeElements.forEach { it.draw(drawer, seed) }
            }
        }
    }
}
