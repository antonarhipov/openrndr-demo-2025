package drawing

import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.color.mix
import org.openrndr.color.rgb
import org.openrndr.draw.*
import org.openrndr.extra.noise.simplex
import org.openrndr.extra.shapes.hobbyCurve
import org.openrndr.math.Vector2
import org.openrndr.shape.ShapeContour
import org.openrndr.shape.contour
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.*
import kotlin.random.Random

/**
 * Hilly Foggy Landscapes
 * Based on ideas from 31_hobby_strata_gradients.kt
 */

enum class LandscapePalette {
    BLUE_MIST,
    FOREST_FOG,
    DESERT_HAZE,
    VIOLET_DAWN,
    GOLDEN_HOUR,
    ARCTIC_COLD
}

data class HillyFoggyParams(
    val seed: Long = System.currentTimeMillis(),
    val hillCount: Int = 8,
    val controlPts: Int = 8,
    val tension: Double = 1.0,
    val baseAmplitude: Double = 40.0,
    val perspective: Double = 1.4, // Higher means more compression at the top
    val palette: LandscapePalette = LandscapePalette.BLUE_MIST,
    val fogDensity: Double = 0.5,
    val showDebug: Boolean = false,
    val showSun: Boolean = true,
    val sunPosition: Vector2 = Vector2(0.7, 0.2) // Normalized position
)

fun getBackgroundColor(palette: LandscapePalette): ColorRGBa {
    return getLandscapeColors(palette).first
}

fun getLandscapeColors(palette: LandscapePalette): Triple<ColorRGBa, ColorRGBa, List<ColorRGBa>> {
    val skyColor: ColorRGBa
    val fogColor: ColorRGBa
    val hillColors: List<ColorRGBa>
    
    when (palette) {
        LandscapePalette.BLUE_MIST -> {
            skyColor = rgb(0.7, 0.8, 0.9)
            fogColor = rgb(0.85, 0.9, 0.95)
            hillColors = listOf(
                rgb(0.15, 0.25, 0.35),
                rgb(0.2, 0.3, 0.45),
                rgb(0.25, 0.35, 0.5),
                rgb(0.3, 0.4, 0.55)
            )
        }
        LandscapePalette.FOREST_FOG -> {
            skyColor = rgb(0.65, 0.75, 0.7)
            fogColor = rgb(0.8, 0.85, 0.82)
            hillColors = listOf(
                rgb(0.05, 0.15, 0.05),
                rgb(0.1, 0.2, 0.1),
                rgb(0.15, 0.25, 0.15),
                rgb(0.2, 0.3, 0.2)
            )
        }
        LandscapePalette.DESERT_HAZE -> {
            skyColor = rgb(0.85, 0.75, 0.65)
            fogColor = rgb(0.9, 0.85, 0.8)
            hillColors = listOf(
                rgb(0.4, 0.2, 0.1),
                rgb(0.5, 0.3, 0.2),
                rgb(0.6, 0.4, 0.3),
                rgb(0.7, 0.5, 0.4)
            )
        }
        LandscapePalette.VIOLET_DAWN -> {
            skyColor = rgb(0.2, 0.15, 0.35)
            fogColor = rgb(0.5, 0.4, 0.6)
            hillColors = listOf(
                rgb(0.05, 0.02, 0.1),
                rgb(0.1, 0.05, 0.2),
                rgb(0.15, 0.08, 0.3),
                rgb(0.2, 0.1, 0.4)
            )
        }
        LandscapePalette.GOLDEN_HOUR -> {
            skyColor = rgb(0.9, 0.6, 0.3)
            fogColor = rgb(1.0, 0.8, 0.5)
            hillColors = listOf(
                rgb(0.3, 0.1, 0.0),
                rgb(0.4, 0.2, 0.05),
                rgb(0.5, 0.3, 0.1),
                rgb(0.6, 0.4, 0.15)
            )
        }
        LandscapePalette.ARCTIC_COLD -> {
            skyColor = rgb(0.85, 0.9, 1.0)
            fogColor = rgb(0.95, 0.98, 1.0)
            hillColors = listOf(
                rgb(0.05, 0.1, 0.15),
                rgb(0.1, 0.15, 0.2),
                rgb(0.15, 0.2, 0.25),
                rgb(0.2, 0.25, 0.3)
            )
        }
    }
    return Triple(skyColor, fogColor, hillColors)
}

