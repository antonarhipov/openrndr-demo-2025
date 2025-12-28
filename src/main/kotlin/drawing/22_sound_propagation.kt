package drawing

import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.color.ColorHSLa
import org.openrndr.color.rgb
import org.openrndr.draw.*
import org.openrndr.extra.color.presets.*
import org.openrndr.extra.shapes.hobbyCurve
import org.openrndr.math.Vector2
import org.openrndr.math.map
import org.openrndr.shape.Rectangle
import org.openrndr.shape.ShapeContour
import org.openrndr.shape.contains
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.*
import kotlin.random.Random
import java.util.PriorityQueue

data class SoundParams(
    var seed: Long = Random.nextLong(),
    var wavelength: Double = 25.0,
    var steps: Int = 120,
    var tension: Double = 0.5,
    var paletteMode: SoundPaletteMode = SoundPaletteMode.MONOCHROME,
    var sourceCount: Int = 2,
    var obstacleCount: Int = 5,
    var debug: Boolean = false,
    var highRes: Boolean = false,
    var dirty: Boolean = true
)

data class CachedWavefront(
    val contour: ShapeContour,
    val alpha: Double,
    val color: ColorRGBa? = null
)

enum class SoundPaletteMode {
    MONOCHROME,
    PHASE_HUE
}

data class Source(
    val pos: Vector2,
    val lambda: Double,
    val phase0: Double = 0.0,
    val amp: Double = 1.0
)

sealed class Obstacle {
    abstract fun contains(p: Vector2): Boolean
    var speed: Double = 0.0 // 0.0 means hard obstacle (blocked)
    
    class Circle(val center: Vector2, val radius: Double) : Obstacle() {
        override fun contains(p: Vector2) = p.distanceTo(center) <= radius
    }
    
    class Capsule(val a: Vector2, val b: Vector2, val radius: Double) : Obstacle() {
        override fun contains(p: Vector2): Boolean {
            val pa = p - a
            val ba = b - a
            val h = (pa.dot(ba) / ba.squaredLength).coerceIn(0.0, 1.0)
            return (pa - ba * h).length <= radius
        }
    }
    
    class Rect(val rect: Rectangle) : Obstacle() {
        override fun contains(p: Vector2) = rect.contains(p)
    }
}

class TravelTimeField(val width: Int, val height: Int, val bounds: Rectangle) {
    val data = DoubleArray(width * height) { Double.POSITIVE_INFINITY }
    
    fun get(x: Int, y: Int) = data[y * width + x]
    fun set(x: Int, y: Int, v: Double) { data[y * width + x] = v }
    
    fun gridToWorld(x: Int, y: Int): Vector2 {
        return Vector2(
            bounds.x + (x.toDouble() / (width - 1)) * bounds.width,
            bounds.y + (y.toDouble() / (height - 1)) * bounds.height
        )
    }
    
    fun worldToGrid(p: Vector2): Pair<Int, Int> {
        val x = ((p.x - bounds.x) / bounds.width * (width - 1)).toInt().coerceIn(0, width - 1)
        val y = ((p.y - bounds.y) / bounds.height * (height - 1)).toInt().coerceIn(0, height - 1)
        return x to y
    }

    fun sample(p: Vector2): Double {
        val x = (p.x - bounds.x) / bounds.width * (width - 1)
        val y = (p.y - bounds.y) / bounds.height * (height - 1)
        
        val xi = x.toInt().coerceIn(0, width - 2)
        val yi = y.toInt().coerceIn(0, height - 2)
        
        val xf = x - xi
        val yf = y - yi
        
        val v00 = get(xi, yi)
        val v10 = get(xi + 1, yi)
        val v01 = get(xi, yi + 1)
        val v11 = get(xi + 1, yi + 1)
        
        if (v00.isInfinite() || v10.isInfinite() || v01.isInfinite() || v11.isInfinite()) return Double.POSITIVE_INFINITY
        
        val v0 = v00 * (1 - xf) + v10 * xf
        val v1 = v01 * (1 - xf) + v11 * xf
        return v0 * (1 - yf) + v1 * yf
    }
}

