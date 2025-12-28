package drawing

import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.*
import org.openrndr.extra.noise.uniform
import org.openrndr.math.Vector2
import org.openrndr.shape.Rectangle
import org.openrndr.shape.contour
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.*
import kotlin.random.Random

/**
 * OP-ART MODULAR PLATE
 * A generative art sketch reproducing a modular line drawing in spirit and layout.
 */

data class OpArtParams(
    var seed: Long = Random.nextLong(),
    val S: Double = 540.0,
    val marginPct: Double = 0.06,
    var spacingD: Double = 5.0,
    var tilingDensity: Double = 0.6,
    var showDebug: Boolean = false
)

enum class MotifType {
    CONCENTRIC_DISK_SPIRAL,
    SQUARE_CORRIDOR_FRAME,
    CENTRAL_LABYRINTH,
    SQUARE_TARGET,
    ROUNDED_U_CHANNEL,
    NESTED_SQUARE_BLOCK,
    SQUARE_CORRIDOR_FIELD,
    STRIPE_FIELD_CAP,
    
    // Filler types
    MICRO_PIXEL_SQUARES,
    PIPE_ELBOW,
    SQUARE_SPIRAL,
    CIRCLE_FRAGMENT,
    PURE_STRIPES
}

class Tile(val rect: Rectangle, val type: MotifType, val rotation: Int = 0, val id: String = "")

fun main() = application {
    configure {
        width = 600
        height = 800
    }

    program {
        var params = OpArtParams()
        var tiles = mutableListOf<Tile>()
        var anchors = mutableListOf<Tile>()

        fun generate() {
            val random = Random(params.seed)
            val fieldRect = Rectangle(
                (width - params.S) / 2.0,
                (height - params.S) / 2.0,
                params.S,
                params.S
            )

            anchors.clear()
            tiles.clear()

            // Normalized anchor definitions
            val anchorSpecs = listOf(
                Triple("A", Rectangle(0.60, 0.03, 0.23, 0.25), MotifType.CONCENTRIC_DISK_SPIRAL),
                Triple("B", Rectangle(0.80, 0.00, 0.20, 0.55), MotifType.SQUARE_CORRIDOR_FRAME),
                Triple("C", Rectangle(0.36, 0.30, 0.32, 0.37), MotifType.CENTRAL_LABYRINTH),
                Triple("D", Rectangle(0.42, 0.73, 0.20, 0.24), MotifType.SQUARE_TARGET),
                Triple("E", Rectangle(0.60, 0.72, 0.23, 0.25), MotifType.ROUNDED_U_CHANNEL),
                Triple("F", Rectangle(0.06, 0.30, 0.19, 0.18), MotifType.NESTED_SQUARE_BLOCK),
                Triple("G", Rectangle(0.00, 0.62, 0.22, 0.33), MotifType.SQUARE_CORRIDOR_FIELD),
                Triple("H", Rectangle(0.78, 0.58, 0.22, 0.17), MotifType.STRIPE_FIELD_CAP)
            )

            val anchorRects = anchorSpecs.map { (id, normRect, type) ->
                val rect = Rectangle(
                    fieldRect.x + normRect.x * fieldRect.width,
                    fieldRect.y + normRect.y * fieldRect.height,
                    normRect.width * fieldRect.width,
                    normRect.height * fieldRect.height
                )
                val tile = Tile(rect, type, id = id)
                anchors.add(tile)
                rect
            }

            // Tile remaining space
            var freeRects = listOf(fieldRect)
            for (ar in anchorRects) {
                val nextFree = mutableListOf<Rectangle>()
                for (fr in freeRects) {
                    nextFree.addAll(subtract(fr, ar))
                }
                freeRects = nextFree
            }

            for (fr in freeRects) {
                val subTiles = splitRecursively(fr, params.tilingDensity, random)
                for (st in subTiles) {
                    val type = when (random.nextDouble()) {
                        in 0.0..0.2 -> MotifType.MICRO_PIXEL_SQUARES
                        in 0.2..0.4 -> MotifType.PIPE_ELBOW
                        in 0.4..0.6 -> MotifType.SQUARE_SPIRAL
                        in 0.6..0.8 -> MotifType.CIRCLE_FRAGMENT
                        else -> MotifType.PURE_STRIPES
                    }
                    tiles.add(Tile(st, type, rotation = random.nextInt(4)))
                }
            }
        }

        fun renderAll(drawer: Drawer) {
            drawer.clear(ColorRGBa.BLACK)

            val fieldRect = Rectangle(
                (width - params.S) / 2.0,
                (height - params.S) / 2.0,
                params.S,
                params.S
            )
            drawer.fill = ColorRGBa.BLACK
            drawer.stroke = null
            drawer.rectangle(fieldRect)

            drawer.stroke = ColorRGBa.WHITE
            drawer.strokeWeight = 1.0
            
            for (tile in anchors + tiles) {
                renderTile(drawer, tile, params.spacingD)
            }

            drawPunctuation(drawer, anchors + tiles, params)
            drawMargins(drawer, fieldRect)
            drawSignatureAndDate(drawer, fieldRect)

            if (params.showDebug) {
                drawer.stroke = ColorRGBa.RED
                drawer.fill = null
                for (tile in anchors + tiles) {
                    drawer.rectangle(tile.rect)
                    if (tile.id.isNotEmpty()) {
                        drawer.fill = ColorRGBa.RED
                        drawer.text(tile.id, tile.rect.x + 5, tile.rect.y + 15)
                        drawer.fill = null
                    }
                }
            }
        }

        generate()

        keyboard.keyDown.listen {
            when (it.name) {
                "r" -> { params.seed = Random.nextLong(); generate() }
                "d" -> params.showDebug = !params.showDebug
                "[" -> { params.spacingD = (params.spacingD - 0.5).coerceAtLeast(2.0); generate() }
                "]" -> { params.spacingD = (params.spacingD + 0.5).coerceAtMost(15.0); generate() }
                "-" -> { params.tilingDensity = (params.tilingDensity - 0.1).coerceAtLeast(0.1); generate() }
                "=" -> { params.tilingDensity = (params.tilingDensity + 0.1).coerceAtMost(0.9); generate() }
                "e" -> {
                    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                    val fileName = "op_art_plate_${params.seed}_$timestamp.png"
                    val imagesDir = File("images")
                    if (!imagesDir.exists()) imagesDir.mkdir()
                    
                    val rt = renderTarget(600, 800) {
                        colorBuffer()
                        depthBuffer()
                    }
                    drawer.isolatedWithTarget(rt) {
                        renderAll(drawer)
                    }
                    rt.colorBuffer(0).saveToFile(File("images/$fileName"))
                    rt.destroy()
                    println("Exported to images/$fileName")
                }
            }
        }

        extend {
            renderAll(drawer)
        }
    }
}

