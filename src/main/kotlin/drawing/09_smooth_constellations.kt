package drawing

import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.*
import org.openrndr.extra.color.presets.*
import org.openrndr.extra.shapes.hobbyCurve
import org.openrndr.math.Vector2
import org.openrndr.math.clamp
import org.openrndr.math.map
import org.openrndr.shape.Rectangle
import org.openrndr.shape.ShapeContour
import org.openrndr.shape.draw
import kotlin.math.*
import kotlin.random.Random

data class Star(
    val pos: Vector2,
    val mag: Double, // 0.0 (faint) to 1.0 (bright)
    val radius: Double,
    val alpha: Double,
    val color: ColorRGBa,
    val hasFlare: Boolean = false
)

data class Constellation(
    val stars: List<Star>,
    val path: List<Vector2>,
    val contour: ShapeContour,
    val name: String,
    val alphaStar: Star,
    val tension: Double
)

class SmoothConstellations {
    var seed = 42L
    var mode = 1 // 1: Atlas, 2: Hero, 3: Study
    var showDebug = false
    
    val width = 600.0
    val height = 800.0
    val margin = min(width, height) * 0.08
    val safeArea = Rectangle(margin, margin, width - 2 * margin, height - 2 * margin)

    fun generateStarField(seed: Long): List<Star> {
        val rng = Random(seed)
        val stars = mutableListOf<Star>()
        val count = rng.nextInt(2000, 4001)
        
        val angle = rng.nextDouble(0.0, PI)
        val cosA = cos(angle)
        val sinA = sin(angle)
        
        for (i in 0 until count) {
            val isBand = rng.nextDouble() < 0.3
            var pos: Vector2
            if (isBand) {
                // Rotated elliptical Gaussian band
                val u1 = rng.nextDouble()
                val u2 = rng.nextDouble()
                val g1 = sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
                val g2 = sqrt(-2.0 * ln(u1)) * sin(2.0 * PI * u2)
                
                // Scale Gaussian to fit band
                val x = g1 * (width * 0.4)
                val y = g2 * (height * 0.1)
                
                // Rotate and translate to center
                val rx = x * cosA - y * sinA + width / 2.0
                val ry = x * sinA + y * cosA + height / 2.0
                pos = Vector2(rx, ry)
            } else {
                pos = Vector2(
                    rng.nextDouble(safeArea.x, safeArea.x + safeArea.width),
                    rng.nextDouble(safeArea.y, safeArea.y + safeArea.height)
                )
            }
            
            // Check if pos is roughly within bounds (with some bleed)
            if (pos.x < -100 || pos.x > width + 100 || pos.y < -100 || pos.y > height + 100) {
                // Skip if way out of bounds, but usually Gaussian will be centered
            }

            val mag = rng.nextDouble().pow(2.5).coerceIn(0.0, 1.0)
            val radius = map(0.0, 1.0, 0.7, 8.0, mag)
            val alpha = map(0.0, 1.0, 0.3, 1.0, mag)
            
            val baseColor = if (rng.nextDouble() < 0.8) ColorRGBa.WHITE else ColorRGBa.BEIGE
            val starColor = baseColor.opacify(alpha)
            val hasFlare = mag > 0.95 && rng.nextDouble() < 0.2
            
            stars.add(Star(pos, mag, radius, alpha, starColor, hasFlare))
        }
        return stars
    }

    fun selectConstellationCenters(seed: Long, k: Int): List<Vector2> {
        val rng = Random(seed)
        val centers = mutableListOf<Vector2>()
        val minSep = min(width, height) * 0.15
        
        var attempts = 0
        while (centers.size < k && attempts < 1000) {
            val p = Vector2(
                rng.nextDouble(safeArea.x, safeArea.x + safeArea.width),
                rng.nextDouble(safeArea.y, safeArea.y + safeArea.height)
            )
            if (centers.all { it.distanceTo(p) > minSep }) {
                centers.add(p)
            }
            attempts++
        }
        return centers
    }

