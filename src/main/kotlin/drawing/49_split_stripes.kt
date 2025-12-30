package drawing

import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.color.rgb
import org.openrndr.draw.*
import org.openrndr.extra.noise.Random
import org.openrndr.extra.parameters.*
import org.openrndr.math.map
import org.openrndr.shape.Rectangle
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.*

/**
 * Slit Stripes
 * A generative sketch with horizontal stripes, gradient fills, and random slit-swaps.
 */

enum class FillVariant { SOLID, DOT, HATCH, PATTERN, STIPPLE }
enum class PalettePreset { 
    MUTED_PAPER, NEON_DARK, OCEANIC,
    CYBERPUNK_NIGHT, DESERT_SUNSET, FOREST_MIST, VINTAGE_POSTER, ARCTIC_ICE,
    LAVA_FLOW, BERRY_GARDEN, GOLDEN_HOUR, MIDNIGHT_GARDEN, COFFEE_SHOP,
    RETRO_FUTURE, SPRING_BLOOM, STORM_CLOUDS, MARS_ROVER, TOKYO_DRIFT,
    CHALKBOARD, AUTUMN_LEAVES, CANDY_SHOP, SPACE_NEBULA, MONO_NO_AWARE,
    MONOCHROME
}

enum class FillVariantMode { SOLID, DOTS, HATCH, PATTERN, STIPPLE, RANDOM, GROUPED }
enum class GradientDirection { HORIZONTAL, VERTICAL }

class SplitStripesParams {
    @IntParameter("Seed", 0, 1000000)
    var seed: Int = 123456

    @IntParameter("Stripe Count", 18, 60)
    var stripeCount: Int = 32

    @DoubleParameter("Stripe Jitter", 0.0, 0.2)
    var stripeJitter: Double = 0.0

    @DoubleParameter("Min Split Offset", 20.0, 200.0)
    var minSplit: Double = 60.0

    @DoubleParameter("Split Bias", 0.0, 1.0)
    var splitBias: Double = 0.5

    @DoubleParameter("Swap Prob", 0.0, 1.0)
    var swapProbability: Double = 1.0

    @BooleanParameter("Debug Overlay")
    var debugOverlay: Boolean = false

    var palettePreset: PalettePreset = PalettePreset.MUTED_PAPER
    var fillVariantMode: FillVariantMode = FillVariantMode.RANDOM
    var gradientDirection: GradientDirection = GradientDirection.HORIZONTAL
}

class StripeData(
    val y0: Double,
    val height: Double,
    val fillVariant: FillVariant,
    val splitX: Double,
    val swap: Boolean,
    val colorA: ColorRGBa,
    val colorB: ColorRGBa
)

