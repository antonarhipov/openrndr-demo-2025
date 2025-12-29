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
 * High Mountain Landscapes
 * Inspired by Tibet, Alps, and Rockies.
 * Based on ideas from 31_hobby_strata_gradients.kt and 41_hilly_foggy_landscapes.kt
 */

enum class MountainPalette {
    TIBETAN_PLATEAU,
    ALPINE_SUMMIT,
    ROCKY_RIDGE,
    GLACIAL_PASS,
    VOLCANIC_PEAK,
    DESERT_MESA
}

data class MountainParams(
    val seed: Long = System.currentTimeMillis(),
    val mountainCount: Int = 10,
    val controlPts: Int = 150, // More points for sharp, jagged edges
    val tension: Double = 1.2,
    val baseAmplitude: Double = 120.0,
    val perspective: Double = 1.8,
    val palette: MountainPalette = MountainPalette.TIBETAN_PLATEAU,
    val fogDensity: Double = 0.3,
    val snowLine: Double = 0.25, // Normalized height within the mountain slice where snow starts
    val textureAmount: Double = 0.5,
    val sharpness: Double = 2.0, // Controls how "peaky" the ridges are
    val showDebug: Boolean = false,
    val showSun: Boolean = true,
    val sunPosition: Vector2 = Vector2(0.8, 0.15)
)

fun getMountainColors(palette: MountainPalette): Triple<ColorRGBa, ColorRGBa, List<ColorRGBa>> {
    val skyColor: ColorRGBa
    val fogColor: ColorRGBa
    val mountainColors: List<ColorRGBa>
    
    when (palette) {
        MountainPalette.TIBETAN_PLATEAU -> {
            skyColor = rgb(0.1, 0.3, 0.6) // Deep high-altitude blue
            fogColor = rgb(0.8, 0.85, 0.9)
            mountainColors = listOf(
                rgb(0.3, 0.25, 0.2), // Dark rock
                rgb(0.4, 0.35, 0.3),
                rgb(0.5, 0.45, 0.4),
                rgb(0.35, 0.3, 0.25)
            )
        }
        MountainPalette.ALPINE_SUMMIT -> {
            skyColor = rgb(0.5, 0.7, 0.9)
            fogColor = rgb(0.9, 0.95, 1.0)
            mountainColors = listOf(
                rgb(0.1, 0.2, 0.1), // Dark forest green
                rgb(0.2, 0.3, 0.2),
                rgb(0.3, 0.3, 0.3), // Grey rock
                rgb(0.4, 0.4, 0.4)
            )
        }
        MountainPalette.ROCKY_RIDGE -> {
            skyColor = rgb(0.6, 0.75, 0.85)
            fogColor = rgb(0.85, 0.8, 0.75)
            mountainColors = listOf(
                rgb(0.3, 0.2, 0.15), // Reddish brown rock
                rgb(0.4, 0.3, 0.25),
                rgb(0.5, 0.4, 0.35),
                rgb(0.35, 0.25, 0.2)
            )
        }
        MountainPalette.GLACIAL_PASS -> {
            skyColor = rgb(0.7, 0.8, 0.9)
            fogColor = rgb(0.95, 1.0, 1.0)
            mountainColors = listOf(
                rgb(0.1, 0.2, 0.3), // Deep ice blue/grey
                rgb(0.2, 0.3, 0.4),
                rgb(0.3, 0.4, 0.5),
                rgb(0.4, 0.5, 0.6)
            )
        }
        MountainPalette.VOLCANIC_PEAK -> {
            skyColor = rgb(0.2, 0.1, 0.15)
            fogColor = rgb(0.4, 0.3, 0.3)
            mountainColors = listOf(
                rgb(0.05, 0.05, 0.05), // Obsidian/Lava rock
                rgb(0.15, 0.1, 0.1),
                rgb(0.2, 0.15, 0.15),
                rgb(0.1, 0.05, 0.05)
            )
        }
        MountainPalette.DESERT_MESA -> {
            skyColor = rgb(0.9, 0.6, 0.2)
            fogColor = rgb(1.0, 0.8, 0.6)
            mountainColors = listOf(
                rgb(0.5, 0.2, 0.1), // Sandstone
                rgb(0.6, 0.3, 0.15),
                rgb(0.7, 0.4, 0.2),
                rgb(0.55, 0.25, 0.1)
            )
        }
    }
    return Triple(skyColor, fogColor, mountainColors)
}

