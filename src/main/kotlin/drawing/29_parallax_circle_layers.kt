package drawing

import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.color.rgb
import org.openrndr.draw.*
import org.openrndr.math.Vector2
import org.openrndr.shape.Circle
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.*
import kotlin.random.Random

// =============================================================================
// PARALLAX CIRCLE LAYERS - Generative Art Sketch
// =============================================================================
// Renders 2-3 depth layers of circle fields, each with its own size distribution,
// density, and motion/parallax factor. Creates a clean, poster-like composition
// where near/far layers separate clearly via scale, opacity, and drift.

// =============================================================================
// ENUMS
// =============================================================================

/**
 * Parallax input mode
 */
enum class ParallaxMode {
    MOUSE,     // Follow mouse position
    AUTO_PAN   // Sinusoidal auto-pan
}

// =============================================================================
// DATA CLASSES
// =============================================================================

/**
 * Global parameters controlling the sketch
 */
data class ParallaxParams(
    val seed: Long = System.currentTimeMillis(),
    val layerCount: Int = 3,              // 2 or 3 layers
    val densityMul: Double = 1.0,         // Global density multiplier
    val sizeMul: Double = 1.0,            // Global size multiplier
    val parallaxMode: ParallaxMode = ParallaxMode.AUTO_PAN,
    val showDebug: Boolean = false,
    val depthFog: Boolean = true          // Apply vertical gradient tint
)

/**
 * Specification for a single layer
 */
data class LayerSpec(
    val count: Int,                       // Number of circles (density)
    val rMin: Double,                     // Minimum radius
    val rMax: Double,                     // Maximum radius
    val alpha: Double,                    // Opacity
    val parallaxFactor: Double,           // How much it shifts (0-1)
    val strokeWidth: Double,              // Stroke thickness
    val fillMode: Boolean,                // true = filled, false = stroke only
    val layerIndex: Int                   // Layer order (0 = far, higher = near)
)

// =============================================================================
// COLOR PALETTE
// =============================================================================

val PARALLAX_BACKGROUND: ColorRGBa = rgb(0.95, 0.94, 0.92)    // Warm off-white
val PARALLAX_FAR_COLOR: ColorRGBa = rgb(0.70, 0.72, 0.78)     // Faint blue-gray
val PARALLAX_MID_COLOR: ColorRGBa = rgb(0.45, 0.48, 0.55)     // Medium gray-blue
val PARALLAX_NEAR_COLOR: ColorRGBa = rgb(0.12, 0.15, 0.22)    // Dark near-black
val PARALLAX_ACCENT: ColorRGBa = rgb(0.85, 0.45, 0.25)        // Warm accent

// =============================================================================
// LAYER SPECIFICATION BUILDER
// =============================================================================

/**
 * Build layer specifications based on parameters
 * Each layer has distinct count, radius range, alpha, parallax factor
 */
fun buildLayerSpecs(params: ParallaxParams): List<LayerSpec> {
    val specs = mutableListOf<LayerSpec>()
    
    // Far layer: many small circles, faint, low parallax
    specs.add(
        LayerSpec(
            count = (80 * params.densityMul).toInt(),
            rMin = 4.0 * params.sizeMul,
            rMax = 12.0 * params.sizeMul,
            alpha = 0.35,
            parallaxFactor = 0.18,
            strokeWidth = 0.8,
            fillMode = false,  // Stroke only for far layer (simulates blur)
            layerIndex = 0
        )
    )
    
    // Mid layer (only if 3 layers): medium circles, medium density
    if (params.layerCount >= 3) {
        specs.add(
            LayerSpec(
                count = (35 * params.densityMul).toInt(),
                rMin = 14.0 * params.sizeMul,
                rMax = 28.0 * params.sizeMul,
                alpha = 0.55,
                parallaxFactor = 0.45,
                strokeWidth = 1.2,
                fillMode = true,
                layerIndex = 1
            )
        )
    }
    
    // Near layer: fewer large circles, stronger contrast, high parallax
    specs.add(
        LayerSpec(
            count = (15 * params.densityMul).toInt(),
            rMin = 25.0 * params.sizeMul,
            rMax = 55.0 * params.sizeMul,
            alpha = 0.85,
            parallaxFactor = 0.88,
            strokeWidth = 2.0,
            fillMode = true,
            layerIndex = if (params.layerCount >= 3) 2 else 1
        )
    )
    
    return specs
}

