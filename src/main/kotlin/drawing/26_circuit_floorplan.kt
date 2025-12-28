package drawing

import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.color.rgb
import org.openrndr.draw.DepthFormat
import org.openrndr.draw.Drawer
import org.openrndr.draw.LineCap
import org.openrndr.draw.LineJoin
import org.openrndr.draw.isolated
import org.openrndr.draw.isolatedWithTarget
import org.openrndr.draw.loadFont
import org.openrndr.draw.renderTarget
import org.openrndr.draw.shadeStyle
import org.openrndr.math.Vector2
import org.openrndr.shape.*
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.*
import kotlin.random.Random

// =============================================================================
// PARAMETERS
// =============================================================================

data class FloorplanParams(
    val seed: Long = Random.nextLong(),
    val gridSize: Double = 20.0,
    val districtCount: Int = 5,
    val roomsPerDistrict: Int = 6,
    val corridorDensity: Double = 0.7,
    val accentRate: Double = 0.15,
    val showAccents: Boolean = true,
    val showDebug: Boolean = false
)

// =============================================================================
// COLOR PALETTE
// =============================================================================

class FloorplanPalette {
    val background = rgb("FDFCF8")      // Off-white paper
    val navy = rgb("1A2B44")            // Dominant dark - outlines
    val grey = rgb("808080")            // Chassis/modules
    val greyLight = rgb("C0C0C0")       // Light grey for fills
    val fillLight = rgb("F5F5F5")       // White/off-white courtyards
    val accentRed = rgb("C42020")       // Power layer
    val accentYellow = rgb("D4A017")    // Signal layer  
    val accentCyan = rgb("20B2AA")      // UI node dots (sparse)
}

// =============================================================================
// DATA CLASSES
// =============================================================================

data class Room(
    val rect: Rectangle,
    val label: String,
    val hasPins: Boolean = false,
    val pinSide: Int = 0, // 0=top, 1=right, 2=bottom, 3=left
    val pinCount: Int = 4,
    val isHighlighted: Boolean = false
)

data class District(
    val bounds: Rectangle,
    val rooms: MutableList<Room> = mutableListOf(),
    val courtyardCenter: Vector2 = Vector2.ZERO,
    val courtyardRadius: Double = 0.0
)

data class Route(
    val points: List<Vector2>,
    val isAccent: Boolean = false,
    val accentType: Int = 0, // 0=navy, 1=red(power), 2=yellow(signal)
    val weight: Double = 1.0,
    val isBus: Boolean = false
)

sealed class Motif {
    data class Via(val pos: Vector2, val isCyan: Boolean = false) : Motif()
    data class ConnectorTeeth(val pos: Vector2, val normal: Vector2, val count: Int) : Motif()
    data class BusBar(val start: Vector2, val end: Vector2, val tapPoints: List<Vector2>) : Motif()
    data class RibbonCable(val lanes: List<Pair<Vector2, Vector2>>) : Motif()
    data class HeatSink(val rect: Rectangle, val finCount: Int) : Motif()
    data class Valve(val center: Vector2, val radius: Double) : Motif()
    data class Gauge(val center: Vector2, val radius: Double, val tickCount: Int) : Motif()
    data class WarningStripes(val rect: Rectangle) : Motif()
    data class Screw(val pos: Vector2) : Motif()
    data class MicroPanel(val rect: Rectangle, val hasVent: Boolean) : Motif()
    data class Stairwell(val center: Vector2, val radius: Double, val turns: Int) : Motif()
}

// =============================================================================
// MAIN CLASS
// =============================================================================

class CircuitFloorplan(val params: FloorplanParams) {
    private val rng = Random(params.seed)
    private val palette = FloorplanPalette()
    
    // Panel boundary (rounded rectangle)
    val panelBounds: Rectangle
    val panelContour: ShapeContour
    val panelShape: Shape
    
    // Generated elements
    val districts: List<District>
    val allRooms: List<Room>
    val courtyards: List<Shape>
    val routes: List<Route>
    val motifs: List<Motif>
    val externalPorts: List<Pair<Vector2, Vector2>> // position, outward direction
    
    // Dimensions
    private val canvasW = 600.0
    private val canvasH = 800.0
    private val panelMarginX = 40.0
    private val panelMarginTop = 60.0
    private val panelMarginBottom = 100.0
    
    init {
        // Build panel boundary
        panelBounds = Rectangle(
            panelMarginX,
            panelMarginTop,
            canvasW - 2 * panelMarginX,
            canvasH - panelMarginTop - panelMarginBottom
        )
        panelContour = buildPanelBoundary()
        panelShape = Shape(listOf(panelContour))
        
        // Generate content
        districts = generateDistricts()
        allRooms = districts.flatMap { it.rooms }
        courtyards = generateCourtyards()
        routes = routeCorridors()
        motifs = addMotifs()
        externalPorts = generateExternalPorts()
    }
    
    // -------------------------------------------------------------------------
    // PANEL BOUNDARY
    // -------------------------------------------------------------------------
    
    private fun buildPanelBoundary(): ShapeContour {
        // Rounded rectangle with corner radius
        val cornerRadius = 12.0
        return roundedRectangle(panelBounds, cornerRadius)
    }
    
    private fun roundedRectangle(rect: Rectangle, radius: Double): ShapeContour {
        val r = minOf(radius, rect.width / 2, rect.height / 2)
        val x = rect.x
        val y = rect.y
        val w = rect.width
        val h = rect.height
        
        return contour {
            moveTo(x + r, y)
            lineTo(x + w - r, y)
            arcTo(r, r, 0.0, false, true, x + w, y + r)
            lineTo(x + w, y + h - r)
            arcTo(r, r, 0.0, false, true, x + w - r, y + h)
            lineTo(x + r, y + h)
            arcTo(r, r, 0.0, false, true, x, y + h - r)
            lineTo(x, y + r)
            arcTo(r, r, 0.0, false, true, x + r, y)
            close()
        }
    }
    
