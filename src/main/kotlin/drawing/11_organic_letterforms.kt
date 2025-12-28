package drawing

import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.*
import org.openrndr.draw.LineCap
import org.openrndr.draw.LineJoin
import org.openrndr.extra.shapes.hobbyCurve
import org.openrndr.math.Vector2
import org.openrndr.shape.*
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.*
import kotlin.random.Random

// ========== Constants ==========

const val TWO_PI = PI * 2.0

// ========== Data Structures ==========

data class GlyphParams(
    val tension: Double = 1.0,
    val strokeWidth: Double = 0.05,
    val jitterAmount: Double = 0.005,
    val slant: Double = 0.0,
    val spacing: Double = 0.15
)

data class ContourSample(
    val position: Vector2,
    val normal: Vector2,
    val tangent: Vector2,
    val curvature: Double,
    val t: Double
)

enum class RenderStyle {
    MONOLINE, RIBBON, BLOB
}

// ========== Utility Functions ==========

fun smoothstep(edge0: Double, edge1: Double, x: Double): Double {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)
}

fun hobbyStroke(points: List<Vector2>, tension: Double, closed: Boolean = false): ShapeContour {
    // Note: tension parameter not directly supported by hobbyCurve API
    // Using default hobby curve algorithm
    if (points.size < 2) {
        return ShapeContour.EMPTY
    }
    return hobbyCurve(points, closed).contour
}

fun sampleContour(contour: ShapeContour, stepLength: Double = 5.0): List<ContourSample> {
    val samples = mutableListOf<ContourSample>()
    var distance = 0.0
    
    while (distance <= contour.length) {
        val t = distance / contour.length
        val pos = contour.position(t)
        val tangent = contour.normal(t).perpendicular()
        val normal = contour.normal(t)
        
        // Estimate curvature
        val dt = 0.001
        val t0 = (t - dt).coerceAtLeast(0.0)
        val t1 = (t + dt).coerceAtMost(1.0)
        val p0 = contour.position(t0)
        val p1 = contour.position(t1)
        val curvature = if (t0 < t1) {
            val chord = (p1 - p0).length
            val arc = contour.length * (t1 - t0)
            if (arc > 0.0) (arc - chord) / arc else 0.0
        } else 0.0
        
        samples.add(ContourSample(pos, normal, tangent, curvature, t))
        distance += stepLength
    }
    
    return samples
}

fun buildRibbon(samples: List<ContourSample>, widthFn: (Double) -> Double): Shape {
    val leftPoints = mutableListOf<Vector2>()
    val rightPoints = mutableListOf<Vector2>()
    
    for (sample in samples) {
        val w = widthFn(sample.t)
        leftPoints.add(sample.position + sample.normal * w * 0.5)
        rightPoints.add(sample.position - sample.normal * w * 0.5)
    }
    
    val allPoints = leftPoints + rightPoints.reversed()
    return Shape(listOf(ShapeContour.fromPoints(allPoints, closed = true)))
}

// ========== Glyph Generation ==========