// =============================================================================
// POISSON-DISC SAMPLING
// =============================================================================

/**
 * Generate circle field using Poisson-disc sampling
 * Creates pleasing spacing with minimal overlaps
 */
fun generateCircleField(
    spec: LayerSpec,
    params: ParallaxParams,
    width: Int,
    height: Int
): List<Circle> {
    val rng = Random(params.seed + spec.layerIndex * 7919L)
    val circles = mutableListOf<Circle>()
    
    // Expand bounds for parallax wrapping (generate in larger area)
    val expandFactor = 1.3
    val expandedWidth = (width * expandFactor).toInt()
    val expandedHeight = (height * expandFactor).toInt()
    val offsetX = -(expandedWidth - width) / 2.0
    val offsetY = -(expandedHeight - height) / 2.0
    
    // Safe margin (6-8% of canvas)
    val margin = min(width, height) * 0.07
    
    // Minimum distance between circle centers (based on max radius)
    // Allow more overlap for far layer, less for near
    val overlapFactor = when (spec.layerIndex) {
        0 -> 0.6    // Far: allow more overlap
        1 -> 0.8    // Mid: moderate
        else -> 1.0 // Near: minimal overlap
    }
    val minDist = spec.rMax * overlapFactor * 1.8
    
    // Poisson-disc sampling parameters
    val k = 30  // Samples before rejection
    val cellSize = minDist / sqrt(2.0)
    val gridWidth = ceil(expandedWidth / cellSize).toInt()
    val gridHeight = ceil(expandedHeight / cellSize).toInt()
    
    // Grid for spatial lookup (-1 = empty)
    val grid = Array(gridWidth * gridHeight) { -1 }
    val active = mutableListOf<Int>()
    
    fun gridIndex(x: Double, y: Double): Int {
        val gx = ((x - offsetX) / cellSize).toInt().coerceIn(0, gridWidth - 1)
        val gy = ((y - offsetY) / cellSize).toInt().coerceIn(0, gridHeight - 1)
        return gy * gridWidth + gx
    }
    
    fun isValidPosition(x: Double, y: Double, radius: Double): Boolean {
        val gx = ((x - offsetX) / cellSize).toInt()
        val gy = ((y - offsetY) / cellSize).toInt()
        
        // Check 5x5 neighborhood
        for (dy in -2..2) {
            for (dx in -2..2) {
                val nx = gx + dx
                val ny = gy + dy
                if (nx >= 0 && nx < gridWidth && ny >= 0 && ny < gridHeight) {
                    val idx = grid[ny * gridWidth + nx]
                    if (idx >= 0) {
                        val other = circles[idx]
                        val dist = Vector2(x, y).distanceTo(other.center)
                        val minRequired = (radius + other.radius) * overlapFactor
                        if (dist < minRequired) return false
                    }
                }
            }
        }
        return true
    }
    
    // Start with a random point
    val startX = offsetX + margin + rng.nextDouble() * (expandedWidth - 2 * margin)
    val startY = offsetY + margin + rng.nextDouble() * (expandedHeight - 2 * margin)
    val startRadius = spec.rMin + rng.nextDouble() * (spec.rMax - spec.rMin)
    
    circles.add(Circle(Vector2(startX, startY), startRadius))
    grid[gridIndex(startX, startY)] = 0
    active.add(0)
    
    // Generate points
    while (active.isNotEmpty() && circles.size < spec.count * 3) {
        val randIdx = rng.nextInt(active.size)
        val parentIdx = active[randIdx]
        val parent = circles[parentIdx]
        
        var found = false
        for (attempt in 0 until k) {
            val angle = rng.nextDouble() * 2 * PI
            val dist = minDist + rng.nextDouble() * minDist
            val newX = parent.center.x + cos(angle) * dist
            val newY = parent.center.y + sin(angle) * dist
            
            // Check bounds (with margin)
            if (newX < offsetX + margin || newX > offsetX + expandedWidth - margin ||
                newY < offsetY + margin || newY > offsetY + expandedHeight - margin) {
                continue
            }
            
            val newRadius = spec.rMin + rng.nextDouble() * (spec.rMax - spec.rMin)
            
            if (isValidPosition(newX, newY, newRadius)) {
                val newCircle = Circle(Vector2(newX, newY), newRadius)
                val newIdx = circles.size
                circles.add(newCircle)
                grid[gridIndex(newX, newY)] = newIdx
                active.add(newIdx)
                found = true
                break
            }
        }
        
        if (!found) {
            active.removeAt(randIdx)
        }
        
        // Stop if we have enough circles
        if (circles.size >= spec.count) break
    }
    
    // Take only the requested count
    return circles.shuffled(rng).take(spec.count)
}

