package drawing

import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.*
import org.openrndr.math.Vector2
import org.openrndr.math.map
import org.openrndr.shape.Rectangle
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.*
import kotlin.random.Random as KotlinRandom

/**
 * Falling Grid
 * A generative art sketch featuring a transition from order to disorder.
 */

enum class FallingGridShapeMode { SQUARES, CIRCLES }

enum class FallingGridPalette(val background: ColorRGBa, val stroke: ColorRGBa) {
    DEFAULT(ColorRGBa.fromHex("F5F4F4"), ColorRGBa.fromHex("5D5B5C")),
    BLUEPRINT(ColorRGBa.fromHex("002045"), ColorRGBa.fromHex("F5F4F4")),
    INVERTED(ColorRGBa.fromHex("1A1A1A"), ColorRGBa.fromHex("D3D3D3")),
    WARM_PAPER(ColorRGBa.fromHex("F5E6D3"), ColorRGBa.fromHex("36454F"))
}

enum class FallingGridZone { ORDERED, TRANSITIONAL, CHAOTIC }

data class FallingGridParams(
    val seed: Long = KotlinRandom.Default.nextLong(),
    val shapeMode: FallingGridShapeMode = FallingGridShapeMode.SQUARES,
    val palette: FallingGridPalette = FallingGridPalette.DEFAULT,
    val strokeWidth: Double = 1.5,
    val densityMul: Double = 1.0,
    val disorderMul: Double = 1.0,
    val debug: Boolean = false
)

data class FallingGridCell(val c: Int, val r: Int, val basePos: Vector2)

data class FallingGridItem(
    val pos: Vector2,
    val size: Vector2,
    val rot: Double,
    val zone: FallingGridZone
)

data class FallingGridDisorder(
    val jitter: Double,
    val rotation: Double,
    val spread: Double,
    val spawnRate: Double
)

fun buildFallingGrid(x0: Double, y0: Double, cols: Int = 12, colPitch: Double = 48.0, rowPitch: Double = 42.0, yMax: Double = 978.0): List<FallingGridCell> {
    val cells = mutableListOf<FallingGridCell>()
    val rows = ceil((yMax - y0) / rowPitch).toInt()
    for (r in 0 until rows) {
        for (c in 0 until cols) {
            cells.add(FallingGridCell(c, r, Vector2(x0 + c * colPitch, y0 + r * rowPitch)))
        }
    }
    return cells
}

fun disorderAtFallingY(y: Double, params: FallingGridParams): FallingGridDisorder {
    val yLoosenStart = 250.0
    val yBottom = 978.0
    
    val u = ((y - yLoosenStart) / (yBottom - yLoosenStart)).coerceIn(0.0, 1.0)
    
    // Smooth easing for disorder
    val easeU = u * u * (3.0 - 2.0 * u)
    
    return FallingGridDisorder(
        jitter = easeU * 80.0 * params.disorderMul,
        rotation = easeU * 45.0 * params.disorderMul,
        spread = easeU * 120.0 * params.disorderMul,
        spawnRate = if (y > 500.0) (u - 0.3) * 5.0 * params.densityMul else 0.0
    )
}

fun generateFallingItems(cells: List<FallingGridCell>, params: FallingGridParams): List<FallingGridItem> {
    val rng = KotlinRandom(params.seed)
    
    fun gaussian(mean: Double = 0.0, std: Double = 1.0): Double {
        val u1 = rng.nextDouble().coerceAtLeast(1e-9)
        val u2 = rng.nextDouble()
        return mean + std * sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
    }
    
    val items = mutableListOf<FallingGridItem>()
    val colPitch = 48.0
    val rowPitch = 42.0
    val baseSize = Vector2(colPitch, rowPitch)

    for (cell in cells) {
        val d = disorderAtFallingY(cell.basePos.y, params)
        
        val zone = when {
            cell.basePos.y < 250.0 -> FallingGridZone.ORDERED
            cell.basePos.y < 500.0 -> FallingGridZone.TRANSITIONAL
            else -> FallingGridZone.CHAOTIC
        }

        // Base item from grid
        val pos = cell.basePos + Vector2(
            gaussian(0.0, d.jitter * 0.4) + gaussian(0.0, d.spread * 0.15),
            gaussian(0.0, d.jitter * 0.3)
        )
        val rot = if (d.rotation > 0.0) rng.nextDouble(-d.rotation, d.rotation) else 0.0
        
        // Slight size variation in chaotic zone
        val sizeVar = if (zone == FallingGridZone.CHAOTIC) rng.nextDouble(0.85, 1.15) else 1.0
        
        items.add(FallingGridItem(pos, baseSize * sizeVar, rot, zone))

        // Extra spawns in chaotic zone
        if (zone == FallingGridZone.CHAOTIC) {
            val extraCount = floor(d.spawnRate + rng.nextDouble(0.0, 1.0)).toInt()
            for (i in 0 until extraCount) {
                val extraPos = cell.basePos + Vector2(
                    gaussian(0.0, d.spread * 0.6),
                    gaussian(0.0, d.jitter * 0.8)
                )
                val extraRot = if (d.rotation > 0.0) rng.nextDouble(-d.rotation, d.rotation) else 0.0
                val extraSizeVar = rng.nextDouble(0.75, 1.25)
                items.add(FallingGridItem(extraPos, baseSize * extraSizeVar, extraRot, zone))
            }
        }
    }
    
    return items
}

