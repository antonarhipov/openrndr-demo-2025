package drawing

import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.color.rgb
import org.openrndr.draw.*
import org.openrndr.extra.shapes.hobbyCurve
import org.openrndr.math.Vector2
import org.openrndr.shape.*
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.*
import kotlin.random.Random

// ============================================================================
// DATA CLASSES
// ============================================================================

data class ArchiveParams(
    val seed: Long = System.currentTimeMillis(),
    val shelfCount: Int = 12,
    val bookDensity: Double = 0.7,
    val drawerDensity: Double = 0.6,
    val paletteMode: Int = 0,    // 0 = warm paper, 1 = cooler blueprint
    val indexWheelStyle: Int = 0, // 0 = mechanical, 1 = archival
    val showDebug: Boolean = false
)

data class Shelf(
    val y: Double,
    val x1: Double,
    val x2: Double,
    val isArced: Boolean = false,
    val arcHeight: Double = 0.0
)

data class BookSpine(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val isAccent: Boolean = false,
    val accentType: Int = 0  // 0=normal, 1=red, 2=gold
)

data class CatalogDrawer(
    val rect: Rectangle,
    val isOpen: Boolean = false,
    val cardCount: Int = 0
)

data class Ladder(
    val x: Double,
    val y1: Double,
    val y2: Double,
    val angle: Double,
    val rungCount: Int
)

data class ChutePath(
    val points: List<Vector2>,
    val isDotted: Boolean = false
)

data class InteriorZones(
    val cranium: Rectangle,
    val forehead: Rectangle,
    val eyeRegion: Circle,
    val cheekNasal: Rectangle,
    val jaw: Rectangle,
    val teethBand: Rectangle
)

// ============================================================================
// PALETTE
// ============================================================================

class ArchivePalette(paletteMode: Int) {
    val background: ColorRGBa
    val lineInk: ColorRGBa
    val fillLight: ColorRGBa
    val fillMedium: ColorRGBa
    val fillDark: ColorRGBa
    val accentRed: ColorRGBa
    val accentGold: ColorRGBa
    val paper: ColorRGBa

    init {
        if (paletteMode == 0) {
            // Warm off-white paper mode
            background = rgb("FAF8F3")    // Warm off-white
            paper = rgb("F5F2EB")
            lineInk = rgb("1A2B44")       // Ink navy
            fillLight = rgb("E8E6E0")     // Light gray
            fillMedium = rgb("A8A6A0")    // Medium gray
            fillDark = rgb("606060")
            accentRed = rgb("A94442")     // Muted red (stamp)
            accentGold = rgb("C9A227")    // Warm gold
        } else {
            // Cooler blueprint mode
            background = rgb("E8EEF2")    // Cooler paper
            paper = rgb("DCE4EA")
            lineInk = rgb("1A3A5C")       // Deeper navy
            fillLight = rgb("C8D4DC")
            fillMedium = rgb("8899AA")
            fillDark = rgb("4A5A6A")
            accentRed = rgb("8B3A3A")
            accentGold = rgb("AA8822")
        }
    }
}

// ============================================================================
// LIBRARY OF MEMORIES GENERATOR
// ============================================================================

class LibraryOfMemories(val params: ArchiveParams) {
    val rng = Random(params.seed)
    val palette = ArchivePalette(params.paletteMode)

    // Core structures
    val skullContour: ShapeContour
    val skullShape: Shape
    val zones: InteriorZones
    val shelves: List<Shelf>
    val books: List<BookSpine>
    val drawers: List<CatalogDrawer>
    val ladders: List<Ladder>
    val indexWheel: IndexWheelData
    val chutes: List<ChutePath>
    val teeth: List<Rectangle>

    init {
        skullContour = buildSkullContour()
        skullShape = Shape(listOf(skullContour))
        zones = defineInteriorZones(skullContour)
        shelves = generateShelves(zones, params)
        books = generateBooksOnShelves(shelves, params)
        drawers = generateCatalogDrawers(zones, params)
        ladders = generateLadders(shelves, params)
        indexWheel = generateIndexWheel(zones.eyeRegion, params)
        chutes = generateChutesAndPaths(zones, params)
        teeth = generateTeeth()
    }

    // ========================================================================
    // SKULL CONTOUR
    // ========================================================================

