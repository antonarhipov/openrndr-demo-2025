package drawing

import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.*
import org.openrndr.extensions.Screenshots
import org.openrndr.extra.noise.gaussian
import org.openrndr.extra.noise.simplex
import org.openrndr.extra.shadestyles.linearGradient
import org.openrndr.math.Vector2
import org.openrndr.math.map
import org.openrndr.shape.*
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.*
import kotlin.random.Random
import java.util.Random as JRandom

data class CoastalParams(
    var seed: Int = Random.nextInt(),
    var horizonRange: ClosedFloatingPointRange<Double> = 0.52..0.65,
    var cliffWidthRange: ClosedFloatingPointRange<Double> = 0.55..0.65,
    var cliffHeightRange: ClosedFloatingPointRange<Double> = 0.4..0.8,
    var facetCount: Int = 25,
    var facetAngleBias: Double = 40.0, // degrees
    var scrapeCount: Int = 12,
    var scratchCount: Int = 15,
    var foregroundBandCount: Int = 18,
    var waterStrokeDensity: Int = 15,
    var foamAccentCount: Int = 20,
    var grainAmount: Double = 0.08,
    var palettePreset: Int = 0,
    var showGrain: Boolean = true,
    var debugMode: Boolean = false
)

class CoastalPalette(
    val sky: ColorRGBa,
    val cliffBase: ColorRGBa,
    val cliffMid: ColorRGBa,
    val cliffDeep: ColorRGBa,
    val seaMid: ColorRGBa,
    val seaDeep: ColorRGBa,
    val foam: ColorRGBa,
    val warmBlush: ColorRGBa? = null
)

val palettes = listOf(
    // Preset 0: Classic Cool Coastal
    CoastalPalette(
        sky = ColorRGBa.fromHex("F5F5DC"), // beige
        cliffBase = ColorRGBa.fromHex("2F4F4F"), // dark slate gray
        cliffMid = ColorRGBa.fromHex("4682B4"), // steel blue
        cliffDeep = ColorRGBa.fromHex("191970"), // midnight blue
        seaMid = ColorRGBa.fromHex("5F9EA0"), // cadet blue
        seaDeep = ColorRGBa.fromHex("2F4F4F"),
        foam = ColorRGBa.fromHex("F0FFFF") // azure
    ),
    // Preset 1: Moody Teal/Grey
    CoastalPalette(
        sky = ColorRGBa.fromHex("E0E0D0"),
        cliffBase = ColorRGBa.fromHex("1A2421"),
        cliffMid = ColorRGBa.fromHex("3D5A5A"),
        cliffDeep = ColorRGBa.fromHex("0A1210"),
        seaMid = ColorRGBa.fromHex("4A6363"),
        seaDeep = ColorRGBa.fromHex("1E2B2B"),
        foam = ColorRGBa.fromHex("DDEEEE")
    ),
    // Preset 2: Warm Dusk
    CoastalPalette(
        sky = ColorRGBa.fromHex("FFF8E1"),
        cliffBase = ColorRGBa.fromHex("3E2723"),
        cliffMid = ColorRGBa.fromHex("5D4037"),
        cliffDeep = ColorRGBa.fromHex("1B110F"),
        seaMid = ColorRGBa.fromHex("455A64"),
        seaDeep = ColorRGBa.fromHex("263238"),
        foam = ColorRGBa.fromHex("ECEFF1"),
        warmBlush = ColorRGBa.fromHex("FFCCBC").opacify(0.3)
    ),
    // Preset 3: Arctic Dawn
    CoastalPalette(
        sky = ColorRGBa.fromHex("E6E6FA"), // lavender
        cliffBase = ColorRGBa.fromHex("191970"), // midnight blue
        cliffMid = ColorRGBa.fromHex("483D8B"), // slate blue
        cliffDeep = ColorRGBa.fromHex("000033"), // deep navy
        seaMid = ColorRGBa.fromHex("708090"), // slate gray
        seaDeep = ColorRGBa.fromHex("2F4F4F"),
        foam = ColorRGBa.fromHex("F8F8FF") // ghost white
    ),
    // Preset 4: Mediterranean Olive
    CoastalPalette(
        sky = ColorRGBa.fromHex("FFFACD"), // lemon chiffon
        cliffBase = ColorRGBa.fromHex("556B2F"), // dark olive green
        cliffMid = ColorRGBa.fromHex("6B8E23"), // olive drab
        cliffDeep = ColorRGBa.fromHex("2E3B10"),
        seaMid = ColorRGBa.fromHex("20B2AA"), // light sea green
        seaDeep = ColorRGBa.fromHex("008B8B"), // dark cyan
        foam = ColorRGBa.fromHex("F0FFFF") // azure
    ),
    // Preset 5: Stormy Ochre
    CoastalPalette(
        sky = ColorRGBa.fromHex("F5F5F5"), // white smoke
        cliffBase = ColorRGBa.fromHex("3B2F2F"), // dark brown
        cliffMid = ColorRGBa.fromHex("8B4513"), // saddle brown
        cliffDeep = ColorRGBa.fromHex("24140E"),
        seaMid = ColorRGBa.fromHex("556B2F"), // olive
        seaDeep = ColorRGBa.fromHex("2F4F4F"),
        foam = ColorRGBa.fromHex("FFFFF0"), // ivory
        warmBlush = ColorRGBa.fromHex("FFD700").opacify(0.2) // golden highlight
    )
)

