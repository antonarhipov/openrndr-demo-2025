package drawing

import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.color.ColorHSLa
import org.openrndr.color.rgb
import org.openrndr.draw.*
import org.openrndr.extra.color.presets.*
import org.openrndr.extra.shapes.hobbyCurve
import org.openrndr.extra.triangulation.DelaunayTriangulation
import org.openrndr.extra.triangulation.VoronoiDiagram
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

enum class DistributionMode {
    UNIFORM, CLUSTERED, GRADIENT
}

enum class PaletteMode {
    AMBER_GOLD, CELLULAR_GREENS
}

data class VoronoiParams(
    var seed: Long = Random.nextLong(),
    var siteCount: Int = 120,
    var distributionMode: DistributionMode = DistributionMode.UNIFORM,
    var relaxIters: Int = 3,
    var tension: Double = 0.5,
    var paletteMode: PaletteMode = PaletteMode.AMBER_GOLD,
    var organellesOn: Boolean = true,
    var debug: Boolean = false
)

class Cell(
    val site: Vector2,
    val contour: ShapeContour,
    val area: Double,
    val centroid: Vector2
)

fun generateSites(params: VoronoiParams, bounds: Rectangle): List<Vector2> {
    val rng = Random(params.seed)
    val sites = mutableListOf<Vector2>()
    
    when (params.distributionMode) {
        DistributionMode.UNIFORM -> {
            val minDist = sqrt(bounds.area / params.siteCount) * 0.5
            var attempts = 0
            while (sites.size < params.siteCount && attempts < 5000) {
                val p = Vector2(rng.nextDouble(bounds.x, bounds.x + bounds.width), rng.nextDouble(bounds.y, bounds.y + bounds.height))
                if (sites.none { it.distanceTo(p) < minDist }) {
                    sites.add(p)
                }
                attempts++
            }
            while (sites.size < params.siteCount) {
                sites.add(Vector2(rng.nextDouble(bounds.x, bounds.x + bounds.width), rng.nextDouble(bounds.y, bounds.y + bounds.height)))
            }
        }
        DistributionMode.CLUSTERED -> {
            val clusters = List(rng.nextInt(2, 6)) {
                Vector2(rng.nextDouble(bounds.x, bounds.x + bounds.width), rng.nextDouble(bounds.y, bounds.y + bounds.height))
            }
            while (sites.size < params.siteCount) {
                val center = clusters[rng.nextInt(clusters.size)]
                val u1 = rng.nextDouble().coerceAtLeast(1e-9)
                val u2 = rng.nextDouble()
                val g = sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
                val g2 = sqrt(-2.0 * ln(u1)) * sin(2.0 * PI * u2)
                val offset = Vector2(g, g2) * (bounds.width * 0.15)
                val p = center + offset
                if (p in bounds) {
                    sites.add(p)
                }
            }
        }
        DistributionMode.GRADIENT -> {
            while (sites.size < params.siteCount) {
                val p = Vector2(rng.nextDouble(bounds.x, bounds.x + bounds.width), rng.nextDouble(bounds.y, bounds.y + bounds.height))
                val t = (p.x - bounds.x) / bounds.width 
                if (rng.nextDouble() < t * 0.8 + 0.1) {
                    sites.add(p)
                }
            }
        }
    }
    return sites
}

fun lloydRelax(sites: List<Vector2>, bounds: Rectangle, iters: Int): List<Vector2> {
    var currentSites = sites
    repeat(iters) {
        if (currentSites.isNotEmpty()) {
            val dt = DelaunayTriangulation(currentSites)
            val voronoi = dt.voronoiDiagram(bounds)
            currentSites = voronoi.cellCentroids()
        }
    }
    return currentSites
}

fun computeVoronoi(sites: List<Vector2>, bounds: Rectangle): List<Cell> {
    if (sites.isEmpty()) return emptyList()
    val dt = DelaunayTriangulation(sites)
    val voronoi = dt.voronoiDiagram(bounds)
    val polygons: List<ShapeContour> = voronoi.cellPolygons()
    val centroids: List<Vector2> = voronoi.cellCentroids()
    
    val result = mutableListOf<Cell>()
    val n = minOf(sites.size, polygons.size, centroids.size)
    for (i in 0 until n) {
        val poly: ShapeContour = polygons[i]
        val area: Double = voronoi.cellArea(i)
        val centroid: Vector2 = centroids[i]
        result.add(Cell(sites[i], poly, area, centroid))
    }
    return result
}