fun glyphStrokes(letter: Char, params: GlyphParams, rng: Random): List<List<Vector2>> {
    val strokes = mutableListOf<List<Vector2>>()
    
    // Define each letter as parametric points in 0..1 space
    // These will be scaled and transformed later
    when (letter) {
        'A' -> {
            // Left stem
            strokes.add(listOf(
                Vector2(0.1, 0.95),
                Vector2(0.25, 0.5),
                Vector2(0.35, 0.1)
            ))
            // Right stem
            strokes.add(listOf(
                Vector2(0.65, 0.1),
                Vector2(0.75, 0.5),
                Vector2(0.9, 0.95)
            ))
            // Crossbar
            strokes.add(listOf(
                Vector2(0.3, 0.6),
                Vector2(0.5, 0.55),
                Vector2(0.7, 0.6)
            ))
        }
        'B' -> {
            // Stem
            strokes.add(listOf(
                Vector2(0.2, 0.95),
                Vector2(0.2, 0.5),
                Vector2(0.2, 0.1)
            ))
            // Upper bowl
            strokes.add(listOf(
                Vector2(0.2, 0.1),
                Vector2(0.6, 0.1),
                Vector2(0.7, 0.3),
                Vector2(0.5, 0.5),
                Vector2(0.2, 0.5)
            ))
            // Lower bowl
            strokes.add(listOf(
                Vector2(0.2, 0.5),
                Vector2(0.65, 0.5),
                Vector2(0.8, 0.7),
                Vector2(0.6, 0.95),
                Vector2(0.2, 0.95)
            ))
        }
        'C' -> {
            strokes.add(listOf(
                Vector2(0.8, 0.2),
                Vector2(0.5, 0.05),
                Vector2(0.2, 0.2),
                Vector2(0.15, 0.5),
                Vector2(0.2, 0.8),
                Vector2(0.5, 0.95),
                Vector2(0.8, 0.8)
            ))
        }
        'D' -> {
            // Stem
            strokes.add(listOf(
                Vector2(0.2, 0.95),
                Vector2(0.2, 0.5),
                Vector2(0.2, 0.1)
            ))
            // Bowl
            strokes.add(listOf(
                Vector2(0.2, 0.1),
                Vector2(0.5, 0.05),
                Vector2(0.75, 0.2),
                Vector2(0.8, 0.5),
                Vector2(0.75, 0.8),
                Vector2(0.5, 0.95),
                Vector2(0.2, 0.95)
            ))
        }
        'E' -> {
            // Stem
            strokes.add(listOf(
                Vector2(0.2, 0.95),
                Vector2(0.2, 0.5),
                Vector2(0.2, 0.1)
            ))
            // Top
            strokes.add(listOf(
                Vector2(0.2, 0.1),
                Vector2(0.5, 0.12),
                Vector2(0.75, 0.1)
            ))
            // Middle
            strokes.add(listOf(
                Vector2(0.2, 0.5),
                Vector2(0.45, 0.52),
                Vector2(0.65, 0.5)
            ))
            // Bottom
            strokes.add(listOf(
                Vector2(0.2, 0.95),
                Vector2(0.5, 0.93),
                Vector2(0.75, 0.95)
            ))
        }
        'G' -> {
            strokes.add(listOf(
                Vector2(0.8, 0.2),
                Vector2(0.5, 0.05),
                Vector2(0.2, 0.2),
                Vector2(0.15, 0.5),
                Vector2(0.2, 0.8),
                Vector2(0.5, 0.95),
                Vector2(0.75, 0.8),
                Vector2(0.75, 0.55),
                Vector2(0.5, 0.55)
            ))
        }
        'H' -> {
            // Left stem
            strokes.add(listOf(
                Vector2(0.2, 0.95),
                Vector2(0.2, 0.5),
                Vector2(0.2, 0.1)
            ))
            // Right stem
            strokes.add(listOf(
                Vector2(0.8, 0.95),
                Vector2(0.8, 0.5),
                Vector2(0.8, 0.1)
            ))
            // Crossbar
            strokes.add(listOf(
                Vector2(0.2, 0.5),
                Vector2(0.5, 0.52),
                Vector2(0.8, 0.5)
            ))
        }
        'J' -> {
            strokes.add(listOf(
                Vector2(0.7, 0.1),
                Vector2(0.7, 0.5),
                Vector2(0.7, 0.8),
                Vector2(0.5, 0.95),
                Vector2(0.3, 0.9)
            ))
        }
        'K' -> {
            // Stem
            strokes.add(listOf(
                Vector2(0.2, 0.95),
                Vector2(0.2, 0.5),
                Vector2(0.2, 0.1)
            ))
            // Upper diagonal
            strokes.add(listOf(
                Vector2(0.8, 0.1),
                Vector2(0.5, 0.4),
                Vector2(0.2, 0.5)
            ))
            // Lower diagonal
            strokes.add(listOf(
                Vector2(0.2, 0.5),
                Vector2(0.5, 0.7),
                Vector2(0.8, 0.95)
            ))
        }
        'L' -> {
            strokes.add(listOf(
                Vector2(0.2, 0.1),
                Vector2(0.2, 0.5),
                Vector2(0.2, 0.95),
                Vector2(0.5, 0.93),
                Vector2(0.75, 0.95)
            ))
        }
        'M' -> {
            // Left stem
            strokes.add(listOf(
                Vector2(0.1, 0.95),
                Vector2(0.1, 0.5),
                Vector2(0.1, 0.1)
            ))
            // Left peak
            strokes.add(listOf(
                Vector2(0.1, 0.1),
                Vector2(0.3, 0.3),
                Vector2(0.5, 0.6)
            ))
            // Right peak
            strokes.add(listOf(
                Vector2(0.5, 0.6),
                Vector2(0.7, 0.3),
                Vector2(0.9, 0.1)
            ))
            // Right stem
            strokes.add(listOf(
                Vector2(0.9, 0.1),
                Vector2(0.9, 0.5),
                Vector2(0.9, 0.95)
            ))
        }
        'N' -> {
            // Left stem
            strokes.add(listOf(
                Vector2(0.2, 0.95),
                Vector2(0.2, 0.5),
                Vector2(0.2, 0.1)
            ))
            // Diagonal
            strokes.add(listOf(
                Vector2(0.2, 0.1),
                Vector2(0.5, 0.5),
                Vector2(0.8, 0.95)
            ))
            // Right stem
            strokes.add(listOf(
                Vector2(0.8, 0.95),
                Vector2(0.8, 0.5),
                Vector2(0.8, 0.1)
            ))
        }
        'P' -> {
            // Stem
            strokes.add(listOf(
                Vector2(0.2, 0.95),
                Vector2(0.2, 0.5),
                Vector2(0.2, 0.1)
            ))
            // Bowl
            strokes.add(listOf(
                Vector2(0.2, 0.1),
                Vector2(0.5, 0.05),
                Vector2(0.75, 0.2),
                Vector2(0.7, 0.4),
                Vector2(0.5, 0.5),
                Vector2(0.2, 0.5)
            ))
        }
        'R' -> {
            // Stem
            strokes.add(listOf(
                Vector2(0.2, 0.95),
                Vector2(0.2, 0.5),
                Vector2(0.2, 0.1)
            ))
            // Bowl
            strokes.add(listOf(
                Vector2(0.2, 0.1),
                Vector2(0.5, 0.05),
                Vector2(0.7, 0.2),
                Vector2(0.65, 0.4),
                Vector2(0.5, 0.5),
                Vector2(0.2, 0.5)
            ))
            // Leg
            strokes.add(listOf(
                Vector2(0.45, 0.5),
                Vector2(0.6, 0.7),
                Vector2(0.8, 0.95)
            ))
        }
        'S' -> {
            strokes.add(listOf(
                Vector2(0.75, 0.15),
                Vector2(0.5, 0.05),
                Vector2(0.25, 0.15),
                Vector2(0.3, 0.35),
                Vector2(0.5, 0.5),
                Vector2(0.7, 0.65),
                Vector2(0.75, 0.85),
                Vector2(0.5, 0.95),
                Vector2(0.25, 0.85)
            ))
        }
        'T' -> {
            // Stem
            strokes.add(listOf(
                Vector2(0.5, 0.95),
                Vector2(0.5, 0.5),
                Vector2(0.5, 0.1)
            ))
            // Top
            strokes.add(listOf(
                Vector2(0.15, 0.1),
                Vector2(0.5, 0.12),
                Vector2(0.85, 0.1)
            ))
        }
        'U' -> {
            strokes.add(listOf(
                Vector2(0.2, 0.1),
                Vector2(0.2, 0.5),
                Vector2(0.2, 0.8),
                Vector2(0.35, 0.95),
                Vector2(0.5, 0.95),
                Vector2(0.65, 0.95),
                Vector2(0.8, 0.8),
                Vector2(0.8, 0.5),
                Vector2(0.8, 0.1)
            ))
        }
        'V' -> {
            strokes.add(listOf(
                Vector2(0.1, 0.1),
                Vector2(0.3, 0.5),
                Vector2(0.5, 0.95),
                Vector2(0.7, 0.5),
                Vector2(0.9, 0.1)
            ))
        }
        'W' -> {
            strokes.add(listOf(
                Vector2(0.05, 0.1),
                Vector2(0.2, 0.7),
                Vector2(0.35, 0.95),
                Vector2(0.5, 0.6),
                Vector2(0.65, 0.95),
                Vector2(0.8, 0.7),
                Vector2(0.95, 0.1)
            ))
        }
        'Y' -> {
            strokes.add(listOf(
                Vector2(0.2, 0.1),
                Vector2(0.35, 0.3),
                Vector2(0.5, 0.5),
                Vector2(0.5, 0.7),
                Vector2(0.5, 0.95)
            ))
            strokes.add(listOf(
                Vector2(0.8, 0.1),
                Vector2(0.65, 0.3),
                Vector2(0.5, 0.5)
            ))
        }
        else -> {
            // Default fallback - vertical line
            strokes.add(listOf(
                Vector2(0.5, 0.1),
                Vector2(0.5, 0.95)
            ))
        }
    }
    
    // Apply jitter
    return strokes.map { stroke ->
        stroke.map { point ->
            val jx = (rng.nextDouble() - 0.5) * params.jitterAmount
            val jy = (rng.nextDouble() - 0.5) * params.jitterAmount
            point + Vector2(jx, jy)
        }
    }
}