fun main() = application {
    configure {
        width = 1080
        height = 1080
    }

    program {
        var params = CoastalParams()
        var needsRedraw = true
        
        val rt = renderTarget(width, height) {
            colorBuffer()
            depthBuffer(DepthFormat.DEPTH24_STENCIL8)
        }

        fun redraw() {
            drawer.isolatedWithTarget(rt) {
                val rng = Random(params.seed.toLong())
                val palette = palettes[params.palettePreset]
                val w = width.toDouble()
                val h = height.toDouble()

                // 1. Background sky
                drawer.clear(palette.sky)
                
                val yH = h * rng.nextDouble(params.horizonRange.start, params.horizonRange.endInclusive)
                
                // Sky gradient
                drawer.fill = palette.sky.shade(0.95)
                drawer.stroke = null
                drawer.shadeStyle = linearGradient(palette.sky.shade(0.85), palette.sky, rotation = 90.0)
                drawer.rectangle(0.0, 0.0, w, yH)
                drawer.shadeStyle = null

                // 3. Main cliff silhouette (left-dominant)
                val cliffW = w * rng.nextDouble(params.cliffWidthRange.start, params.cliffWidthRange.endInclusive)
                val cliffH = h * rng.nextDouble(params.cliffHeightRange.start, params.cliffHeightRange.endInclusive)
                
                val cliffPoints = mutableListOf<Vector2>()
                cliffPoints.add(Vector2(0.0, yH))
                val segments = 12
                for (i in 0..segments) {
                    val t = i.toDouble() / segments
                    val px = t * cliffW
                    val py = yH - (cliffH * (1.0 - t)) * rng.nextDouble(0.8, 1.2)
                    cliffPoints.add(Vector2(px, py))
                }
                cliffPoints.add(Vector2(cliffW * 0.8, yH))
                cliffPoints.add(Vector2(0.0, yH))
                
                val cliffSilhouette = contour {
                    moveTo(cliffPoints[0])
                    for (i in 1 until cliffPoints.size) {
                        lineTo(cliffPoints[i])
                    }
                    close()
                }

                // 4. Facet the main cliff
                drawer.fill = palette.cliffBase
                drawer.contour(cliffSilhouette)

                val silhouetteShape = Shape(listOf(cliffSilhouette))
                val jrng = JRandom(params.seed.toLong())

                for (i in 0 until params.facetCount) {
                    val p1 = Vector2(rng.nextDouble(0.0, cliffW), rng.nextDouble(yH - cliffH, yH))
                    val size = rng.nextDouble(80.0, 250.0)
                    val angle = Math.toRadians(params.facetAngleBias + jrng.nextGaussian() * 20.0)
                    
                    val p2 = p1 + Vector2(cos(angle), sin(angle)) * size
                    val p3 = p1 + Vector2(cos(angle + PI/2), sin(angle + PI/2)) * size * rng.nextDouble(0.3, 0.7)
                    
                    val facet = contour {
                        moveTo(p1)
                        lineTo(p2)
                        lineTo(p3)
                        close()
                    }
                    
                    val intersections = intersection(silhouetteShape, Shape(listOf(facet)))
                    val baseCol = if (rng.nextDouble() > 0.4) {
                        if (rng.nextDouble() > 0.5) palette.cliffMid else palette.cliffDeep
                    } else {
                        palette.cliffBase
                    }
                    drawer.fill = baseCol.shade(rng.nextDouble(0.8, 1.2))
                    drawer.shape(intersections)
                }

                for (i in 0 until params.scrapeCount) {
                    val p1 = Vector2(rng.nextDouble(0.0, cliffW), rng.nextDouble(yH - cliffH, yH))
                    val length = rng.nextDouble(150.0, 400.0)
                    val angle = Math.toRadians(params.facetAngleBias + rng.nextDouble(-15.0, 15.0))
                    val thickness = rng.nextDouble(1.0, 6.0)
                    
                    val p2 = p1 + Vector2(cos(angle), sin(angle)) * length
                    drawer.stroke = palette.cliffDeep.opacify(rng.nextDouble(0.2, 0.6))
                    drawer.strokeWeight = thickness
                    drawer.lineSegment(p1, p2)
                }

                // 5. Secondary right cliffs
                val secCount = rng.nextInt(1, 4)
                for (i in 0 until secCount) {
                    val sW = w * rng.nextDouble(0.15, 0.35)
                    val sH = h * rng.nextDouble(0.05, 0.25)
                    val sX = w - sW * rng.nextDouble(0.8, 1.8)
                    
                    val sPoints = mutableListOf<Vector2>()
                    sPoints.add(Vector2(sX, yH))
                    sPoints.add(Vector2(sX + sW * 0.3, yH - sH * rng.nextDouble(0.8, 1.2)))
                    sPoints.add(Vector2(sX + sW, yH))
                    
                    drawer.fill = palette.seaDeep.mix(palette.sky, 0.6).opacify(0.8)
                    drawer.stroke = null
                    drawer.contour(contour {
                        moveTo(sPoints[0])
                        lineTo(sPoints[1])
                        lineTo(sPoints[2])
                        close()
                    })
                }

                // 6. Midground surf + rock line
                drawer.stroke = palette.cliffDeep.opacify(0.7)
                drawer.strokeWeight = 2.0
                for (x in 0..w.toInt() step 30) {
                    val yy = yH + rng.nextDouble(-4.0, 4.0)
                    if (rng.nextDouble() < 0.15) {
                        drawer.fill = palette.cliffBase.shade(rng.nextDouble(0.7, 1.0))
                        val rw = rng.nextDouble(5.0, 15.0)
                        val rh = rng.nextDouble(5.0, 15.0)
                        drawer.rectangle(x.toDouble(), yy - rh * 0.7, rw, rh)
                    }
                }
                
                drawer.fill = palette.foam
                drawer.stroke = null
                for (i in 0 until params.foamAccentCount) {
                    val fx = rng.nextDouble(0.0, w)
                    val fy = yH + rng.nextDouble(-8.0, 4.0)
                    val fw = rng.nextDouble(10.0, 40.0)
                    val fh = rng.nextDouble(2.0, 8.0)
                    drawer.pushTransforms()
                    drawer.translate(fx, fy)
                    drawer.rotate(rng.nextDouble(-10.0, 10.0))
                    drawer.rectangle(-fw/2, -fh/2, fw, fh)
                    drawer.popTransforms()
                }

                // 7. Foreground water/wet sand bands
                for (i in 0 until params.foregroundBandCount) {
                    val t = i.toDouble() / params.foregroundBandCount
                    val y = map(0.0, 1.0, yH, h, t)
                    val bandH = (h - yH) / params.foregroundBandCount * 1.8
                    
                    val baseCol = palette.seaMid.mix(palette.seaDeep, rng.nextDouble())
                    
                    for (j in 0 until params.waterStrokeDensity) {
                        val sx = rng.nextDouble(-200.0, w)
                        val sw = rng.nextDouble(200.0, 600.0)
                        val sy = y + rng.nextDouble(-bandH, bandH)
                        val sthick = rng.nextDouble(1.0, 8.0)
                        
                        var col = baseCol
                        if (palette.warmBlush != null && rng.nextDouble() < 0.15) {
                            col = palette.warmBlush
                        }
                        
                        drawer.stroke = col.opacify(rng.nextDouble(0.2, 0.6))
                        drawer.strokeWeight = sthick
                        val segs = rng.nextInt(2, 5)
                        var curX = sx
                        val segmentW = sw / segs
                        for (k in 0 until segs) {
                            if (rng.nextDouble() > 0.2) {
                                drawer.lineSegment(curX, sy, curX + segmentW * rng.nextDouble(0.6, 1.0), sy)
                            }
                            curX += segmentW
                        }
                    }
                    
                    if (rng.nextDouble() < 0.25) {
                        drawer.stroke = palette.foam.opacify(rng.nextDouble(0.1, 0.4))
                        drawer.strokeWeight = rng.nextDouble(0.5, 2.0)
                        val fx = rng.nextDouble(0.0, w)
                        drawer.lineSegment(fx, y, fx + rng.nextDouble(100.0, 400.0), y)
                    }
                }

                // 8. Birds
                drawer.stroke = palette.cliffDeep.opacify(0.8)
                drawer.strokeWeight = 1.2
                drawer.fill = null
                for (i in 0 until rng.nextInt(3, 7)) {
                    val bx = w * rng.nextDouble(0.6, 0.95)
                    val by = h * rng.nextDouble(0.1, 0.45)
                    val bSize = rng.nextDouble(4.0, 10.0)
                    drawer.contour(contour {
                        moveTo(bx - bSize, by)
                        curveTo(bx - bSize/2, by - bSize/2, bx, by)
                        curveTo(bx + bSize/2, by - bSize/2, bx + bSize, by)
                    })
                }

                // 9. Unify
                drawer.fill = palette.sky.mix(palette.seaMid, 0.2).opacify(0.15)
                drawer.stroke = null
                drawer.rectangle(0.0, yH - 30.0, w, 60.0)

                if (params.showGrain) {
                    drawer.defaults()
                    for (i in 0 until 100000) {
                        val gx = rng.nextDouble(0.0, w)
                        val gy = rng.nextDouble(0.0, h)
                        drawer.stroke = (if (rng.nextDouble() > 0.5) ColorRGBa.WHITE else ColorRGBa.BLACK).opacify(params.grainAmount * rng.nextDouble())
                        drawer.point(gx, gy)
                    }
                }
                
                for (i in 0 until params.scratchCount) {
                    val p1 = Vector2(rng.nextDouble(0.0, w), rng.nextDouble(0.0, h))
                    val p2 = p1 + Vector2(rng.nextDouble(-100.0, 100.0), rng.nextDouble(-100.0, 100.0))
                    drawer.stroke = palette.cliffDeep.opacify(0.15)
                    drawer.strokeWeight = 0.5
                    drawer.lineSegment(p1, p2)
                }

                if (params.debugMode) {
                    drawer.stroke = ColorRGBa.RED
                    drawer.strokeWeight = 1.0
                    drawer.lineSegment(0.0, yH, w, yH)
                    drawer.stroke = ColorRGBa.GREEN
                    drawer.contour(cliffSilhouette)
                }
            }
        }

        extend(Screenshots()) {
            key = "s"
        }

        keyboard.keyDown.listen {
            when (it.name) {
                "r" -> { params.seed = Random.nextInt(); needsRedraw = true }
                "p" -> { params.palettePreset = (params.palettePreset + 1) % palettes.size; needsRedraw = true }
                "e" -> {
                    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                    val fileName = "images/coastal_speedpaint_${params.seed}_$timestamp.png"
                    File("images").mkdirs()
                    rt.colorBuffer(0).saveToFile(File(fileName))
                    println("Exported to $fileName")
                }
                "[" -> { params.facetCount = max(5, params.facetCount - 5); needsRedraw = true }
                "]" -> { params.facetCount = min(100, params.facetCount + 5); needsRedraw = true }
                "-" -> { params.foregroundBandCount = max(5, params.foregroundBandCount - 2); needsRedraw = true }
                "=" -> { params.foregroundBandCount = min(50, params.foregroundBandCount + 2); needsRedraw = true }
                "g" -> { params.showGrain = !params.showGrain; needsRedraw = true }
                "d" -> { params.debugMode = !params.debugMode; needsRedraw = true }
            }
        }

        extend {
            if (needsRedraw) {
                redraw()
                needsRedraw = false
            }
            drawer.image(rt.colorBuffer(0))
        }
    }
}