    // -------------------------------------------------------------------------
    // DISTRICT GENERATION
    // -------------------------------------------------------------------------
    
    private fun generateDistricts(): List<District> {
        val districts = mutableListOf<District>()
        val innerMargin = 15.0
        val availableRect = Rectangle(
            panelBounds.x + innerMargin,
            panelBounds.y + innerMargin,
            panelBounds.width - 2 * innerMargin,
            panelBounds.height - 2 * innerMargin
        )
        
        // Subdivide into districts using a grid-based approach
        val count = params.districtCount.coerceIn(3, 7)
        
        // Create a layout: 2-3 columns, varying rows
        val cols = if (count <= 4) 2 else 3
        val rows = (count + cols - 1) / cols
        
        val cellW = availableRect.width / cols
        val cellH = availableRect.height / rows
        
        var created = 0
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                if (created >= count) break
                
                // Add some randomness to district bounds
                val jitterX = rng.nextDouble(-10.0, 10.0)
                val jitterY = rng.nextDouble(-10.0, 10.0)
                val shrink = rng.nextDouble(5.0, 20.0)
                
                val distRect = Rectangle(
                    availableRect.x + col * cellW + shrink + jitterX,
                    availableRect.y + row * cellH + shrink + jitterY,
                    cellW - 2 * shrink,
                    cellH - 2 * shrink
                )
                
                val district = District(
                    bounds = distRect,
                    courtyardCenter = distRect.center + Vector2(
                        rng.nextDouble(-20.0, 20.0),
                        rng.nextDouble(-20.0, 20.0)
                    ),
                    courtyardRadius = rng.nextDouble(20.0, 40.0)
                )
                
                // Generate rooms for this district
                generateRooms(district)
                districts.add(district)
                created++
            }
        }
        
        return districts
    }
    
    // -------------------------------------------------------------------------
    // ROOM GENERATION
    // -------------------------------------------------------------------------
    
    private fun generateRooms(district: District) {
        val roomCount = params.roomsPerDistrict + rng.nextInt(-2, 3)
        val grid = params.gridSize
        
        // Labels pool
        val labelPool = listOf(
            "A-01", "A-02", "A-03", "B-01", "B-02", "CORE", 
            "LIFT-1", "LIFT-2", "LIFT-3", "STAIR", "BUS-PWR", "BUS-SIG",
            "AUX-1", "AUX-2", "CTRL", "DATA", "PWR", "SYS", "IO-1", "IO-2"
        ).shuffled(rng)
        
        val placed = mutableListOf<Rectangle>()
        var attempts = 0
        var labelIdx = 0
        
        while (district.rooms.size < roomCount && attempts < 200) {
            attempts++
            
            // Room size (snapped to grid)
            val w = (rng.nextInt(2, 5) * grid).coerceAtMost(district.bounds.width * 0.4)
            val h = (rng.nextInt(2, 4) * grid).coerceAtMost(district.bounds.height * 0.3)
            
            // Position (snapped to grid)
            val maxX = district.bounds.x + district.bounds.width - w - 5
            val maxY = district.bounds.y + district.bounds.height - h - 5
            if (maxX <= district.bounds.x + 5 || maxY <= district.bounds.y + 5) continue
            
            val x = snapToGrid(rng.nextDouble(district.bounds.x + 5, maxX), grid)
            val y = snapToGrid(rng.nextDouble(district.bounds.y + 5, maxY), grid)
            
            val roomRect = Rectangle(x, y, w, h)
            
            // Check overlap with existing rooms and courtyard
            val overlaps = placed.any { it.intersects(roomRect.grow(5.0)) }
            val tooCloseToCourtyard = roomRect.center.distanceTo(district.courtyardCenter) < district.courtyardRadius + 20
            
            if (!overlaps && !tooCloseToCourtyard) {
                val label = labelPool.getOrElse(labelIdx) { "M-${labelIdx}" }
                labelIdx++
                
                val hasPins = rng.nextDouble() < 0.6
                val pinSide = rng.nextInt(4)
                val pinCount = rng.nextInt(3, 8)
                val isHighlighted = rng.nextDouble() < 0.1
                
                district.rooms.add(Room(roomRect, label, hasPins, pinSide, pinCount, isHighlighted))
                placed.add(roomRect)
            }
        }
    }
    
    private fun snapToGrid(value: Double, grid: Double): Double {
        return (value / grid).roundToInt() * grid
    }
    
    private fun Rectangle.grow(amount: Double): Rectangle {
        return Rectangle(x - amount, y - amount, width + 2 * amount, height + 2 * amount)
    }
    
    // -------------------------------------------------------------------------
    // COURTYARD GENERATION
    // -------------------------------------------------------------------------
    
    private fun generateCourtyards(): List<Shape> {
        return districts.map { district ->
            // Create a rounded rectangle courtyard
            val cx = district.courtyardCenter.x
            val cy = district.courtyardCenter.y
            val r = district.courtyardRadius
            
            val courtyardRect = Rectangle.fromCenter(Vector2(cx, cy), r * 2, r * 1.5)
            Shape(listOf(roundedRectangle(courtyardRect, 8.0)))
        }
    }
    
    // -------------------------------------------------------------------------
    // CORRIDOR ROUTING
    // -------------------------------------------------------------------------
    
    private fun routeCorridors(): List<Route> {
        val routes = mutableListOf<Route>()
        
        // 1. Create main spine corridors (major buses)
        routes.addAll(createSpineCorridors())
        
        // 2. Connect rooms within districts
        for (district in districts) {
            routes.addAll(connectRoomsInDistrict(district))
        }
        
        // 3. Connect districts to each other
        routes.addAll(connectDistricts())
        
        return routes
    }
    
    private fun createSpineCorridors(): List<Route> {
        val spines = mutableListOf<Route>()
        
        // Vertical spine (elevator bus)
        val spineX = panelBounds.center.x + rng.nextDouble(-30.0, 30.0)
        val spineTop = panelBounds.y + 20
        val spineBottom = panelBounds.y + panelBounds.height - 20
        
        val verticalSpine = listOf(
            Vector2(spineX, spineTop),
            Vector2(spineX, spineBottom)
        )
        spines.add(Route(verticalSpine, isAccent = true, accentType = 1, weight = 2.0, isBus = true))
        
        // Horizontal spines
        val hSpineCount = 2 + rng.nextInt(2)
        for (i in 0 until hSpineCount) {
            val y = panelBounds.y + (i + 1) * panelBounds.height / (hSpineCount + 1)
            val x1 = panelBounds.x + 20
            val x2 = panelBounds.x + panelBounds.width - 20
            
            val horizontalSpine = listOf(Vector2(x1, y), Vector2(x2, y))
            val isAccent = rng.nextDouble() < params.accentRate
            spines.add(Route(horizontalSpine, isAccent, if (isAccent) 2 else 0, 1.8, true))
        }
        
        return spines
    }
    
    private fun connectRoomsInDistrict(district: District): List<Route> {
        val routes = mutableListOf<Route>()
        val rooms = district.rooms
        if (rooms.size < 2) return routes
        
        // Connect adjacent rooms with orthogonal routes
        for (i in 0 until rooms.size - 1) {
            if (rng.nextDouble() > params.corridorDensity) continue
            
            val room1 = rooms[i]
            val room2 = rooms[(i + 1) % rooms.size]
            
            val route = createOrthogonalRoute(room1.rect.center, room2.rect.center)
            val isAccent = rng.nextDouble() < params.accentRate
            routes.add(Route(route, isAccent, if (isAccent) rng.nextInt(3) else 0, 1.0))
        }
        
        return routes
    }
    
    private fun connectDistricts(): List<Route> {
        val routes = mutableListOf<Route>()
        
        for (i in 0 until districts.size - 1) {
            val d1 = districts[i]
            val d2 = districts[i + 1]
            
            val route = createOrthogonalRoute(d1.bounds.center, d2.bounds.center)
            val isAccent = rng.nextDouble() < params.accentRate * 1.5
            routes.add(Route(route, isAccent, if (isAccent) 1 else 0, 1.5))
        }
        
        return routes
    }
    
    private fun createOrthogonalRoute(start: Vector2, end: Vector2): List<Vector2> {
        // Create an L-shaped or Z-shaped orthogonal route
        val points = mutableListOf<Vector2>()
        points.add(start)
        
        val dx = end.x - start.x
        val dy = end.y - start.y
        
        if (rng.nextBoolean()) {
            // Horizontal first, then vertical
            val midX = start.x + dx
            points.add(Vector2(midX, start.y))
            points.add(Vector2(midX, end.y))
        } else {
            // Vertical first, then horizontal
            val midY = start.y + dy
            points.add(Vector2(start.x, midY))
            points.add(Vector2(end.x, midY))
        }
        
        points.add(end)
        return points.distinct()
    }
    
    // -------------------------------------------------------------------------
    // MOTIF GENERATION
    // -------------------------------------------------------------------------
    
    private fun addMotifs(): List<Motif> {
        val motifs = mutableListOf<Motif>()
        
        // Vias / test pads throughout panel
        motifs.addAll(generateVias())
        
        // Bus bars with tap points
        motifs.addAll(generateBusBars())
        
        // Heat sinks on some rooms
        motifs.addAll(generateHeatSinks())
        
        // Valves and gauges
        motifs.addAll(generateValvesAndGauges())
        
        // Warning stripes on 1-3 components
        motifs.addAll(generateWarningStripes())
        
        // Screws/rivets at module corners
        motifs.addAll(generateScrews())
        
        // Micro-panels
        motifs.addAll(generateMicroPanels())
        
        // Stairwells (spiral/arc motifs)
        motifs.addAll(generateStairwells())
        
        // Ribbon cables
        motifs.addAll(generateRibbonCables())
        
        return motifs
    }
    
    private fun generateVias(): List<Motif> {
        val vias = mutableListOf<Motif>()
        val count = 80 + rng.nextInt(40)
        
        repeat(count) {
            val pos = Vector2(
                rng.nextDouble(panelBounds.x + 10, panelBounds.x + panelBounds.width - 10),
                rng.nextDouble(panelBounds.y + 10, panelBounds.y + panelBounds.height - 10)
            )
            if (panelShape.contains(pos)) {
                val isCyan = rng.nextDouble() < 0.08 // Sparse cyan dots
                vias.add(Motif.Via(pos, isCyan))
            }
        }
        
        return vias
    }
    
    private fun generateBusBars(): List<Motif> {
        val bars = mutableListOf<Motif>()
        
        // Create 2-4 bus bars
        repeat(rng.nextInt(2, 5)) {
            val isVertical = rng.nextBoolean()
            val start: Vector2
            val end: Vector2
            
            if (isVertical) {
                val x = rng.nextDouble(panelBounds.x + 30, panelBounds.x + panelBounds.width - 30)
                start = Vector2(x, panelBounds.y + 30)
                end = Vector2(x, panelBounds.y + panelBounds.height - 30)
            } else {
                val y = rng.nextDouble(panelBounds.y + 30, panelBounds.y + panelBounds.height - 30)
                start = Vector2(panelBounds.x + 30, y)
                end = Vector2(panelBounds.x + panelBounds.width - 30, y)
            }
            
            // Generate tap points along the bar
            val tapCount = rng.nextInt(3, 8)
            val taps = (0 until tapCount).map { i ->
                val t = (i + 1).toDouble() / (tapCount + 1)
                start + (end - start) * t
            }
            
            bars.add(Motif.BusBar(start, end, taps))
        }
        
        return bars
    }
    
    private fun generateHeatSinks(): List<Motif> {
        val sinks = mutableListOf<Motif>()
        
        // Add heat sinks to some rooms
        val roomsWithSinks = allRooms.filter { rng.nextDouble() < 0.15 }
        for (room in roomsWithSinks) {
            val finCount = rng.nextInt(4, 10)
            sinks.add(Motif.HeatSink(room.rect, finCount))
        }
        
        return sinks
    }
    
    private fun generateValvesAndGauges(): List<Motif> {
        val items = mutableListOf<Motif>()
        
        // Valves
        repeat(rng.nextInt(3, 7)) {
            val pos = Vector2(
                rng.nextDouble(panelBounds.x + 20, panelBounds.x + panelBounds.width - 20),
                rng.nextDouble(panelBounds.y + 20, panelBounds.y + panelBounds.height - 20)
            )
            if (panelShape.contains(pos)) {
                items.add(Motif.Valve(pos, rng.nextDouble(4.0, 8.0)))
            }
        }
        
        // Gauges
        repeat(rng.nextInt(2, 5)) {
            val pos = Vector2(
                rng.nextDouble(panelBounds.x + 20, panelBounds.x + panelBounds.width - 20),
                rng.nextDouble(panelBounds.y + 20, panelBounds.y + panelBounds.height - 20)
            )
            if (panelShape.contains(pos)) {
                items.add(Motif.Gauge(pos, rng.nextDouble(6.0, 12.0), rng.nextInt(8, 16)))
            }
        }
        
        return items
    }
    
    private fun generateWarningStripes(): List<Motif> {
        val stripes = mutableListOf<Motif>()
        
        // Add warning stripes to 1-3 components
        val roomsWithStripes = allRooms.shuffled(rng).take(rng.nextInt(1, 4))
        for (room in roomsWithStripes) {
            stripes.add(Motif.WarningStripes(room.rect))
        }
        
        return stripes
    }
    
    private fun generateScrews(): List<Motif> {
        val screws = mutableListOf<Motif>()
        
        // Add screws at corners of some rooms
        val roomsWithScrews = allRooms.filter { rng.nextDouble() < 0.4 }
        for (room in roomsWithScrews) {
            val corners = listOf(
                Vector2(room.rect.x + 3, room.rect.y + 3),
                Vector2(room.rect.x + room.rect.width - 3, room.rect.y + 3),
                Vector2(room.rect.x + 3, room.rect.y + room.rect.height - 3),
                Vector2(room.rect.x + room.rect.width - 3, room.rect.y + room.rect.height - 3)
            )
            corners.forEach { screws.add(Motif.Screw(it)) }
        }
        
        return screws
    }
    
    private fun generateMicroPanels(): List<Motif> {
        val panels = mutableListOf<Motif>()
        
        repeat(rng.nextInt(4, 10)) {
            val w = rng.nextDouble(15.0, 35.0)
            val h = rng.nextDouble(10.0, 25.0)
            val x = rng.nextDouble(panelBounds.x + 15, panelBounds.x + panelBounds.width - w - 15)
            val y = rng.nextDouble(panelBounds.y + 15, panelBounds.y + panelBounds.height - h - 15)
            
            val rect = Rectangle(x, y, w, h)
            if (panelShape.contains(rect.center)) {
                panels.add(Motif.MicroPanel(rect, rng.nextBoolean()))
            }
        }
        
        return panels
    }
    
    private fun generateStairwells(): List<Motif> {
        val stairs = mutableListOf<Motif>()
        
        // One stairwell per wing (roughly one per side of panel)
        repeat(rng.nextInt(2, 4)) {
            val pos = Vector2(
                rng.nextDouble(panelBounds.x + 50, panelBounds.x + panelBounds.width - 50),
                rng.nextDouble(panelBounds.y + 50, panelBounds.y + panelBounds.height - 50)
            )
            if (panelShape.contains(pos)) {
                stairs.add(Motif.Stairwell(pos, rng.nextDouble(12.0, 20.0), rng.nextInt(2, 4)))
            }
        }
        
        return stairs
    }
    
    private fun generateRibbonCables(): List<Motif> {
        val ribbons = mutableListOf<Motif>()
        
        repeat(rng.nextInt(2, 5)) {
            val laneCount = rng.nextInt(3, 6)
            val isVertical = rng.nextBoolean()
            val baseX = rng.nextDouble(panelBounds.x + 30, panelBounds.x + panelBounds.width - 60)
            val baseY = rng.nextDouble(panelBounds.y + 30, panelBounds.y + panelBounds.height - 60)
            val length = rng.nextDouble(40.0, 100.0)
            
            val lanes = mutableListOf<Pair<Vector2, Vector2>>()
            for (i in 0 until laneCount) {
                val offset = i * 3.0
                if (isVertical) {
                    lanes.add(Vector2(baseX + offset, baseY) to Vector2(baseX + offset, baseY + length))
                } else {
                    lanes.add(Vector2(baseX, baseY + offset) to Vector2(baseX + length, baseY + offset))
                }
            }
            
            ribbons.add(Motif.RibbonCable(lanes))
        }
        
        return ribbons
    }
    
    // -------------------------------------------------------------------------
    // EXTERNAL PORTS
    // -------------------------------------------------------------------------
    
    private fun generateExternalPorts(): List<Pair<Vector2, Vector2>> {
        val ports = mutableListOf<Pair<Vector2, Vector2>>()
        
        // Distribute ports around the panel perimeter
        val portCount = 20 + rng.nextInt(10)
        
        repeat(portCount) {
            val side = rng.nextInt(4)
            val t = rng.nextDouble(0.1, 0.9)
            
            val pos: Vector2
            val normal: Vector2
            
            when (side) {
                0 -> { // Top
                    pos = Vector2(panelBounds.x + t * panelBounds.width, panelBounds.y)
                    normal = Vector2(0.0, -1.0)
                }
                1 -> { // Right
                    pos = Vector2(panelBounds.x + panelBounds.width, panelBounds.y + t * panelBounds.height)
                    normal = Vector2(1.0, 0.0)
                }
                2 -> { // Bottom
                    pos = Vector2(panelBounds.x + t * panelBounds.width, panelBounds.y + panelBounds.height)
                    normal = Vector2(0.0, 1.0)
                }
                else -> { // Left
                    pos = Vector2(panelBounds.x, panelBounds.y + t * panelBounds.height)
                    normal = Vector2(-1.0, 0.0)
                }
            }
            
            ports.add(pos to normal)
        }
        
        return ports
    }
    
    // =========================================================================
    // RENDERING
    // =========================================================================
    
    fun renderBackground(drawer: Drawer) {
        drawer.clear(palette.background)
        
        // Faint gridlines across entire page
        drawer.stroke = palette.navy.opacify(0.04)
        drawer.strokeWeight = 0.5
        
        val gridStep = 20.0
        for (x in 0..canvasW.toInt() step gridStep.toInt()) {
            drawer.lineSegment(x.toDouble(), 0.0, x.toDouble(), canvasH)
        }
        for (y in 0..canvasH.toInt() step gridStep.toInt()) {
            drawer.lineSegment(0.0, y.toDouble(), canvasW, y.toDouble())
        }
    }
    
    fun renderPanel(drawer: Drawer) {
        val contentRT = renderTarget(canvasW.toInt(), canvasH.toInt()) {
            colorBuffer()
            depthBuffer(DepthFormat.DEPTH24_STENCIL8)
        }
        val maskRT = renderTarget(canvasW.toInt(), canvasH.toInt()) {
            colorBuffer()
            depthBuffer(DepthFormat.DEPTH24_STENCIL8)
        }
        
        // Create mask from panel shape
        drawer.isolatedWithTarget(maskRT) {
            clear(ColorRGBa.TRANSPARENT)
            fill = ColorRGBa.WHITE
            stroke = null
            contour(panelContour)
        }
        
        // Render all internal content
        drawer.isolatedWithTarget(contentRT) {
            clear(ColorRGBa.TRANSPARENT)
            
            // Courtyards (large light fields)
            renderCourtyards(this)
            
            // Rooms
            renderRooms(this)
            
            // Routes/corridors
            renderRoutes(this)
            
            // Motifs
            renderMotifs(this)
        }
        
        // Apply mask to content
        drawer.isolated {
            drawer.shadeStyle = shadeStyle {
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
            drawer.fill = ColorRGBa.WHITE
            drawer.stroke = null
            drawer.rectangle(0.0, 0.0, canvasW, canvasH)
        }
        
        contentRT.destroy()
        maskRT.destroy()
        
        // Panel outline (thicker stroke)
        drawer.fill = null
        drawer.stroke = palette.navy
        drawer.strokeWeight = 2.5
        drawer.lineCap = LineCap.ROUND
        drawer.lineJoin = LineJoin.ROUND
        drawer.contour(panelContour)
        
        // External ports
        renderExternalPorts(drawer)
    }
    
    private fun renderCourtyards(drawer: Drawer) {
        drawer.stroke = palette.navy.opacify(0.3)
        drawer.strokeWeight = 0.5
        drawer.fill = palette.fillLight
        
        for (courtyard in courtyards) {
            drawer.shape(courtyard)
        }
    }
    
    private fun renderRooms(drawer: Drawer) {
        val cornerRadius = 4.0
        
        for (room in allRooms) {
            // Room fill
            drawer.fill = if (room.isHighlighted) palette.greyLight else palette.fillLight
            drawer.stroke = palette.navy
            drawer.strokeWeight = 1.0
            drawer.lineCap = LineCap.ROUND
            drawer.lineJoin = LineJoin.ROUND
            
            // Draw rounded rectangle
            val roomContour = roundedRectangle(room.rect, cornerRadius)
            drawer.contour(roomContour)
            
            // Pins (IC package vibe)
            if (room.hasPins) {
                renderRoomPins(drawer, room)
            }
            
            // Room label
            renderRoomLabel(drawer, room)
        }
    }
    
    private fun renderRoomPins(drawer: Drawer, room: Room) {
        drawer.fill = palette.navy
        drawer.stroke = null
        
        val pinLength = 6.0
        val pinWidth = 2.0
        val spacing = room.rect.width / (room.pinCount + 1)
        
        when (room.pinSide) {
            0 -> { // Top
                for (i in 1..room.pinCount) {
                    val x = room.rect.x + i * spacing - pinWidth / 2
                    drawer.rectangle(x, room.rect.y - pinLength, pinWidth, pinLength)
                }
            }
            1 -> { // Right
                val vSpacing = room.rect.height / (room.pinCount + 1)
                for (i in 1..room.pinCount) {
                    val y = room.rect.y + i * vSpacing - pinWidth / 2
                    drawer.rectangle(room.rect.x + room.rect.width, y, pinLength, pinWidth)
                }
            }
            2 -> { // Bottom
                for (i in 1..room.pinCount) {
                    val x = room.rect.x + i * spacing - pinWidth / 2
                    drawer.rectangle(x, room.rect.y + room.rect.height, pinWidth, pinLength)
                }
            }
            3 -> { // Left
                val vSpacing = room.rect.height / (room.pinCount + 1)
                for (i in 1..room.pinCount) {
                    val y = room.rect.y + i * vSpacing - pinWidth / 2
                    drawer.rectangle(room.rect.x - pinLength, y, pinLength, pinWidth)
                }
            }
        }
    }
    
    private fun renderRoomLabel(drawer: Drawer, room: Room) {
        try {
            val font = loadFont("data/fonts/default.otf", 8.0)
            drawer.fontMap = font
            drawer.fill = palette.navy
            drawer.stroke = null
            
            val textX = room.rect.center.x - room.label.length * 2.5
            val textY = room.rect.center.y + 3
            drawer.text(room.label, textX, textY)
        } catch (e: Exception) {
            // Font not available, skip labels
        }
    }
    
    private fun renderRoutes(drawer: Drawer) {
        drawer.lineCap = LineCap.ROUND
        drawer.lineJoin = LineJoin.ROUND
        
        for (route in routes) {
            val color = when {
                !params.showAccents || !route.isAccent -> palette.navy
                route.accentType == 1 -> palette.accentRed
                route.accentType == 2 -> palette.accentYellow
                else -> palette.navy
            }
            
            drawer.stroke = color
            drawer.strokeWeight = if (route.isBus) route.weight * 1.5 else route.weight
            drawer.fill = null
            
            if (route.points.size >= 2) {
                // Draw with filleted corners
                val contour = createFilletedPath(route.points)
                drawer.contour(contour)
            }
        }
    }
    
    private fun createFilletedPath(points: List<Vector2>): ShapeContour {
        if (points.size < 2) return ShapeContour.EMPTY
        if (points.size == 2) {
            return ShapeContour(listOf(Segment(points[0], points[1])), false)
        }
        
        // Simple approach: create properly chained line segments
        // This avoids the complex fillet logic that caused segment discontinuity issues
        val segments = mutableListOf<Segment>()
        
        for (i in 0 until points.size - 1) {
            val p1 = points[i]
            val p2 = points[i + 1]
            
            // Skip zero-length segments
            if ((p2 - p1).length < 0.01) continue
            
            segments.add(Segment(p1, p2))
        }
        
        return if (segments.isNotEmpty()) {
            ShapeContour(segments, false)
        } else {
            ShapeContour.EMPTY
        }
    }
    
    private fun renderMotifs(drawer: Drawer) {
        for (motif in motifs) {
            when (motif) {
                is Motif.Via -> renderVia(drawer, motif)
                is Motif.ConnectorTeeth -> renderConnectorTeeth(drawer, motif)
                is Motif.BusBar -> renderBusBar(drawer, motif)
                is Motif.RibbonCable -> renderRibbonCable(drawer, motif)
                is Motif.HeatSink -> renderHeatSink(drawer, motif)
                is Motif.Valve -> renderValve(drawer, motif)
                is Motif.Gauge -> renderGauge(drawer, motif)
                is Motif.WarningStripes -> renderWarningStripes(drawer, motif)
                is Motif.Screw -> renderScrew(drawer, motif)
                is Motif.MicroPanel -> renderMicroPanel(drawer, motif)
                is Motif.Stairwell -> renderStairwell(drawer, motif)
            }
        }
    }
    
    private fun renderVia(drawer: Drawer, via: Motif.Via) {
        val color = if (via.isCyan && params.showAccents) palette.accentCyan else palette.navy
        drawer.fill = color
        drawer.stroke = null
        drawer.circle(via.pos, 2.0)
    }
    
    private fun renderConnectorTeeth(drawer: Drawer, teeth: Motif.ConnectorTeeth) {
        drawer.fill = palette.navy
        drawer.stroke = null
        
        for (i in 0 until teeth.count) {
            val offset = (i - teeth.count / 2.0) * 4.0
            val perpendicular = Vector2(-teeth.normal.y, teeth.normal.x)
            val pos = teeth.pos + perpendicular * offset
            drawer.rectangle(Rectangle.fromCenter(pos, 3.0, 6.0))
        }
    }
    
    private fun renderBusBar(drawer: Drawer, bar: Motif.BusBar) {
        drawer.stroke = palette.navy
        drawer.strokeWeight = 2.5
        drawer.lineSegment(bar.start, bar.end)
        
        // Tap points
        drawer.fill = palette.navy
        drawer.stroke = null
        for (tap in bar.tapPoints) {
            drawer.circle(tap, 3.0)
        }
    }
    
    private fun renderRibbonCable(drawer: Drawer, ribbon: Motif.RibbonCable) {
        drawer.stroke = palette.navy
        drawer.strokeWeight = 0.75
        
        for ((start, end) in ribbon.lanes) {
            drawer.lineSegment(start, end)
        }
        
        // End clamps
        if (ribbon.lanes.isNotEmpty()) {
            val first = ribbon.lanes.first()
            val last = ribbon.lanes.last()
            drawer.fill = palette.grey
            drawer.stroke = palette.navy
            drawer.strokeWeight = 0.5
            
            val clampWidth = (last.first.x - first.first.x).absoluteValue + 6
            val clampHeight = 4.0
            drawer.rectangle(Rectangle.fromCenter(
                Vector2((first.first.x + last.first.x) / 2, first.first.y - 2),
                clampWidth, clampHeight
            ))
            drawer.rectangle(Rectangle.fromCenter(
                Vector2((first.second.x + last.second.x) / 2, first.second.y + 2),
                clampWidth, clampHeight
            ))
        }
    }
    
    private fun renderHeatSink(drawer: Drawer, sink: Motif.HeatSink) {
        drawer.stroke = palette.grey
        drawer.strokeWeight = 0.5
        
        val spacing = sink.rect.width / (sink.finCount + 1)
        for (i in 1..sink.finCount) {
            val x = sink.rect.x + i * spacing
            drawer.lineSegment(x, sink.rect.y, x, sink.rect.y + sink.rect.height)
        }
    }
    
    private fun renderValve(drawer: Drawer, valve: Motif.Valve) {
        drawer.fill = palette.fillLight
        drawer.stroke = palette.navy
        drawer.strokeWeight = 1.0
        drawer.circle(valve.center, valve.radius)
        
        // Spokes
        drawer.strokeWeight = 0.75
        for (i in 0 until 4) {
            val angle = i * PI / 2
            val outer = valve.center + Vector2(cos(angle), sin(angle)) * valve.radius
            drawer.lineSegment(valve.center, outer)
        }
    }
    
    private fun renderGauge(drawer: Drawer, gauge: Motif.Gauge) {
        drawer.fill = palette.fillLight
        drawer.stroke = palette.navy
        drawer.strokeWeight = 1.0
        drawer.circle(gauge.center, gauge.radius)
        
        // Tick marks
        drawer.strokeWeight = 0.5
        for (i in 0 until gauge.tickCount) {
            val angle = i * 2 * PI / gauge.tickCount
            val inner = gauge.center + Vector2(cos(angle), sin(angle)) * (gauge.radius * 0.7)
            val outer = gauge.center + Vector2(cos(angle), sin(angle)) * gauge.radius
            drawer.lineSegment(inner, outer)
        }
        
        // Center dot
        drawer.fill = palette.navy
        drawer.stroke = null
        drawer.circle(gauge.center, 1.5)
    }
    
    private fun renderWarningStripes(drawer: Drawer, stripes: Motif.WarningStripes) {
        if (!params.showAccents) return
        
        drawer.stroke = palette.accentYellow.opacify(0.6)
        drawer.strokeWeight = 2.0
        
        val stripeSpacing = 6.0
        val rect = stripes.rect
        var offset = 0.0
        
        while (offset < rect.width + rect.height) {
            val x1 = rect.x + offset
            val y1 = rect.y
            val x2 = rect.x
            val y2 = rect.y + offset
            
            // Clip to rectangle bounds
            val clippedStart = Vector2(
                x1.coerceIn(rect.x, rect.x + rect.width),
                y1.coerceIn(rect.y, rect.y + rect.height)
            )
            val clippedEnd = Vector2(
                x2.coerceIn(rect.x, rect.x + rect.width),
                y2.coerceIn(rect.y, rect.y + rect.height)
            )
            
            if (clippedStart != clippedEnd) {
                drawer.lineSegment(clippedStart, clippedEnd)
            }
            
            offset += stripeSpacing
        }
    }
    
    private fun renderScrew(drawer: Drawer, screw: Motif.Screw) {
        drawer.fill = palette.grey
        drawer.stroke = palette.navy
        drawer.strokeWeight = 0.5
        drawer.circle(screw.pos, 2.5)
        
        // Cross pattern
        drawer.strokeWeight = 0.5
        drawer.lineSegment(screw.pos - Vector2(1.5, 0.0), screw.pos + Vector2(1.5, 0.0))
        drawer.lineSegment(screw.pos - Vector2(0.0, 1.5), screw.pos + Vector2(0.0, 1.5))
    }
    
    private fun renderMicroPanel(drawer: Drawer, panel: Motif.MicroPanel) {
        drawer.fill = palette.greyLight
        drawer.stroke = palette.navy
        drawer.strokeWeight = 0.75
        drawer.rectangle(panel.rect)
        
        // Inner rectangle
        if (panel.rect.width > 10 && panel.rect.height > 8) {
            drawer.fill = null
            drawer.rectangle(Rectangle(
                panel.rect.x + 3, panel.rect.y + 3,
                panel.rect.width - 6, panel.rect.height - 6
            ))
        }
        
        // Vent lines
        if (panel.hasVent && panel.rect.width > 15) {
            drawer.strokeWeight = 0.5
            val ventSpacing = 3.0
            var x = panel.rect.x + 5
            while (x < panel.rect.x + panel.rect.width - 5) {
                drawer.lineSegment(
                    x, panel.rect.y + panel.rect.height * 0.3,
                    x, panel.rect.y + panel.rect.height * 0.7
                )
                x += ventSpacing
            }
        }
    }
    
    private fun renderStairwell(drawer: Drawer, stair: Motif.Stairwell) {
        drawer.fill = null
        drawer.stroke = palette.navy
        drawer.strokeWeight = 1.0
        
        // Draw spiral/coil
        val segments = mutableListOf<Segment>()
        val steps = stair.turns * 12
        var prevPoint: Vector2? = null
        
        for (i in 0..steps) {
            val t = i.toDouble() / steps
            val angle = t * stair.turns * 2 * PI
            val radius = stair.radius * (1.0 - t * 0.5)
            val point = stair.center + Vector2(cos(angle), sin(angle)) * radius
            
            if (prevPoint != null) {
                segments.add(Segment(prevPoint, point))
            }
            prevPoint = point
        }
        
        if (segments.isNotEmpty()) {
            drawer.contour(ShapeContour(segments, false))
        }
        
        // Center marker
        drawer.fill = palette.navy
        drawer.stroke = null
        drawer.circle(stair.center, 2.0)
    }
    
    private fun renderExternalPorts(drawer: Drawer) {
        drawer.stroke = palette.navy
        drawer.strokeWeight = 1.0
        
        for ((pos, normal) in externalPorts) {
            val outer = pos + normal * 12.0
            drawer.lineSegment(pos, outer)
            
            // Terminal dot or bracket
            if (rng.nextDouble() < 0.3) {
                drawer.fill = palette.navy
                drawer.stroke = null
                drawer.circle(outer, 2.0)
            } else {
                drawer.fill = palette.fillLight
                drawer.stroke = palette.navy
                drawer.strokeWeight = 0.75
                val bracketSize = 4.0
                drawer.rectangle(Rectangle.fromCenter(outer, bracketSize, bracketSize))
            }
        }
    }
    
    fun renderLegend(drawer: Drawer) {
        val legendX = panelBounds.x
        val legendY = panelBounds.y + panelBounds.height + 15
        
        try {
            val font = loadFont("data/fonts/default.otf", 9.0)
            drawer.fontMap = font
        } catch (e: Exception) {
            // Font not available
        }
        
        drawer.fill = palette.navy
        drawer.stroke = null
        
        // Title
        val titleFont = try { loadFont("data/fonts/default.otf", 12.0) } catch (e: Exception) { null }
        if (titleFont != null) drawer.fontMap = titleFont
        drawer.text("ARCHITECTURAL CIRCUIT PLAN — REV A", legendX, legendY)
        
        // Subtitle
        val smallFont = try { loadFont("data/fonts/default.otf", 8.0) } catch (e: Exception) { null }
        if (smallFont != null) drawer.fontMap = smallFont
        drawer.text("SEED: ${params.seed.toString(16).uppercase()}  |  ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))}  |  SCALE 1:200", legendX, legendY + 14)
        
        // Legend swatches
        val swatchY = legendY + 30
        val swatchSize = 8.0
        val swatchSpacing = 70.0
        
        val legendItems = listOf(
            palette.navy to "STRUCTURE",
            palette.accentRed to "POWER",
            palette.accentYellow to "SIGNAL",
            palette.grey to "CHASSIS",
            palette.accentCyan to "UI NODE"
        )
        
        for ((i, item) in legendItems.withIndex()) {
            val (color, label) = item
            val x = legendX + i * swatchSpacing
            
            drawer.fill = color
            drawer.stroke = palette.navy
            drawer.strokeWeight = 0.5
            
            if (label == "UI NODE") {
                // Dot for UI node
                drawer.circle(x + swatchSize / 2, swatchY - swatchSize / 2, swatchSize / 2)
            } else {
                drawer.rectangle(x, swatchY - swatchSize, swatchSize, swatchSize)
            }
            
            drawer.fill = palette.navy
            drawer.stroke = null
            drawer.text(label, x + swatchSize + 4, swatchY)
        }
        
        // Scale bar
        val scaleBarX = panelBounds.x + panelBounds.width - 100
        val scaleBarY = legendY + 30
        val scaleBarWidth = 80.0
        val segments = 4
        
        drawer.strokeWeight = 1.0
        for (i in 0 until segments) {
            val segX = scaleBarX + i * (scaleBarWidth / segments)
            val segW = scaleBarWidth / segments
            
            drawer.fill = if (i % 2 == 0) palette.navy else palette.background
            drawer.stroke = palette.navy
            drawer.rectangle(segX, scaleBarY - 4, segW, 4.0)
        }
        
        drawer.fill = palette.navy
        drawer.stroke = null
        drawer.text("0", scaleBarX - 4, scaleBarY + 10)
        drawer.text("10m", scaleBarX + scaleBarWidth - 10, scaleBarY + 10)
    }
    
    fun renderDebugOverlay(drawer: Drawer) {
        if (!params.showDebug) return
        
        // District bounds
        drawer.fill = null
        drawer.strokeWeight = 1.5
        
        for ((i, district) in districts.withIndex()) {
            drawer.stroke = ColorRGBa.fromHex(listOf(
                "#FF6B6B", "#4ECDC4", "#45B7D1", "#96CEB4", "#FFEAA7", "#DDA0DD", "#98D8C8"
            )[i % 7])
            drawer.rectangle(district.bounds)
            
            // Courtyard center
            drawer.fill = drawer.stroke
            drawer.circle(district.courtyardCenter, 5.0)
            drawer.fill = null
        }
        
        // Grid overlay
        drawer.stroke = ColorRGBa.RED.opacify(0.2)
        drawer.strokeWeight = 0.5
        val grid = params.gridSize
        
        var x = panelBounds.x
        while (x < panelBounds.x + panelBounds.width) {
            drawer.lineSegment(x, panelBounds.y, x, panelBounds.y + panelBounds.height)
            x += grid
        }
        
        var y = panelBounds.y
        while (y < panelBounds.y + panelBounds.height) {
            drawer.lineSegment(panelBounds.x, y, panelBounds.x + panelBounds.width, y)
            y += grid
        }
    }
    
    fun export600x800(drawer: Drawer, filename: String) {
        val rt = renderTarget(600, 800) {
            colorBuffer()
            depthBuffer(DepthFormat.DEPTH24_STENCIL8)
        }
        
        drawer.isolatedWithTarget(rt) {
            renderBackground(this)
            renderPanel(this)
            renderLegend(this)
        }
        
        rt.colorBuffer(0).saveToFile(File(filename))
        rt.destroy()
        
        println("Exported $filename")
    }
}