fun hillNoise(seed: Long, hillIndex: Int, pointIndex: Int, freq: Double = 0.2): Double {
    val seedInt = seed.toInt()
    return simplex(seedInt, pointIndex * freq, hillIndex * 2.3)
}

fun generateHillBoundaries(params: HillyFoggyParams, width: Double, height: Double): List<ShapeContour> {
    val boundaries = mutableListOf<ShapeContour>()
    
    // Add a top boundary for the sky (at the horizon)
    // We use a non-linear distribution for Y bases to simulate perspective
    for (i in 0..params.hillCount) {
        val points = mutableListOf<Vector2>()
        
        // Normalized depth [0, 1], where 0 is far (top) and 1 is near (bottom)
        val tDepth = i.toDouble() / params.hillCount
        
        // Apply perspective power
        val yBaseNorm = tDepth.pow(params.perspective)
        val horizon = 0.3 * height // Top 30% is sky
        val yBase = horizon + yBaseNorm * (height - horizon)
        
        // Amplitude increases with proximity
        val currentAmplitude = params.baseAmplitude * (0.2 + 0.8 * yBaseNorm)
        
        for (j in 0 until params.controlPts) {
            val tX = j.toDouble() / (params.controlPts - 1)
            val x = -50.0 + tX * (width + 100.0)
            
            val noise = hillNoise(params.seed, i, j)
            val y = yBase + noise * currentAmplitude
            
            points.add(Vector2(x, y))
        }
        
        val contour = hobbyCurve(points, closed = false, curl = params.tension.coerceIn(0.5, 2.0)).contour
        boundaries.add(contour)
    }
    
    return boundaries
}

fun buildHillSlice(topBoundary: ShapeContour, bottomBoundary: ShapeContour, width: Double, height: Double): ShapeContour {
    val samples = 100
    return contour {
        moveTo(topBoundary.position(0.0))
        for (i in 1..samples) {
            lineTo(topBoundary.position(i.toDouble() / samples))
        }
        
        // Instead of just going to the bottom boundary, we can go to the screen bottom for the last hill
        // But for consistency, we use the bottom boundary.
        // The last "bottom boundary" should ideally be at or below the screen bottom.
        
        lineTo(bottomBoundary.position(1.0))
        for (i in samples - 1 downTo 0) {
            lineTo(bottomBoundary.position(i.toDouble() / samples))
        }
        close()
    }
}

fun createFoggyShadeStyle(
    hillIndex: Int,
    totalHills: Int,
    params: HillyFoggyParams,
    hillColor: ColorRGBa,
    fogColor: ColorRGBa
): ShadeStyle {
    // Atmospheric perspective: far hills are more blended with fog
    val tDepth = hillIndex.toDouble() / totalHills
    val depthBlend = (1.0 - tDepth).pow(2.0) * params.fogDensity
    
    val baseColor = mix(hillColor, fogColor, depthBlend)
    
    return shadeStyle {
        fragmentTransform = """
            vec2 screenPos = c_boundsPosition.xy;
            // Vertical gradient: top of the hill is baseColor, bottom is fogColor
            float t = screenPos.y; 
            t = pow(t, 1.5); // non-linear fog fade
            x_fill = mix(p_baseColor, p_fogColor, t);
        """
        parameter("baseColor", baseColor)
        parameter("fogColor", fogColor)
    }
}

fun renderLandscape(drawer: Drawer, params: HillyFoggyParams, width: Double, height: Double) {
    val (skyColor, fogColor, hillColors) = getLandscapeColors(params.palette)
    
    drawer.clear(skyColor)
    
    // Draw Sun
    if (params.showSun) {
        drawer.isolated {
            val sunX = params.sunPosition.x * width
            val sunY = params.sunPosition.y * height
            
            // Sun glow
            for (i in 0..20) {
                drawer.fill = ColorRGBa.WHITE.copy(alpha = 0.02)
                drawer.stroke = null
                drawer.circle(sunX, sunY, 40.0 + i * 5.0)
            }
            
            drawer.fill = ColorRGBa.WHITE
            drawer.circle(sunX, sunY, 40.0)
        }
    }
    
    val boundaries = generateHillBoundaries(params, width, height)
    
    // Add a virtual boundary at the very bottom to close the last hill
    val bottomPoints = listOf(
        Vector2(-50.0, height + 100.0),
        Vector2(width + 50.0, height + 100.0)
    )
    val bottomBoundary = hobbyCurve(bottomPoints, closed = false).contour
    
    val allBoundaries = boundaries + bottomBoundary
    
    for (i in 0 until allBoundaries.size - 1) {
        val hillShape = buildHillSlice(allBoundaries[i], allBoundaries[i+1], width, height)
        
        val hillColor = hillColors[i % hillColors.size]
        val style = createFoggyShadeStyle(i, allBoundaries.size - 1, params, hillColor, fogColor)
        
        drawer.isolated {
            drawer.shadeStyle = style
            drawer.stroke = null
            drawer.fill = ColorRGBa.WHITE
            drawer.contour(hillShape)
        }
    }
}