    fun buildSkullContour(): ShapeContour {
        val center = Vector2(300.0, 380.0)
        val scale = 1.15

        // Side-profile skull facing left
        val points = listOf(
            // Top of cranium
            Vector2(30.0, -200.0),
            Vector2(100.0, -180.0),
            Vector2(150.0, -120.0),
            // Back of skull
            Vector2(170.0, -40.0),
            Vector2(160.0, 60.0),
            Vector2(130.0, 120.0),
            // Nuchal / back of neck area
            Vector2(90.0, 160.0),
            Vector2(60.0, 180.0),
            // Jaw hinge area
            Vector2(50.0, 210.0),
            Vector2(50.0, 260.0),
            // Jaw / chin
            Vector2(20.0, 290.0),
            Vector2(-30.0, 300.0),
            Vector2(-70.0, 280.0),
            // Below teeth
            Vector2(-100.0, 240.0),
            // Upper teeth / mouth
            Vector2(-105.0, 200.0),
            Vector2(-110.0, 160.0),
            // Nose
            Vector2(-120.0, 120.0),
            Vector2(-100.0, 80.0),
            Vector2(-90.0, 40.0),
            // Brow / orbital ridge
            Vector2(-80.0, 0.0),
            Vector2(-60.0, -60.0),
            // Forehead
            Vector2(-30.0, -140.0),
            Vector2(0.0, -190.0),
        ).map { it * scale + center }

        return ShapeContour.fromPoints(points, true).hobbyCurve()
    }

    // ========================================================================
    // INTERIOR ZONES
    // ========================================================================

    fun defineInteriorZones(skull: ShapeContour): InteriorZones {
        val bounds = skull.bounds

        val cranium = Rectangle(
            bounds.x + 20.0,
            bounds.y + 20.0,
            bounds.width - 40.0,
            bounds.height * 0.55
        )

        val forehead = Rectangle(
            bounds.x + bounds.width * 0.2,
            bounds.y + 20.0,
            bounds.width * 0.5,
            bounds.height * 0.25
        )

        // Eye region - positioned in the orbital area
        val eyeCenter = Vector2(
            bounds.x + bounds.width * 0.28,
            bounds.y + bounds.height * 0.42
        )
        val eyeRegion = Circle(eyeCenter, 48.0)

        val cheekNasal = Rectangle(
            bounds.x + 20.0,
            bounds.y + bounds.height * 0.45,
            bounds.width * 0.4,
            bounds.height * 0.25
        )

        val jaw = Rectangle(
            bounds.x + 30.0,
            bounds.y + bounds.height * 0.65,
            bounds.width * 0.6,
            bounds.height * 0.3
        )

        val teethBand = Rectangle(
            bounds.x + 10.0,
            bounds.y + bounds.height * 0.75,
            bounds.width * 0.35,
            40.0
        )

        return InteriorZones(cranium, forehead, eyeRegion, cheekNasal, jaw, teethBand)
    }

    // ========================================================================
    // SHELVES
    // ========================================================================

    fun generateShelves(zones: InteriorZones, params: ArchiveParams): List<Shelf> {
        val shelves = mutableListOf<Shelf>()
        val bounds = skullContour.bounds

        // Cranium shelves (6-10)
        val craniumTop = bounds.y + 40.0
        val craniumBottom = bounds.y + bounds.height * 0.55
        val craniumShelfCount = params.shelfCount.coerceIn(6, 18)

        for (i in 0 until craniumShelfCount) {
            val t = i.toDouble() / (craniumShelfCount - 1).coerceAtLeast(1)
            val y = craniumTop + t * (craniumBottom - craniumTop)

            // Skip shelves that would intersect eye region
            val eyeY = zones.eyeRegion.center.y
            val eyeR = zones.eyeRegion.radius + 10.0
            if (abs(y - eyeY) < eyeR && i > 2) continue

            // Find x bounds at this y level by scanning
            val xLeft = findLeftEdge(y)
            val xRight = findRightEdge(y)

            if (xRight - xLeft > 40.0) {
                val isArced = rng.nextDouble() < 0.3 && i > 1 && i < craniumShelfCount - 2
                val arcHeight = if (isArced) rng.nextDouble(-8.0, 8.0) else 0.0
                shelves.add(Shelf(y, xLeft + 10.0, xRight - 10.0, isArced, arcHeight))
            }
        }

        // Jaw area - denser shelves
        val jawTop = bounds.y + bounds.height * 0.7
        val jawBottom = bounds.y + bounds.height * 0.88
        val jawShelfCount = 4

        for (i in 0 until jawShelfCount) {
            val t = i.toDouble() / (jawShelfCount - 1).coerceAtLeast(1)
            val y = jawTop + t * (jawBottom - jawTop)
            val xLeft = findLeftEdge(y)
            val xRight = findRightEdge(y)

            if (xRight - xLeft > 30.0) {
                shelves.add(Shelf(y, xLeft + 15.0, xRight - 15.0, false, 0.0))
            }
        }

        return shelves
    }

    private fun findLeftEdge(y: Double): Double {
        val bounds = skullContour.bounds
        for (x in bounds.x.toInt()..(bounds.x + bounds.width).toInt() step 2) {
            if (skullShape.contains(Vector2(x.toDouble(), y))) {
                return x.toDouble()
            }
        }
        return bounds.x
    }

    private fun findRightEdge(y: Double): Double {
        val bounds = skullContour.bounds
        for (x in (bounds.x + bounds.width).toInt() downTo bounds.x.toInt() step 2) {
            if (skullShape.contains(Vector2(x.toDouble(), y))) {
                return x.toDouble()
            }
        }
        return bounds.x + bounds.width
    }

    // ========================================================================
    // BOOKS
    // ========================================================================