// =============================================================================
// PARALLAX FUNCTIONS
// =============================================================================

/**
 * Calculate camera offset from input
 * Returns normalized offset in range [-1, 1]
 */
fun getCameraOffset(
    params: ParallaxParams,
    mousePos: Vector2,
    canvasCenter: Vector2,
    time: Double
): Vector2 {
    return when (params.parallaxMode) {
        ParallaxMode.MOUSE -> {
            val dx = (mousePos.x - canvasCenter.x) / canvasCenter.x
            val dy = (mousePos.y - canvasCenter.y) / canvasCenter.y
            Vector2(dx.coerceIn(-1.0, 1.0), dy.coerceIn(-1.0, 1.0))
        }
        ParallaxMode.AUTO_PAN -> {
            // Slow sinusoidal pan
            val panSpeed = 0.15
            Vector2(
                sin(time * panSpeed * 2 * PI) * 0.7,
                cos(time * panSpeed * 1.5 * PI) * 0.5
            )
        }
    }
}

/**
 * Apply parallax shift to a position
 * Near layers shift more, far layers shift less
 */
fun applyParallax(
    pos: Vector2,
    spec: LayerSpec,
    cam: Vector2,
    maxShift: Double = 40.0
): Vector2 {
    val shift = cam * spec.parallaxFactor * maxShift
    return pos + shift
}

// =============================================================================
// RENDERING FUNCTIONS
// =============================================================================

/**
 * Get color for a layer
 */
fun getLayerColor(spec: LayerSpec, params: ParallaxParams): ColorRGBa {
    return when (spec.layerIndex) {
        0 -> PARALLAX_FAR_COLOR
        1 -> if (params.layerCount >= 3) PARALLAX_MID_COLOR else PARALLAX_NEAR_COLOR
        else -> PARALLAX_NEAR_COLOR
    }
}

/**
 * Render a single layer of circles with parallax applied
 */
fun renderLayer(
    drawer: Drawer,
    circles: List<Circle>,
    spec: LayerSpec,
    cam: Vector2,
    params: ParallaxParams,
    width: Int,
    height: Int
) {
    val baseColor = getLayerColor(spec, params)
    
    // Apply depth fog (vertical gradient that affects far layers more)
    val applyFog = params.depthFog && spec.layerIndex == 0
    
    for (circle in circles) {
        // Apply parallax shift
        val shiftedPos = applyParallax(circle.center, spec, cam)
        
        // Skip if completely outside canvas (with some buffer)
        val buffer = circle.radius * 2
        if (shiftedPos.x < -buffer || shiftedPos.x > width + buffer ||
            shiftedPos.y < -buffer || shiftedPos.y > height + buffer) {
            continue
        }
        
        // Calculate alpha with optional fog effect
        var alpha = spec.alpha
        if (applyFog) {
            // Fade more at top of canvas
            val fogFactor = 1.0 - (shiftedPos.y / height) * 0.3
            alpha *= fogFactor.coerceIn(0.5, 1.0)
        }
        
        // Draw circle
        if (spec.fillMode) {
            drawer.fill = baseColor.opacify(alpha)
            drawer.stroke = null
        } else {
            drawer.fill = null
            drawer.stroke = baseColor.opacify(alpha)
            drawer.strokeWeight = spec.strokeWidth
        }
        
        drawer.circle(shiftedPos, circle.radius)
    }
}

/**
 * Render the complete poster
 */
