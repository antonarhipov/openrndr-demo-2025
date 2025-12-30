package drawing

import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.*
import org.openrndr.extra.noise.Random
import org.openrndr.extra.noise.simplex
import org.openrndr.ffmpeg.ScreenRecorder
import org.openrndr.math.Vector2
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.*

/**
 * Nearest-on-the-Rim
 * A generative art sketch where points move along circular paths in a grid.
 * Connections are drawn between points with the closest angular phase (arc distance).
 */

data class NearestOnTheRimParams(
    var seed: Long = (Math.random() * 1000000).toLong(),
    val width: Int = 600,
    val height: Int = 800,
    var rows: Int = 8,
    var cols: Int = 6,
    var baseRadius: Double = 30.0,
    var radiusJitter: Double = 10.0,
    var gap: Double = 15.0,
    var speedRange: ClosedRange<Double> = 0.5..2.0,
    var neighborCount: Int = 3,
    var backgroundDark: Boolean = true,
    var showOutlines: Boolean = true,
    var useNoise: Boolean = false,
    var debugMode: Boolean = false,
    var alternateDirection: Boolean = true
)

class CircleAgent(
    val id: Int,
    val center: Vector2,
    val radius: Double,
    val theta0: Double,
    val speed: Double
) {
    fun position(t: Double, useNoise: Boolean, globalSeed: Long): Vector2 {
        val theta = thetaAt(t, useNoise, globalSeed)
        return center + Vector2(cos(theta), sin(theta)) * radius
    }

    fun thetaAt(t: Double, useNoise: Boolean, globalSeed: Long): Double {
        val drift = if (useNoise) {
            simplex(globalSeed.toInt() + id, t * 0.2) * 1.5
        } else {
            0.0
        }
        return theta0 + speed * t + drift
    }

    fun normalizedAngle(t: Double, useNoise: Boolean, globalSeed: Long): Double {
        var a = thetaAt(t, useNoise, globalSeed) % (2.0 * PI)
        if (a < 0) a += 2.0 * PI
        return a
    }
}

fun arcDistance(a1: Double, a2: Double): Double {
    val diff = abs(a1 - a2)
    return min(diff, 2.0 * PI - diff)
}