    fun generateBooksOnShelves(shelves: List<Shelf>, params: ArchiveParams): List<BookSpine> {
        val books = mutableListOf<BookSpine>()

        for (shelf in shelves) {
            var x = shelf.x1 + rng.nextDouble(2.0, 8.0)

            while (x < shelf.x2 - 10.0) {
                // Skip with probability for rhythm
                if (rng.nextDouble() > params.bookDensity) {
                    x += rng.nextDouble(8.0, 20.0)
                    continue
                }

                val width = rng.nextDouble(4.0, 14.0)
                val height = rng.nextDouble(18.0, 32.0)

                // Check if within skull
                val bookCenter = Vector2(x + width / 2, shelf.y - height / 2)
                if (skullShape.contains(bookCenter)) {
                    val accentRoll = rng.nextDouble()
                    val accentType = when {
                        accentRoll < 0.03 -> 1  // Red
                        accentRoll < 0.06 -> 2  // Gold
                        else -> 0
                    }

                    books.add(BookSpine(
                        x = x,
                        y = shelf.y - height,
                        width = width,
                        height = height,
                        isAccent = accentType > 0,
                        accentType = accentType
                    ))
                }

                x += width + rng.nextDouble(1.0, 3.0)
            }
        }

        return books
    }

    // ========================================================================
    // CATALOG DRAWERS
    // ========================================================================

    fun generateCatalogDrawers(zones: InteriorZones, params: ArchiveParams): List<CatalogDrawer> {
        val drawers = mutableListOf<CatalogDrawer>()
        val drawerWidth = 22.0
        val drawerHeight = 14.0

        // Cheek/nasal region drawers
        val cheekArea = zones.cheekNasal
        val startX = cheekArea.x + 10.0
        val startY = cheekArea.y + 10.0

        val cols = ((cheekArea.width - 20.0) / (drawerWidth + 3)).toInt().coerceIn(2, 5)
        val rows = ((cheekArea.height - 20.0) / (drawerHeight + 3)).toInt().coerceIn(2, 6)

        for (row in 0 until rows) {
            for (col in 0 until cols) {
                if (rng.nextDouble() > params.drawerDensity) continue

                val x = startX + col * (drawerWidth + 3)
                val y = startY + row * (drawerHeight + 3)
                val center = Vector2(x + drawerWidth / 2, y + drawerHeight / 2)

                // Skip if too close to eye
                if (center.distanceTo(zones.eyeRegion.center) < zones.eyeRegion.radius + 20.0) continue

                if (skullShape.contains(center)) {
                    val isOpen = rng.nextDouble() < 0.2
                    val cardCount = if (isOpen) rng.nextInt(2, 6) else 0

                    drawers.add(CatalogDrawer(
                        rect = Rectangle(x, y, drawerWidth, drawerHeight),
                        isOpen = isOpen,
                        cardCount = cardCount
                    ))
                }
            }
        }

        // Jaw region drawers (denser)
        val jawArea = zones.jaw
        val jawStartX = jawArea.x + 5.0
        val jawStartY = jawArea.y + 5.0

        val jawCols = ((jawArea.width - 10.0) / (drawerWidth * 0.8 + 2)).toInt().coerceIn(2, 8)
        val jawRows = ((jawArea.height - 10.0) / (drawerHeight * 0.8 + 2)).toInt().coerceIn(2, 5)

        for (row in 0 until jawRows) {
            for (col in 0 until jawCols) {
                if (rng.nextDouble() > params.drawerDensity * 1.2) continue

                val x = jawStartX + col * (drawerWidth * 0.8 + 2)
                val y = jawStartY + row * (drawerHeight * 0.8 + 2)
                val center = Vector2(x + drawerWidth * 0.4, y + drawerHeight * 0.4)

                if (skullShape.contains(center)) {
                    drawers.add(CatalogDrawer(
                        rect = Rectangle(x, y, drawerWidth * 0.8, drawerHeight * 0.8),
                        isOpen = rng.nextDouble() < 0.15,
                        cardCount = if (rng.nextDouble() < 0.15) rng.nextInt(2, 5) else 0
                    ))
                }
            }
        }

        return drawers
    }

    // ========================================================================
    // LADDERS
    // ========================================================================

    fun generateLadders(shelves: List<Shelf>, params: ArchiveParams): List<Ladder> {
        val ladders = mutableListOf<Ladder>()
        if (shelves.size < 3) return ladders

        val ladderCount = rng.nextInt(1, 4)

        repeat(ladderCount) {
            val startShelfIdx = rng.nextInt(1, (shelves.size - 2).coerceAtLeast(2))
            val endShelfIdx = (startShelfIdx + rng.nextInt(2, 5)).coerceAtMost(shelves.size - 1)

            val startShelf = shelves[startShelfIdx]
            val endShelf = shelves[endShelfIdx]

            val x = rng.nextDouble(
                max(startShelf.x1, endShelf.x1) + 20.0,
                min(startShelf.x2, endShelf.x2) - 20.0
            )

            if (skullShape.contains(Vector2(x, startShelf.y)) &&
                skullShape.contains(Vector2(x, endShelf.y))) {

                val rungCount = ((endShelf.y - startShelf.y) / 12.0).toInt().coerceIn(3, 12)

                ladders.add(Ladder(
                    x = x,
                    y1 = startShelf.y,
                    y2 = endShelf.y,
                    angle = rng.nextDouble(-10.0, 10.0),
                    rungCount = rungCount
                ))
            }
        }

        return ladders
    }

