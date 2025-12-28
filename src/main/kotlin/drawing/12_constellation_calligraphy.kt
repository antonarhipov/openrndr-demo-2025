package drawing

import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.color.hsla
import org.openrndr.draw.*
import org.openrndr.extra.shapes.hobbyCurve
import org.openrndr.math.IntVector2
import org.openrndr.math.Matrix44
import org.openrndr.math.Vector2
import org.openrndr.math.transforms.transform
import org.openrndr.math.transforms.translate
import org.openrndr.shape.Rectangle
import org.openrndr.shape.ShapeContour
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.*
import java.util.Random as JRandom

// --- Data Models ---

data class AtlasStar(
    val pos: Vector2,
    val mag: Double, // 0.0 (brightest) to ~6.0+ (faintest)
    val radius: Double,
    val alpha: Double,
    val tint: ColorRGBa
)

data class AtlasConstellation(
    val id: String,
    val stars: List<AtlasStar>, // Ordered subset
    val contour: ShapeContour,
    val center: Vector2,
    val alphaStar: AtlasStar
)

data class ConstellationParams(
    val tension: Double = 1.0,
    val subsetSizeTarget: Int = 10
)

// --- Program Configuration ---

const val WIDTH = 600
const val HEIGHT = 800
const val SAFE_MARGIN_PCT = 0.08
const val SAFE_MARGIN = 600 * SAFE_MARGIN_PCT // 48.0

// --- Generation Logic ---

fun generateStarfield(seed: Long, bounds: Rectangle, n: Int): List<AtlasStar> {
    val rng = JRandom(seed)
    val stars = mutableListOf<AtlasStar>()
    val area = bounds.offsetEdges(-SAFE_MARGIN)
    
    val milkyWayAngle = rng.nextDouble() * PI 
    val milkyWayWidth = area.width * 0.3
    val center = area.center

    var attempts = 0
    while (stars.size < n && attempts < n * 10) {
        attempts++
        
        // 1. Position Mixture
        val pos = if (rng.nextDouble() < 0.7) {
            // Uniform
            Vector2(
                area.x + rng.nextDouble() * area.width,
                area.y + rng.nextDouble() * area.height
            )
        } else {
            // Milky Way Strip (Elliptical Gaussian)
            val w = area.width * 1.5 
            val h = milkyWayWidth
            val lx = (rng.nextDouble() - 0.5) * w
            val ly = rng.nextGaussian() * (h * 0.2) 
            
            // Rotate and translate
            val c = cos(milkyWayAngle)
            val s = sin(milkyWayAngle)
            val rx = lx * c - ly * s
            val ry = lx * s + ly * c
            center + Vector2(rx, ry)
        }
        
        if (!area.contains(pos)) continue

        // 2. Magnitude (Heavy-tailed)
        val r = rng.nextDouble()
        val mag = 6.5 - (r.pow(5.0) * 8.0)
        
        // 3. Radius & Alpha mapping
        val brightness = (6.5 - mag).coerceAtLeast(0.0) / 8.0 
        val radius = 0.6 + brightness.pow(1.5) * 4.0 
        
        val baseAlpha = 0.4 + brightness * 0.6
        
        // 4. Color/Tint
        val temp = (rng.nextDouble() - 0.5) * 0.4 
        val tint = if (temp > 0) ColorRGBa.fromHex("fffae0").mix(ColorRGBa.WHITE, 1.0 - temp)
                   else ColorRGBa.fromHex("e0f0ff").mix(ColorRGBa.WHITE, 1.0 + temp)

        // 5. Anti-clumping (rejection)
        val minDst = 1.5 + brightness * 2.0
        
        var tooClose = false
        for (other in stars) {
             if (pos.distanceTo(other.pos) < minDst) {
                 tooClose = true
                 break
             }
        }
        
        if (!tooClose) {
            stars.add(AtlasStar(pos, mag, radius, baseAlpha, tint))
        }
    }
    return stars.sortedBy { it.mag }
}

fun pickCenters(seed: Long, bounds: Rectangle, k: Int, stars: List<AtlasStar>): List<Vector2> {
    val rng = JRandom(seed)
    val centers = mutableListOf<Vector2>()
    val area = bounds.offsetEdges(-SAFE_MARGIN * 1.5) 
    val minSpacing = min(bounds.width, bounds.height) * 0.15 
    
    var attempts = 0
    while (centers.size < k && attempts < 1000) {
        attempts++
        val p = Vector2(
            area.x + rng.nextDouble() * area.width,
            area.y + rng.nextDouble() * area.height
        )
        
        if (centers.none { it.distanceTo(p) < minSpacing }) {
             centers.add(p)
        }
    }
    return centers
}