fun transformGlyphPoints(
    points: List<Vector2>,
    glyphBox: Rectangle,
    params: GlyphParams
): List<Vector2> {
    return points.map { p ->
        val x = glyphBox.x + p.x * glyphBox.width + p.y * params.slant * glyphBox.height
        val y = glyphBox.y + p.y * glyphBox.height
        Vector2(x, y)
    }
}

// ========== Rendering Functions ==========

fun renderGlyphMonoline(
    drawer: Drawer,
    strokes: List<ShapeContour>,
    baseWidth: Double,
    color: ColorRGBa
) {
    drawer.stroke = color
    drawer.fill = null
    drawer.lineCap = LineCap.ROUND
    drawer.lineJoin = LineJoin.ROUND
    
    for (contour in strokes) {
        if (contour.length > 0.0) {
            // Draw with subtle variable width
            val samples = sampleContour(contour, 5.0)
            for (i in 0 until samples.size - 1) {
                val t = samples[i].t
                // Thicker in middle, thinner at ends
                val width = baseWidth * (0.7 + 0.3 * sin(t * PI))
                drawer.strokeWeight = width
                drawer.lineSegment(samples[i].position, samples[i + 1].position)
            }
        }
    }
}

fun renderGlyphRibbon(
    drawer: Drawer,
    strokes: List<ShapeContour>,
    baseWidth: Double,
    color: ColorRGBa
) {
    drawer.fill = color
    drawer.stroke = null
    
    for (contour in strokes) {
        val widthFn: (Double) -> Double = { t ->
            baseWidth * (0.8 + 0.2 * sin(t * PI))
        }
        val ribbon = buildRibbon(sampleContour(contour, 3.0), widthFn)
        drawer.shape(ribbon)
    }
    
    // Edge highlight
    drawer.stroke = color.shade(0.7)
    drawer.strokeWeight = 1.5
    drawer.fill = null
    for (contour in strokes) {
        drawer.contour(contour)
    }
}