fun renderParallaxPoster(
    drawer: Drawer,
    params: ParallaxParams,
    cam: Vector2,
    circlesByLayer: Map<Int, List<Circle>>,
    specs: List<LayerSpec>,
    width: Int,
    height: Int
) {
    // Background
    drawer.clear(PARALLAX_BACKGROUND)
    
    // Render layers back-to-front (far first, near last)
    for (spec in specs.sortedBy { it.layerIndex }) {
        val circles = circlesByLayer[spec.layerIndex] ?: continue
        renderLayer(drawer, circles, spec, cam, params, width, height)
    }
}

/**
 * Render debug overlay
 */
fun renderParallaxDebugOverlay(
    drawer: Drawer,
    params: ParallaxParams,
    cam: Vector2,
    specs: List<LayerSpec>,
    circlesByLayer: Map<Int, List<Circle>>,
    width: Int,
    height: Int
) {
    if (!params.showDebug) return
    
    // Draw layer bounds indicators
    drawer.stroke = ColorRGBa.RED.opacify(0.5)
    drawer.strokeWeight = 1.0
    drawer.fill = null
    
    val margin = min(width, height) * 0.07
    drawer.rectangle(margin, margin, width - 2 * margin, height - 2 * margin)
    
    // Draw camera vector
    val cx = width / 2.0
    val cy = height / 2.0
    drawer.stroke = ColorRGBa.GREEN.opacify(0.8)
    drawer.strokeWeight = 2.0
    drawer.lineSegment(Vector2(cx, cy), Vector2(cx + cam.x * 50, cy + cam.y * 50))
    drawer.circle(cx + cam.x * 50, cy + cam.y * 50, 5.0)
    
    // Info text
    drawer.fill = PARALLAX_NEAR_COLOR.opacify(0.9)
    drawer.stroke = null
    
    val modeStr = if (params.parallaxMode == ParallaxMode.MOUSE) "Mouse" else "Auto"
    var y = 20.0
    drawer.text("Parallax Mode: $modeStr  Layers: ${params.layerCount}", 10.0, y)
    y += 18.0
    drawer.text("Camera: (${String.format("%.2f", cam.x)}, ${String.format("%.2f", cam.y)})", 10.0, y)
    y += 18.0
    drawer.text("Density: ${String.format("%.1f", params.densityMul)}x  Size: ${String.format("%.1f", params.sizeMul)}x", 10.0, y)
    y += 18.0
    
    for (spec in specs.sortedBy { it.layerIndex }) {
        val count = circlesByLayer[spec.layerIndex]?.size ?: 0
        val layerName = when (spec.layerIndex) {
            0 -> "Far"
            1 -> if (params.layerCount >= 3) "Mid" else "Near"
            else -> "Near"
        }
        drawer.text("$layerName: $count circles, pF=${String.format("%.2f", spec.parallaxFactor)}", 10.0, y)
        y += 18.0
    }
}

// =============================================================================
// EXPORT FUNCTION
// =============================================================================

/**
 * Export PNG at exactly 600x800
 */
fun exportParallax600x800(
    drawer: Drawer,
    params: ParallaxParams,
    circlesByLayer: Map<Int, List<Circle>>,
    specs: List<LayerSpec>
) {
    val rt = renderTarget(600, 800) {
        colorBuffer()
        depthBuffer(DepthFormat.DEPTH24_STENCIL8)
    }
    
    // Use centered camera for export (no parallax shift)
    val cam = Vector2.ZERO
    
    drawer.isolatedWithTarget(rt) {
        ortho(rt)
        renderParallaxPoster(this, params, cam, circlesByLayer, specs, 600, 800)
    }
    
    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val filename = "parallax_circles_s${params.seed}_L${params.layerCount}_$timestamp.png"
    
    File("images").mkdirs()
    rt.colorBuffer(0).saveToFile(File("images/$filename"))
    rt.destroy()
    
    println("Exported: images/$filename")
}

// =============================================================================
// MAIN APPLICATION
// =============================================================================