fun subtract(base: Rectangle, sub: Rectangle): List<Rectangle> {
    if (!base.intersects(sub)) return listOf(base)
    
    val x0 = max(base.x, sub.x)
    val y0 = max(base.y, sub.y)
    val x1 = min(base.x + base.width, sub.x + sub.width)
    val y1 = min(base.y + base.height, sub.y + sub.height)
    
    if (x1 <= x0 || y1 <= y0) return listOf(base)

    val res = mutableListOf<Rectangle>()
    // Top
    if (y0 > base.y) {
        res.add(Rectangle(base.x, base.y, base.width, y0 - base.y))
    }
    // Bottom
    if (y1 < base.y + base.height) {
        res.add(Rectangle(base.x, y1, base.width, (base.y + base.height) - y1))
    }
    // Left
    if (x0 > base.x) {
        res.add(Rectangle(base.x, y0, x0 - base.x, y1 - y0))
    }
    // Right
    if (x1 < base.x + base.width) {
        res.add(Rectangle(x1, y0, (base.x + base.width) - x1, y1 - y0))
    }
    return res
}

fun splitRecursively(rect: Rectangle, density: Double, random: Random, minSize: Double = 30.0): List<Rectangle> {
    if (rect.width < minSize * 1.5 && rect.height < minSize * 1.5) return listOf(rect)
    
    val stopChance = (1.0 - density).pow(2.0)
    if (rect.width < 100 && rect.height < 100 && random.nextDouble() < stopChance) return listOf(rect)

    val splitHorizontal = if (rect.width > rect.height * 1.8) false
    else if (rect.height > rect.width * 1.8) true
    else random.nextBoolean()

    if (splitHorizontal) {
        if (rect.height < minSize * 2) return listOf(rect)
        val splitY = rect.y + rect.height * (0.3 + random.nextDouble() * 0.4)
        return splitRecursively(Rectangle(rect.x, rect.y, rect.width, splitY - rect.y), density, random, minSize) +
               splitRecursively(Rectangle(rect.x, splitY, rect.width, rect.y + rect.height - splitY), density, random, minSize)
    } else {
        if (rect.width < minSize * 2) return listOf(rect)
        val splitX = rect.x + rect.width * (0.3 + random.nextDouble() * 0.4)
        return splitRecursively(Rectangle(rect.x, rect.y, splitX - rect.x, rect.height), density, random, minSize) +
               splitRecursively(Rectangle(splitX, rect.y, rect.x + rect.width - splitX, rect.height), density, random, minSize)
    }
}