fun main() = application {
    configure {
        width = 600
        height = 800
        title = "Nearest-on-the-Rim"
    }

    program {
        var params = NearestOnTheRimParams()
        var agents = mutableListOf<CircleAgent>()

        val rt = renderTarget(params.width, params.height) {
            colorBuffer()
        }

        fun rebuild() {
            Random.seed = params.seed.toString()
            agents.clear()
            
            val margin = 40.0
            val availableWidth = params.width - 2 * margin
            val availableHeight = params.height - 2 * margin
            
            val cellW = availableWidth / params.cols
            val cellH = availableHeight / params.rows
            
            var idCounter = 0
            for (r in 0 until params.rows) {
                for (c in 0 until params.cols) {
                    val centerX = margin + cellW * (c + 0.5)
                    val centerY = margin + cellH * (r + 0.5)
                    
                    // Enforce spacing: center spacing >= 2*r + gap
                    val maxR = min(cellW, cellH) / 2.0 - params.gap / 2.0
                    val radius = (params.baseRadius + Random.double(-params.radiusJitter, params.radiusJitter)).coerceIn(5.0, maxR)
                    
                    val theta0 = Random.double(0.0, 2.0 * PI)
                    val speedBase = Random.double(params.speedRange.start, params.speedRange.endInclusive)
                    val direction = if (params.alternateDirection) {
                        if ((r + c) % 2 == 0) 1.0 else -1.0
                    } else {
                        1.0
                    }
                    val speed = speedBase * direction
                    
                    agents.add(CircleAgent(idCounter++, Vector2(centerX, centerY), radius, theta0, speed))
                }
            }
        }

        rebuild()
        
        val recorder = ScreenRecorder().apply {
            enabled = false
        }
        extend(recorder)

        var exportRequested = false

        keyboard.keyDown.listen {
            when (it.name) {
                "v" -> {
                    recorder.enabled = !recorder.enabled
                    if (recorder.enabled) println("Recording started") else println("Recording paused")
                }
                "r" -> {
                    params.seed = (Math.random() * 1000000).toLong()
                    rebuild()
                }
                "b" -> params.backgroundDark = !params.backgroundDark
                "c" -> params.showOutlines = !params.showOutlines
                "n" -> params.useNoise = !params.useNoise
                "d" -> params.debugMode = !params.debugMode
                "e" -> {
                    exportRequested = true
                }
                "s" -> {
                    params.alternateDirection = !params.alternateDirection
                    rebuild()
                }
            }
        }

        extend {
            val bg = if (params.backgroundDark) ColorRGBa.fromHex("#121212") else ColorRGBa.fromHex("#F5F5F5")
            val circleColor = if (params.backgroundDark) ColorRGBa.WHITE.opacify(0.15) else ColorRGBa.BLACK.opacify(0.15)
            val pointColor = if (params.backgroundDark) ColorRGBa.WHITE else ColorRGBa.BLACK
            val lineColor = if (params.backgroundDark) ColorRGBa.WHITE.opacify(0.3) else ColorRGBa.BLACK.opacify(0.3)

            // Precompute positions and angles
            val positions = agents.map { it.position(seconds, params.useNoise, params.seed) }
            val angles = agents.map { it.normalizedAngle(seconds, params.useNoise, params.seed) }

            fun drawScene(d: Drawer) {
                d.clear(bg)

                // Draw connections
                d.strokeWeight = 0.5
                for (i in agents.indices) {
                    val neighbors = agents.indices.filter { it != i }
                        .map { j -> j to arcDistance(angles[i], angles[j]) }
                        .sortedBy { it.second }
                        .take(params.neighborCount)

                    for ((j, dArc) in neighbors) {
                        // weight/opacity inversely proportional to d_arc
                        val opacity = (1.0 - dArc / PI).coerceIn(0.0, 1.0) * 0.4
                        d.stroke = lineColor.opacify(opacity)
                        d.lineSegment(positions[i], positions[j])
                    }
                }

                // Draw circles
                if (params.showOutlines) {
                    d.fill = null
                    d.stroke = circleColor
                    d.strokeWeight = 1.0
                    agents.forEach {
                        d.circle(it.center, it.radius)
                    }
                }

                // Draw points
                d.stroke = null
                d.fill = pointColor
                positions.forEach {
                    d.circle(it, 3.0)
                }

                if (params.debugMode) {
                    val mousePos = mouse.position
                    val nearestIdx = agents.indices.minByOrNull { positions[it].distanceTo(mousePos) } ?: -1
                    
                    d.fill = ColorRGBa.RED
                    agents.forEachIndexed { index, agent ->
                        d.text("$index", agent.center.x - 5, agent.center.y + 5)
                    }
                    
                    if (nearestIdx != -1) {
                        d.fill = if (params.backgroundDark) ColorRGBa.YELLOW else ColorRGBa.BLUE
                        d.text("Selected: $nearestIdx", 20.0, 20.0)
                        for (i in agents.indices) {
                            if (i == nearestIdx) continue
                            val dist = arcDistance(angles[nearestIdx], angles[i])
                            d.text(String.format("%.2f", dist), positions[i].x, positions[i].y - 10.0)
                        }
                    }
                }

                if (recorder.enabled) {
                    d.isolated {
                        d.fill = ColorRGBa.RED
                        d.stroke = null
                        d.circle(d.width - 30.0, 30.0, 8.0)
                    }
                }
            }

            if (exportRequested) {
                drawer.isolatedWithTarget(rt) {
                    drawScene(this)
                }
                val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                val fileName = "images/nearest_on_the_rim_${params.seed}_$timestamp.png"
                val file = File(fileName)
                if (!file.parentFile.exists()) file.parentFile.mkdirs()
                rt.colorBuffer(0).saveToFile(file)
                println("Exported to $fileName")
                exportRequested = false
            }

            drawScene(drawer)
        }
    }
}