fun main() = application {
    configure {
        width = 600
        height = 800
    }
    program {
        val params = SplitStripesParams()
        
        // Palette definitions
        fun getPalette(preset: PalettePreset): Triple<ColorRGBa, List<ColorRGBa>, ColorRGBa?> {
            return when (preset) {
                PalettePreset.MUTED_PAPER -> Triple(
                    rgb("#F5F2E7"), // Background
                    listOf(rgb("#D8D1C5"), rgb("#8E806A"), rgb("#C84B31")), // Ramp colors
                    null // No vignette
                )
                PalettePreset.NEON_DARK -> Triple(
                    rgb("#0F0F0F"),
                    listOf(rgb("#00FFC6"), rgb("#FF00FF"), rgb("#FFFF00"), rgb("#007DFF")),
                    rgb("#000000") // Vignette
                )
                PalettePreset.OCEANIC -> Triple(
                    rgb("#003B46"),
                    listOf(rgb("#07575B"), rgb("#66A5AD"), rgb("#C4DFE6"), rgb("#FF6F61")),
                    null
                )
                PalettePreset.CYBERPUNK_NIGHT -> Triple(
                    rgb("#050505"),
                    listOf(rgb("#FF0055"), rgb("#00FF9F"), rgb("#7000FF"), rgb("#FFD300")),
                    rgb("#000000")
                )
                PalettePreset.DESERT_SUNSET -> Triple(
                    rgb("#2D142C"),
                    listOf(rgb("#510A32"), rgb("#801336"), rgb("#C72C41"), rgb("#EE4540")),
                    null
                )
                PalettePreset.FOREST_MIST -> Triple(
                    rgb("#1B262C"),
                    listOf(rgb("#0F4C75"), rgb("#3282B8"), rgb("#BBE1FA"), rgb("#1A5F7A")),
                    rgb("#000000")
                )
                PalettePreset.VINTAGE_POSTER -> Triple(
                    rgb("#EAE3C8"),
                    listOf(rgb("#D96098"), rgb("#C85250"), rgb("#415A77"), rgb("#778DA9")),
                    null
                )
                PalettePreset.ARCTIC_ICE -> Triple(
                    rgb("#F0F5F9"),
                    listOf(rgb("#C9D6DF"), rgb("#52616B"), rgb("#1E2022"), rgb("#00ADB5")),
                    null
                )
                PalettePreset.LAVA_FLOW -> Triple(
                    rgb("#000000"),
                    listOf(rgb("#3D0000"), rgb("#950101"), rgb("#FF0000"), rgb("#FFBD33")),
                    rgb("#000000")
                )
                PalettePreset.BERRY_GARDEN -> Triple(
                    rgb("#2B1B17"),
                    listOf(rgb("#5E1914"), rgb("#962D2D"), rgb("#C21E56"), rgb("#800020")),
                    null
                )
                PalettePreset.GOLDEN_HOUR -> Triple(
                    rgb("#3E2723"),
                    listOf(rgb("#D84315"), rgb("#FF8F00"), rgb("#FFCA28"), rgb("#FFF59D")),
                    null
                )
                PalettePreset.MIDNIGHT_GARDEN -> Triple(
                    rgb("#1A1A1D"),
                    listOf(rgb("#4E4E50"), rgb("#6F2232"), rgb("#950740"), rgb("#C3073F")),
                    rgb("#000000")
                )
                PalettePreset.COFFEE_SHOP -> Triple(
                    rgb("#3C2A21"),
                    listOf(rgb("#5F4B32"), rgb("#8D7B68"), rgb("#E4DCCF"), rgb("#F9F5EB")),
                    null
                )
                PalettePreset.RETRO_FUTURE -> Triple(
                    rgb("#222831"),
                    listOf(rgb("#393E46"), rgb("#00ADB5"), rgb("#EEEEEE"), rgb("#FF5722")),
                    rgb("#000000")
                )
                PalettePreset.SPRING_BLOOM -> Triple(
                    rgb("#F7F9F2"),
                    listOf(rgb("#E2F1AF"), rgb("#89B399"), rgb("#F9B7B0"), rgb("#E78292")),
                    null
                )
                PalettePreset.STORM_CLOUDS -> Triple(
                    rgb("#2C3333"),
                    listOf(rgb("#2E4F4F"), rgb("#0E8388"), rgb("#CBE4DE"), rgb("#7C9D96")),
                    rgb("#000000")
                )
                PalettePreset.MARS_ROVER -> Triple(
                    rgb("#4E342E"),
                    listOf(rgb("#A1887F"), rgb("#8D6E63"), rgb("#6D4C41"), rgb("#FF8A65")),
                    null
                )
                PalettePreset.TOKYO_DRIFT -> Triple(
                    rgb("#000000"),
                    listOf(rgb("#00D2FF"), rgb("#3A7BD5"), rgb("#BB00FF"), rgb("#FF00C1")),
                    rgb("#111111")
                )
                PalettePreset.CHALKBOARD -> Triple(
                    rgb("#1E2022"),
                    listOf(rgb("#67727E"), rgb("#A2B29F"), rgb("#F9F5EB"), rgb("#E8C4C4")),
                    null
                )
                PalettePreset.AUTUMN_LEAVES -> Triple(
                    rgb("#3D2B1F"),
                    listOf(rgb("#63412C"), rgb("#D4AC0D"), rgb("#A04000"), rgb("#5D4037")),
                    null
                )
                PalettePreset.CANDY_SHOP -> Triple(
                    rgb("#FFF5E4"),
                    listOf(rgb("#FFC4C4"), rgb("#EE6983"), rgb("#850E35"), rgb("#FFACAC")),
                    null
                )
                PalettePreset.SPACE_NEBULA -> Triple(
                    rgb("#0B0B19"),
                    listOf(rgb("#1B1B2F"), rgb("#162447"), rgb("#1F4068"), rgb("#E43F5A")),
                    rgb("#000000")
                )
                PalettePreset.MONO_NO_AWARE -> Triple(
                    rgb("#E8E8E8"),
                    listOf(rgb("#D1D1D1"), rgb("#B9C4C9"), rgb("#C9B9B9"), rgb("#B9C9B9")),
                    null
                )
                PalettePreset.MONOCHROME -> Triple(
                    ColorRGBa.WHITE,
                    listOf(ColorRGBa.BLACK, rgb("#333333"), rgb("#666666"), rgb("#999999"), rgb("#CCCCCC")),
                    ColorRGBa.BLACK
                )
            }
        }

        var stripes = listOf<StripeData>()
        val stripeRT = renderTarget(width, height) {
            colorBuffer()
            depthBuffer()
        }

        fun generate() {
            Random.seed = params.seed.toString()
            val newStripes = mutableListOf<StripeData>()
            val (_, colors, _) = getPalette(params.palettePreset)
            
            var currentY = 0.0
            val baseHeight = height.toDouble() / params.stripeCount
            
            for (i in 0 until params.stripeCount) {
                val h = baseHeight * (1.0 + Random.double(-params.stripeJitter, params.stripeJitter))
                val y1 = if (i == params.stripeCount - 1) height.toDouble() else (currentY + h).coerceAtMost(height.toDouble())
                val actualH = y1 - currentY
                
                val variant = when (params.fillVariantMode) {
                    FillVariantMode.SOLID -> FillVariant.SOLID
                    FillVariantMode.DOTS -> FillVariant.DOT
                    FillVariantMode.HATCH -> FillVariant.HATCH
                    FillVariantMode.PATTERN -> FillVariant.PATTERN
                    FillVariantMode.STIPPLE -> FillVariant.STIPPLE
                    FillVariantMode.RANDOM -> Random.pick(FillVariant.entries)
                    FillVariantMode.GROUPED -> {
                        val section = (i.toDouble() / params.stripeCount)
                        when {
                            section < 0.2 -> FillVariant.SOLID
                            section < 0.4 -> FillVariant.DOT
                            section < 0.6 -> FillVariant.HATCH
                            section < 0.8 -> FillVariant.PATTERN
                            else -> FillVariant.STIPPLE
                        }
                    }
                }
                
                val splitDist = when {
                    params.splitBias < 0.3 -> { // Biased toward center
                        val t = Random.double(0.0, 1.0)
                        val b = (t - 0.5).pow(3) * 4 + 0.5
                        params.minSplit + b * (width - 2 * params.minSplit)
                    }
                    params.splitBias > 0.7 -> { // Biased toward thirds
                        if (Random.bool()) {
                            Random.double(params.minSplit, width / 3.0)
                        } else {
                            Random.double(2.0 * width / 3.0, width - params.minSplit)
                        }
                    }
                    else -> Random.double(params.minSplit, width - params.minSplit)
                }

                val cA = Random.pick(colors)
                var cB = Random.pick(colors)
                while (cB == cA && colors.size > 1) cB = Random.pick(colors)

                newStripes.add(StripeData(
                    currentY, actualH, variant, splitDist,
                    Random.bool(params.swapProbability),
                    cA, cB
                ))
                currentY = y1
            }
            stripes = newStripes
        }

        fun renderSolidGradient(drawer: Drawer, rect: Rectangle, cA: ColorRGBa, cB: ColorRGBa) {
            drawer.shadeStyle = shadeStyle {
                fragmentTransform = """
                    float g = (p_dir == 0) ? c_boundsPosition.x : c_boundsPosition.y;
                    x_fill = mix(p_colorA, p_colorB, g);
                """.trimIndent()
                parameter("colorA", cA)
                parameter("colorB", cB)
                parameter("dir", if (params.gradientDirection == GradientDirection.HORIZONTAL) 0 else 1)
            }
            drawer.rectangle(rect)
            drawer.shadeStyle = null
        }

        fun renderDotGradient(drawer: Drawer, rect: Rectangle, cA: ColorRGBa, cB: ColorRGBa) {
            val rows = (rect.height / 6.0).toInt().coerceAtLeast(1)
            val cols = (rect.width / 6.0).toInt().coerceAtLeast(1)
            val dx = rect.width / cols
            val dy = rect.height / rows
            
            drawer.stroke = null
            for (iy in 0 until rows) {
                for (ix in 0 until cols) {
                    val px = rect.x + ix * dx + dx / 2.0
                    val py = rect.y + iy * dy + dy / 2.0
                    val g = if (params.gradientDirection == GradientDirection.HORIZONTAL) {
                        (px - rect.x) / rect.width
                    } else {
                        (py - rect.y) / rect.height
                    }
                    
                    val color = cA.mix(cB, g)
                    val size = map(0.0, 1.0, 1.0, dx * 0.8, g)
                    val alpha = map(0.0, 1.0, 0.2, 1.0, g)
                    
                    drawer.fill = color.opacify(alpha)
                    drawer.circle(px, py, size / 2.0)
                }
            }
        }

        fun renderHatchGradient(drawer: Drawer, rect: Rectangle, cA: ColorRGBa, cB: ColorRGBa) {
            val count = (rect.width / 4.0).toInt().coerceAtLeast(5)
            val step = rect.width / count
            
            for (i in 0..count) {
                val g = i.toDouble() / count
                val x = rect.x + i * step
                
                val thickness = map(0.0, 1.0, 0.5, 3.0, g)
                val alpha = map(0.0, 1.0, 0.3, 1.0, g)
                val color = cA.mix(cB, g)
                
                drawer.stroke = color.opacify(alpha)
                drawer.strokeWeight = thickness
                
                if (params.gradientDirection == GradientDirection.HORIZONTAL) {
                    drawer.lineSegment(x, rect.y, x, rect.y + rect.height)
                } else {
                    val y = rect.y + g * rect.height
                    drawer.lineSegment(rect.x, y, rect.x + rect.width, y)
                }
            }
        }

        fun renderPatternGradient(drawer: Drawer, rect: Rectangle, cA: ColorRGBa, cB: ColorRGBa) {
            val size = 10.0
            val cols = (rect.width / size).toInt().coerceAtLeast(1)
            val rows = (rect.height / size).toInt().coerceAtLeast(1)
            
            drawer.stroke = null
            for (iy in 0 until rows) {
                for (ix in 0 until cols) {
                    val px = rect.x + ix * size + size/2.0
                    val py = rect.y + iy * size + size/2.0
                    val g = if (params.gradientDirection == GradientDirection.HORIZONTAL) {
                        (px - rect.x) / rect.width
                    } else {
                        (py - rect.y) / rect.height
                    }
                    
                    val color = cA.mix(cB, g)
                    val scale = map(0.0, 1.0, 0.2, 0.9, g)
                    val rotation = g * PI * 2.0
                    
                    drawer.fill = color
                    drawer.pushTransforms()
                    drawer.translate(px, py)
                    drawer.rotate(Math.toDegrees(rotation))
                    drawer.rectangle(-size*scale/2.0, -size*scale/2.0, size*scale, size*scale)
                    drawer.popTransforms()
                }
            }
        }

        fun renderStippleGradient(drawer: Drawer, rect: Rectangle, cA: ColorRGBa, cB: ColorRGBa) {
            val area = rect.width * rect.height
            val dotCount = (area * 0.7).toInt().coerceIn(1000, 30000)
            drawer.stroke = null
            
            drawer.fill = cA
            drawer.rectangle(rect)

            for (i in 0 until dotCount) {
                val px = Random.double(rect.x, rect.x + rect.width)
                val py = Random.double(rect.y, rect.y + rect.height)
                
                val g = if (params.gradientDirection == GradientDirection.HORIZONTAL) {
                    (px - rect.x) / rect.width
                } else {
                    (py - rect.y) / rect.height
                }
                
                if (Random.double(0.0, 1.0) < g) {
                    drawer.fill = cB
                    drawer.circle(px, py, 0.4 + Random.double(0.0, 1.0) * 0.6)
                }
            }
        }

        fun renderAll(targetDrawer: Drawer) {
            val (bgColor, _, vignetteColor) = getPalette(params.palettePreset)

            // 1. Render stripes into offscreen RT
            targetDrawer.isolatedWithTarget(stripeRT) {
                clear(bgColor)
                for (s in stripes) {
                    val rect = Rectangle(0.0, s.y0, width.toDouble(), s.height)
                    when (s.fillVariant) {
                        FillVariant.SOLID -> renderSolidGradient(this, rect, s.colorA, s.colorB)
                        FillVariant.DOT -> renderDotGradient(this, rect, s.colorA, s.colorB)
                        FillVariant.HATCH -> renderHatchGradient(this, rect, s.colorA, s.colorB)
                        FillVariant.PATTERN -> renderPatternGradient(this, rect, s.colorA, s.colorB)
                        FillVariant.STIPPLE -> renderStippleGradient(this, rect, s.colorA, s.colorB)
                    }
                }
            }

            // 2. Draw with split-swap
            targetDrawer.clear(bgColor)
            for (s in stripes) {
                if (s.swap) {
                    val srcRight = Rectangle(s.splitX, s.y0, width - s.splitX, s.height)
                    val dstRight = Rectangle(0.0, s.y0, width - s.splitX, s.height)
                    targetDrawer.image(stripeRT.colorBuffer(0), srcRight, dstRight)

                    val srcLeft = Rectangle(0.0, s.y0, s.splitX, s.height)
                    val dstLeft = Rectangle(width - s.splitX, s.y0, s.splitX, s.height)
                    targetDrawer.image(stripeRT.colorBuffer(0), srcLeft, dstLeft)
                } else {
                    val rect = Rectangle(0.0, s.y0, width.toDouble(), s.height)
                    targetDrawer.image(stripeRT.colorBuffer(0), rect, rect)
                }
            }

            if (vignetteColor != null) {
                targetDrawer.shadeStyle = shadeStyle {
                    fragmentTransform = """
                        vec2 uv = c_boundsPosition.xy;
                        float d = distance(uv, vec2(0.5));
                        float v = smoothstep(0.4, 0.8, d);
                        x_fill = vec4(p_vColor.rgb, v * 0.5);
                    """.trimIndent()
                    parameter("vColor", vignetteColor)
                }
                targetDrawer.rectangle(0.0, 0.0, width.toDouble(), height.toDouble())
                targetDrawer.shadeStyle = null
            }
        }

        generate()

        keyboard.keyDown.listen {
            when (it.name) {
                "r" -> { params.seed = Random.int(0, 1000000); generate() }
                "v" -> {
                    params.fillVariantMode = FillVariantMode.entries[(params.fillVariantMode.ordinal + 1) % FillVariantMode.entries.size]
                    generate()
                }
                "p" -> {
                    params.palettePreset = PalettePreset.entries[(params.palettePreset.ordinal + 1) % PalettePreset.entries.size]
                    generate()
                }
                "[" -> { params.stripeCount = (params.stripeCount - 2).coerceAtLeast(18); generate() }
                "]" -> { params.stripeCount = (params.stripeCount + 2).coerceAtMost(60); generate() }
                "-" -> { params.splitBias = (params.splitBias - 0.1).coerceAtLeast(0.0); generate() }
                "=" -> { params.splitBias = (params.splitBias + 0.1).coerceAtMost(1.0); generate() }
                "g" -> {
                    params.gradientDirection = if (params.gradientDirection == GradientDirection.HORIZONTAL) GradientDirection.VERTICAL else GradientDirection.HORIZONTAL
                    generate()
                }
                "e" -> {
                    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                    val fileName = "images/split_stripes_${params.seed}_$timestamp.png"
                    File("images").mkdirs()
                    val exportRT = renderTarget(width, height) {
                        colorBuffer()
                    }
                    drawer.isolatedWithTarget(exportRT) {
                        renderAll(this)
                    }
                    exportRT.colorBuffer(0).saveToFile(File(fileName))
                    exportRT.destroy()
                    println("Exported to $fileName")
                }
                "d" -> { params.debugOverlay = !params.debugOverlay }
            }
        }

        extend {
            renderAll(drawer)

            if (params.debugOverlay) {
                drawer.fill = ColorRGBa.RED
                drawer.stroke = null
                for (s in stripes) {
                    drawer.circle(s.splitX, s.y0 + s.height/2.0, 3.0)
                }
                drawer.fill = ColorRGBa.WHITE
                drawer.text("Seed: ${params.seed}", 20.0, 30.0)
                drawer.text("Mode: ${params.fillVariantMode}", 20.0, 50.0)
                drawer.text("Palette: ${params.palettePreset}", 20.0, 70.0)
                drawer.text("Stripes: ${params.stripeCount}", 20.0, 90.0)
                drawer.text("Split Bias: ${String.format("%.1f", params.splitBias)}", 20.0, 110.0)
            }
        }
    }
}