    fun chooseConstellationStars(center: Vector2, allStars: List<Star>, seed: Long, isHero: Boolean, tension: Double = 1.0): Constellation? {
        val rng = Random(seed)
        val radius = if (isHero) min(width, height) * 0.22 else min(width, height) * 0.12
        val candidates = allStars.filter { it.pos.distanceTo(center) < radius }
        if (candidates.size < 6) return null
        
        val count = rng.nextInt(6, 17)
        val selected = mutableListOf<Star>()
        
        // Start with brightest near center
        var current = candidates.minByOrNull { it.pos.distanceTo(center) / (it.mag + 0.1) } ?: return null
        selected.add(current)
        
        val alphaStar = candidates.maxByOrNull { it.mag } ?: current

        for (i in 1 until count) {
            val last = selected.last()
            val prev = if (selected.size > 1) selected[selected.size - 2] else null
            
            val best = candidates.filter { it !in selected }.minByOrNull { s ->
                val d = s.pos.distanceTo(last.pos)
                val distScore = if (d < radius * 0.1) 1000.0 else if (d > radius * 0.6) d else d * 0.5
                
                var angleScore = 0.0
                if (prev != null) {
                    val v1 = (last.pos - prev.pos).normalized
                    val v2 = (s.pos - last.pos).normalized
                    val dot = v1.dot(v2)
                    // penalize collinear (dot close to 1 or -1)
                    angleScore = if (abs(dot) > 0.9) 500.0 else (1.0 - dot) * 100.0
                }
                
                distScore + angleScore - s.mag * 200.0
            } ?: break
            selected.add(best)
        }
        
        // Order path (Greedy stroke walk)
        val path = selected.map { it.pos }
        if (path.size < 3) return null
        
        val contour = ShapeContour.fromPoints(path, closed = false).hobbyCurve(tension)
        val name = generateName(rng)
        
        return Constellation(selected, path, contour, name, alphaStar, tension)
    }

    fun renderStudyMode(drawer: Drawer, stars: List<Star>) {
        val panelW = width / 3.0
        val panelH = height / 3.0
        for (row in 0..2) {
            for (col in 0..2) {
                val panelBounds = Rectangle(col * panelW, row * panelH, panelW, panelH)
                val panelCenter = panelBounds.center
                val panelSeed = seed + row * 3 + col
                
                // Varied parameters for study
                val t = 0.8 + (row * 3 + col) * 0.1
                
                val c = chooseConstellationStars(panelCenter, stars, panelSeed, false, t)
                val panelConstellations = if (c != null) listOf(c) else emptyList()
                
                render(drawer, stars, panelConstellations, panelBounds, isSubPanel = true)
                
                // Panel label
                drawer.isolated {
                    drawer.fill = ColorRGBa.WHITE.opacify(0.5)
                    drawer.text("TENSION: ${"%.1f".format(t)}", panelBounds.x + 40.0, panelBounds.y + 60.0)
                }
            }
        }
    }
    
    private fun generateName(rng: Random): String {
        val prefixes = listOf("AUR", "CYG", "LYR", "CAS", "ORI", "DRA", "CEP", "PER")
        val suffix = rng.nextInt(10, 99)
        return "${prefixes.random(rng)}-$suffix"
    }

    fun render(drawer: Drawer, stars: List<Star>, constellations: List<Constellation>, bounds: Rectangle = Rectangle(0.0, 0.0, width, height), isSubPanel: Boolean = false) {
        drawer.isolated {
            drawer.stroke = null
            drawer.fill = ColorRGBa.fromHex("#050508")
            drawer.rectangle(bounds)

            // 1. Background effects
            renderVignette(drawer, bounds)
            renderGrain(drawer, bounds, if (isSubPanel) 1000 else 15000)
            renderGrid(drawer, bounds)

            // 2. Stars
            // Filter stars to bounds
            val visibleStars = stars.filter { bounds.contains(it.pos) }
            for (star in visibleStars) {
                drawer.fill = star.color
                drawer.circle(star.pos, star.radius)
                if (star.hasFlare) {
                    renderFlare(drawer, star)
                }
            }

            // 3. Constellations
            for (c in constellations) {
                // Check if constellation is roughly in bounds
                if (c.stars.any { bounds.contains(it.pos) }) {
                    renderConstellation(drawer, c)
                }
            }

            // 4. Atlas details (only if not a subpanel or if specifically requested)
            if (!isSubPanel) {
                renderLegend(drawer, bounds)
            }
        }
    }