    // ========================================================================
    // INDEX WHEEL (Eye Socket)
    // ========================================================================

    data class IndexWheelData(
        val center: Vector2,
        val outerRadius: Double,
        val innerRadius: Double,
        val tickCount: Int,
        val cardSlots: List<CardSlot>,
        val spokeCount: Int,
        val pointerAngle: Double,
        val style: Int
    )

    data class CardSlot(
        val angle: Double,
        val isSelected: Boolean = false
    )

    fun generateIndexWheel(eyeRegion: Circle, params: ArchiveParams): IndexWheelData {
        val center = eyeRegion.center
        val outerRadius = eyeRegion.radius + 5.0
        val innerRadius = outerRadius * 0.4

        val tickCount = 24
        val slotCount = 12

        // Select one random slot to be "selected"
        val selectedSlotIndex = rng.nextInt(slotCount)

        val cardSlots = (0 until slotCount).map { i ->
            CardSlot(
                angle = i * 2 * PI / slotCount,
                isSelected = i == selectedSlotIndex
            )
        }

        return IndexWheelData(
            center = center,
            outerRadius = outerRadius,
            innerRadius = innerRadius,
            tickCount = tickCount,
            cardSlots = cardSlots,
            spokeCount = if (params.indexWheelStyle == 0) 6 else 8,
            pointerAngle = cardSlots.first { it.isSelected }.angle,
            style = params.indexWheelStyle
        )
    }

    // ========================================================================
    // CHUTES AND PATHS
    // ========================================================================

    fun generateChutesAndPaths(zones: InteriorZones, params: ArchiveParams): List<ChutePath> {
        val paths = mutableListOf<ChutePath>()

        // Create connecting paths between zones
        repeat(15) {
            val startZone = when (rng.nextInt(4)) {
                0 -> zones.cranium
                1 -> zones.forehead
                2 -> zones.cheekNasal
                else -> zones.jaw
            }

            val start = Vector2(
                rng.nextDouble(startZone.x + 10.0, startZone.x + startZone.width - 10.0),
                rng.nextDouble(startZone.y + 10.0, startZone.y + startZone.height - 10.0)
            )

            if (!skullShape.contains(start)) return@repeat

            // Orthogonal path with rounded corners
            val points = mutableListOf(start)
            var current = start
            val segments = rng.nextInt(2, 5)

            repeat(segments) { seg ->
                val isHorizontal = seg % 2 == 0
                val distance = rng.nextDouble(20.0, 80.0) * (if (rng.nextBoolean()) 1 else -1)

                val next = if (isHorizontal) {
                    current + Vector2(distance, 0.0)
                } else {
                    current + Vector2(0.0, distance)
                }

                if (skullShape.contains(next)) {
                    points.add(next)
                    current = next
                }
            }

            if (points.size > 1) {
                paths.add(ChutePath(points, isDotted = rng.nextDouble() < 0.4))
            }
        }

        return paths
    }

    // ========================================================================
    // TEETH
    // ========================================================================

    fun generateTeeth(): List<Rectangle> {
        val teeth = mutableListOf<Rectangle>()
        val bounds = skullContour.bounds

        // Upper teeth row
        val upperY = bounds.y + bounds.height * 0.73
        val lowerY = upperY + 28.0

        val teethStartX = bounds.x + 30.0
        val toothWidth = 10.0
        val toothHeight = 18.0
        val gap = 2.0

        // Upper teeth (6-7)
        for (i in 0 until 7) {
            val x = teethStartX + i * (toothWidth + gap)
            val center = Vector2(x + toothWidth / 2, upperY + toothHeight / 2)
            if (skullShape.contains(center)) {
                teeth.add(Rectangle(x, upperY, toothWidth, toothHeight))
            }
        }

        // Lower teeth (6-7)
        for (i in 0 until 7) {
            val x = teethStartX + i * (toothWidth + gap)
            val center = Vector2(x + toothWidth / 2, lowerY + toothHeight / 2)
            if (skullShape.contains(center)) {
                teeth.add(Rectangle(x, lowerY, toothWidth, toothHeight))
            }
        }

        return teeth
    }

    // ========================================================================
    // RENDERING
    // ========================================================================

    fun renderBackground(drawer: Drawer) {
        drawer.clear(palette.background)

        // Faint grid
        drawer.stroke = palette.lineInk.opacify(0.03)
        drawer.strokeWeight = 0.5
        for (x in 0..600 step 20) {
            drawer.lineSegment(x.toDouble(), 0.0, x.toDouble(), 800.0)
        }
        for (y in 0..800 step 20) {
            drawer.lineSegment(0.0, y.toDouble(), 600.0, y.toDouble())
        }
    }