fun exportPNG(drawer: Drawer, params: HillyFoggyParams) {
    val rt = renderTarget(800, 800) {
        colorBuffer()
        depthBuffer(DepthFormat.DEPTH24_STENCIL8)
    }
    
    drawer.isolatedWithTarget(rt) {
        ortho(rt)
        renderLandscape(this, params, rt.width.toDouble(), rt.height.toDouble())
    }
    
    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val filename = "hilly_fog_${params.seed}_${params.palette.name.lowercase()}_$timestamp.png"
    
    File("images").mkdirs()
    rt.colorBuffer(0).saveToFile(File("images/$filename"))
    rt.destroy()
    
    println("Exported: images/$filename")
}

fun main() = application {
    configure {
        width = 800
        height = 800
        title = "Hilly Foggy Landscapes"
    }
    
    program {
        var params = HillyFoggyParams()
        var statusMessage = ""
        var statusTime = 0.0

        fun showStatus(msg: String) {
            statusMessage = msg
            statusTime = seconds
        }
        
        fun randomize() {
            params = params.copy(seed = Random.nextLong())
            showStatus("Randomized seed: ${params.seed}")
        }
        
        keyboard.keyDown.listen {
            when (it.name) {
                "r" -> randomize()
                "p" -> {
                    val palettes = LandscapePalette.entries
                    val nextIdx = (palettes.indexOf(params.palette) + 1) % palettes.size
                    params = params.copy(palette = palettes[nextIdx])
                    showStatus("Palette: ${params.palette}")
                }
                "h" -> {
                    params = params.copy(hillCount = (params.hillCount + 1).coerceIn(3, 20))
                    showStatus("Hills: ${params.hillCount}")
                }
                "j" -> {
                    params = params.copy(hillCount = (params.hillCount - 1).coerceIn(3, 20))
                    showStatus("Hills: ${params.hillCount}")
                }
                "f" -> {
                    params = params.copy(fogDensity = (params.fogDensity + 0.05).coerceIn(0.0, 1.0))
                    showStatus("Fog: ${String.format("%.2f", params.fogDensity)}")
                }
                "g" -> {
                    params = params.copy(fogDensity = (params.fogDensity - 0.05).coerceIn(0.0, 1.0))
                    showStatus("Fog: ${String.format("%.2f", params.fogDensity)}")
                }
                "u" -> {
                    params = params.copy(showSun = !params.showSun)
                    showStatus("Sun: ${if (params.showSun) "ON" else "OFF"}")
                }
                "d" -> {
                    params = params.copy(showDebug = !params.showDebug)
                    showStatus("Debug: ${if (params.showDebug) "ON" else "OFF"}")
                }
                "e" -> {
                    exportPNG(drawer, params)
                    showStatus("Exported PNG")
                }
            }
        }
        
        extend {
            renderLandscape(drawer, params, width.toDouble(), height.toDouble())
            
            // Display status message
            if (seconds - statusTime < 2.0 && statusMessage.isNotEmpty()) {
                drawer.isolated {
                    drawer.fill = ColorRGBa.BLACK.copy(alpha = 0.7)
                    drawer.text(statusMessage, 20.0, height - 20.0)
                }
            }

            if (params.showDebug) {
                drawer.isolated {
                    drawer.fill = ColorRGBa.BLACK
                    drawer.fontMap = defaultFontMap
                    drawer.text("Seed: ${params.seed}", 20.0, 30.0)
                    drawer.text("Palette: ${params.palette}", 20.0, 50.0)
                    drawer.text("Hills: ${params.hillCount}", 20.0, 70.0)
                    drawer.text("Fog: ${String.format("%.2f", params.fogDensity)}", 20.0, 90.0)
                }
            }
        }
    }
}