fun renderTile(drawer: Drawer, tile: Tile, d: Double) {
    drawer.isolated {
        // We handle rotation by translating to center, rotating, then drawing
        // But some motifs are better drawn directly in the rect
        when (tile.type) {
            MotifType.CONCENTRIC_DISK_SPIRAL -> drawConcentricDiskSpiral(drawer, tile.rect, d)
            MotifType.SQUARE_CORRIDOR_FRAME -> drawSquareCorridorFrame(drawer, tile.rect, d)
            MotifType.CENTRAL_LABYRINTH -> drawCentralLabyrinth(drawer, tile.rect, d)
            MotifType.SQUARE_TARGET -> drawSquareTarget(drawer, tile.rect, d)
            MotifType.ROUNDED_U_CHANNEL -> drawRoundedUChannel(drawer, tile.rect, d)
            MotifType.NESTED_SQUARE_BLOCK -> drawNestedSquareBlock(drawer, tile.rect, d)
            MotifType.SQUARE_CORRIDOR_FIELD -> drawSquareCorridorField(drawer, tile.rect, d)
            MotifType.STRIPE_FIELD_CAP -> drawStripeFieldCap(drawer, tile.rect, d)
            
            MotifType.MICRO_PIXEL_SQUARES -> drawMicroPixelSquares(drawer, tile.rect, d)
            MotifType.PIPE_ELBOW -> drawPipeElbow(drawer, tile.rect, d, tile.rotation)
            MotifType.SQUARE_SPIRAL -> drawSquareSpiral(drawer, tile.rect, d, tile.rotation)
            MotifType.CIRCLE_FRAGMENT -> drawCircleFragment(drawer, tile.rect, d, tile.rotation)
            MotifType.PURE_STRIPES -> drawPureStripes(drawer, tile.rect, d, tile.rotation)
        }
    }
}

// --- MOTIF DRAW FUNCTIONS ---

fun drawConcentricDiskSpiral(drawer: Drawer, rect: Rectangle, d: Double) {
    val center = rect.center
    val radius = min(rect.width, rect.height) / 2.0
    drawer.fill = null
    drawer.stroke = ColorRGBa.WHITE
    
    var r = d
    while (r < radius) {
        drawer.circle(center, r)
        r += d
    }
    // Add a small spiral hook near center
    drawer.strokeWeight = 1.5
    val hook = contour {
        moveTo(center + Vector2(d, 0.0))
        arcTo(d, d, 0.0, false, true, center + Vector2(-d, 0.0))
        arcTo(d * 0.5, d * 0.5, 0.0, false, true, center)
    }
    drawer.contour(hook)
}

fun drawSquareCorridorFrame(drawer: Drawer, rect: Rectangle, d: Double) {
    drawer.fill = null
    drawer.stroke = ColorRGBa.WHITE
    var i = 0.0
    while (i < min(rect.width, rect.height) / 2.0) {
        drawer.rectangle(rect.x + i, rect.y + i, rect.width - 2 * i, rect.height - 2 * i)
        i += d
    }
}

fun drawCentralLabyrinth(drawer: Drawer, rect: Rectangle, d: Double) {
    // A more complex nested square labyrinth
    drawer.fill = null
    var i = 0.0
    while (i < min(rect.width, rect.height) / 2.0) {
        if ((i / d).toInt() % 3 != 1) {
             drawer.rectangle(rect.x + i, rect.y + i, rect.width - 2 * i, rect.height - 2 * i)
        } else {
            // Break it into a U-shape
            val r = Rectangle(rect.x + i, rect.y + i, rect.width - 2 * i, rect.height - 2 * i)
            val c = contour {
                moveTo(Vector2(r.x, r.y))
                lineTo(Vector2(r.x + r.width, r.y))
                lineTo(Vector2(r.x + r.width, r.y + r.height))
                lineTo(Vector2(r.x, r.y + r.height))
            }
            drawer.contour(c)
        }
        i += d
    }
}