    fun renderSkullAndInternals(drawer: Drawer) {
        val w = 600
        val h = 800

        // Create render target for masked content
        val contentRT = renderTarget(w, h) {
            colorBuffer()
            depthBuffer(DepthFormat.DEPTH24_STENCIL8)
        }

        val maskRT = renderTarget(w, h) {
            colorBuffer()
            depthBuffer(DepthFormat.DEPTH24_STENCIL8)
        }

        // Draw mask
        drawer.isolatedWithTarget(maskRT) {
            clear(ColorRGBa.TRANSPARENT)
            fill = ColorRGBa.WHITE
            stroke = null
            contour(skullContour)
        }

        // Draw all internals to content RT
        drawer.isolatedWithTarget(contentRT) {
            clear(ColorRGBa.TRANSPARENT)

            // Background fill inside skull
            fill = palette.paper
            stroke = null
            contour(skullContour)

            // Render shelves
            renderShelves(this)

            // Render books
            renderBooks(this)

            // Render catalog drawers
            renderDrawers(this)

            // Render ladders
            renderLadders(this)

            // Render chutes/paths
            renderChutes(this)

            // Render teeth
            renderTeeth(this)

            // Render index wheel (eye socket)
            renderIndexWheel(this)
        }

        // Composite: apply mask
        drawer.isolated {
            shadeStyle = shadeStyle {
                fragmentTransform = """
                    vec2 uv = c_boundsPosition.xy;
                    vec4 color = texture(p_content, vec2(uv.x, 1.0 - uv.y));
                    vec4 mask = texture(p_mask, vec2(uv.x, 1.0 - uv.y));
                    x_fill = color;
                    x_fill.a *= mask.a;
                """
                parameter("content", contentRT.colorBuffer(0))
                parameter("mask", maskRT.colorBuffer(0))
            }
            fill = ColorRGBa.WHITE
            stroke = null
            rectangle(0.0, 0.0, w.toDouble(), h.toDouble())
        }

        contentRT.destroy()
        maskRT.destroy()

        // Draw skull outline
        drawer.fill = null
        drawer.stroke = palette.lineInk
        drawer.strokeWeight = 2.5
        drawer.contour(skullContour)

        // External attachments
        renderExternalAttachments(drawer)
    }

    private fun renderShelves(drawer: Drawer) {
        for (shelf in shelves) {
            drawer.stroke = palette.lineInk
            drawer.strokeWeight = 1.2
            drawer.fill = null

            // Main shelf line
            if (shelf.isArced) {
                val mid = Vector2((shelf.x1 + shelf.x2) / 2, shelf.y + shelf.arcHeight)
                val pts = listOf(Vector2(shelf.x1, shelf.y), mid, Vector2(shelf.x2, shelf.y))
                val curve = ShapeContour.fromPoints(pts, false).hobbyCurve()
                drawer.contour(curve)
            } else {
                drawer.lineSegment(shelf.x1, shelf.y, shelf.x2, shelf.y)
            }

            // Shelf brackets/uprights
            drawer.strokeWeight = 0.8
            val bracketCount = ((shelf.x2 - shelf.x1) / 60.0).toInt().coerceIn(2, 6)
            for (i in 0..bracketCount) {
                val x = shelf.x1 + i * (shelf.x2 - shelf.x1) / bracketCount
                drawer.lineSegment(x, shelf.y, x, shelf.y - 8.0)
            }
        }
    }

    private fun renderBooks(drawer: Drawer) {
        for (book in books) {
            val rect = Rectangle(book.x, book.y, book.width, book.height)

            // Book fill
            drawer.fill = when (book.accentType) {
                1 -> palette.accentRed.opacify(0.8)
                2 -> palette.accentGold.opacify(0.8)
                else -> palette.fillLight
            }
            drawer.stroke = palette.lineInk
            drawer.strokeWeight = 0.5
            drawer.rectangle(rect)

            // Spine line
            if (book.width > 5.0) {
                drawer.strokeWeight = 0.3
                drawer.lineSegment(book.x + book.width * 0.3, book.y + 2, book.x + book.width * 0.3, book.y + book.height - 2)
            }

            // Title tick marks
            if (book.height > 22.0 && book.width > 6.0) {
                val tickY = book.y + book.height * 0.4
                drawer.strokeWeight = 0.4
                drawer.lineSegment(book.x + 1.5, tickY, book.x + book.width - 1.5, tickY)
                drawer.lineSegment(book.x + 1.5, tickY + 3, book.x + book.width - 1.5, tickY + 3)
            }
        }
    }