fun selectSubset(center: Vector2, allStars: List<AtlasStar>, params: ConstellationParams): List<AtlasStar> {
    val R = min(WIDTH, HEIGHT) * 0.15 
    val candidates = allStars.filter { it.pos.distanceTo(center) < R }
    if (candidates.size < 4) return emptyList()
    
    val sorted = candidates.sortedBy { it.mag }
    val alpha = sorted.first()
    
    val subset = mutableListOf<AtlasStar>()
    subset.add(alpha)
    val remaining = sorted.drop(1).toMutableList()
    
    // Cost function params
    val wD = 1.0 
    val wA = 2.0 
    val wC = 1.0 
    val wO = 0.5 
    val wB = 1.5 
    
    while (subset.size < params.subsetSizeTarget && remaining.isNotEmpty()) {
        val last = subset.last()
        val prev = if (subset.size > 1) subset[subset.size - 2] else null
        
        var best: AtlasStar? = null
        var minCost = Double.MAX_VALUE
        
        for (cand in remaining) {
            val dist = last.pos.distanceTo(cand.pos)
            if (dist < 10.0) continue 
            if (dist > 60.0) continue 
            
            val costD = dist / 60.0 
            
            var costA = 0.0
            var costC = 0.0
            if (prev != null) {
                val v1 = (last.pos - prev.pos).normalized
                val v2 = (cand.pos - last.pos).normalized
                val dot = v1.dot(v2).coerceIn(-1.0, 1.0)
                val angle = acos(dot)
                
                costA = (PI - angle) / PI 
                if (dot < -0.5) costA += 5.0 
                
                if (abs(dot) > 0.95) costC += 1.0
            }
            
            val costO = cand.pos.distanceTo(center) / R
            val brightness = (6.5 - cand.mag).coerceAtLeast(0.0) / 8.0
            val rewardB = brightness
            
            val totalCost = wD * costD + wA * costA + wC * costC + wO * costO - wB * rewardB
            
            if (totalCost < minCost) {
                minCost = totalCost
                best = cand
            }
        }
        
        if (best != null) {
            subset.add(best)
            remaining.remove(best)
        } else {
            break 
        }
    }
    
    return if (subset.size >= 4) subset else emptyList()
}

fun makeHobby(stars: List<AtlasStar>, tension: Double): ShapeContour {
    if (stars.size < 2) return ShapeContour.EMPTY
    val points = stars.map { it.pos }
    // hobbyCurve(points, closed). No tension param in this version of the lib.
    return hobbyCurve(points, false).contour
}

// --- Main Program ---