fun drawFallingSquare(drawer: Drawer, item: FallingGridItem) {
    drawer.isolated {
        drawer.translate(item.pos)
        drawer.rotate(item.rot)
        drawer.rectangle(Rectangle.fromCenter(Vector2.ZERO, item.size.x, item.size.y))
    }
}

fun drawFallingCircle(drawer: Drawer, item: FallingGridItem) {
    drawer.isolated {
        drawer.translate(item.pos)
        drawer.circle(Vector2.ZERO, item.size.x * 0.5)
    }
}

fun renderFallingGrid(drawer: Drawer, params: FallingGridParams, items: List<FallingGridItem>) {
    drawer.clear(params.palette.background)
    
    drawer.fill = null
    drawer.stroke = params.palette.stroke.copy(alpha = 0.7)
    drawer.strokeWeight = params.strokeWidth
    drawer.lineCap = LineCap.ROUND
    drawer.lineJoin = LineJoin.ROUND

    for (item in items) {
        when (params.shapeMode) {
            FallingGridShapeMode.SQUARES -> drawFallingSquare(drawer, item)
            FallingGridShapeMode.CIRCLES -> drawFallingCircle(drawer, item)
        }
    }

    if (params.debug) {
        drawer.isolated {
            drawer.stroke = ColorRGBa.RED
            drawer.strokeWeight = 1.0
            // Target ink bounds: x 63..706, y 37..978
            drawer.rectangle(63.0, 37.0, 706.0 - 63.0, 978.0 - 37.0)
            
            drawer.stroke = ColorRGBa.BLUE
            drawer.lineSegment(63.0, 250.0, 706.0, 250.0)
            drawer.lineSegment(63.0, 500.0, 706.0, 500.0)
            
            drawer.fill = ColorRGBa.RED
            drawer.text("Ordered (y < 250)", 70.0, 240.0)
            drawer.text("Transitional (250-500)", 70.0, 490.0)
            drawer.text("Chaotic (> 500)", 70.0, 530.0)
        }
    }
}

fun main() = application {
    configure {
        width = 756
        height = 1020
        title = "Falling Grid"
    }

    program {
        var params = FallingGridParams()
        // Edges: Left 101, Top 37. Centers: 101+24, 37+21
        val grid = buildFallingGrid(101.0 + 24.0, 37.0 + 21.0)
        var items = generateFallingItems(grid, params)

        fun regenerate() {
            items = generateFallingItems(grid, params)
        }

        keyboard.keyDown.listen {
            when (it.name) {
                "r" -> {
                    params = params.copy(seed = KotlinRandom.Default.nextLong())
                    regenerate()
                }
                "s" -> {
                    params = params.copy(shapeMode = if (params.shapeMode == FallingGridShapeMode.SQUARES) FallingGridShapeMode.CIRCLES else FallingGridShapeMode.SQUARES)
                    regenerate()
                }
                "p" -> {
                    val nextPalette = FallingGridPalette.values()[(params.palette.ordinal + 1) % FallingGridPalette.values().size]
                    params = params.copy(palette = nextPalette)
                }
                "d" -> {
                    params = params.copy(debug = !params.debug)
                }
                "[" -> {
                    params = params.copy(densityMul = (params.densityMul - 0.1).coerceAtLeast(0.1))
                    regenerate()
                }
                "]" -> {
                    params = params.copy(densityMul = (params.densityMul + 0.1).coerceAtMost(5.0))
                    regenerate()
                }
                "-" -> {
                    params = params.copy(disorderMul = (params.disorderMul - 0.1).coerceAtLeast(0.0))
                    regenerate()
                }
                "=" -> {
                    params = params.copy(disorderMul = (params.disorderMul + 0.1).coerceAtMost(5.0))
                    regenerate()
                }
                "e" -> {
                    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                    val fileName = "falling_grid_${params.seed}_$timestamp.png"
                    val imagesDir = File("images")
                    if (!imagesDir.exists()) imagesDir.mkdir()
                    
                    val renderTarget = renderTarget(width, height) {
                        colorBuffer()
                    }
                    drawer.withTarget(renderTarget) {
                        renderFallingGrid(drawer, params, items)
                    }
                    renderTarget.colorBuffer(0).saveToFile(File("images/$fileName"))
                    println("Exported to images/$fileName")
                    renderTarget.destroy()
                }
            }
        }

        extend {
            renderFallingGrid(drawer, params, items)
        }
    }
}
