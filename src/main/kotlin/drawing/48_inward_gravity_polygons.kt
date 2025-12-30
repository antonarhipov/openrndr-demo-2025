package drawing

/**
 * Inward Gravity Polygons
 * A generative sketch where multiple polygons are deformed by a central gravity field.
 * Polygons bend and lean toward the gravity center while maintaining a non-intersection constraint.
 */

import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.*
import org.openrndr.extra.gui.GUI
import org.openrndr.extra.noise.perlinQuintic
import org.openrndr.extra.parameters.*
import org.openrndr.extra.shapes.hobbyCurve
import org.openrndr.math.Vector2
import org.openrndr.shape.ShapeContour
import org.openrndr.shape.contains
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.math.*

class InwardGravityPolygonsParams {
    @IntParameter("Seed", 0, 1000000)
    var seed: Int = 123456

    @IntParameter("Num Polygons", 6, 18)
    var numPolygons: Int = 12

    @DoubleParameter("Gravity Jitter", 0.0, 0.2)
    var gravityJitter: Double = 0.08

    @DoubleParameter("Gravity Strength", 0.0, 500.0)
    var gravityStrength: Double = 200.0

    @DoubleParameter("Gravity Falloff Scale", 10.0, 1000.0)
    var gravityFalloffScale: Double = 400.0

    @DoubleParameter("Shear Amount", 0.0, 1.0)
    var shearAmount: Double = 0.3

    @DoubleParameter("Min Gap", 0.0, 50.0)
    var minGap: Double = 15.0

    @IntParameter("Min Sides", 5, 9)
    var minSides: Int = 5

    @IntParameter("Max Sides", 5, 9)
    var maxSides: Int = 8

    @DoubleParameter("Min Size", 20.0, 100.0)
    var minSize: Double = 40.0

    @DoubleParameter("Max Size", 50.0, 200.0)
    var maxSize: Double = 100.0

    @IntParameter("Samples Per Edge", 2, 20)
    var deformationSamplesPerEdge: Int = 10

    @IntParameter("Palette Mode", 0, 3)
    var paletteMode: Int = 0

    @BooleanParameter("Show Debug")
    var showDebug: Boolean = false

    @BooleanParameter("Dark Mode")
    var darkMode: Boolean = true

    @BooleanParameter("Show Strokes")
    var showStrokes: Boolean = false
}

class DeformedPolygon(
    val baseCenter: Vector2,
    val deformedPoints: List<Vector2>,
    val contour: ShapeContour,
    val color: ColorRGBa,
    val boundingRadius: Double
)