fun drawSquareTarget(drawer: Drawer, rect: Rectangle, d: Double) {
    val center = rect.center
    val size = min(rect.width, rect.height)
    var i = 0.0
    while (i < size / 2.0 - d) {
        drawer.fill = null
        drawer.stroke = ColorRGBa.WHITE
        drawer.rectangle(rect.x + i, rect.y + i, rect.width - 2 * i, rect.height - 2 * i)
        i += d
    }
    // Black core
    drawer.fill = ColorRGBa.BLACK
    drawer.stroke = ColorRGBa.WHITE
    drawer.rectangle(rect.x + i, rect.y + i, rect.width - 2 * i, rect.height - 2 * i)
}

fun drawRoundedUChannel(drawer: Drawer, rect: Rectangle, d: Double) {
    drawer.fill = null
    val r = min(rect.width, rect.height) / 2.0
    var currR = r
    while (currR > 0) {
        val c = contour {
            moveTo(Vector2(rect.x, rect.y + r - currR))
            lineTo(Vector2(rect.x + rect.width - currR, rect.y + r - currR))
            arcTo(currR, currR, 90.0, false, true, Vector2(rect.x + rect.width, rect.y + r))
            lineTo(Vector2(rect.x + rect.width, rect.y + rect.height - r))
            arcTo(currR, currR, 90.0, false, true, Vector2(rect.x + rect.width - currR, rect.y + rect.height - r + currR))
            lineTo(Vector2(rect.x, rect.y + rect.height - r + currR))
        }
        drawer.contour(c)
        currR -= d
    }
}

fun drawNestedSquareBlock(drawer: Drawer, rect: Rectangle, d: Double) {
    drawSquareCorridorFrame(drawer, rect, d)
    // Strong central square
    val mid = min(rect.width, rect.height) * 0.3
    drawer.fill = ColorRGBa.BLACK
    drawer.stroke = ColorRGBa.WHITE
    drawer.rectangle(rect.center.x - mid/2, rect.center.y - mid/2, mid, mid)
}

fun drawSquareCorridorField(drawer: Drawer, rect: Rectangle, d: Double) {
    // Open labyrinth
    var i = 0.0
    while (i < min(rect.width, rect.height) / 2.0) {
        drawer.fill = null
        val r = Rectangle(rect.x + i, rect.y + i, rect.width - 2 * i, rect.height - 2 * i)
        if ((i/d).toInt() % 2 == 0) {
             drawer.rectangle(r)
        }
        i += d
    }
}

fun drawStripeFieldCap(drawer: Drawer, rect: Rectangle, d: Double) {
    // Horizontal stripes
    var y = rect.y + d/2
    while (y < rect.y + rect.height) {
        drawer.lineSegment(rect.x, y, rect.x + rect.width, y)
        y += d
    }
    // Black semicircle cap at bottom right
    val capR = min(rect.width, rect.height) * 0.4
    drawer.fill = ColorRGBa.BLACK
    drawer.stroke = ColorRGBa.WHITE
    val cap = contour {
        moveTo(Vector2(rect.x + rect.width, rect.y + rect.height - capR))
        arcTo(capR, capR, 0.0, false, false, Vector2(rect.x + rect.width - capR, rect.y + rect.height))
        lineTo(Vector2(rect.x + rect.width, rect.y + rect.height))
        close()
    }
    drawer.contour(cap)
}

fun drawMicroPixelSquares(drawer: Drawer, rect: Rectangle, d: Double) {
    val cols = (rect.width / (3 * d)).toInt().coerceAtLeast(1)
    val rows = (rect.height / (3 * d)).toInt().coerceAtLeast(1)
    val cw = rect.width / cols
    val ch = rect.height / rows
    for (i in 0 until cols) {
        for (j in 0 until rows) {
            val sx = rect.x + i * cw + cw * 0.2
            val sy = rect.y + j * ch + ch * 0.2
            val sw = cw * 0.6
            val sh = ch * 0.6
            drawer.stroke = ColorRGBa.WHITE
            drawer.fill = if ((i+j) % 3 == 0) ColorRGBa.BLACK else null
            drawer.rectangle(sx, sy, sw, sh)
            if (sw > d * 2) {
                drawer.rectangle(sx + d, sy + d, sw - 2 * d, sh - 2 * d)
            }
        }
    }
}