fun renderGlyphBlob(
    drawer: Drawer,
    strokes: List<ShapeContour>,
    baseWidth: Double,
    color: ColorRGBa,
    rng: Random
) {
    drawer.fill = color.opacify(0.95)
    drawer.stroke = color.shade(0.6)
    drawer.strokeWeight = 2.0
    
    for (contour in strokes) {
        // Create overlapping blobs along the stroke
        val samples = sampleContour(contour, baseWidth * 0.8)
        
        for (sample in samples) {
            val blobPoints = (0..8).map { i ->
                val angle = i * TWO_PI / 8.0 + rng.nextDouble() * 0.3
                val radius = baseWidth * (0.6 + rng.nextDouble() * 0.4)
                sample.position + Vector2(cos(angle), sin(angle)) * radius
            }
            
            val blobContour = hobbyStroke(blobPoints, 1.0, closed = true)
            drawer.contour(blobContour)
        }
    }
}

// ========== Layout ==========

fun layoutWord(
    text: String,
    centerX: Double,
    centerY: Double,
    glyphHeight: Double,
    params: GlyphParams,
    rng: Random
): List<Rectangle> {
    val glyphWidth = glyphHeight * 0.7
    val totalWidth = text.length * glyphWidth + (text.length - 1) * glyphHeight * params.spacing
    
    val startX = centerX - totalWidth / 2.0
    
    return text.indices.map { i ->
        val x = startX + i * (glyphWidth + glyphHeight * params.spacing)
        Rectangle(x, centerY - glyphHeight / 2.0, glyphWidth, glyphHeight)
    }
}