fun mountainNoise(seed: Long, mountainIndex: Int, x: Double, octaves: Int = 6, sharpness: Double = 2.0): Double {
    var result = 0.0
    var amp = 1.0
    var freq = 2.0
    val seedInt = seed.toInt()
    for (i in 0 until octaves) {
        // Ridged noise: 1.0 - abs(simplex)
        val n = simplex(seedInt + i * 137, x * freq, mountainIndex * 2.718)
        val ridge = 1.0 - abs(n)
        result += ridge.pow(sharpness) * amp
        amp *= 0.5
        freq *= 2.0
    }
    return result
}

fun generateMountainBoundaries(params: MountainParams, width: Double, height: Double): List<ShapeContour> {
    val boundaries = mutableListOf<ShapeContour>()
    
    for (i in 0..params.mountainCount) {
        val points = mutableListOf<Vector2>()
        
        // Normalized depth [0, 1]
        val tDepth = i.toDouble() / params.mountainCount
        
        // Apply perspective
        val yBaseNorm = tDepth.pow(params.perspective)
        val horizon = 0.25 * height // Horizon a bit higher for mountains
        val yBase = horizon + yBaseNorm * (height - horizon)
        
        // Amplitude is much larger for high mountains
        // Far mountains can have high amplitude too to show peaks above horizon
        val currentAmplitude = params.baseAmplitude * (0.5 + 0.5 * yBaseNorm)
        
        for (j in 0 until params.controlPts) {
            val tX = j.toDouble() / (params.controlPts - 1)
            // Wider spread for mountains
            val x = -100.0 + tX * (width + 200.0)
            
            val noise = mountainNoise(params.seed, i, tX, sharpness = params.sharpness)
            val y = yBase - noise * currentAmplitude
            
            points.add(Vector2(x, y))
        }
        
        val contour = contour {
            moveTo(points.first())
            for (pIdx in 1 until points.size) {
                lineTo(points[pIdx])
            }
        }
        boundaries.add(contour)
    }
    
    return boundaries
}

fun buildMountainSlice(topBoundary: ShapeContour, bottomBoundary: ShapeContour): ShapeContour {
    val samples = 150 // More samples for rugged mountains
    return contour {
        moveTo(topBoundary.position(0.0))
        for (i in 1..samples) {
            lineTo(topBoundary.position(i.toDouble() / samples))
        }
        
        lineTo(bottomBoundary.position(1.0))
        for (i in samples - 1 downTo 0) {
            lineTo(bottomBoundary.position(i.toDouble() / samples))
        }
        close()
    }
}