fun drawPipeElbow(drawer: Drawer, rect: Rectangle, d: Double, rotation: Int) {
    drawer.translate(rect.center)
    drawer.rotate(rotation * 90.0)
    val hw = rect.width / 2.0
    val hh = rect.height / 2.0
    val r = min(rect.width, rect.height)
    var currR = r
    while (currR > 0) {
        drawer.stroke = ColorRGBa.WHITE
        drawer.fill = null
        val c = contour {
            moveTo(Vector2(-hw + currR, -hh))
            arcTo(currR, currR, 0.0, false, true, Vector2(-hw, -hh + currR))
        }
        drawer.contour(c)
        currR -= d
    }
}

fun drawSquareSpiral(drawer: Drawer, rect: Rectangle, d: Double, rotation: Int) {
    drawer.translate(rect.center)
    drawer.rotate(rotation * 90.0)
    val hw = rect.width / 2.0
    val hh = rect.height / 2.0
    var i = 0.0
    while (i < min(rect.width, rect.height) / 2.0) {
        drawer.rectangle(-hw + i, -hh + i, rect.width - 2 * i, rect.height - 2 * i)
        i += 2 * d // Every other for "spiral" feel
    }
}

fun drawCircleFragment(drawer: Drawer, rect: Rectangle, d: Double, rotation: Int) {
    drawer.translate(rect.center)
    drawer.rotate(rotation * 90.0)
    val hw = rect.width / 2.0
    val hh = rect.height / 2.0
    val r = min(rect.width, rect.height)
    var currR = d
    while (currR <= r) {
        val c = contour {
            moveTo(Vector2(-hw + currR, -hh))
            arcTo(currR, currR, 0.0, false, true, Vector2(-hw, -hh + currR))
        }
        drawer.contour(c)
        currR += d
    }
}

fun drawPureStripes(drawer: Drawer, rect: Rectangle, d: Double, rotation: Int) {
    drawer.translate(rect.center)
    drawer.rotate(rotation * 90.0)
    val hw = rect.width / 2.0
    val hh = rect.height / 2.0
    val size = max(rect.width, rect.height)
    var x = -size
    while (x < size) {
        drawer.lineSegment(x, -size, x, size)
        x += d
    }
}

fun drawPunctuation(drawer: Drawer, tiles: List<Tile>, params: OpArtParams) {
    val random = Random(params.seed + 42)
    for (tile in tiles) {
        if (random.nextDouble() < 0.15) {
            val pw = params.spacingD * (1.0 + random.nextInt(3))
            val px = tile.rect.x + random.nextDouble() * (tile.rect.width - pw).coerceAtLeast(0.0)
            val py = tile.rect.y + random.nextDouble() * (tile.rect.height - pw).coerceAtLeast(0.0)
            drawer.fill = ColorRGBa.BLACK
            drawer.stroke = null
            if (random.nextBoolean()) {
                drawer.rectangle(px, py, pw, pw)
            } else {
                drawer.circle(px + pw / 2.0, py + pw / 2.0, pw / 2.0)
            }
        }
    }
}

fun drawMargins(drawer: Drawer, fieldRect: Rectangle) {
    drawer.fill = ColorRGBa.BLACK
    drawer.stroke = null
    // Top
    drawer.rectangle(0.0, 0.0, drawer.width.toDouble(), fieldRect.y)
    // Bottom
    drawer.rectangle(0.0, fieldRect.y + fieldRect.height, drawer.width.toDouble(), drawer.height - (fieldRect.y + fieldRect.height))
    // Left
    drawer.rectangle(0.0, fieldRect.y, fieldRect.x, fieldRect.height)
    // Right
    drawer.rectangle(fieldRect.x + fieldRect.width, fieldRect.y, drawer.width - (fieldRect.x + fieldRect.width), fieldRect.height)
}

fun drawSignatureAndDate(drawer: Drawer, fieldRect: Rectangle) {
    drawer.fill = ColorRGBa.fromHex("CCCCCC")
    val date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
    drawer.text(date, fieldRect.x, fieldRect.y + fieldRect.height + 25.0)
}