// ========== Variant Renderers ==========

fun renderVariant1(drawer: Drawer, text: String, seed: Long) {
    val rng = Random(seed)
    val params = GlyphParams(
        tension = 1.2,
        strokeWidth = 0.04,
        jitterAmount = 0.002,
        slant = 0.05,
        spacing = 0.2
    )
    
    val glyphHeight = drawer.height * 0.4
    val boxes = layoutWord(text, drawer.width / 2.0, drawer.height / 2.0, glyphHeight, params, rng)
    
    val color = ColorRGBa.BLACK
    
    for ((i, char) in text.withIndex()) {
        val strokes = glyphStrokes(char, params, Random(seed + i))
        val contours = strokes.map { stroke ->
            val transformed = transformGlyphPoints(stroke, boxes[i], params)
            hobbyStroke(transformed, params.tension, closed = false)
        }
        
        renderGlyphMonoline(drawer, contours, glyphHeight * params.strokeWidth, color)
    }
    
    // Caption
    drawer.fill = ColorRGBa.BLACK
    drawer.text("Organic Letterforms | Variant 1: Monoline Signature", 20.0, drawer.height - 80.0)
    drawer.text("text=$text | seed=$seed | tension=${params.tension} | width=${params.strokeWidth}", 20.0, drawer.height - 50.0)
}

fun renderVariant2(drawer: Drawer, text: String, seed: Long) {
    val rng = Random(seed)
    val params = GlyphParams(
        tension = 1.0,
        strokeWidth = 0.08,
        jitterAmount = 0.003,
        slant = 0.0,
        spacing = 0.05
    )
    
    val glyphHeight = drawer.height * 0.5
    val boxes = layoutWord(text, drawer.width / 2.0, drawer.height / 2.0, glyphHeight, params, rng)
    
    val color = ColorRGBa.fromHex(0x1a1a1a)
    
    for ((i, char) in text.withIndex()) {
        val strokes = glyphStrokes(char, params, Random(seed + i))
        val contours = strokes.map { stroke ->
            val transformed = transformGlyphPoints(stroke, boxes[i], params)
            hobbyStroke(transformed, params.tension, closed = false)
        }
        
        renderGlyphRibbon(drawer, contours, glyphHeight * params.strokeWidth, color)
    }
    
    // Caption
    drawer.fill = ColorRGBa.BLACK
    drawer.text("Organic Letterforms | Variant 2: Ribbon Monogram", 20.0, drawer.height - 80.0)
    drawer.text("text=$text | seed=$seed | tension=${params.tension} | width=${params.strokeWidth}", 20.0, drawer.height - 50.0)
}