// =============================================================================
// MAIN APPLICATION
// =============================================================================

fun main() = application {
    configure {
        width = 600
        height = 800
        title = "Circuit Floorplan Manual Page"
    }
    
    program {
        var params = FloorplanParams()
        var floorplan = CircuitFloorplan(params)
        
        keyboard.keyDown.listen {
            when (it.name) {
                "r" -> {
                    // Reseed layout
                    params = params.copy(seed = Random.nextLong())
                    floorplan = CircuitFloorplan(params)
                    println("Reseeded: ${params.seed}")
                }
                "d" -> {
                    // Toggle debug overlay
                    params = params.copy(showDebug = !params.showDebug)
                    floorplan = CircuitFloorplan(params)
                    println("Debug: ${params.showDebug}")
                }
                "c" -> {
                    // Toggle color accents
                    params = params.copy(showAccents = !params.showAccents)
                    floorplan = CircuitFloorplan(params)
                    println("Accents: ${params.showAccents}")
                }
                "e" -> {
                    // Export PNG 600x800
                    val filename = "images/circuit_${params.seed}_${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.png"
                    floorplan.export600x800(drawer, filename)
                }
            }
        }
        
        extend {
            floorplan.renderBackground(drawer)
            floorplan.renderPanel(drawer)
            floorplan.renderDebugOverlay(drawer)
            floorplan.renderLegend(drawer)
        }
    }
}