    private fun renderVignette(drawer: Drawer, bounds: Rectangle) {
        // Simple procedural vignette using multiple large low-alpha circles or a rectangle with gradient
        // For simplicity and print-quality, we can use a few concentric rectangles with low alpha
        drawer.isolated {
            val center = bounds.center
            val maxDist = bounds.width * 0.7
            for (i in 0..10) {
                val r = map(0.0, 10.0, maxDist * 0.5, maxDist * 1.2, i.toDouble())
                drawer.fill = ColorRGBa.BLACK.opacify(0.02)
                drawer.circle(center, r)
            }
        }
    }

    private fun renderGrain(drawer: Drawer, bounds: Rectangle, count: Int) {
        val rng = Random(seed + 999)
        drawer.isolated {
            drawer.stroke = null
            for (i in 0 until count) {
                drawer.fill = ColorRGBa.WHITE.opacify(rng.nextDouble(0.01, 0.05))
                val p = Vector2(rng.nextDouble(bounds.x, bounds.x + bounds.width), rng.nextDouble(bounds.y, bounds.y + bounds.height))
                drawer.circle(p, rng.nextDouble(0.5, 1.5))
            }
            // Dust specks
            for (i in 0 until count / 100) {
                drawer.fill = ColorRGBa.WHITE.opacify(rng.nextDouble(0.05, 0.15))
                val p = Vector2(rng.nextDouble(bounds.x, bounds.x + bounds.width), rng.nextDouble(bounds.y, bounds.y + bounds.height))
                drawer.circle(p, rng.nextDouble(2.0, 4.0))
            }
        }
    }

    private fun renderGrid(drawer: Drawer, bounds: Rectangle) {
        drawer.isolated {
            drawer.stroke = ColorRGBa.WHITE.opacify(0.03)
            drawer.strokeWeight = 1.0
            val step = 500.0
            // Draw RA/Dec-like lines
            for (x in (step.toInt()..bounds.width.toInt() step step.toInt())) {
                drawer.lineSegment(bounds.x + x.toDouble(), bounds.y, bounds.x + x.toDouble(), bounds.y + bounds.height)
            }
            for (y in (step.toInt()..bounds.height.toInt() step step.toInt())) {
                drawer.lineSegment(bounds.x, bounds.y + y.toDouble(), bounds.x + bounds.width, bounds.y + y.toDouble())
            }
        }
    }

    private fun renderConstellation(drawer: Drawer, c: Constellation) {
        drawer.isolated {
            drawer.fill = null
            // Background glow pass
            drawer.stroke = ColorRGBa.WHITE.opacify(0.05)
            drawer.strokeWeight = 4.0
            drawer.contour(c.contour)

            // Main path with variation (thicker in mid, thinner at ends)
            val samples = 50
            for (i in 0 until samples) {
                val t0 = i.toDouble() / samples
                val t1 = (i + 1).toDouble() / samples
                val midT = (t0 + t1) / 2.0
                val distFromMid = abs(0.5 - midT) * 2.0 // 0.0 at mid, 1.0 at ends
                val weight = map(0.0, 1.0, 3.0, 1.2, distFromMid)
                
                drawer.stroke = ColorRGBa.WHITE.opacify(0.3)
                drawer.strokeWeight = weight
                drawer.contour(c.contour.sub(t0, t1))
            }
            
            // Alpha star marker
            drawer.stroke = ColorRGBa.WHITE.opacify(0.4)
            drawer.strokeWeight = 1.5
            drawer.circle(c.alphaStar.pos, c.alphaStar.radius + 15.0)
            
            // Typography
            drawer.fill = ColorRGBa.WHITE.opacify(0.7)
            val labelPos = c.contour.position(0.5) + Vector2(30.0, 0.0)
            drawer.text(c.name, labelPos)
            drawer.text("α", c.alphaStar.pos + Vector2(20.0, -20.0))
        }
    }

