package drawing

import org.openrndr.KEY_SPACEBAR
import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.color.rgb
import org.openrndr.draw.*
import org.openrndr.extra.shapes.hobbyCurve
import org.openrndr.math.Vector2
import org.openrndr.math.map
import org.openrndr.shape.ShapeContour
import org.openrndr.shape.intersections
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.*
import kotlin.random.Random

data class KnotParams(
    var seed: Int = 100,
    var N: Int = 12,
    var stepK: Int = 3, // For modular permutation
    var permMode: Int = 0, // 0: Modular, 1: Random
    var tension: Double = 0.0, // Hobby tension (simulated or ignored if not supported)
    var widthPct: Double = 0.04,
    var paletteMode: Int = 0, // 0: Emerald, 1: Monochrome
    var showDebug: Boolean = false
)

data class Crossing(
    val position: Vector2,
    val t1: Double,
    val t2: Double
)

fun main() = application {
    configure {
        width = 600
        height = 800
        title = "Knot Garden"
    }
    program {
        val params = KnotParams()
        var currentKnot: ShapeContour? = null
        var currentPoints: List<Vector2> = emptyList()
        var overEvents: List<Double> = emptyList()

        fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

        fun generatePermutation(n: Int, k: Int, mode: Int, rng: Random): List<Int> {
            return if (mode == 0) {
                // Modular: ensure k is coprime to n
                var validK = k % n
                if (validK == 0) validK = 1
                while (gcd(n, validK) != 1) {
                    validK = (validK + 1) % n
                    if (validK == 0) validK = 1
                }
                // Build cycle
                val perm = mutableListOf<Int>()
                var current = 0
                do {
                    perm.add(current)
                    current = (current + validK) % n
                } while (current != 0 && perm.size < n)
                perm
            } else {
                (0 until n).toList().shuffled(rng)
            }
        }

        fun buildKnot() {
            val rng = Random(params.seed)
            
            // 1. Ring Points
            val margin = min(width, height) * 0.08
            val safeBounds = drawer.bounds.offsetEdges(-margin)
            val center = safeBounds.center + Vector2(0.0, -height * 0.05)
            
            val radiusX = safeBounds.width * 0.35
            val radiusY = safeBounds.height * 0.35
            
            val basePoints = (0 until params.N).map { i ->
                val theta = 2.0 * PI * i / params.N - PI / 2.0
                val rJitter = rng.nextDouble(0.95, 1.05)
                val angleJitter = rng.nextDouble(-0.05, 0.05)
                val x = center.x + cos(theta + angleJitter) * radiusX * rJitter
                val y = center.y + sin(theta + angleJitter) * radiusY * rJitter
                Vector2(x, y)
            }

            // 2. Permutation
            val perm = generatePermutation(params.N, params.stepK, params.permMode, rng)
            val orderedPoints = perm.map { basePoints[it] }
            currentPoints = orderedPoints

            // 3. Hobby Curve
            currentKnot = hobbyCurve(orderedPoints, closed = true)

            // 4. Intersections & Over/Under
            if (currentKnot != null) {
                val knot = currentKnot!!
                val rawIntersections = knot.intersections(knot)
                
                // Process intersections
                // Filter and convert to Crossing objects
                val crossings = rawIntersections.mapNotNull { 
                    // Filter trivial self-intersections (adjacent segments)
                    val dt = abs(it.a.contourT - it.b.contourT)
                    if (dt > 0.05 && dt < (knot.length - 0.05)) { // Use arbitrary small delta
                         if (it.a.contourT < it.b.contourT) {
                             Crossing(it.position, it.a.contourT, it.b.contourT)
                         } else {
                             Crossing(it.position, it.b.contourT, it.a.contourT)
                         }
                    } else null
                }.distinctBy { (it.t1 * 1000).toInt() to (it.t2 * 1000).toInt() } // Dedup

                // Sort all events
                data class Event(val t: Double, val index: Int)
                val allEvents = mutableListOf<Event>()
                crossings.forEachIndexed { i, c ->
                    allEvents.add(Event(c.t1, i))
                    allEvents.add(Event(c.t2, i))
                }
                allEvents.sortBy { it.t }

                // Assign "Over" based on sorted index parity
                // If index is even -> Over. If index is odd -> Under.
                // We collect t values that are "Over".
                overEvents = allEvents.filterIndexed { i, _ -> i % 2 == 0 }.map { it.t }
            }
        }

        buildKnot()

        fun drawComposition(drawer: Drawer, w: Int, h: Int, p: KnotParams, knot: ShapeContour, overs: List<Double>, points: List<Vector2>) {
            val bgColor = if (p.paletteMode == 0) rgb(0.96, 0.95, 0.92) else rgb(0.15, 0.15, 0.15)
            val strandColor = if (p.paletteMode == 0) rgb(0.0, 0.4, 0.3) else rgb(0.9, 0.9, 0.9)
            val highlightColor = if (p.paletteMode == 0) rgb(0.8, 1.0, 0.9, 0.4) else rgb(1.0, 0.8, 0.4, 0.7)
            val debugColor = if (p.paletteMode == 0) ColorRGBa.RED else ColorRGBa.YELLOW
            
            val baseWidth = min(w, h) * p.widthPct

            drawer.clear(bgColor)

            // Grain
            drawer.stroke = null
            drawer.fill = ColorRGBa.BLACK.opacify(0.03)
            val noiseRng = Random(p.seed)
            for (i in 0..5000) {
                drawer.circle(Vector2(noiseRng.nextDouble(0.0, w.toDouble()), noiseRng.nextDouble(0.0, h.toDouble())), 0.7)
            }

            // 1. Draw Full "Under" Knot
            // Shadow
            drawer.translate(2.0, 2.0)
            drawer.stroke = ColorRGBa.BLACK.opacify(0.1)
            drawer.strokeWeight = baseWidth
            drawer.fill = null
            drawer.contour(knot)
            drawer.translate(-2.0, -2.0)

            // Base Strand
            drawer.stroke = strandColor
            drawer.strokeWeight = baseWidth
            drawer.fill = null
            drawer.contour(knot)

            // 2. Draw "Over" Patches
            val patchLenT = (baseWidth * 3.0) / knot.length // Approximate t-length for patch
            
            overs.forEach { t ->
                val t0 = t - patchLenT
                val t1 = t + patchLenT
                
                val segments = mutableListOf<ShapeContour>()
                
                if (t0 < 0) {
                    segments.add(knot.sub(1.0 + t0, 1.0))
                    segments.add(knot.sub(0.0, t1))
                } else if (t1 > 1.0) {
                    segments.add(knot.sub(t0, 1.0))
                    segments.add(knot.sub(0.0, t1 - 1.0))
                } else {
                    segments.add(knot.sub(t0, t1))
                }
                
                segments.forEach { seg ->
                    // Gap
                    drawer.stroke = bgColor
                    drawer.strokeWeight = baseWidth + 8.0
                    drawer.contour(seg)
                    
                    // Strand
                    drawer.stroke = strandColor
                    drawer.strokeWeight = baseWidth
                    drawer.contour(seg)
                    
                    // Highlight (simple centered line for now)
                    drawer.stroke = highlightColor
                    drawer.strokeWeight = baseWidth * 0.25
                    drawer.contour(seg)
                }
            }
            
            // Debug Overlay
            if (p.showDebug) {
                drawer.stroke = null
                drawer.fill = debugColor.opacify(0.8)
                drawer.circles(points, 4.0)
                
                // Connection lines
                drawer.stroke = debugColor.opacify(0.4)
                drawer.strokeWeight = 1.0
                drawer.fill = null
                drawer.lineLoop(points)
            }
            
            // Caption
            drawer.defaults()
            drawer.fill = ColorRGBa.BLACK.opacify(0.6)
            drawer.fontMap = null // use default
            drawer.text("KNOT GARDEN", 20.0, h - 40.0)
            drawer.text("Seed: ${p.seed}  N: ${p.N}  K: ${p.stepK}  Mode: ${p.permMode}", 20.0, h - 20.0)
        }

        keyboard.keyDown.listen {
            when (it.name) {
                "r" -> { params.seed = Random.nextInt(0, 10000); buildKnot() }
                "p" -> { 
                    params.permMode = (params.permMode + 1) % 2
                    if (params.permMode == 0) params.stepK = (params.stepK + 1) % params.N
                    if (params.stepK <= 1) params.stepK = 2
                    buildKnot() 
                }
                "open_bracket" -> { if (params.N > 6) params.N--; buildKnot() }
                "close_bracket" -> { if (params.N < 32) params.N++; buildKnot() }
                "minus" -> { params.tension -= 0.1; buildKnot() }
                "equals" -> { params.tension += 0.1; buildKnot() }
                "o" -> { params.paletteMode = (params.paletteMode + 1) % 2 }
                "d" -> { params.showDebug = !params.showDebug }
                "e" -> {
                    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                    val filename = "knot_s${params.seed}_N${params.N}_p${params.permMode}_${timestamp}.png"
                    val target = renderTarget(width, height) {
                        colorBuffer()
                        depthBuffer(DepthFormat.DEPTH24_STENCIL8)
                    }
                    drawer.withTarget(target) {
                         if (currentKnot != null) {
                             drawComposition(drawer, width, height, params, currentKnot!!, overEvents, currentPoints)
                         }
                    }
                    target.colorBuffer(0).saveToFile(File(filename))
                    target.destroy()
                    println("Saved to $filename")
                }
            }
        }

        extend {
            if (currentKnot != null) {
                drawComposition(drawer, width, height, params, currentKnot!!, overEvents, currentPoints)
            }
        }
    }
}