fun cellToHobbyContour(cell: Cell, params: VoronoiParams): ShapeContour {
    val vertices = cell.contour.segments.map { it.start }
    if (vertices.size < 3) return cell.contour
    
    val areaFactor = map(0.0, 10000.0, 1.2, 0.8, cell.area).coerceIn(0.5, 2.0)
    val t = params.tension * areaFactor
    
    return try {
        hobbyCurve(vertices, true, t).contour
    } catch (e: Exception) {
        cell.contour
    }
}

fun membraneStrokeWidth(cellArea: Double, _params: VoronoiParams, baseScale: Double): Double {
    return map(0.0, 10000.0, 1.5, 5.0, cellArea).coerceIn(1.0, 8.0) * baseScale
}

fun cellFillColor(_cell: Cell, params: VoronoiParams, rng: Random): ColorRGBa {
    val h: Double
    val s: Double
    val l: Double
    when (params.paletteMode) {
        PaletteMode.AMBER_GOLD -> {
            h = rng.nextDouble(35.0, 55.0)
            s = rng.nextDouble(0.6, 0.9)
            l = rng.nextDouble(0.4, 0.7)
        }
        PaletteMode.CELLULAR_GREENS -> {
            h = rng.nextDouble(80.0, 160.0)
            s = rng.nextDouble(0.4, 0.8)
            l = rng.nextDouble(0.2, 0.5)
        }
    }
    return ColorHSLa(h, s, l, 0.7).toRGBa()
}

