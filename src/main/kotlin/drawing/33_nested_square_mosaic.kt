package drawing

import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.*
import org.openrndr.extra.noise.uniform
import org.openrndr.math.Vector2
import org.openrndr.shape.Rectangle
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.*
import kotlin.random.Random

/**
 * Nested Square Mosaic
 * A dense mosaic of axis-aligned square tiles with concentric bands and saturated colors.
 */

data class MosaicParams(
    val seed: Long = Random.nextLong(),
    val width: Int = 800,
    val height: Int = 800,
    var seamMin: Double = 1.0,
    var seamMax: Double = 4.0,
    var microFillPasses: Int = 8,
    var density: Double = 1.0,
    var enforceAnchors: Boolean = true,
    var debug: Boolean = false
)

data class MosaicTile(
    val rect: Rectangle,
    val rings: Int,
    val colors: List<ColorRGBa>,
    val outlineColor: ColorRGBa
)

class NestedSquareMosaicGenerator(val params: MosaicParams) {
    private val random = Random(params.seed)
    private val occupied = BooleanArray(params.width * params.height)
    
    private val colorsCool = listOf(
        ColorRGBa.fromHex("00FFFF"), // Cyan
        ColorRGBa.fromHex("40E0D0"), // Turquoise
        ColorRGBa.fromHex("008080"), // Teal
        ColorRGBa.fromHex("7FFFD4"), // Aquamarine
        ColorRGBa.fromHex("87CEEB"), // Sky Blue
        ColorRGBa.fromHex("4169E1"), // Royal Blue
        ColorRGBa.fromHex("000080"), // Navy
        ColorRGBa.fromHex("008B8B")  // Dark Cyan / Blue-Green
    )
    
    private val colorsWarm = listOf(
        ColorRGBa.fromHex("FFF700"), // Lemon Yellow
        ColorRGBa.fromHex("FFD700"), // Gold
        ColorRGBa.fromHex("FFA500"), // Orange
        ColorRGBa.fromHex("FF8C00"), // Dark Orange / Tangerine
        ColorRGBa.fromHex("FFBF00"), // Amber
        ColorRGBa.fromHex("FF0000"), // Red
        ColorRGBa.fromHex("B22222")  // Firebrick
    )
    
    private val colorsViolet = listOf(
        ColorRGBa.fromHex("FF69B4"), // Hot Pink
        ColorRGBa.fromHex("FF00FF"), // Fuchsia / Magenta
        ColorRGBa.fromHex("800080"), // Purple
        ColorRGBa.fromHex("EE82EE"), // Violet
        ColorRGBa.fromHex("4B0082")  // Indigo
    )

    private val colorNearBlack = ColorRGBa.fromHex("0D0D0D")
    private val colorOliveDrab = ColorRGBa.fromHex("3B3C36")
    private val colorIndigoDeep = ColorRGBa.fromHex("0A0A23")

    fun generate(): List<MosaicTile> {
        val tiles = mutableListOf<MosaicTile>()
        occupied.fill(false)

        if (params.enforceAnchors) {
            tiles.addAll(placeAnchors())
        }

        // Tiered packing
        // Large
        packTier(tiles, 120..220, 50)
        // Medium
        packTier(tiles, 30..100, 400)
        // Small
        packTier(tiles, 15..25, 2000)
        // Micro
        packTier(tiles, 4..12, 10000)

        return tiles
    }

    private fun placeAnchors(): List<MosaicTile> {
        val anchors = mutableListOf<MosaicTile>()
        
        // Upper-left quadrant: warm red/magenta/orange
        tryPlaceAnchor(anchors, params.width / 4, params.height / 4, 180..220, "warm")
        
        // Upper-middle: cool cyan/teal
        tryPlaceAnchor(anchors, params.width / 2, params.height / 5, 160..200, "cool")
        
        // Center: aqua/blue-green
        tryPlaceAnchor(anchors, params.width / 2, params.height / 2, 140..180, "aqua")
        
        // Lower-right quadrant: yellow-heavy
        tryPlaceAnchor(anchors, 3 * params.width / 4, 3 * params.height / 4, 140..180, "yellow")
        
        // Lower-right quadrant: blue/teal with warm inner
        tryPlaceAnchor(anchors, 3 * params.width / 4 + 40, 3 * params.height / 4 + 100, 120..160, "cool-warm")

        return anchors
    }

    private fun tryPlaceAnchor(tiles: MutableList<MosaicTile>, targetX: Int, targetY: Int, sizeRange: IntRange, type: String) {
        val size = random.nextInt(sizeRange.first, sizeRange.last + 1)
        val x = (targetX - size / 2).coerceIn(0, params.width - size)
        val y = (targetY - size / 2).coerceIn(0, params.height - size)
        
        if (isAreaEmpty(x, y, size, params.seamMax.toInt())) {
            val tile = createTile(x, y, size, type)
            tiles.add(tile)
            markOccupied(x, y, size)
        }
    }