fun main() = application {
    configure {
        width = 600
        height = 800
        title = "Constellation Calligraphy Atlas"
    }
    
    program {
        var seed = System.currentTimeMillis()
        var mode = 1 
        var showDebug = false
        var requestExport = false
        
        var stars: List<AtlasStar> = emptyList()
        var constellations: List<AtlasConstellation> = emptyList()
        
        val bounds = Rectangle(0.0, 0.0, width.toDouble(), height.toDouble())
        val safeArea = bounds.offsetEdges(-SAFE_MARGIN)
        
        fun regenerate() {
            println("Regenerating with seed $seed (Mode $mode)")
            stars = generateStarfield(seed, bounds, if (mode == 2) 5000 else 2500)
            
            constellations = mutableListOf()
            val rng = JRandom(seed)
            
            when (mode) {
                1 -> { // Atlas Mode
                    val k = 8 + rng.nextInt(13) // 8 to 20
                    val centers = pickCenters(seed, bounds, k, stars)
                    
                    val usedStars = mutableSetOf<AtlasStar>()
                    val codeNames = listOf("AUR", "VEL", "CAS", "ORI", "CYG", "LYR", "AQL", "PER", "AND", "PEG", "UMA", "LEO", "VIR", "LIB", "SCO", "SGR", "CAP", "AQR", "PSC", "GEM", "CNC")
                    
                    for ((i, c) in centers.withIndex()) {
                        val avail = stars.filter { !usedStars.contains(it) }
                        
                        val params = ConstellationParams(
                            tension = 0.9 + rng.nextDouble() * 0.5,
                            subsetSizeTarget = 6 + rng.nextInt(11)
                        )
                        val subset = selectSubset(c, avail, params)
                        
                        if (subset.isNotEmpty()) {
                            val namePrefix = codeNames[rng.nextInt(codeNames.size)]
                            val name = "$namePrefix-${rng.nextInt(99).toString().padStart(2, '0')}"
                            val path = makeHobby(subset, params.tension)
                            
                            constellations += AtlasConstellation(name, subset, path, c, subset.first())
                            usedStars.addAll(subset)
                        }
                    }
                }
                2 -> { // Hero Mode
                    val center = safeArea.center
                    val params = ConstellationParams(tension = 1.0, subsetSizeTarget = 20)
                    val subset = selectSubset(center, stars, params)
                    if (subset.isNotEmpty()) {
                        val path = makeHobby(subset, 1.1)
                        constellations += AtlasConstellation("HERO-01", subset, path, center, subset.first())
                    }
                }
                3 -> { 
                    // Study grid placeholder
                }
            }
        }
        
        regenerate()
        
        fun Drawer.drawStar(s: AtlasStar) {
            if (s.mag < 2.0) {
                fill = s.tint.opacify(0.1)
                stroke = null
                circle(s.pos, s.radius * 2.5)
                circle(s.pos, s.radius * 1.5)
            }
            
            fill = s.tint.opacify(s.alpha)
            stroke = null
            circle(s.pos, s.radius)
            
            if (s.mag < 0.0) { 
                stroke = s.tint.opacify(0.3)
                strokeWeight = 0.5
                lineSegment(s.pos - Vector2(4.0, 0.0), s.pos + Vector2(4.0, 0.0))
                lineSegment(s.pos - Vector2(0.0, 4.0), s.pos + Vector2(0.0, 4.0))
            }
        }
        
        fun Drawer.drawConstellation(c: AtlasConstellation) {
            stroke = ColorRGBa.WHITE.opacify(0.4)
            strokeWeight = 1.2
            fill = null
            contour(c.contour)
            
            val rngC = JRandom(c.id.hashCode().toLong())
            repeat(2) {
                val ox = (rngC.nextDouble() - 0.5) * 1.0
                val oy = (rngC.nextDouble() - 0.5) * 1.0
                stroke = ColorRGBa.WHITE.opacify(0.15)
                strokeWeight = 0.8
                // Use transform builder to create matrix
                val m = transform { translate(ox, oy, 0.0) }
                contour(c.contour.transform(m))
            }

            stroke = null
            fill = ColorRGBa.WHITE.opacify(0.8)
            circle(c.alphaStar.pos, 2.0)
            text("α", c.alphaStar.pos + Vector2(4.0, 4.0)) 
            
            val centroid = c.stars.fold(Vector2.ZERO) { acc, s -> acc + s.pos } / c.stars.size.toDouble()
            fill = ColorRGBa.WHITE.opacify(0.6)
            text(c.id, centroid)
        }

        fun renderScene(drawer: Drawer) {
            drawer.clear(ColorRGBa.fromHex("050508")) 
            
            // Vignette & Grain
            drawer.shadeStyle = shadeStyle {
                fragmentTransform = """
                    vec2 uv = c_boundsPosition.xy;
                    float d = distance(uv, vec2(0.5, 0.5));
                    float vig = smoothstep(0.4, 1.0, d);
                    vec3 col = x_fill.rgb;
                    col = mix(col, vec3(0.0), vig * 0.6); 
                    
                    float noise = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453);
                    col += (noise - 0.5) * 0.03;
                    
                    x_fill = vec4(col, 1.0);
                """
            }
            drawer.fill = ColorRGBa.fromHex("08090b")
            drawer.rectangle(bounds)
            drawer.shadeStyle = null
            
            stars.forEach { drawer.drawStar(it) }
            constellations.forEach { drawer.drawConstellation(it) }
            
            drawer.fill = ColorRGBa.WHITE.opacify(0.7)
            drawer.text("CONSTELLATION CALLIGRAPHY ATLAS", 20.0, height - 60.0)
            drawer.text("Seed: $seed | Mode: $mode | Stars: ${stars.size} | Constellations: ${constellations.size}", 20.0, height - 40.0)
            
            if (showDebug) {
                drawer.stroke = ColorRGBa.RED.opacify(0.5)
                drawer.fill = null
                drawer.rectangle(safeArea)
                constellations.forEach { c ->
                    drawer.stroke = ColorRGBa.YELLOW.opacify(0.5)
                    drawer.circle(c.center, 5.0)
                }
            }
        }

        extend {
            renderScene(drawer)
            
            if (requestExport) {
                requestExport = false
                val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                val filename = "constellation_atlas_s${seed}_m${mode}_$timestamp.png"
                val rt = renderTarget(width, height) {
                    colorBuffer()
                    depthBuffer()
                }
                drawer.isolatedWithTarget(rt) {
                    renderScene(this)
                }
                rt.colorBuffer(0).saveToFile(File(filename))
                println("Exported: $filename")
                rt.destroy()
            }
        }
        
        keyboard.keyDown.listen {
            when (it.name) {
                "r" -> {
                    seed = System.currentTimeMillis()
                    regenerate()
                }
                "1" -> { mode = 1; regenerate() }
                "2" -> { mode = 2; regenerate() }
                "3" -> { mode = 3; regenerate() }
                "d" -> showDebug = !showDebug
                "e" -> requestExport = true
            }
        }
    }
}