fun createMountainShadeStyle(
    mountainIndex: Int,
    totalMountains: Int,
    params: MountainParams,
    mountainColor: ColorRGBa,
    fogColor: ColorRGBa
): ShadeStyle {
    val tDepth = mountainIndex.toDouble() / totalMountains
    val depthBlend = (1.0 - tDepth).pow(1.5) * params.fogDensity
    
    val baseColor = mix(mountainColor, fogColor, depthBlend)
    val snowColor = mix(ColorRGBa.WHITE, fogColor, depthBlend * 0.5)
    
    return shadeStyle {
        fragmentPreamble = """
            float hash(vec2 p) {
                return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123);
            }

            float noise(vec2 p) {
                vec2 i = floor(p);
                vec2 f = fract(p);
                vec2 u = f * f * (3.0 - 2.0 * f);
                return mix(mix(hash(i + vec2(0.0, 0.0)), hash(i + vec2(1.0, 0.0)), u.x),
                           mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x), u.y);
            }

            float fbm(vec2 p) {
                float v = 0.0;
                float a = 0.5;
                for (int i = 0; i < 4; i++) {
                    v += a * noise(p);
                    p *= 2.0;
                    a *= 0.5;
                }
                return v;
            }
        """
        fragmentTransform = """
            vec2 pos = v_objectPosition.xy * 0.005;
            
            vec2 screenPos = c_boundsPosition.xy;
            float t = screenPos.y; 
            
            // Irregular snow line
            float snowNoise = noise(pos * 8.0 + vec2(p_seed * 0.01)) * 0.12;
            float snowVal = smoothstep(p_snowLine + 0.1 + snowNoise, p_snowLine - 0.1 + snowNoise, t);
            vec3 mColor = mix(p_baseColor.rgb, p_snowColor.rgb, snowVal);
            
            // Rocky texture
            float rock = fbm(pos * 25.0 + vec2(p_seed * 0.02)) * 0.7 + noise(pos * 60.0) * 0.3;
            mColor *= (1.0 - p_textureAmount * 0.5) + rock * p_textureAmount;
            
            // Fog fade at the bottom of the slice
            float fogT = pow(t, 2.0);
            x_fill.rgb = mix(mColor, p_fogColor.rgb, fogT);
            x_fill.a = p_baseColor.a;
        """
        parameter("baseColor", baseColor)
        parameter("snowColor", snowColor)
        parameter("fogColor", fogColor)
        parameter("snowLine", params.snowLine)
        parameter("textureAmount", params.textureAmount)
        parameter("seed", params.seed.toDouble() % 10000.0)
    }
}

fun renderMountains(drawer: Drawer, params: MountainParams, width: Double, height: Double) {
    val (skyColor, fogColor, mountainColors) = getMountainColors(params.palette)
    
    drawer.clear(skyColor)
    
    // Draw Sun/Moon
    if (params.showSun) {
        drawer.isolated {
            val sunX = params.sunPosition.x * width
            val sunY = params.sunPosition.y * height
            
            // Sun glow
            for (i in 0..25) {
                drawer.fill = ColorRGBa.WHITE.copy(alpha = 0.015)
                drawer.stroke = null
                drawer.circle(sunX, sunY, 30.0 + i * 8.0)
            }
            
            drawer.fill = ColorRGBa.WHITE
            drawer.circle(sunX, sunY, 30.0)
        }
    }
    
    val boundaries = generateMountainBoundaries(params, width, height)
    
    // Virtual bottom boundary
    val bottomPoints = listOf(
        Vector2(-100.0, height + 200.0),
        Vector2(width + 100.0, height + 200.0)
    )
    val bottomBoundary = hobbyCurve(bottomPoints, closed = false).contour
    
    val allBoundaries = boundaries + bottomBoundary
    
    for (i in 0 until allBoundaries.size - 1) {
        val mountainShape = buildMountainSlice(allBoundaries[i], allBoundaries[i+1])
        
        val mountainColor = mountainColors[i % mountainColors.size]
        val style = createMountainShadeStyle(i, allBoundaries.size - 1, params, mountainColor, fogColor)
        
        drawer.isolated {
            drawer.shadeStyle = style
            drawer.stroke = null
            drawer.fill = ColorRGBa.WHITE
            drawer.contour(mountainShape)
        }
    }
}

fun exportHighMountains(drawer: Drawer, params: MountainParams) {
    val rt = renderTarget(1000, 1000) {
        colorBuffer()
        depthBuffer(DepthFormat.DEPTH24_STENCIL8)
    }
    
    drawer.isolatedWithTarget(rt) {
        ortho(rt)
        renderMountains(this, params, rt.width.toDouble(), rt.height.toDouble())
    }
    
    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val filename = "high_mountains_${params.seed}_${params.palette.name.lowercase()}_$timestamp.png"
    
    File("images").mkdirs()
    rt.colorBuffer(0).saveToFile(File("images/$filename"))
    rt.destroy()
    
    println("Exported: images/$filename")
}