fun main() = application {
    configure {
        width = 1000
        height = 800
        title = "Parallax Circle Layers"
    }
    
    program {
        var params = ParallaxParams()
        var specs = buildLayerSpecs(params)
        var circlesByLayer = mutableMapOf<Int, List<Circle>>()
        
        // Generate initial circles
        fun regenerateCircles() {
            specs = buildLayerSpecs(params)
            circlesByLayer.clear()
            for (spec in specs) {
                circlesByLayer[spec.layerIndex] = generateCircleField(spec, params, width, height)
            }
        }
        
        regenerateCircles()
        
        var statusMessage = ""
        var statusTime = 0.0
        
        fun showStatus(msg: String) {
            statusMessage = msg
            statusTime = seconds
        }
        
        keyboard.keyDown.listen { event ->
            when (event.name) {
                // R: Reseed
                "r" -> {
                    params = params.copy(seed = Random.nextLong())
                    regenerateCircles()
                    showStatus("Reseeded: ${params.seed}")
                }
                
                // 2/3: Switch number of layers
                "2" -> {
                    params = params.copy(layerCount = 2)
                    regenerateCircles()
                    showStatus("Layers: 2 (far + near)")
                }
                "3" -> {
                    params = params.copy(layerCount = 3)
                    regenerateCircles()
                    showStatus("Layers: 3 (far + mid + near)")
                }
                
                // [ ]: Adjust global density multiplier
                "[" -> {
                    val newDensity = (params.densityMul - 0.2).coerceIn(0.2, 3.0)
                    params = params.copy(densityMul = newDensity)
                    regenerateCircles()
                    showStatus("Density: ${String.format("%.1f", newDensity)}x")
                }
                "]" -> {
                    val newDensity = (params.densityMul + 0.2).coerceIn(0.2, 3.0)
                    params = params.copy(densityMul = newDensity)
                    regenerateCircles()
                    showStatus("Density: ${String.format("%.1f", newDensity)}x")
                }
                
                // - =: Adjust global size multiplier
                "-" -> {
                    val newSize = (params.sizeMul - 0.1).coerceIn(0.3, 2.5)
                    params = params.copy(sizeMul = newSize)
                    regenerateCircles()
                    showStatus("Size: ${String.format("%.1f", newSize)}x")
                }
                "=" -> {
                    val newSize = (params.sizeMul + 0.1).coerceIn(0.3, 2.5)
                    params = params.copy(sizeMul = newSize)
                    regenerateCircles()
                    showStatus("Size: ${String.format("%.1f", newSize)}x")
                }
                
                // P: Toggle parallax input mode
                "p" -> {
                    val newMode = if (params.parallaxMode == ParallaxMode.MOUSE) 
                        ParallaxMode.AUTO_PAN else ParallaxMode.MOUSE
                    params = params.copy(parallaxMode = newMode)
                    val modeName = if (newMode == ParallaxMode.MOUSE) "Mouse" else "Auto-pan"
                    showStatus("Parallax: $modeName")
                }
                
                // D: Toggle debug overlay
                "d" -> {
                    params = params.copy(showDebug = !params.showDebug)
                    showStatus("Debug: ${if (params.showDebug) "ON" else "OFF"}")
                }
                
                // F: Toggle depth fog
                "f" -> {
                    params = params.copy(depthFog = !params.depthFog)
                    showStatus("Depth fog: ${if (params.depthFog) "ON" else "OFF"}")
                }
                
                // E: Export PNG
                "e" -> {
                    exportParallax600x800(drawer, params, circlesByLayer, specs)
                    showStatus("Exported PNG")
                }
            }
        }
        
        extend {
            // Calculate camera offset
            val canvasCenter = Vector2(width / 2.0, height / 2.0)
            val cam = getCameraOffset(params, mouse.position, canvasCenter, seconds)
            
            // Render
            renderParallaxPoster(drawer, params, cam, circlesByLayer, specs, width, height)
            renderParallaxDebugOverlay(drawer, params, cam, specs, circlesByLayer, width, height)
            
            // Status message
            if (statusMessage.isNotEmpty() && seconds - statusTime < 2.5) {
                drawer.fill = PARALLAX_NEAR_COLOR.opacify(0.85)
                drawer.stroke = null
                drawer.rectangle(5.0, height - 28.0, statusMessage.length * 8.0 + 10.0, 22.0)
                drawer.fill = ColorRGBa.WHITE
                drawer.text(statusMessage, 10.0, height - 12.0)
            }
        }
    }
}