    private fun renderDrawers(drawer: Drawer) {
        for (catalogDrawer in drawers) {
            val rect = catalogDrawer.rect

            // Drawer body
            drawer.fill = palette.fillMedium.opacify(0.6)
            drawer.stroke = palette.lineInk
            drawer.strokeWeight = 0.7
            drawer.rectangle(rect)

            // Label slot
            val labelW = rect.width * 0.6
            val labelH = rect.height * 0.3
            val labelRect = Rectangle(
                rect.x + (rect.width - labelW) / 2,
                rect.y + rect.height * 0.2,
                labelW,
                labelH
            )
            drawer.fill = palette.paper
            drawer.rectangle(labelRect)

            // Handle (small circle or rectangle)
            drawer.fill = palette.lineInk
            drawer.stroke = null
            drawer.circle(rect.x + rect.width / 2, rect.y + rect.height * 0.75, 1.5)

            // If open, draw card edges
            if (catalogDrawer.isOpen && catalogDrawer.cardCount > 0) {
                drawer.stroke = palette.lineInk
                drawer.strokeWeight = 0.4
                drawer.fill = null

                for (i in 0 until catalogDrawer.cardCount) {
                    val cardY = rect.y - 3 - i * 2.5
                    drawer.lineSegment(rect.x + 2, cardY, rect.x + rect.width - 2, cardY)
                }

                // Index tab on one card
                drawer.fill = palette.accentGold.opacify(0.7)
                drawer.stroke = palette.lineInk
                drawer.strokeWeight = 0.3
                val tabRect = Rectangle(rect.x + rect.width - 6, rect.y - 8, 5.0, 4.0)
                drawer.rectangle(tabRect)
            }
        }
    }

    private fun renderLadders(drawer: Drawer) {
        for (ladder in ladders) {
            val radians = ladder.angle * PI / 180.0
            val dx = sin(radians) * (ladder.y2 - ladder.y1)

            val leftTopX = ladder.x - 4 + dx * 0.5
            val leftBottomX = ladder.x - 4 - dx * 0.5
            val rightTopX = ladder.x + 4 + dx * 0.5
            val rightBottomX = ladder.x + 4 - dx * 0.5

            // Rails
            drawer.stroke = palette.lineInk
            drawer.strokeWeight = 1.0
            drawer.fill = null
            drawer.lineSegment(leftTopX, ladder.y1, leftBottomX, ladder.y2)
            drawer.lineSegment(rightTopX, ladder.y1, rightBottomX, ladder.y2)

            // Rungs
            drawer.strokeWeight = 0.7
            for (i in 0 until ladder.rungCount) {
                val t = i.toDouble() / (ladder.rungCount - 1).coerceAtLeast(1)
                val y = ladder.y1 + t * (ladder.y2 - ladder.y1)
                val xOffset = dx * (0.5 - t)
                drawer.lineSegment(ladder.x - 4 + xOffset, y, ladder.x + 4 + xOffset, y)
            }

            // Wheels at bottom
            drawer.fill = palette.lineInk
            drawer.stroke = null
            drawer.circle(leftBottomX, ladder.y2 + 3, 2.0)
            drawer.circle(rightBottomX, ladder.y2 + 3, 2.0)
        }
    }

    private fun renderIndexWheel(drawer: Drawer) {
        val wheel = indexWheel
        val center = wheel.center

        // Outer ring
        drawer.fill = palette.fillLight
        drawer.stroke = palette.lineInk
        drawer.strokeWeight = 2.0
        drawer.circle(center, wheel.outerRadius)

        // Inner ring
        drawer.fill = palette.paper
        drawer.strokeWeight = 1.5
        drawer.circle(center, wheel.outerRadius * 0.75)

        // Tick marks around outer ring
        drawer.strokeWeight = 0.8
        for (i in 0 until wheel.tickCount) {
            val angle = i * 2 * PI / wheel.tickCount
            val innerP = center + Vector2(cos(angle), sin(angle)) * (wheel.outerRadius - 8)
            val outerP = center + Vector2(cos(angle), sin(angle)) * wheel.outerRadius
            drawer.lineSegment(innerP, outerP)
        }

        // Card slots
        drawer.strokeWeight = 0.6
        for (slot in wheel.cardSlots) {
            val r = wheel.outerRadius * 0.62
            val slotCenter = center + Vector2(cos(slot.angle), sin(slot.angle)) * r

            // Small rectangle representing card slot
            val slotW = 8.0
            val slotH = 12.0

            drawer.fill = if (slot.isSelected) palette.accentGold.opacify(0.8) else palette.fillMedium.opacify(0.5)
            drawer.stroke = palette.lineInk
            drawer.strokeWeight = 0.5

            drawer.isolated {
                translate(slotCenter)
                rotate(slot.angle * 180.0 / PI + 90.0)
                rectangle(Rectangle.fromCenter(Vector2.ZERO, slotW, slotH))
            }
        }

        // Central hub
        drawer.fill = palette.fillDark
        drawer.stroke = palette.lineInk
        drawer.strokeWeight = 1.2
        drawer.circle(center, wheel.innerRadius)

        // Hub center dot
        drawer.fill = palette.lineInk
        drawer.stroke = null
        drawer.circle(center, 4.0)

        // Spokes
        drawer.stroke = palette.lineInk
        drawer.strokeWeight = 1.0
        for (i in 0 until wheel.spokeCount) {
            val angle = i * 2 * PI / wheel.spokeCount
            val inner = center + Vector2(cos(angle), sin(angle)) * 5.0
            val outer = center + Vector2(cos(angle), sin(angle)) * wheel.innerRadius
            drawer.lineSegment(inner, outer)
        }

        // Pointer arrow
        val pointerR = wheel.outerRadius + 12
        val arrowTip = center + Vector2(cos(wheel.pointerAngle), sin(wheel.pointerAngle)) * pointerR
        val arrowBase1 = center + Vector2(cos(wheel.pointerAngle - 0.15), sin(wheel.pointerAngle - 0.15)) * (pointerR - 10)
        val arrowBase2 = center + Vector2(cos(wheel.pointerAngle + 0.15), sin(wheel.pointerAngle + 0.15)) * (pointerR - 10)

        drawer.fill = palette.accentRed
        drawer.stroke = palette.lineInk
        drawer.strokeWeight = 0.8
        drawer.contour(ShapeContour.fromPoints(listOf(arrowTip, arrowBase1, arrowBase2), true))

        // Optional viewing window
        if (wheel.style == 1) {
            val windowPos = center + Vector2(wheel.outerRadius + 25.0, -20.0)
            drawer.fill = palette.paper
            drawer.stroke = palette.lineInk
            drawer.strokeWeight = 1.0
            drawer.rectangle(Rectangle.fromCenter(windowPos, 30.0, 20.0))

            // "Card" lines inside window
            drawer.strokeWeight = 0.4
            for (i in 0 until 3) {
                val y = windowPos.y - 6 + i * 5
                drawer.lineSegment(windowPos.x - 12, y, windowPos.x + 12, y)
            }
        }
    }