fun main() = application {
    configure {
        width = 6000
        height = 8000
        title = "Voronoi Curves"
    }
    program {
        var params = VoronoiParams()
        
        fun renderPoster(drawer: Drawer, p: VoronoiParams, export: Boolean = false) {
            val margin = minOf(drawer.width, drawer.height) * 0.08
            val safeArea = drawer.bounds.offsetEdges(-margin)
            
            var sites = generateSites(p, safeArea)
            sites = lloydRelax(sites, safeArea, p.relaxIters)
            val cells = computeVoronoi(sites, safeArea)
            
            val bgColor = if (p.paletteMode == PaletteMode.AMBER_GOLD) rgb("080808") else rgb("051005")
            drawer.clear(bgColor)
            
            drawer.isolated {
                drawer.shadeStyle = shadeStyle {
                    fragmentTransform = """
                        vec2 uv = c_boundsPosition.xy;
                        float d = distance(uv, vec2(0.5));
                        x_fill.rgb *= (1.0 - smoothstep(0.4, 1.2, d) * 0.5);
                    """.trimIndent()
                }
                drawer.fill = ColorRGBa.WHITE
                drawer.stroke = null
                drawer.rectangle(drawer.bounds)
            }
            
            val cellData = cells.map { cell ->
                val contour = cellToHobbyContour(cell, p)
                val color = cellFillColor(cell, p, Random(p.seed + cell.site.hashCode()))
                Triple(cell, contour, color)
            }
            
            // 1. Fills
            for (data in cellData) {
                val (cell, contour, color) = data
                drawer.fill = color
                drawer.stroke = null
                
                drawer.shadeStyle = shadeStyle {
                    parameter("center", cell.centroid)
                    parameter("color", color)
                    parameter("radius", sqrt(cell.area) * 1.5)
                    fragmentTransform = """
                        vec2 p = va_position.xy;
                        float d = distance(p, p_center);
                        float f = smoothstep(p_radius, 0.0, d);
                        x_fill.rgb = p_color.rgb * (0.6 + 0.4 * f);
                        x_fill.a = p_color.a;
                    """.trimIndent()
                }
                drawer.contour(contour)
                drawer.shadeStyle = null
            }
            
            // 2. Membranes
            for (data in cellData) {
                val (cell, contour, _) = data
                drawer.fill = null
                drawer.stroke = if (p.paletteMode == PaletteMode.AMBER_GOLD) ColorRGBa.BLACK.opacify(0.8) else ColorRGBa.BLACK.opacify(0.9)
                drawer.strokeWeight = membraneStrokeWidth(cell.area, p, if(export) 4.0 else 1.0)
                drawer.contour(contour)
                
                drawer.stroke = ColorRGBa.WHITE.opacify(0.1)
                drawer.strokeWeight = drawer.strokeWeight * 0.3
                drawer.contour(contour)
            }
            
            // 3. Organelles
            if (p.organellesOn) {
                for (data in cellData) {
                    val (cell, _, color) = data
                    if (Random(cell.site.hashCode()).nextDouble() < 0.2) {
                        val organelleRng = Random(cell.site.hashCode() + 1)
                        drawer.isolated {
                            drawer.translate(cell.centroid)
                            drawer.rotate(organelleRng.nextDouble(360.0))
                            val size = sqrt(cell.area) * 0.2
                            drawer.fill = color.shade(1.5).opacify(0.5)
                            drawer.stroke = ColorRGBa.BLACK.opacify(0.3)
                            drawer.strokeWeight = if(export) 4.0 else 1.0
                            
                            val opPoints = List(3) { Vector2(organelleRng.nextDouble(-size, size), organelleRng.nextDouble(-size, size)) }
                            if (opPoints.size >= 3) {
                                try {
                                    drawer.contour(hobbyCurve(opPoints, true).contour)
                                } catch (e: Exception) {}
                            }
                        }
                    }
                }
            }
            
            // 4. Debug
            if (p.debug) {
                for (cell in cells) {
                    drawer.isolated {
                        drawer.fill = null
                        drawer.stroke = ColorRGBa.RED
                        drawer.strokeWeight = 0.5
                        drawer.contour(cell.contour)
                        drawer.fill = ColorRGBa.RED
                        drawer.circle(cell.site, 2.0)
                    }
                }
            }
            
            // 5. Caption
            drawer.isolated {
                drawer.fill = ColorRGBa.WHITE.opacify(0.6)
                val caption = "VORONOI CURVES | Seed: ${p.seed} | Sites: ${p.siteCount} | Relax: ${p.relaxIters} | Tension: ${"%.2f".format(p.tension)} | Palette: ${p.paletteMode}"
                drawer.fontMap = defaultFontMap
                drawer.text(caption, margin, drawer.height - margin * 0.4)
            }
        }
        
        extend {
            renderPoster(drawer, params)
        }
        
        keyboard.keyDown.listen {
            when (it.name) {
                "r" -> params.seed = Random.nextLong()
                "1" -> params.paletteMode = PaletteMode.AMBER_GOLD
                "2" -> params.paletteMode = PaletteMode.CELLULAR_GREENS
                "m" -> {
                    val modes = DistributionMode.entries
                    params.distributionMode = modes[(params.distributionMode.ordinal + 1) % modes.size]
                }
                "right-bracket" -> params.siteCount += 10
                "left-bracket" -> params.siteCount = (params.siteCount - 10).coerceAtLeast(10)
                "l" -> params.relaxIters = (params.relaxIters + 1).coerceAtMost(10)
                "k" -> params.relaxIters = (params.relaxIters - 1).coerceAtLeast(0)
                "equals" -> params.tension += 0.1
                "minus" -> params.tension -= 0.1
                "o" -> params.organellesOn = !params.organellesOn
                "d" -> params.debug = !params.debug
                "e" -> {
                    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                    val filename = "images/voronoi_s${params.seed}_S${params.siteCount}_R${params.relaxIters}_T${"%.2f".format(params.tension)}_${params.paletteMode}_$timestamp.png"

                    val highRes = renderTarget(width, height) {
                        colorBuffer()
                        depthBuffer()
                    }
                    drawer.isolatedWithTarget(highRes) {
                        renderPoster(this, params, true)
                    }
                    highRes.colorBuffer(0).saveToFile(File(filename))
                    highRes.destroy()
                    println("Exported $filename")
                }
            }
        }
    }
}