    private fun packTier(tiles: MutableList<MosaicTile>, sizeRange: IntRange, attempts: Int) {
        repeat(attempts) {
            val size = random.nextInt(sizeRange.first, sizeRange.last + 1)
            val x = random.nextInt(0, params.width - size)
            val y = random.nextInt(0, params.height - size)
            val seam = (params.seamMin + random.nextDouble() * (params.seamMax - params.seamMin)).toInt()
            
            if (isAreaEmpty(x, y, size, seam)) {
                tiles.add(createTile(x, y, size, "random"))
                markOccupied(x, y, size)
            }
        }
    }

    private fun isAreaEmpty(x: Int, y: Int, size: Int, seam: Int): Boolean {
        val xMin = (x - seam).coerceAtLeast(0)
        val xMax = (x + size + seam).coerceAtMost(params.width - 1)
        val yMin = (y - seam).coerceAtLeast(0)
        val yMax = (y + size + seam).coerceAtMost(params.height - 1)
        
        for (iy in yMin..yMax) {
            val rowOffset = iy * params.width
            for (ix in xMin..xMax) {
                if (occupied[rowOffset + ix]) return false
            }
        }
        return true
    }

    private fun markOccupied(x: Int, y: Int, size: Int) {
        for (iy in y until (y + size).coerceAtMost(params.height)) {
            val rowOffset = iy * params.width
            for (ix in x until (x + size).coerceAtMost(params.width)) {
                occupied[rowOffset + ix] = true
            }
        }
    }

    private fun createTile(x: Int, y: Int, size: Int, type: String): MosaicTile {
        val ringCount = when {
            size <= 12 -> 2 + random.nextInt(2)
            size <= 30 -> 3 + random.nextInt(3)
            else -> 4 + random.nextInt(6)
        }.coerceIn(3, 9)

        val colors = choosePaletteSequence(ringCount, type)
        val outlineColor = when(random.nextInt(3)) {
            0 -> colorNearBlack
            1 -> colorOliveDrab
            else -> colorIndigoDeep
        }

        return MosaicTile(Rectangle(x.toDouble(), y.toDouble(), size.toDouble(), size.toDouble()), ringCount, colors, outlineColor)
    }

    private fun choosePaletteSequence(ringCount: Int, type: String): List<ColorRGBa> {
        val sequence = mutableListOf<ColorRGBa>()
        
        fun jitter(c: ColorRGBa): ColorRGBa {
            val amt = 0.015
            return ColorRGBa(
                (c.r + random.nextDouble(-amt, amt)).coerceIn(0.0, 1.0),
                (c.g + random.nextDouble(-amt, amt)).coerceIn(0.0, 1.0),
                (c.b + random.nextDouble(-amt, amt)).coerceIn(0.0, 1.0),
                c.alpha
            )
        }

        when (type) {
            "warm" -> {
                sequence.add(jitter(colorsWarm.random(random))) // outer
                repeat(ringCount - 2) {
                    if (it % 2 == 0) sequence.add(jitter(colorsViolet.random(random)))
                    else sequence.add(colorNearBlack)
                }
                sequence.add(jitter(colorsWarm.random(random))) // center
            }
            "cool" -> {
                sequence.add(jitter(colorsCool.random(random)))
                repeat(ringCount - 2) {
                    if (it % 2 == 0) sequence.add(jitter(colorsCool.random(random)))
                    else sequence.add(jitter(colorsViolet.random(random)))
                }
                sequence.add(jitter(colorsViolet.random(random)))
            }
            "aqua" -> {
                sequence.add(jitter(ColorRGBa.fromHex("7FFFD4"))) // Aquamarine
                repeat(ringCount - 1) {
                    sequence.add(jitter(colorsCool.random(random)))
                }
            }
            "yellow" -> {
                sequence.add(jitter(colorsWarm.random(random)))
                repeat(ringCount - 2) {
                    sequence.add(jitter(colorsViolet.random(random)))
                }
                sequence.add(jitter(ColorRGBa.YELLOW))
            }
            "cool-warm" -> {
                sequence.add(jitter(colorsCool.random(random)))
                sequence.add(jitter(colorsWarm.random(random))) // warm inner
                repeat(ringCount - 2) {
                    sequence.add(jitter(colorsCool.random(random)))
                }
            }
            else -> {
                // Saturated complementary logic
                val baseSet = listOf(colorsCool, colorsWarm, colorsViolet).random(random)
                val compSet = when(baseSet) {
                    colorsCool -> colorsWarm
                    colorsWarm -> colorsCool
                    else -> listOf(colorsCool, colorsWarm).random(random)
                }
                
                sequence.add(jitter(baseSet.random(random)))
                for (i in 1 until ringCount) {
                    if (i % 2 == 1) {
                        sequence.add(if (random.nextDouble() < 0.7) jitter(compSet.random(random)) else colorNearBlack)
                    } else {
                        sequence.add(jitter(baseSet.random(random)))
                    }
                }
            }
        }
        
        return sequence.take(ringCount)
    }
}