fun main() = application {
    configure {
        width = 1000
        height = 1000
        title = "High Mountain Landscapes"
    }
    
    program {
        var params = MountainParams()
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
                    val palettes = MountainPalette.entries
                    val nextIdx = (palettes.indexOf(params.palette) + 1) % palettes.size
                    params = params.copy(palette = palettes[nextIdx])
                    showStatus("Palette: ${params.palette}")
                }
                "m" -> {
                    params = params.copy(mountainCount = (params.mountainCount + 1).coerceIn(3, 25))
                    showStatus("Mountains: ${params.mountainCount}")
                }
                "n" -> {
                    params = params.copy(mountainCount = (params.mountainCount - 1).coerceIn(3, 25))
                    showStatus("Mountains: ${params.mountainCount}")
                }
                "f" -> {
                    params = params.copy(fogDensity = (params.fogDensity + 0.05).coerceIn(0.0, 1.0))
                    showStatus("Fog: ${String.format("%.2f", params.fogDensity)}")
                }
                "g" -> {
                    params = params.copy(fogDensity = (params.fogDensity - 0.05).coerceIn(0.0, 1.0))
                    showStatus("Fog: ${String.format("%.2f", params.fogDensity)}")
                }
                "s" -> {
                    params = params.copy(snowLine = (params.snowLine + 0.05).coerceIn(0.0, 1.0))
                    showStatus("Snow Line: ${String.format("%.2f", params.snowLine)}")
                }
                "a" -> {
                    params = params.copy(snowLine = (params.snowLine - 0.05).coerceIn(0.0, 1.0))
                    showStatus("Snow Line: ${String.format("%.2f", params.snowLine)}")
                }
                "t" -> {
                    params = params.copy(textureAmount = (params.textureAmount + 0.05).coerceIn(0.0, 1.0))
                    showStatus("Texture: ${String.format("%.2f", params.textureAmount)}")
                }
                "y" -> {
                    params = params.copy(textureAmount = (params.textureAmount - 0.05).coerceIn(0.0, 1.0))
                    showStatus("Texture: ${String.format("%.2f", params.textureAmount)}")
                }
                "q" -> {
                    params = params.copy(sharpness = (params.sharpness + 0.1).coerceIn(1.0, 5.0))
                    showStatus("Sharpness: ${String.format("%.1f", params.sharpness)}")
                }
                "w" -> {
                    params = params.copy(sharpness = (params.sharpness - 0.1).coerceIn(1.0, 5.0))
                    showStatus("Sharpness: ${String.format("%.1f", params.sharpness)}")
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
                    exportHighMountains(drawer, params)
                    showStatus("Exported PNG")
                }
            }
        }
        
        extend {
            renderMountains(drawer, params, width.toDouble(), height.toDouble())
            
            // Display status message
            if (seconds - statusTime < 2.0 && statusMessage.isNotEmpty()) {
                drawer.isolated {
                    drawer.fill = ColorRGBa.BLACK.copy(alpha = 0.7)
                    drawer.text(statusMessage, 20.0, height - 20.0)
                }
            }

            if (params.showDebug) {
                drawer.isolated {
                    drawer.fill = ColorRGBa.WHITE
                    drawer.text("Seed: ${params.seed}", 20.0, 30.0)
                    drawer.text("Palette: ${params.palette}", 20.0, 50.0)
                    drawer.text("Mountains: ${params.mountainCount}", 20.0, 70.0)
                    drawer.text("Fog: ${String.format("%.2f", params.fogDensity)}", 20.0, 90.0)
                    drawer.text("Snow Line: ${String.format("%.2f", params.snowLine)}", 20.0, 110.0)
                    drawer.text("Texture: ${String.format("%.2f", params.textureAmount)}", 20.0, 130.0)
                    drawer.text("Sharpness: ${String.format("%.1f", params.sharpness)}", 20.0, 150.0)
                }
            }
        }
    }
}