    private fun renderLegend(drawer: Drawer, bounds: Rectangle) {
        drawer.isolated {
            drawer.fill = ColorRGBa.WHITE.opacify(0.8)
            val x = bounds.x + margin
            val y = bounds.y + bounds.height - margin
            drawer.text("SMOOTH CONSTELLATIONS", x, y - 60.0)
            drawer.text("SEED: $seed", x, y - 40.0)
            drawer.text("MODE: ${if (mode==1) "ATLAS" else if (mode==2) "HERO" else "STUDY"}", x, y - 20.0)
            drawer.text("DATE: 2025-12-28", x, y)
        }
    }

    private fun renderFlare(drawer: Drawer, star: Star) {
        drawer.stroke = star.color.opacify(0.4)
        drawer.strokeWeight = 1.0
        val s = star.radius * 4.0
        drawer.lineSegment(star.pos - Vector2(s, 0.0), star.pos + Vector2(s, 0.0))
        drawer.lineSegment(star.pos - Vector2(0.0, s), star.pos + Vector2(0.0, s))
    }
}

fun main() = application {
    configure {
        width = 600
        height = 800
    }
    program {
        val sc = SmoothConstellations()
        var stars = sc.generateStarField(sc.seed)
        var centers = sc.selectConstellationCenters(sc.seed, 12)
        var constellations = centers.mapIndexed { i, ctr ->
            sc.chooseConstellationStars(ctr, stars, sc.seed + i, i == 0)
        }.filterNotNull()

        fun update() {
            stars = sc.generateStarField(sc.seed)
            val k = when(sc.mode) {
                1 -> 12
                2 -> 5
                3 -> 9
                else -> 12
            }
            centers = sc.selectConstellationCenters(sc.seed, k)
            constellations = centers.mapIndexed { i, ctr ->
                sc.chooseConstellationStars(ctr, stars, sc.seed + i, (sc.mode == 2 && i == 0) || (sc.mode == 1 && i < 2))
            }.filterNotNull()
        }

        keyboard.keyDown.listen {
            when (it.name) {
                "r" -> { sc.seed = Random.nextLong(); update() }
                "1" -> { sc.mode = 1; update() }
                "2" -> { sc.mode = 2; update() }
                "3" -> { sc.mode = 3; update() }
                "d" -> sc.showDebug = !sc.showDebug
                "e" -> {
                    // Export high-res
                    val rt = renderTarget(6000, 8000) {
                        colorBuffer()
                        depthBuffer()
                    }
                    drawer.isolatedWithTarget(rt) {
                        ortho(rt)
                        if (sc.mode == 3) {
                            sc.renderStudyMode(this, stars)
                        } else {
                            sc.render(this, stars, constellations)
                        }
                    }
                    val filename = "smooth-constellations-mode${sc.mode}-seed${sc.seed}.png"
                    rt.colorBuffer(0).saveToFile(java.io.File(filename))
                    rt.destroy()
                    println("Exported $filename")
                }
            }
        }

        extend {
            drawer.scale(width.toDouble() / 6000.0)
            if (sc.mode == 3) {
                sc.renderStudyMode(drawer, stars)
            } else {
                sc.render(drawer, stars, constellations)
            }
            
            if (sc.showDebug) {
                drawer.stroke = ColorRGBa.RED
                drawer.fill = null
                drawer.rectangle(sc.safeArea)
            }
        }
    }
}