data class Node(val x: Int, val y: Int, val dist: Double)

fun computeTravelTimeField(source: Source, obstacles: List<Obstacle>, width: Int, height: Int, bounds: Rectangle): TravelTimeField {
    val field = TravelTimeField(width, height, bounds)
    val speedGrid = DoubleArray(width * height)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val worldPos = field.gridToWorld(x, y)
            val obstacle = obstacles.find { it.contains(worldPos) }
            speedGrid[y * width + x] = obstacle?.speed ?: 1.0
        }
    }

    val pq = PriorityQueue<Node>(compareBy { it.dist })
    
    val (sx, sy) = field.worldToGrid(source.pos)
    field.set(sx, sy, 0.0)
    pq.add(Node(sx, sy, 0.0))
    
    val dx = intArrayOf(-1, 1, 0, 0, -1, 1, -1, 1)
    val dy = intArrayOf(0, 0, -1, 1, -1, -1, 1, 1)
    val stepSize = bounds.width / (width - 1)
    val costs = doubleArrayOf(1.0, 1.0, 1.0, 1.0, sqrt(2.0), sqrt(2.0), sqrt(2.0), sqrt(2.0)).map { it * stepSize }.toDoubleArray()
    
    while (pq.isNotEmpty()) {
        val (x, y, d) = pq.poll()
        if (d > field.get(x, y)) continue
        
        for (i in 0 until 8) {
            val nx = x + dx[i]
            val ny = y + dy[i]
            if (nx in 0 until width && ny in 0 until height) {
                val speed = speedGrid[ny * width + nx]
                if (speed > 0.0) {
                    val newDist = d + costs[i] / speed
                    if (newDist < field.get(nx, ny)) {
                        field.set(nx, ny, newDist)
                        pq.add(Node(nx, ny, newDist))
                    }
                }
            }
        }
    }
    return field
}

fun extractAllContours(field: TravelTimeField, thresholds: List<Double>): List<List<List<Vector2>>> {
    val buckets = Array(thresholds.size) { mutableListOf<Pair<Vector2, Vector2>>() }
    
    for (y in 0 until field.height - 1) {
        for (x in 0 until field.width - 1) {
            val v00 = field.get(x, y)
            val v10 = field.get(x + 1, y)
            val v01 = field.get(x, y + 1)
            val v11 = field.get(x + 1, y + 1)
            
            if (v00.isInfinite() && v10.isInfinite() && v01.isInfinite() && v11.isInfinite()) continue

            val minV = minOf(v00, minOf(v10, minOf(v01, v11)))
            val maxV = maxOf(v00, maxOf(v10, maxOf(v01, v11)))
            
            val p00 by lazy { field.gridToWorld(x, y) }
            val p10 by lazy { field.gridToWorld(x + 1, y) }
            val p01 by lazy { field.gridToWorld(x, y + 1) }
            val p11 by lazy { field.gridToWorld(x + 1, y + 1) }

            for (tIdx in thresholds.indices) {
                val threshold = thresholds[tIdx]
                if (threshold >= minV && threshold <= maxV) {
                    fun lerp(p1: Vector2, p2: Vector2, v1: Double, v2: Double): Vector2? {
                        if (v1.isInfinite() || v2.isInfinite()) return null
                        if ((v1 <= threshold && v2 > threshold) || (v2 <= threshold && v1 > threshold)) {
                            val t = (threshold - v1) / (v2 - v1)
                            return p1 + (p2 - p1) * t
                        }
                        return null
                    }
                    
                    val e1 = lerp(p00, p10, v00, v10)
                    val e2 = lerp(p10, p11, v10, v11)
                    val e3 = lerp(p11, p01, v11, v01)
                    val e4 = lerp(p01, p00, v01, v00)
                    
                    val pts = listOfNotNull(e1, e2, e3, e4)
                    if (pts.size == 2) {
                        buckets[tIdx].add(pts[0] to pts[1])
                    } else if (pts.size == 4) {
                        buckets[tIdx].add(pts[0] to pts[1])
                        buckets[tIdx].add(pts[2] to pts[3])
                    }
                }
            }
        }
    }
    return buckets.map { linkSegments(it) }
}