fun main() = application {
    configure {
        width = 600
        height = 800
    }
    program {
        val params = InwardGravityPolygonsParams()
        val gui = GUI()
        gui.add(params)
        extend(gui)
        
        var polygons = mutableListOf<DeformedPolygon>()
        var gravityCenter = Vector2(width / 2.0, height / 2.0)
        var dirty = true

        var lastParamsHash = -1
        fun getParamsHash(): Int {
            return Objects.hash(
                params.seed, params.numPolygons, params.gravityStrength,
                params.gravityFalloffScale, params.shearAmount, params.minGap,
                params.minSides, params.maxSides, params.minSize, params.maxSize,
                params.deformationSamplesPerEdge, params.paletteMode, params.gravityJitter,
                params.darkMode, params.showStrokes
            )
        }
        
        fun getPalette(mode: Int): List<ColorRGBa> {
            return when (mode) {
                0 -> listOf( // Earthy
                    ColorRGBa.fromHex("#4a5d23"),
                    ColorRGBa.fromHex("#7c9473"),
                    ColorRGBa.fromHex("#cfd1a9"),
                    ColorRGBa.fromHex("#a68a64"),
                    ColorRGBa.fromHex("#582f0e")
                )
                1 -> listOf( // Cyberpunk
                    ColorRGBa.fromHex("#00f5d4"),
                    ColorRGBa.fromHex("#00bbf9"),
                    ColorRGBa.fromHex("#fee440"),
                    ColorRGBa.fromHex("#f15bb5"),
                    ColorRGBa.fromHex("#9b5de5")
                )
                2 -> listOf( // Grayscale
                    ColorRGBa.fromHex("#333333"),
                    ColorRGBa.fromHex("#666666"),
                    ColorRGBa.fromHex("#999999"),
                    ColorRGBa.fromHex("#cccccc"),
                    ColorRGBa.fromHex("#eeeeee")
                )
                else -> listOf( // Bauhaus
                    ColorRGBa.fromHex("#e63946"),
                    ColorRGBa.fromHex("#f1faee"),
                    ColorRGBa.fromHex("#a8dadc"),
                    ColorRGBa.fromHex("#457b9d"),
                    ColorRGBa.fromHex("#1d3557")
                )
            }
        }

        fun generate() {
            val random = Random(params.seed.toLong())
            polygons.clear()
            
            val minDim = min(width, height).toDouble()
            val jitter = minDim * params.gravityJitter
            gravityCenter = Vector2(
                width / 2.0 + (random.nextDouble() * 2.0 - 1.0) * jitter,
                height / 2.0 + (random.nextDouble() * 2.0 - 1.0) * jitter
            )
            
            val palette = getPalette(params.paletteMode)
            
            var attempts = 0
            val maxAttempts = 2000
            
            while (polygons.size < params.numPolygons && attempts < maxAttempts) {
                attempts++
                
                val center = Vector2(
                    random.nextDouble() * (width - 150.0) + 75.0,
                    random.nextDouble() * (height - 150.0) + 75.0
                )
                
                val sides = random.nextInt(params.minSides, params.maxSides + 1)
                val baseRadius = random.nextDouble() * (params.maxSize - params.minSize) + params.minSize
                val rotation = random.nextDouble() * PI * 2.0
                
                val vertices = mutableListOf<Vector2>()
                for (i in 0 until sides) {
                    val angle = rotation + (i.toDouble() / sides) * PI * 2.0
                    val r = baseRadius * (0.8 + random.nextDouble() * 0.4)
                    vertices.add(center + Vector2(cos(angle), sin(angle)) * r)
                }
                
                val resampled = mutableListOf<Vector2>()
                for (i in 0 until sides) {
                    val p1 = vertices[i]
                    val p2 = vertices[(i + 1) % sides]
                    for (j in 0 until params.deformationSamplesPerEdge) {
                        resampled.add(p1.mix(p2, j.toDouble() / params.deformationSamplesPerEdge))
                    }
                }
                
                val deformed = resampled.map { p ->
                    val u = gravityCenter - p
                    val d = u.length
                    val w = params.gravityStrength * exp(-d / params.gravityFalloffScale)
                    
                    val displacement = u.normalized * w
                    
                    val t = Vector2(-u.y, u.x).normalized
                    val n = perlinQuintic(params.seed, p.x * 0.01, p.y * 0.01)
                    val shear = t * (params.shearAmount * w * n)
                    
                    p + displacement + shear
                }
                
                val polyContour = try {
                    hobbyCurve(deformed, true).contour
                } catch (e: Exception) {
                    continue
                }
                
                var intersects = false
                val effectiveRadius = baseRadius + params.gravityStrength * 0.5 // Rough estimate
                
                for (existing in polygons) {
                    val dist = (center - existing.baseCenter).length
                    if (dist < (effectiveRadius + existing.boundingRadius + params.minGap)) {
                         intersects = true
                         break
                    }
                }
                
                if (!intersects) {
                    for (existing in polygons) {
                        for (p in deformed) {
                             if (existing.contour.contains(p)) {
                                 intersects = true
                                 break
                             }
                        }
                        if (intersects) break
                        for (p in existing.deformedPoints) {
                            if (polyContour.contains(p)) {
                                intersects = true
                                break
                            }
                        }
                        if (intersects) break
                    }
                }
                
                if (!intersects) {
                    polygons.add(DeformedPolygon(
                        center,
                        deformed,
                        polyContour,
                        palette[random.nextInt(palette.size)],
                        baseRadius
                    ))
                }
            }
        }

        fun render(g: Drawer, debug: Boolean = params.showDebug) {
            g.clear(if (params.darkMode) ColorRGBa.fromHex("#1a1a1a") else ColorRGBa.fromHex("#f0f0f0"))
            
            for (poly in polygons) {
                g.stroke = if (params.showStrokes) {
                    if (params.darkMode) ColorRGBa.WHITE.opacify(0.3) else ColorRGBa.BLACK.opacify(0.3)
                } else null
                g.strokeWeight = 0.5
                g.fill = poly.color
                g.contour(poly.contour)
            }
            
            if (debug) {
                g.fill = ColorRGBa.RED
                g.circle(gravityCenter, 5.0)
                
                g.stroke = ColorRGBa.RED.opacify(0.3)
                g.strokeWeight = 1.0
                g.fill = null
                g.circle(gravityCenter, params.gravityFalloffScale)
                
                for (poly in polygons) {
                    g.stroke = ColorRGBa.GREEN.opacify(0.5)
                    g.circle(poly.baseCenter, poly.boundingRadius)
                }
            }
        }

        keyboard.keyDown.listen {
            when (it.key) {
                'r'.code -> {
                    params.seed = (Math.random() * 1000000).toInt()
                    dirty = true
                }
                'g'.code -> {
                    params.showDebug = !params.showDebug
                }
                '['.code -> {
                    params.gravityStrength = max(0.0, params.gravityStrength - 20.0)
                    dirty = true
                }
                ']'.code -> {
                    params.gravityStrength = min(1000.0, params.gravityStrength + 20.0)
                    dirty = true
                }
                '-'.code -> {
                    params.numPolygons = max(1, params.numPolygons - 1)
                    dirty = true
                }
                '='.code -> {
                    params.numPolygons = min(50, params.numPolygons + 1)
                    dirty = true
                }
                'b'.code -> {
                    params.darkMode = !params.darkMode
                }
                'd'.code -> {
                    params.showDebug = !params.showDebug
                }
                's'.code -> {
                    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                    val folder = File("images")
                    if (!folder.exists()) folder.mkdir()
                    val file = File(folder, "inward_gravity_${params.seed}_$timestamp.png")
                    
                    val rt = renderTarget(width, height) {
                        colorBuffer()
                    }
                    drawer.isolatedWithTarget(rt) {
                        render(this, debug = false)
                    }
                    rt.colorBuffer(0).saveToFile(file)
                    println("Saved to ${file.absolutePath}")
                    rt.colorBuffer(0).destroy()
                    rt.destroy()
                }
            }
        }

        extend {
            val currentHash = getParamsHash()
            if (dirty || currentHash != lastParamsHash) {
                generate()
                dirty = false
                lastParamsHash = currentHash
            }
            render(drawer)
        }
    }
}