    private fun renderChutes(drawer: Drawer) {
        for (chute in chutes) {
            if (chute.points.size < 2) continue

            if (chute.isDotted) {
                // Dotted path
                drawer.fill = palette.lineInk.opacify(0.4)
                drawer.stroke = null
                for (i in 0 until chute.points.size - 1) {
                    val start = chute.points[i]
                    val end = chute.points[i + 1]
                    val dist = start.distanceTo(end)
                    val dotCount = (dist / 4.0).toInt()
                    for (d in 0 until dotCount) {
                        val t = d.toDouble() / dotCount.coerceAtLeast(1)
                        val p = start + (end - start) * t
                        drawer.circle(p, 1.0)
                    }
                }
            } else {
                // Solid line path
                drawer.fill = null
                drawer.stroke = palette.lineInk.opacify(0.3)
                drawer.strokeWeight = 0.8
                for (i in 0 until chute.points.size - 1) {
                    drawer.lineSegment(chute.points[i], chute.points[i + 1])
                }
            }
        }
    }

    private fun renderTeeth(drawer: Drawer) {
        for (tooth in teeth) {
            // Rounded rectangle approximation
            val r = 3.0
            drawer.fill = palette.paper
            drawer.stroke = palette.lineInk
            drawer.strokeWeight = 1.0
            drawer.rectangle(tooth)

            // Inner line for depth
            drawer.strokeWeight = 0.4
            val innerRect = Rectangle(tooth.x + 2, tooth.y + 2, tooth.width - 4, tooth.height - 4)
            drawer.rectangle(innerRect)
        }
    }

    private fun renderExternalAttachments(drawer: Drawer) {
        // Label plates, hinges, paperclip hooks along outline
        val attachmentCount = 15
        val rngAttach = Random(params.seed + 999)

        for (i in 0 until attachmentCount) {
            val t = rngAttach.nextDouble()
            val pos = skullContour.position(t)
            val normal = skullContour.normal(t)

            when (rngAttach.nextInt(5)) {
                0 -> {
                    // Small label plate
                    val platePos = pos + normal * 10.0
                    drawer.fill = palette.fillLight
                    drawer.stroke = palette.lineInk
                    drawer.strokeWeight = 0.6
                    drawer.rectangle(Rectangle.fromCenter(platePos, 12.0, 6.0))
                }
                1 -> {
                    // Hinge bracket
                    val hingePos = pos + normal * 6.0
                    drawer.fill = palette.lineInk
                    drawer.stroke = null
                    drawer.circle(hingePos, 2.0)
                    drawer.strokeWeight = 0.5
                    drawer.stroke = palette.lineInk
                    drawer.lineSegment(pos, hingePos)
                }
                2 -> {
                    // Stamp mark (small red circle)
                    if (rngAttach.nextDouble() < 0.3) {
                        val stampPos = pos + normal * 8.0
                        drawer.fill = palette.accentRed.opacify(0.4)
                        drawer.stroke = palette.accentRed
                        drawer.strokeWeight = 0.5
                        drawer.circle(stampPos, 4.0)
                    }
                }
                3 -> {
                    // Short shelf rail extending outward
                    val railEnd = pos + normal * 12.0
                    drawer.stroke = palette.lineInk
                    drawer.strokeWeight = 1.0
                    drawer.lineSegment(pos, railEnd)
                    drawer.fill = palette.lineInk
                    drawer.circle(railEnd, 1.5)
                }
                4 -> {
                    // Tag strip
                    val tagPos = pos + normal * 8.0
                    drawer.fill = palette.accentGold.opacify(0.6)
                    drawer.stroke = palette.lineInk
                    drawer.strokeWeight = 0.4
                    drawer.rectangle(Rectangle.fromCenter(tagPos, 8.0, 4.0))
                }
            }
        }
    }