fun linkSegments(segments: List<Pair<Vector2, Vector2>>): List<List<Vector2>> {
    if (segments.isEmpty()) return emptyList()
    
    val connections = mutableMapOf<Vector2, MutableList<Vector2>>()
    for ((p1, p2) in segments) {
        connections.getOrPut(p1) { mutableListOf() }.add(p2)
        connections.getOrPut(p2) { mutableListOf() }.add(p1)
    }
    
    val polylines = mutableListOf<List<Vector2>>()
    val visited = mutableSetOf<Vector2>()
    
    // Endpoints first
    for (start in connections.keys) {
        if (start in visited) continue
        if (connections[start]!!.size == 1) {
            val poly = mutableListOf<Vector2>()
            var curr: Vector2? = start
            while (curr != null && curr !in visited) {
                visited.add(curr)
                poly.add(curr)
                curr = connections[curr]?.find { it !in visited }
            }
            if (poly.size > 1) polylines.add(poly)
        }
    }
    
    // Then loops
    for (start in connections.keys) {
        if (start in visited) continue
        val poly = mutableListOf<Vector2>()
        var curr: Vector2? = start
        while (curr != null && curr !in visited) {
            visited.add(curr)
            poly.add(curr)
            curr = connections[curr]?.find { it !in visited }
        }
        if (poly.size > 1) {
            if (connections[poly.last()]?.contains(poly.first()) == true) {
                poly.add(poly.first())
            }
            polylines.add(poly)
        }
    }
    return polylines
}