fun main() = application {
    configure {
        width = 800
        height = 800
        title = "Nested Square Mosaic"
    }

    program {
        var params = MosaicParams(seed = Random.nextLong())
        var tiles = NestedSquareMosaicGenerator(params).generate()

        fun regenerate() {
            tiles = NestedSquareMosaicGenerator(params).generate()
        }

        keyboard.keyDown.listen {
            when (it.name) {
                "r" -> {
                    params = params.copy(seed = Random.nextLong())
                    regenerate()
                }
                "a" -> {
                    params = params.copy(enforceAnchors = !params.enforceAnchors)
                    regenerate()
                }
                "d" -> {
                    params = params.copy(debug = !params.debug)
                }
                "e" -> {
                    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                    val fileName = "nested_square_mosaic_${params.seed}_$timestamp.png"
                    val imagesDir = File("images")
                    if (!imagesDir.exists()) imagesDir.mkdir()
                    
                    val rt = renderTarget(params.width, params.height) {
                        colorBuffer()
                    }
                    drawer.isolatedWithTarget(rt) {
                        drawMosaic(drawer, params, tiles)
                    }
                    rt.colorBuffer(0).saveToFile(File("images/$fileName"))
                    println("Exported to images/$fileName")
                    rt.destroy()
                }
                "[" -> {
                    params = params.copy(microFillPasses = (params.microFillPasses - 1).coerceAtLeast(0))
                    regenerate()
                }
                "]" -> {
                    params = params.copy(microFillPasses = (params.microFillPasses + 1).coerceAtMost(20))
                    regenerate()
                }
                "-" -> {
                    params = params.copy(seamMax = (params.seamMax - 0.5).coerceAtLeast(params.seamMin))
                    regenerate()
                }
                "=" -> {
                    params = params.copy(seamMax = (params.seamMax + 0.5).coerceAtMost(10.0))
                    regenerate()
                }
            }
        }

        extend {
            drawMosaic(drawer, params, tiles)
        }
    }
}

fun drawMosaic(drawer: Drawer, params: MosaicParams, tiles: List<MosaicTile>) {
    // Background: muted olive/khaki field
    drawer.clear(ColorRGBa.fromHex("556B2F")) // DarkOliveGreen
    
    // Subtle variation across canvas
    val random = Random(params.seed)
    drawer.stroke = null
    for (i in 0 until 1000) {
        drawer.fill = ColorRGBa.fromHex("BDB76B").copy(alpha = 0.05) // DarkKhaki
        drawer.circle(random.nextDouble() * params.width, random.nextDouble() * params.height, random.nextDouble() * 100.0)
    }

    for (tile in tiles) {
        renderTile(drawer, tile, params)
    }

    if (params.debug) {
        for (tile in tiles) {
            drawer.fill = null
            drawer.stroke = when {
                tile.rect.width > 120 -> ColorRGBa.RED
                tile.rect.width > 30 -> ColorRGBa.GREEN
                else -> ColorRGBa.BLUE
            }
            drawer.strokeWeight = 1.0
            drawer.rectangle(tile.rect)
        }
    }
}

fun renderTile(drawer: Drawer, tile: MosaicTile, params: MosaicParams) {
    drawer.isolated {
        // Outer outline
        drawer.fill = null
        drawer.stroke = tile.outlineColor
        drawer.strokeWeight = 1.5
        drawer.rectangle(tile.rect)

        // Nested bands
        var currentRect = tile.rect.offsetEdges(-0.75) // Move inside outline
        val ringCount = tile.rings
        
        // Build band thicknesses
        val size = tile.rect.width
        val totalPadding = size * 0.45 // leave some space for center
        val baseThickness = totalPadding / ringCount
        
        for (i in 0 until ringCount) {
            drawer.fill = tile.colors.getOrElse(i) { ColorRGBa.BLACK }
            drawer.stroke = null
            drawer.rectangle(currentRect)
            
            val thickness = when {
                size <= 12 -> 1.0
                size <= 100 -> baseThickness * (0.8 + 0.4 * (i.toDouble() / ringCount))
                else -> baseThickness * (1.2 - 0.4 * (i.toDouble() / ringCount))
            }
            
            currentRect = currentRect.offsetEdges(-thickness)
            if (currentRect.width <= 0 || currentRect.height <= 0) break
        }
    }
}