    fun renderTitleBlock(drawer: Drawer) {
        drawer.fill = palette.lineInk
        drawer.stroke = null

        val font = loadFont("data/fonts/default.otf", 11.0)
        drawer.fontMap = font

        val x = 30.0
        val y = 750.0

        drawer.text("LIBRARY OF MEMORIES — ARCHIVE SECTION", x, y)
        drawer.text("SEED: ${params.seed.toString(16).uppercase()}", x, y + 14)
        drawer.text("CATALOG REV A", x, y + 28)

        // Barcode lines
        val barcodeRng = Random(params.seed + 12345)
        val barcodeX = x + 200
        for (i in 0 until 15) {
            val w = barcodeRng.nextDouble(1.0, 3.0)
            drawer.rectangle(barcodeX + i * 4, y - 8, w, 18.0)
        }

        // Small stamp icon
        drawer.fill = palette.accentRed.opacify(0.6)
        drawer.stroke = palette.accentRed
        drawer.strokeWeight = 1.0
        drawer.circle(x + 350, y + 10, 12.0)

        drawer.fontMap = font
        drawer.fill = palette.accentRed
        drawer.text("✓", x + 346, y + 14)
    }

    fun renderDebugOverlay(drawer: Drawer) {
        if (!params.showDebug) return

        drawer.stroke = ColorRGBa.RED.opacify(0.5)
        drawer.strokeWeight = 1.0
        drawer.fill = ColorRGBa.RED.opacify(0.1)

        // Zones
        drawer.rectangle(zones.cranium)
        drawer.rectangle(zones.forehead)
        drawer.rectangle(zones.cheekNasal)
        drawer.rectangle(zones.jaw)
        drawer.rectangle(zones.teethBand)

        drawer.fill = ColorRGBa.BLUE.opacify(0.2)
        drawer.circle(zones.eyeRegion)

        // Shelf guides
        drawer.stroke = ColorRGBa.GREEN.opacify(0.5)
        for (shelf in shelves) {
            drawer.lineSegment(shelf.x1, shelf.y, shelf.x2, shelf.y)
        }

        // Eye center
        drawer.fill = ColorRGBa.YELLOW
        drawer.stroke = null
        drawer.circle(indexWheel.center, 5.0)
    }

    // ========================================================================
    // EXPORT
    // ========================================================================

    fun export600x800(drawer: Drawer, filename: String) {
        val rt = renderTarget(600, 800) {
            colorBuffer()
            depthBuffer(DepthFormat.DEPTH24_STENCIL8)
        }

        drawer.isolatedWithTarget(rt) {
            renderBackground(this)
            renderSkullAndInternals(this)
            renderTitleBlock(this)
        }

        rt.colorBuffer(0).saveToFile(File(filename))
        rt.destroy()
        println("Exported: $filename")
    }
}

// ============================================================================
// MAIN APPLICATION
// ============================================================================

fun main() = application {
    configure {
        width = 600
        height = 800
        title = "Library of Memories"
    }

    program {
        var params = ArchiveParams()
        var library = LibraryOfMemories(params)

        keyboard.keyDown.listen { event ->
            when (event.name) {
                "r" -> {
                    // Reseed internal layout
                    params = params.copy(seed = System.currentTimeMillis())
                    library = LibraryOfMemories(params)
                }
                "i" -> {
                    // Toggle index wheel style
                    val newStyle = (params.indexWheelStyle + 1) % 2
                    params = params.copy(indexWheelStyle = newStyle)
                    library = LibraryOfMemories(params)
                }
                "d" -> {
                    // Toggle debug overlay
                    params = params.copy(showDebug = !params.showDebug)
                    library = LibraryOfMemories(params)
                }
                "e" -> {
                    // Export PNG
                    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                    val filename = "images/library_${params.seed}_$timestamp.png"
                    library.export600x800(drawer, filename)
                }
                "1" -> {
                    // Warm paper palette
                    params = params.copy(paletteMode = 0)
                    library = LibraryOfMemories(params)
                }
                "2" -> {
                    // Cooler blueprint palette
                    params = params.copy(paletteMode = 1)
                    library = LibraryOfMemories(params)
                }
            }
        }

        extend {
            library.renderBackground(drawer)
            library.renderSkullAndInternals(drawer)
            library.renderDebugOverlay(drawer)
            library.renderTitleBlock(drawer)
        }
    }
}