fun main() = application {
    configure {
        width = 600
        height = 800
        title = "Sound Propagation"
    }
    program {
        var params = SoundParams()
        
        fun generate(p: SoundParams, bounds: Rectangle): Pair<List<Source>, List<Obstacle>> {
            val rng = Random(p.seed)
            val sources = mutableListOf<Source>()
            
            // Primary source
            sources.add(Source(
                pos = Vector2(bounds.x + bounds.width * rng.nextDouble(0.3, 0.7), bounds.y + bounds.height * rng.nextDouble(0.15, 0.35)),
                lambda = p.wavelength
            ))
            
            if (p.sourceCount > 1) {
                repeat(p.sourceCount - 1) { i ->
                    sources.add(Source(
                        pos = Vector2(bounds.x + bounds.width * rng.nextDouble(0.1, 0.9), bounds.y + bounds.height * rng.nextDouble(0.1, 0.9)),
                        lambda = p.wavelength * rng.nextDouble(0.9, 1.1),
                        amp = rng.nextDouble(0.4, 0.8)
                    ))
                }
            }
            
            val obstacles = mutableListOf<Obstacle>()
            
            // Slit diffraction setup
            val gapY = bounds.y + bounds.height * rng.nextDouble(0.5, 0.6)
            val gapX = bounds.x + bounds.width * rng.nextDouble(0.4, 0.6)
            val gapWidth = rng.nextDouble(30.0, 60.0)
            val wallHeight = 15.0
            
            obstacles.add(Obstacle.Rect(Rectangle(bounds.x - 50.0, gapY - wallHeight/2, gapX - gapWidth/2 - (bounds.x - 50.0), wallHeight)))
            obstacles.add(Obstacle.Rect(Rectangle(gapX + gapWidth/2, gapY - wallHeight/2, bounds.x + bounds.width + 50.0 - (gapX + gapWidth/2), wallHeight)))
            
            // Additional obstacles
            repeat(p.obstacleCount - 2) {
                val type = rng.nextInt(3)
                val pos = Vector2(bounds.x + bounds.width * rng.nextDouble(0.1, 0.9), bounds.y + bounds.height * rng.nextDouble(0.1, 0.9))
                
                // Avoid placing obstacles too close to sources
                if (sources.none { it.pos.distanceTo(pos) < 80.0 }) {
                    val obs = when (type) {
                        0 -> Obstacle.Circle(pos, rng.nextDouble(20.0, 50.0))
                        1 -> Obstacle.Capsule(pos, pos + Vector2(rng.nextDouble(-60.0, 60.0), rng.nextDouble(-60.0, 60.0)), rng.nextDouble(10.0, 25.0))
                        else -> {
                            val w = rng.nextDouble(40.0, 80.0)
                            val h = rng.nextDouble(40.0, 80.0)
                            Obstacle.Rect(Rectangle(pos.x - w/2, pos.y - h/2, w, h))
                        }
                    }
                    if (rng.nextDouble() < 0.2) obs.speed = rng.nextDouble(0.3, 0.6) // Soft obstacle
                    obstacles.add(obs)
                }
            }
            
            return sources to obstacles
        }
        
        val wavefrontCache = mutableListOf<CachedWavefront>()

        fun renderPoster(drawer: Drawer, p: SoundParams, export: Boolean = false) {
            val margin = minOf(drawer.width, drawer.height) * 0.08
            val safeArea = drawer.bounds.offsetEdges(-margin)
            
            val activeCache = if (export) mutableListOf() else wavefrontCache

            if (p.dirty || export) {
                activeCache.clear()
                val (sources, obstacles) = generate(p, safeArea)
                
                val isDark = p.seed % 2L == 0L
                val baseLineColor = if (isDark) ColorRGBa.WHITE else ColorRGBa.BLACK
                
                val gridRes = if (export) 600 else 250
                val fields = sources.map { computeTravelTimeField(it, obstacles, gridRes, (gridRes * 1.33).toInt(), safeArea) }
                
                val thresholds = (1..p.steps).map { it * (p.wavelength * 0.4) }
                
                for (sIdx in sources.indices) {
                    val source = sources[sIdx]
                    val fieldContours = extractAllContours(fields[sIdx], thresholds)
                    
                    for (stepIdx in fieldContours.indices) {
                        val step = stepIdx + 1
                        val polylines = fieldContours[stepIdx]
                        
                        for (poly in polylines) {
                            if (poly.size < 4) continue
                            val isClosed = poly.first().distanceTo(poly.last()) < 2.0
                            
                            try {
                                val simplified = if (poly.size > 100) {
                                    poly.filterIndexed { index, _ -> index % (poly.size / 50).coerceAtLeast(1) == 0 || index == poly.lastIndex }
                                } else poly
                                
                                val contour = ShapeContour.fromPoints(simplified, isClosed).hobbyCurve(p.tension)
                                
                                // Interference alpha modulation
                                val midPoint = poly[poly.size / 2]
                                var interference = 0.0
                                for (otherIdx in sources.indices) {
                                    if (otherIdx == sIdx) {
                                        interference += 1.0
                                    } else {
                                        val otherDist = fields[otherIdx].sample(midPoint)
                                        if (!otherDist.isInfinite()) {
                                            val phase = 2 * PI * otherDist / sources[otherIdx].lambda
                                            interference += sources[otherIdx].amp * cos(phase)
                                        }
                                    }
                                }
                                val iMod = map(-1.0, 1.0, 0.3, 1.0, (interference / sources.size).coerceIn(-1.0, 1.0))
                                
                                val timeAlpha = map(0.0, p.steps.toDouble(), 0.9, 0.05, step.toDouble())
                                val finalAlpha = (timeAlpha * iMod * source.amp).coerceIn(0.0, 1.0)
                                
                                val color = if (p.paletteMode == SoundPaletteMode.MONOCHROME) {
                                    baseLineColor.opacify(finalAlpha)
                                } else {
                                    val hue = (step.toDouble() / 25.0 + sIdx * 0.3) % 1.0
                                    ColorHSLa(hue * 360.0, 0.5, if (isDark) 0.6 else 0.4, finalAlpha).toRGBa()
                                }
                                
                                activeCache.add(CachedWavefront(contour, finalAlpha, color))
                            } catch (e: Exception) { }
                        }
                    }
                }
                if (!export) p.dirty = false
                else println("Export: generated ${activeCache.size} wavefronts")
            }

            val isDark = p.seed % 2L == 0L
            val bgColor = if (isDark) rgb("121212") else rgb("fdfdfd")
            val baseLineColor = if (isDark) ColorRGBa.WHITE else ColorRGBa.BLACK
            
            drawer.clear(bgColor)
            
            // Procedural grain
            drawer.isolated {
                drawer.shadeStyle = shadeStyle {
                    fragmentTransform = """
                        vec2 uv = mod(c_screenPosition.xy, 1024.0);
                        float n = fract(sin(dot(uv, vec2(12.9898, 78.233))) * 43758.5453);
                        x_fill.rgb += (n - 0.5) * 0.04;
                    """.trimIndent()
                }
                drawer.fill = bgColor
                drawer.rectangle(drawer.bounds)
            }
            
            drawer.fill = null
            drawer.strokeWeight = if (export) 1.5 * (drawer.width / 600.0) else 0.8
            
            for (wf in activeCache) {
                drawer.stroke = wf.color
                drawer.contour(wf.contour)
            }
            
            // Caption
            val (sources, obstacles) = generate(p, safeArea)
            drawer.fill = baseLineColor.opacify(0.7)
            drawer.stroke = null
            drawer.fontMap = loadFont("data/fonts/default.otf", 10.0 * (drawer.width / 600.0))
            val caption = "SOUND PROPAGATION | SEED: ${p.seed} | SOURCES: ${sources.size} | OBSTACLES: ${obstacles.size} | WAVELENGTH: ${"%.1f".format(p.wavelength)} | STEPS: ${p.steps} | TENSION: ${"%.2f".format(p.tension)}"
            drawer.text(caption, margin, drawer.height - margin * 0.6)
            
            if (p.debug) {
                drawer.strokeWeight = 1.0
                for (s in sources) {
                    drawer.stroke = ColorRGBa.RED
                    drawer.circle(s.pos, 5.0)
                }
                for (o in obstacles) {
                    drawer.stroke = ColorRGBa.BLUE.opacify(0.5)
                    when (o) {
                        is Obstacle.Circle -> drawer.circle(o.center, o.radius)
                        is Obstacle.Capsule -> drawer.lineSegment(o.a, o.b)
                        is Obstacle.Rect -> drawer.rectangle(o.rect)
                    }
                }
            }
        }
        
        extend {
            renderPoster(drawer, params)
        }
        
        keyboard.keyDown.listen {
            when (it.name) {
                "r" -> { params.seed = Random.nextLong(); params.dirty = true }
                "1" -> { params.paletteMode = SoundPaletteMode.MONOCHROME; params.dirty = true }
                "2" -> { params.paletteMode = SoundPaletteMode.PHASE_HUE; params.dirty = true }
                "left_bracket" -> { params.wavelength = (params.wavelength - 2.0).coerceAtLeast(5.0); params.dirty = true }
                "right_bracket" -> { params.wavelength += 2.0; params.dirty = true }
                "minus" -> { params.tension = (params.tension - 0.05).coerceIn(0.0, 1.0); params.dirty = true }
                "equal" -> { params.tension = (params.tension + 0.05).coerceIn(0.0, 1.0); params.dirty = true }
                "s" -> { params.sourceCount = (params.sourceCount % 3) + 1; params.dirty = true }
                "o" -> { params.obstacleCount = (params.obstacleCount % 7) + 2; params.dirty = true }
                "d" -> params.debug = !params.debug
                "e" -> {
                    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                    val fileName = "sound_propagation_s${params.seed}_w${params.wavelength.toInt()}_$timestamp.png"
                    val file = File("images/$fileName")
                    
                    val rt = renderTarget(width, height) {
                        colorBuffer()
                        depthBuffer(DepthFormat.DEPTH24_STENCIL8)
                    }
                    
                    drawer.isolatedWithTarget(rt) {
                        renderPoster(this, params, true)
                    }
                    
                    rt.colorBuffer(0).saveToFile(file)
                    println("Exported high-res to ${file.absolutePath}")
                    
                    rt.destroy()
                }
            }
        }
    }
}