fun renderVariant3(drawer: Drawer, text: String, seed: Long) {
    val rng = Random(seed)
    val params = GlyphParams(
        tension = 0.9,
        strokeWidth = 0.12,
        jitterAmount = 0.008,
        slant = 0.0,
        spacing = 0.1
    )
    
    val glyphHeight = drawer.height * 0.45
    val boxes = layoutWord(text, drawer.width / 2.0, drawer.height / 2.0, glyphHeight, params, rng)
    
    val color = ColorRGBa.fromHex(0x2d2d2d)
    
    for ((i, char) in text.withIndex()) {
        val strokes = glyphStrokes(char, params, Random(seed + i * 100))
        val contours = strokes.map { stroke ->
            val transformed = transformGlyphPoints(stroke, boxes[i], params)
            hobbyStroke(transformed, params.tension, closed = false)
        }
        
        renderGlyphBlob(drawer, contours, glyphHeight * params.strokeWidth, color, Random(seed + i * 100))
    }
    
    // Caption
    drawer.fill = ColorRGBa.BLACK
    drawer.text("Organic Letterforms | Variant 3: Blob-Constructed Logo", 20.0, drawer.height - 80.0)
    drawer.text("text=$text | seed=$seed | tension=${params.tension} | width=${params.strokeWidth}", 20.0, drawer.height - 50.0)
}

// ========== Text Generation ==========

fun generateText(seed: Long): String {
    val rng = Random(seed)
    val letters = "ABCDEFGHJKLMNPRSTUVWY"
    val count = if (rng.nextBoolean()) 2 else 3
    return (1..count).map { letters[rng.nextInt(letters.length)] }.joinToString("")
}

// ========== Main Application ==========

fun main() = application {
    configure {
        width = 1200
        height = 800
        title = "Organic Letterforms"
    }
    
    program {
        var seed = 12345L
        var variant = 1
        var showDebug = false
        var currentText = generateText(seed)
        
        fun exportFrame() {
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            val filename = "letterforms_v${variant}_s${seed}_${currentText}_${timestamp}.png"
            
            val rt = renderTarget(6000, 4000) {
                colorBuffer()
                depthBuffer(DepthFormat.DEPTH24_STENCIL8)
            }
            
            drawer.isolatedWithTarget(rt) {
                drawer.ortho(rt)
                drawer.clear(ColorRGBa.WHITE)
                
                when (variant) {
                    1 -> renderVariant1(drawer, currentText, seed)
                    2 -> renderVariant2(drawer, currentText, seed)
                    3 -> renderVariant3(drawer, currentText, seed)
                }
            }
            
            rt.colorBuffer(0).saveToFile(File(filename))
            rt.destroy()
            
            println("Exported: $filename")
        }
        
        keyboard.keyDown.listen {
            when (it.name) {
                "r" -> {
                    seed = Random.nextLong()
                    currentText = generateText(seed)
                }
                "1" -> variant = 1
                "2" -> variant = 2
                "3" -> variant = 3
                "t" -> {
                    seed = Random.nextLong()
                    currentText = generateText(seed)
                }
                "d" -> showDebug = !showDebug
                "e" -> exportFrame()
            }
        }
        
        extend {
            drawer.clear(ColorRGBa.WHITE)
            
            when (variant) {
                1 -> renderVariant1(drawer, currentText, seed)
                2 -> renderVariant2(drawer, currentText, seed)
                3 -> renderVariant3(drawer, currentText, seed)
            }
        }
    }
}
