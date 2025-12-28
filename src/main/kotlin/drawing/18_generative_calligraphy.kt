package drawing

import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.*
import org.openrndr.extra.shapes.hobbyCurve
import org.openrndr.math.Vector2
import org.openrndr.math.smoothstep
import org.openrndr.shape.Rectangle
import org.openrndr.shape.ShapeContour
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.*
import kotlin.random.Random

// ==========================================
// Generative Katakana Calligraphy (カタカナ書道)
// ==========================================
// This sketch creates calligraphic compositions of Japanese Katakana characters
// using Hobby curves as the stroke skeleton and a brush-like variable-width
// ribbon rendering that mimics pressure-sensitive ink.
// Curvature relates to bending energy; Hobby produces a low-bending-energy spline.

// ==========================================
// Data Structures
// ==========================================

/**
 * Sample along a curve with position, tangent, normal, arc-length parameter s (0-1), and curvature κ
 */
data class CalligraphySample(
    val pos: Vector2,
    val tangent: Vector2,
    val normal: Vector2,
    val s: Double,        // arc-length parameter [0,1]
    val curvature: Double // κ(s)
)

/**
 * Parameters controlling the generative calligraphy composition
 */
data class CalligraphyParams(
    val seed: Long,
    val tension: Double,           // Hobby curve tension (default ~1.1)
    val pointCount: Int,           // control points per stroke (8-40)
    val strokeCount: Int,          // major strokes (1-3)
    val wMin: Double,              // minimum stroke width
    val wMax: Double,              // maximum stroke width
    val curvGain: Double,          // curvature→width mapping strength
    val washPasses: Int,           // ink bleed passes (2-6)
    val paletteMode: Int,          // 0 = sumi ink, 1 = indigo
    val echoCount: Int,            // secondary echo strokes
    val pressureFreq: Double,      // pressure wave frequency
    val taperAmount: Double,       // endpoint taper strength
    val katakanaIndex: Int = 0,    // which Katakana character to draw (0-45)
    // === INK SIMULATION PARAMETERS ===
    val useInkSim: Boolean = true,           // Enable physics-based ink simulation
    val simIterations: Int = 12,             // Diffusion iterations per stroke
    val wetDiffusion: Double = 0.35,         // Wetness diffusion rate
    val pigDiffusion: Double = 0.08,         // Pigment diffusion rate (weaker than wetness)
    val absorbRate: Double = 0.12,           // Paper absorption rate
    val bleedStrength: Double = 0.15,        // Pigment advection with wetness gradient
    val inkLoad: Double = 0.9,               // Initial ink concentration (0-1)
    val waterLoad: Double = 0.6,             // Initial water amount (drives bleed/bloom)
    val kasureThreshold: Double = 0.45,      // Paper height threshold for dry-brush breaks
    val sheenStrength: Double = 0.25,        // Oily sheen intensity on pooled areas
    val splashChance: Double = 0.15          // Probability of splashes at high curvature
)

// ==========================================
// INK SIMULATION SYSTEM (墨のシミュレーション)
// ==========================================
// Model ink on paper as a small physical simulation with 3 coupled layers:
// 1. Pigment (RGBA): how dark the ink is on the surface
// 2. Wetness (R): how much liquid is present (drives diffusion/bloom)
// 3. Paper field (RG): static texture controlling absorption + fiber direction
//    - paperHeight: how "bumpy" the paper is (creates kasure gaps)
//    - fiberDir: a 2D direction field (makes bleed anisotropic)

/**
 * Ink simulation using ping-pong render targets and GPU shaders.
 * Simulates realistic sumi-e ink behavior: bleed, kasure, pooling, and sheen.
 */
class InkSimulation(val width: Int, val height: Int, seed: Long) {
    // Ping-pong simulation buffers
    // RGB = pigment intensity, A = wetness
    private var rtA: RenderTarget
    private var rtB: RenderTarget
    
    // Paper texture: R = height/absorption, G = fiber direction X, B = fiber direction Y
    val paperTexture: ColorBuffer
    
    // Brush stamp texture (soft circular brush with bristle texture)
    val brushTexture: ColorBuffer
    
    // Shaders
    private lateinit var depositShader: ShadeStyle
    private lateinit var simShader: ShadeStyle
    private lateinit var compositeShader: ShadeStyle
    
    // State
    var currentBuffer: RenderTarget
        private set
    
    init {
        // Create simulation render targets with floating point precision for smooth diffusion
        rtA = renderTarget(width, height) {
            colorBuffer(ColorFormat.RGBa, ColorType.FLOAT32)
        }
        rtB = renderTarget(width, height) {
            colorBuffer(ColorFormat.RGBa, ColorType.FLOAT32)
        }
        currentBuffer = rtA
        
        // Generate paper texture
        paperTexture = generatePaperTexture(width, height, seed)
        
        // Generate brush stamp texture
        brushTexture = generateBrushTexture(128, seed)
        
        // Initialize shaders
        initShaders()
        
        // Note: buffers start cleared (TRANSPARENT)
    }
    
    private fun generatePaperTexture(w: Int, h: Int, seed: Long): ColorBuffer {
        val buffer = colorBuffer(w, h, format = ColorFormat.RGBa, type = ColorType.FLOAT32)
        val shadow = buffer.shadow
        shadow.download()
        
        val rng = Random(seed)
        
        // Generate paper field with height variation and fiber direction
        for (y in 0 until h) {
            for (x in 0 until w) {
                // Paper height: multi-octave noise for realistic paper tooth
                val nx = x.toDouble() / w
                val ny = y.toDouble() / h
                
                // Base height from noise
                val height1 = simplexNoise2D(nx * 8.0, ny * 8.0, seed) * 0.5 + 0.5
                val height2 = simplexNoise2D(nx * 25.0, ny * 25.0, seed + 100) * 0.3 + 0.5
                val height3 = simplexNoise2D(nx * 60.0, ny * 60.0, seed + 200) * 0.2 + 0.5
                val paperHeight = (height1 * 0.5 + height2 * 0.35 + height3 * 0.15).coerceIn(0.0, 1.0)
                
                // Fiber direction: slightly anisotropic, mostly horizontal with variation
                val fiberAngle = (simplexNoise2D(nx * 4.0, ny * 4.0, seed + 300) * 0.5 + 0.5) * PI * 0.3
                val fiberX = cos(fiberAngle) * 0.5 + 0.5  // Encode as 0-1
                val fiberY = sin(fiberAngle) * 0.5 + 0.5
                
                // Absorption rate: varies with paper height (higher = less absorbent)
                val absorption = 0.8 - paperHeight * 0.4 + rng.nextDouble() * 0.1
                
                shadow[x, y] = ColorRGBa(paperHeight, fiberX, fiberY, absorption)
            }
        }
        
        shadow.upload()
        return buffer
    }
    
    private fun generateBrushTexture(size: Int, seed: Long): ColorBuffer {
        val buffer = colorBuffer(size, size, format = ColorFormat.RGBa, type = ColorType.FLOAT32)
        val shadow = buffer.shadow
        shadow.download()
        
        val rng = Random(seed)
        val center = size / 2.0
        
        for (y in 0 until size) {
            for (x in 0 until size) {
                val dx = x - center
                val dy = y - center
                val dist = sqrt(dx * dx + dy * dy) / center
                
                // Soft circular falloff with bristle texture
                val baseFalloff = 1.0 - smoothstep(0.0, 1.0, dist)
                
                // Add bristle variation (radial texture)
                val angle = atan2(dy, dx)
                val bristleNoise = simplexNoise2D(angle * 4.0, dist * 3.0, seed + 500) * 0.3
                
                // Edge bristle separation
                val edgeBristles = if (dist > 0.7) {
                    val bristleFreq = 20.0
                    val bristle = sin(angle * bristleFreq + rng.nextDouble() * PI)
                    (bristle * 0.5 + 0.5) * 0.5
                } else 1.0
                
                val intensity = (baseFalloff + bristleNoise).coerceIn(0.0, 1.0) * edgeBristles
                
                shadow[x, y] = ColorRGBa(intensity, intensity, intensity, intensity)
            }
        }
        
        shadow.upload()
        return buffer
    }
    
    private fun initShaders() {
        // Deposit shader: stamps brush into simulation buffer
        depositShader = shadeStyle {
            fragmentTransform = """
                // Brush stamp parameters passed as uniforms
                vec2 brushPos = p_brushPos;
                float brushRadius = p_brushRadius;
                float inkAmount = p_inkAmount;
                float waterAmount = p_waterAmount;
                float pressure = p_pressure;
                float rotation = p_rotation;
                
                vec2 fragPos = c_boundsPosition.xy * vec2(p_width, p_height);
                vec2 toBrush = fragPos - brushPos;
                
                // Rotate for brush angle
                float c = cos(rotation);
                float s = sin(rotation);
                vec2 rotated = vec2(
                    toBrush.x * c - toBrush.y * s,
                    toBrush.x * s + toBrush.y * c
                );
                
                // Scale to brush texture space
                vec2 brushUV = (rotated / brushRadius) * 0.5 + 0.5;
                
                if (brushUV.x >= 0.0 && brushUV.x <= 1.0 && brushUV.y >= 0.0 && brushUV.y <= 1.0) {
                    // Sample brush texture
                    float brushMask = texture(p_brush, brushUV).r;
                    
                    // Sample paper for kasure effect
                    vec2 paperUV = c_boundsPosition.xy;
                    vec4 paper = texture(p_paper, paperUV);
                    float paperHeight = paper.r;
                    
                    // Kasure: dry brush breaks where paper is rough
                    float dryFactor = 1.0 - waterAmount * 0.8;
                    float kasureMask = smoothstep(p_kasureThreshold, p_kasureThreshold + 0.15, paperHeight - dryFactor * 0.3);
                    
                    float effectiveMask = brushMask * kasureMask * pressure;
                    
                    // Current simulation state
                    vec4 current = texture(p_simBuffer, c_boundsPosition.xy);
                    
                    // Deposit: pigment and wetness
                    float newPigment = current.r + effectiveMask * inkAmount;
                    float newWetness = current.a + effectiveMask * waterAmount;
                    
                    x_fill = vec4(
                        min(newPigment, 1.0),
                        current.g,
                        current.b,
                        min(newWetness, 1.0)
                    );
                } else {
                    x_fill = texture(p_simBuffer, c_boundsPosition.xy);
                }
            """.trimIndent()
        }
        
        // Simulation shader: diffusion, absorption, pigment advection
        simShader = shadeStyle {
            fragmentTransform = """
                vec2 uv = c_boundsPosition.xy;
                vec2 texelSize = vec2(1.0 / p_width, 1.0 / p_height);
                
                // Current state
                vec4 current = texture(p_simBuffer, uv);
                float pigment = current.r;
                float wetness = current.a;
                
                // Sample paper field
                vec4 paper = texture(p_paper, uv);
                float paperHeight = paper.r;
                vec2 fiberDir = normalize(paper.gb * 2.0 - 1.0);
                float absorbMap = paper.a;
                
                // Sample neighbors for diffusion
                vec4 n = texture(p_simBuffer, uv + vec2(0.0, texelSize.y));
                vec4 s = texture(p_simBuffer, uv - vec2(0.0, texelSize.y));
                vec4 e = texture(p_simBuffer, uv + vec2(texelSize.x, 0.0));
                vec4 w = texture(p_simBuffer, uv - vec2(texelSize.x, 0.0));
                
                // Laplacian for wetness diffusion
                float lapWet = (n.a + s.a + e.a + w.a - 4.0 * wetness);
                
                // Fiber-guided anisotropic diffusion
                // Diffusion is stronger along fiber direction
                float fiberInfluence = 0.3;
                vec2 alongFiber = fiberDir * texelSize * 1.5;
                float wetAlongPlus = texture(p_simBuffer, uv + alongFiber).a;
                float wetAlongMinus = texture(p_simBuffer, uv - alongFiber).a;
                float fiberLap = (wetAlongPlus + wetAlongMinus - 2.0 * wetness);
                
                // Combined wetness diffusion
                float wetDiff = lapWet + fiberLap * fiberInfluence;
                wetness += p_wetDiffusion * wetDiff;
                
                // Pigment diffusion: weaker, and modulated by wetness
                float lapPig = (n.r + s.r + e.r + w.r - 4.0 * pigment);
                pigment += p_pigDiffusion * lapPig * wetness;
                
                // Pigment advection: pigment drifts with wetness gradient
                vec2 wetGrad = vec2(
                    (e.a - w.a) / (2.0 * texelSize.x),
                    (n.a - s.a) / (2.0 * texelSize.y)
                );
                float gradMag = length(wetGrad);
                if (gradMag > 0.01) {
                    vec2 advectDir = wetGrad / gradMag;
                    vec2 advectUV = uv - advectDir * texelSize * p_bleedStrength * wetness;
                    float advectedPig = texture(p_simBuffer, advectUV).r;
                    pigment = mix(pigment, advectedPig, p_bleedStrength * wetness * 0.5);
                }
                
                // Absorption: wetness decreases based on paper absorption
                wetness *= (1.0 - p_absorbRate * absorbMap);
                
                // Pigment "fixes" as wetness drops - it stops moving
                // (handled implicitly by wetness-modulated diffusion)
                
                // Clamp values
                pigment = clamp(pigment, 0.0, 1.0);
                wetness = clamp(wetness, 0.0, 1.0);
                
                x_fill = vec4(pigment, current.g, current.b, wetness);
            """.trimIndent()
        }
        
        // Composite shader: render final image with ink + paper + sheen
        compositeShader = shadeStyle {
            fragmentTransform = """
                vec2 uv = c_boundsPosition.xy;
                
                // Sample simulation result
                vec4 sim = texture(p_simBuffer, uv);
                float pigment = sim.r;
                float wetness = sim.a;
                
                // Sample paper texture for grain
                vec4 paper = texture(p_paper, uv);
                float paperHeight = paper.r;
                
                // Base paper color
                vec3 paperColor = p_paperColor;
                
                // Ink color with density variation
                vec3 inkColor = p_inkColor;
                
                // Ink density: darker where more pigment
                float inkDensity = smoothstep(0.0, 0.7, pigment);
                
                // Add subtle tame (pooling) effect: darker spots where pigment accumulated
                float pooling = smoothstep(0.6, 1.0, pigment) * 0.2;
                inkDensity += pooling;
                
                // Paper grain showing through (kasure effect)
                float grainShow = (1.0 - inkDensity) * paperHeight * 0.1;
                
                // Mix paper and ink
                vec3 color = mix(paperColor, inkColor, inkDensity);
                color += grainShow * (paperColor - vec3(0.5));
                
                // Oily sheen effect on pooled ink
                float sheen = smoothstep(0.5, 0.9, pigment) * smoothstep(0.3, 0.6, wetness);
                sheen *= p_sheenStrength;
                
                // Fake specular highlight (screen-space)
                vec2 lightDir = normalize(vec2(0.3, -0.5));
                vec2 texelSize = vec2(1.0 / p_width, 1.0 / p_height);
                float pigLeft = texture(p_simBuffer, uv - vec2(texelSize.x, 0.0)).r;
                float pigUp = texture(p_simBuffer, uv - vec2(0.0, texelSize.y)).r;
                vec2 pigGrad = vec2(pigment - pigLeft, pigment - pigUp);
                float specular = max(0.0, dot(normalize(pigGrad + vec2(0.001)), lightDir));
                specular = pow(specular, 8.0) * sheen;
                
                color += vec3(specular * 0.15);
                
                // Subtle edge darkening (ink pooling at boundaries)
                float edgeDark = smoothstep(0.3, 0.6, pigment) * (1.0 - smoothstep(0.6, 0.9, pigment)) * 0.1;
                color -= vec3(edgeDark);
                
                x_fill = vec4(clamp(color, 0.0, 1.0), 1.0);
            """.trimIndent()
        }
    }
    
    fun clear(drawer: Drawer) {
        // Clear both simulation buffers
        drawer.isolatedWithTarget(rtA) {
            drawer.clear(ColorRGBa.TRANSPARENT)
        }
        drawer.isolatedWithTarget(rtB) {
            drawer.clear(ColorRGBa.TRANSPARENT)
        }
        currentBuffer = rtA
    }
    
    // Overload for lazy initialization - just reset buffer reference
    fun clearBuffers() {
        currentBuffer = rtA
    }
    
    /**
     * Deposit ink along a stroke path.
     * Stamps brush at each sample point with speed-dependent spacing.
     */
    fun depositStroke(
        drawer: Drawer,
        samples: List<CalligraphySample>,
        params: CalligraphyParams,
        seedOffset: Long
    ) {
        if (samples.isEmpty()) return
        
        val rng = Random(params.seed + seedOffset)
        
        // Calculate speeds between samples for spacing
        val speeds = mutableListOf<Double>()
        for (i in 0 until samples.size - 1) {
            speeds.add((samples[i + 1].pos - samples[i].pos).length)
        }
        speeds.add(speeds.lastOrNull() ?: 1.0)
        val avgSpeed = speeds.average().coerceAtLeast(0.1)
        
        // Deposit stamps along the stroke
        var inkRemaining = params.inkLoad
        var waterRemaining = params.waterLoad
        
        for ((idx, sample) in samples.withIndex()) {
            val speed = speeds[idx]
            val speedRatio = speed / avgSpeed
            
            // Skip some stamps when moving fast (speed-based spacing)
            if (speedRatio > 1.5 && rng.nextDouble() < 0.3) continue
            
            // Calculate brush parameters
            val w = widthAt(sample.s, sample.curvature, params, seedOffset)
            val brushRadius = w * 0.6
            
            // Pressure based on width and curvature
            val pressure = (w / params.wMax).coerceIn(0.3, 1.0)
            
            // Tame pooling: increase wetness when slow or high curvature
            val poolingBoost = if (speedRatio < 0.7 || abs(sample.curvature) > 0.1) 1.3 else 1.0
            
            // Ink/water depletion along stroke
            val depletion = sample.s * 0.4
            val currentInk = (inkRemaining * (1.0 - depletion)).coerceAtLeast(0.1)
            val currentWater = (waterRemaining * (1.0 - depletion * 0.5) * poolingBoost).coerceAtLeast(0.1)
            
            // Brush rotation follows tangent
            val rotation = atan2(sample.tangent.y, sample.tangent.x)
            
            // Deposit this stamp
            drawer.isolatedWithTarget(rtB) {
                drawer.shadeStyle = depositShader.apply {
                    parameter("brushPos", sample.pos)
                    parameter("brushRadius", brushRadius)
                    parameter("inkAmount", currentInk)
                    parameter("waterAmount", currentWater)
                    parameter("pressure", pressure)
                    parameter("rotation", rotation)
                    parameter("width", width.toDouble())
                    parameter("height", height.toDouble())
                    parameter("brush", brushTexture)
                    parameter("paper", paperTexture)
                    parameter("simBuffer", currentBuffer.colorBuffer(0))
                    parameter("kasureThreshold", params.kasureThreshold)
                }
                drawer.fill = ColorRGBa.WHITE
                drawer.stroke = null
                drawer.rectangle(0.0, 0.0, width.toDouble(), height.toDouble())
            }
            
            // Swap buffers: copy rtB to rtA for next iteration
            rtB.colorBuffer(0).copyTo(rtA.colorBuffer(0))
            currentBuffer = rtA
        }
    }
    
    /**
     * Run diffusion/absorption simulation iterations.
     */
    fun simulate(drawer: Drawer, params: CalligraphyParams) {
        repeat(params.simIterations) {
            // Simulate: rtA -> rtB
            drawer.isolatedWithTarget(rtB) {
                drawer.shadeStyle = simShader.apply {
                    parameter("width", width.toDouble())
                    parameter("height", height.toDouble())
                    parameter("simBuffer", currentBuffer.colorBuffer(0))
                    parameter("paper", paperTexture)
                    parameter("wetDiffusion", params.wetDiffusion)
                    parameter("pigDiffusion", params.pigDiffusion)
                    parameter("absorbRate", params.absorbRate)
                    parameter("bleedStrength", params.bleedStrength)
                }
                drawer.fill = ColorRGBa.WHITE
                drawer.stroke = null
                drawer.rectangle(0.0, 0.0, width.toDouble(), height.toDouble())
            }
            
            // Swap: rtB becomes current
            currentBuffer.colorBuffer(0).copyTo(rtA.colorBuffer(0))
            currentBuffer = rtA
            rtB.colorBuffer(0).copyTo(currentBuffer.colorBuffer(0))
        }
    }
    
    /**
     * Add splash particles at high curvature points.
     */
    fun addSplashes(
        drawer: Drawer,
        samples: List<CalligraphySample>,
        params: CalligraphyParams,
        seedOffset: Long
    ) {
        if (samples.isEmpty()) return
        
        val rng = Random(params.seed + seedOffset + 999)
        val maxKappa = samples.maxOfOrNull { abs(it.curvature) } ?: 0.1
        
        for (sample in samples) {
            val kappaRatio = abs(sample.curvature) / (maxKappa + 0.001)
            
            // Splash at high curvature or sudden direction changes
            if (kappaRatio > 0.5 && rng.nextDouble() < params.splashChance) {
                // Generate 1-4 splash particles
                val splashCount = 1 + rng.nextInt(3)
                
                for (s in 0 until splashCount) {
                    // Random offset along stroke direction
                    val offset = sample.tangent * rng.nextDouble(-20.0, 20.0) +
                                sample.normal * rng.nextDouble(-15.0, 15.0)
                    val splashPos = sample.pos + offset
                    
                    // Small splash stamp
                    val splashRadius = rng.nextDouble(1.0, 4.0)
                    val splashInk = rng.nextDouble(0.3, 0.7)
                    val splashWater = rng.nextDouble(0.2, 0.5)
                    
                    drawer.isolatedWithTarget(rtB) {
                        drawer.shadeStyle = depositShader.apply {
                            parameter("brushPos", splashPos)
                            parameter("brushRadius", splashRadius)
                            parameter("inkAmount", splashInk)
                            parameter("waterAmount", splashWater)
                            parameter("pressure", 0.8)
                            parameter("rotation", rng.nextDouble() * PI * 2)
                            parameter("width", width.toDouble())
                            parameter("height", height.toDouble())
                            parameter("brush", brushTexture)
                            parameter("paper", paperTexture)
                            parameter("simBuffer", currentBuffer.colorBuffer(0))
                            parameter("kasureThreshold", params.kasureThreshold)
                        }
                        drawer.fill = ColorRGBa.WHITE
                        drawer.stroke = null
                        drawer.rectangle(0.0, 0.0, width.toDouble(), height.toDouble())
                    }
                    
                    rtB.colorBuffer(0).copyTo(currentBuffer.colorBuffer(0))
                }
            }
        }
    }
    
    /**
     * Composite and render the final image.
     */
    fun composite(drawer: Drawer, params: CalligraphyParams) {
        val palette = getPalette(params.paletteMode)
        
        drawer.shadeStyle = compositeShader.apply {
            parameter("width", width.toDouble())
            parameter("height", height.toDouble())
            parameter("simBuffer", currentBuffer.colorBuffer(0))
            parameter("paper", paperTexture)
            parameter("paperColor", colorToVec3(palette.background))
            parameter("inkColor", colorToVec3(palette.inkPrimary))
            parameter("sheenStrength", params.sheenStrength)
        }
        drawer.fill = ColorRGBa.WHITE
        drawer.stroke = null
        drawer.rectangle(0.0, 0.0, width.toDouble(), height.toDouble())
        
        // Reset shade style
        drawer.shadeStyle = null
    }
    
    fun destroy() {
        rtA.destroy()
        rtB.destroy()
        paperTexture.destroy()
        brushTexture.destroy()
    }
}

/**
 * Simple 2D noise function for paper texture generation
 */
private fun simplexNoise2D(x: Double, y: Double, seed: Long): Double {
    // Simple hash-based noise approximation
    val xi = x.toInt()
    val yi = y.toInt()
    val xf = x - xi
    val yf = y - yi
    
    fun hash(ix: Int, iy: Int): Double {
        val h = (ix * 374761393 + iy * 668265263 + seed.toInt()) xor (seed.toInt() shr 13)
        return ((h * h * h * 60493) and 0x7FFFFFFF).toDouble() / 0x7FFFFFFF.toDouble()
    }
    
    val v00 = hash(xi, yi)
    val v10 = hash(xi + 1, yi)
    val v01 = hash(xi, yi + 1)
    val v11 = hash(xi + 1, yi + 1)
    
    // Smoothstep interpolation
    val tx = xf * xf * (3 - 2 * xf)
    val ty = yf * yf * (3 - 2 * yf)
    
    val v0 = v00 * (1 - tx) + v10 * tx
    val v1 = v01 * (1 - tx) + v11 * tx
    
    return v0 * (1 - ty) + v1 * ty
}

// Helper to extract RGB from Vector4 as Vector3 for shader parameters
private fun colorToVec3(color: ColorRGBa): org.openrndr.math.Vector3 =
    org.openrndr.math.Vector3(color.r, color.g, color.b)

// ==========================================
// Katakana Character Definitions (カタカナ定義)
// ==========================================

/**
 * Represents a single stroke of a Katakana character.
 * Points are normalized to [0,1] coordinate space and scaled at render time.
 */
data class KatakanaStroke(
    val points: List<Vector2>,    // Control points for Hobby curve (normalized 0-1)
    val pressureProfile: List<Double> = emptyList()  // Optional pressure at each point (0-1)
)

/**
 * Represents a complete Katakana character with multiple strokes.
 */
data class KatakanaCharacter(
    val name: String,             // Romanization (e.g., "A", "KA", "SA")
    val unicode: Char,            // Unicode character (e.g., 'ア', 'カ', 'サ')
    val strokes: List<KatakanaStroke>  // Ordered list of strokes
)

/**
 * Complete Katakana alphabet definitions.
 * Each character is defined by its strokes, with control points in normalized [0,1] space.
 * The stroke order follows traditional Japanese calligraphy conventions (筆順).
 */
val KATAKANA_CHARACTERS: List<KatakanaCharacter> = listOf(
    // ア (A) - 2 strokes: horizontal sweep, then diagonal
    KatakanaCharacter("A", 'ア', listOf(
        KatakanaStroke(listOf(
            Vector2(0.2, 0.15), Vector2(0.5, 0.12), Vector2(0.85, 0.18)
        )),
        KatakanaStroke(listOf(
            Vector2(0.55, 0.1), Vector2(0.5, 0.35), Vector2(0.35, 0.65), Vector2(0.15, 0.9)
        ))
    )),
    
    // イ (I) - 2 strokes: diagonal left, then vertical
    KatakanaCharacter("I", 'イ', listOf(
        KatakanaStroke(listOf(
            Vector2(0.65, 0.1), Vector2(0.45, 0.3), Vector2(0.25, 0.55)
        )),
        KatakanaStroke(listOf(
            Vector2(0.6, 0.25), Vector2(0.62, 0.5), Vector2(0.58, 0.85)
        ))
    )),
    
    // ウ (U) - 3 strokes: short horizontal, then the "roof", then vertical
    KatakanaCharacter("U", 'ウ', listOf(
        KatakanaStroke(listOf(
            Vector2(0.45, 0.08), Vector2(0.55, 0.08)
        )),
        KatakanaStroke(listOf(
            Vector2(0.15, 0.25), Vector2(0.5, 0.22), Vector2(0.85, 0.28)
        )),
        KatakanaStroke(listOf(
            Vector2(0.5, 0.25), Vector2(0.48, 0.55), Vector2(0.5, 0.88)
        ))
    )),
    
    // エ (E) - 3 strokes: top horizontal, vertical, bottom horizontal
    KatakanaCharacter("E", 'エ', listOf(
        KatakanaStroke(listOf(
            Vector2(0.2, 0.15), Vector2(0.5, 0.15), Vector2(0.8, 0.15)
        )),
        KatakanaStroke(listOf(
            Vector2(0.5, 0.15), Vector2(0.5, 0.5), Vector2(0.5, 0.85)
        )),
        KatakanaStroke(listOf(
            Vector2(0.15, 0.85), Vector2(0.5, 0.85), Vector2(0.85, 0.85)
        ))
    )),
    
    // オ (O) - 3 strokes: horizontal, vertical through, diagonal flick
    KatakanaCharacter("O", 'オ', listOf(
        KatakanaStroke(listOf(
            Vector2(0.2, 0.3), Vector2(0.5, 0.28), Vector2(0.8, 0.32)
        )),
        KatakanaStroke(listOf(
            Vector2(0.5, 0.1), Vector2(0.48, 0.5), Vector2(0.5, 0.9)
        )),
        KatakanaStroke(listOf(
            Vector2(0.5, 0.45), Vector2(0.65, 0.55), Vector2(0.85, 0.7)
        ))
    )),
    
    // カ (KA) - 2 strokes: angular shape with horizontal and vertical
    KatakanaCharacter("KA", 'カ', listOf(
        KatakanaStroke(listOf(
            Vector2(0.25, 0.15), Vector2(0.55, 0.12), Vector2(0.75, 0.2),
            Vector2(0.65, 0.5), Vector2(0.55, 0.85)
        )),
        KatakanaStroke(listOf(
            Vector2(0.35, 0.35), Vector2(0.32, 0.6), Vector2(0.15, 0.9)
        ))
    )),
    
    // キ (KI) - 3 strokes: two horizontals and vertical
    KatakanaCharacter("KI", 'キ', listOf(
        KatakanaStroke(listOf(
            Vector2(0.2, 0.25), Vector2(0.5, 0.22), Vector2(0.8, 0.28)
        )),
        KatakanaStroke(listOf(
            Vector2(0.25, 0.5), Vector2(0.5, 0.48), Vector2(0.75, 0.52)
        )),
        KatakanaStroke(listOf(
            Vector2(0.5, 0.1), Vector2(0.48, 0.5), Vector2(0.5, 0.9)
        ))
    )),
    
    // ク (KU) - 2 strokes: diagonal and curved stroke
    KatakanaCharacter("KU", 'ク', listOf(
        KatakanaStroke(listOf(
            Vector2(0.7, 0.1), Vector2(0.55, 0.35), Vector2(0.35, 0.6)
        )),
        KatakanaStroke(listOf(
            Vector2(0.3, 0.2), Vector2(0.5, 0.25), Vector2(0.6, 0.4),
            Vector2(0.55, 0.65), Vector2(0.35, 0.9)
        ))
    )),
    
    // ケ (KE) - 3 strokes: angular with horizontals
    KatakanaCharacter("KE", 'ケ', listOf(
        KatakanaStroke(listOf(
            Vector2(0.25, 0.15), Vector2(0.55, 0.12), Vector2(0.75, 0.18)
        )),
        KatakanaStroke(listOf(
            Vector2(0.35, 0.4), Vector2(0.55, 0.38), Vector2(0.75, 0.42)
        )),
        KatakanaStroke(listOf(
            Vector2(0.55, 0.15), Vector2(0.5, 0.5), Vector2(0.45, 0.88)
        ))
    )),
    
    // コ (KO) - 3 strokes: three lines forming angular "C"
    KatakanaCharacter("KO", 'コ', listOf(
        KatakanaStroke(listOf(
            Vector2(0.2, 0.2), Vector2(0.5, 0.18), Vector2(0.8, 0.2)
        )),
        KatakanaStroke(listOf(
            Vector2(0.8, 0.2), Vector2(0.78, 0.5), Vector2(0.8, 0.8)
        )),
        KatakanaStroke(listOf(
            Vector2(0.2, 0.8), Vector2(0.5, 0.78), Vector2(0.8, 0.8)
        ))
    )),
    
    // サ (SA) - 3 strokes: two horizontals and diagonal
    KatakanaCharacter("SA", 'サ', listOf(
        KatakanaStroke(listOf(
            Vector2(0.15, 0.2), Vector2(0.5, 0.18), Vector2(0.85, 0.22)
        )),
        KatakanaStroke(listOf(
            Vector2(0.2, 0.45), Vector2(0.5, 0.43), Vector2(0.8, 0.47)
        )),
        KatakanaStroke(listOf(
            Vector2(0.55, 0.18), Vector2(0.45, 0.55), Vector2(0.3, 0.9)
        ))
    )),
    
    // シ (SHI) - 3 strokes: two dots and curved sweep
    KatakanaCharacter("SHI", 'シ', listOf(
        KatakanaStroke(listOf(
            Vector2(0.2, 0.2), Vector2(0.28, 0.28)
        )),
        KatakanaStroke(listOf(
            Vector2(0.3, 0.5), Vector2(0.38, 0.58)
        )),
        KatakanaStroke(listOf(
            Vector2(0.8, 0.15), Vector2(0.65, 0.4), Vector2(0.4, 0.7), Vector2(0.15, 0.9)
        ))
    )),
    
    // ス (SU) - 2 strokes: horizontal and complex diagonal
    KatakanaCharacter("SU", 'ス', listOf(
        KatakanaStroke(listOf(
            Vector2(0.2, 0.15), Vector2(0.5, 0.13), Vector2(0.8, 0.17)
        )),
        KatakanaStroke(listOf(
            Vector2(0.5, 0.15), Vector2(0.55, 0.35), Vector2(0.35, 0.55),
            Vector2(0.6, 0.75), Vector2(0.85, 0.9)
        ))
    )),
    
    // セ (SE) - 2 strokes: horizontal and angular shape
    KatakanaCharacter("SE", 'セ', listOf(
        KatakanaStroke(listOf(
            Vector2(0.15, 0.35), Vector2(0.5, 0.32), Vector2(0.85, 0.38)
        )),
        KatakanaStroke(listOf(
            Vector2(0.55, 0.1), Vector2(0.5, 0.35), Vector2(0.45, 0.55),
            Vector2(0.55, 0.7), Vector2(0.75, 0.88)
        ))
    )),
    
    // ソ (SO) - 2 strokes: two diagonal strokes
    KatakanaCharacter("SO", 'ソ', listOf(
        KatakanaStroke(listOf(
            Vector2(0.25, 0.12), Vector2(0.35, 0.25), Vector2(0.4, 0.4)
        )),
        KatakanaStroke(listOf(
            Vector2(0.75, 0.15), Vector2(0.55, 0.45), Vector2(0.25, 0.9)
        ))
    )),
    
    // タ (TA) - 3 strokes: horizontal, diagonal, cross stroke
    KatakanaCharacter("TA", 'タ', listOf(
        KatakanaStroke(listOf(
            Vector2(0.2, 0.2), Vector2(0.5, 0.18), Vector2(0.8, 0.22)
        )),
        KatakanaStroke(listOf(
            Vector2(0.6, 0.2), Vector2(0.5, 0.5), Vector2(0.35, 0.88)
        )),
        KatakanaStroke(listOf(
            Vector2(0.3, 0.5), Vector2(0.5, 0.48), Vector2(0.7, 0.52)
        ))
    )),
    
    // チ (CHI) - 3 strokes: horizontal, curve, bottom
    KatakanaCharacter("CHI", 'チ', listOf(
        KatakanaStroke(listOf(
            Vector2(0.15, 0.2), Vector2(0.5, 0.18), Vector2(0.85, 0.22)
        )),
        KatakanaStroke(listOf(
            Vector2(0.5, 0.2), Vector2(0.48, 0.5), Vector2(0.35, 0.7),
            Vector2(0.5, 0.85), Vector2(0.75, 0.88)
        ))
    )),
    
    // ツ (TSU) - 3 strokes: two dots and diagonal
    KatakanaCharacter("TSU", 'ツ', listOf(
        KatakanaStroke(listOf(
            Vector2(0.18, 0.15), Vector2(0.28, 0.32)
        )),
        KatakanaStroke(listOf(
            Vector2(0.5, 0.12), Vector2(0.55, 0.28)
        )),
        KatakanaStroke(listOf(
            Vector2(0.82, 0.18), Vector2(0.6, 0.45), Vector2(0.25, 0.88)
        ))
    )),
    
    // テ (TE) - 3 strokes: two horizontals and vertical
    KatakanaCharacter("TE", 'テ', listOf(
        KatakanaStroke(listOf(
            Vector2(0.25, 0.18), Vector2(0.5, 0.15), Vector2(0.75, 0.18)
        )),
        KatakanaStroke(listOf(
            Vector2(0.15, 0.4), Vector2(0.5, 0.38), Vector2(0.85, 0.42)
        )),
        KatakanaStroke(listOf(
            Vector2(0.5, 0.4), Vector2(0.48, 0.65), Vector2(0.5, 0.9)
        ))
    )),
    
    // ト (TO) - 2 strokes: vertical and horizontal flick
    KatakanaCharacter("TO", 'ト', listOf(
        KatakanaStroke(listOf(
            Vector2(0.4, 0.1), Vector2(0.38, 0.5), Vector2(0.4, 0.9)
        )),
        KatakanaStroke(listOf(
            Vector2(0.4, 0.4), Vector2(0.6, 0.5), Vector2(0.8, 0.65)
        ))
    )),
    
    // ナ (NA) - 2 strokes: horizontal and diagonal
    KatakanaCharacter("NA", 'ナ', listOf(
        KatakanaStroke(listOf(
            Vector2(0.15, 0.28), Vector2(0.5, 0.25), Vector2(0.85, 0.3)
        )),
        KatakanaStroke(listOf(
            Vector2(0.55, 0.1), Vector2(0.48, 0.45), Vector2(0.35, 0.88)
        ))
    )),
    
    // ニ (NI) - 2 strokes: two horizontal lines
    KatakanaCharacter("NI", 'ニ', listOf(
        KatakanaStroke(listOf(
            Vector2(0.25, 0.3), Vector2(0.5, 0.28), Vector2(0.75, 0.3)
        )),
        KatakanaStroke(listOf(
            Vector2(0.15, 0.7), Vector2(0.5, 0.68), Vector2(0.85, 0.72)
        ))
    )),
    
    // ヌ (NU) - 2 strokes: complex crossing strokes
    KatakanaCharacter("NU", 'ヌ', listOf(
        KatakanaStroke(listOf(
            Vector2(0.2, 0.2), Vector2(0.5, 0.18), Vector2(0.75, 0.25),
            Vector2(0.6, 0.5), Vector2(0.35, 0.88)
        )),
        KatakanaStroke(listOf(
            Vector2(0.4, 0.35), Vector2(0.55, 0.55), Vector2(0.8, 0.85)
        ))
    )),
    
    // ネ (NE) - 4 strokes: complex character
    KatakanaCharacter("NE", 'ネ', listOf(
        KatakanaStroke(listOf(
            Vector2(0.45, 0.08), Vector2(0.55, 0.08)
        )),
        KatakanaStroke(listOf(
            Vector2(0.5, 0.08), Vector2(0.48, 0.35), Vector2(0.5, 0.6)
        )),
        KatakanaStroke(listOf(
            Vector2(0.15, 0.35), Vector2(0.5, 0.32), Vector2(0.85, 0.38)
        )),
        KatakanaStroke(listOf(
            Vector2(0.5, 0.5), Vector2(0.35, 0.7), Vector2(0.5, 0.85), Vector2(0.75, 0.9)
        ))
    )),
    
    // ノ (NO) - 1 stroke: simple diagonal
    KatakanaCharacter("NO", 'ノ', listOf(
        KatakanaStroke(listOf(
            Vector2(0.75, 0.1), Vector2(0.55, 0.4), Vector2(0.25, 0.9)
        ))
    )),
    
    // ハ (HA) - 2 strokes: two diagonal strokes spreading
    KatakanaCharacter("HA", 'ハ', listOf(
        KatakanaStroke(listOf(
            Vector2(0.5, 0.15), Vector2(0.35, 0.5), Vector2(0.15, 0.88)
        )),
        KatakanaStroke(listOf(
            Vector2(0.55, 0.2), Vector2(0.7, 0.55), Vector2(0.85, 0.88)
        ))
    )),
    
    // ヒ (HI) - 2 strokes: vertical and curved
    KatakanaCharacter("HI", 'ヒ', listOf(
        KatakanaStroke(listOf(
            Vector2(0.35, 0.1), Vector2(0.32, 0.5), Vector2(0.35, 0.75)
        )),
        KatakanaStroke(listOf(
            Vector2(0.35, 0.45), Vector2(0.55, 0.4), Vector2(0.75, 0.5),
            Vector2(0.7, 0.7), Vector2(0.45, 0.88)
        ))
    )),
    
    // フ (FU) - 1 stroke: curved sweep
    KatakanaCharacter("FU", 'フ', listOf(
        KatakanaStroke(listOf(
            Vector2(0.2, 0.2), Vector2(0.5, 0.15), Vector2(0.8, 0.25),
            Vector2(0.65, 0.55), Vector2(0.4, 0.88)
        ))
    )),
    
    // ヘ (HE) - 1 stroke: angular "へ" shape
    KatakanaCharacter("HE", 'ヘ', listOf(
        KatakanaStroke(listOf(
            Vector2(0.15, 0.55), Vector2(0.4, 0.35), Vector2(0.5, 0.3),
            Vector2(0.7, 0.45), Vector2(0.88, 0.65)
        ))
    )),
    
    // ホ (HO) - 4 strokes: cross with flicks
    KatakanaCharacter("HO", 'ホ', listOf(
        KatakanaStroke(listOf(
            Vector2(0.5, 0.1), Vector2(0.48, 0.5), Vector2(0.5, 0.9)
        )),
        KatakanaStroke(listOf(
            Vector2(0.15, 0.35), Vector2(0.5, 0.32), Vector2(0.85, 0.38)
        )),
        KatakanaStroke(listOf(
            Vector2(0.5, 0.55), Vector2(0.3, 0.75), Vector2(0.15, 0.88)
        )),
        KatakanaStroke(listOf(
            Vector2(0.5, 0.55), Vector2(0.7, 0.75), Vector2(0.85, 0.88)
        ))
    )),
    
    // マ (MA) - 2 strokes: horizontal and curved
    KatakanaCharacter("MA", 'マ', listOf(
        KatakanaStroke(listOf(
            Vector2(0.15, 0.25), Vector2(0.5, 0.22), Vector2(0.85, 0.28)
        )),
        KatakanaStroke(listOf(
            Vector2(0.75, 0.28), Vector2(0.55, 0.5), Vector2(0.35, 0.88)
        ))
    )),
    
    // ミ (MI) - 3 strokes: three horizontal dashes
    KatakanaCharacter("MI", 'ミ', listOf(
        KatakanaStroke(listOf(
            Vector2(0.3, 0.2), Vector2(0.55, 0.22), Vector2(0.75, 0.28)
        )),
        KatakanaStroke(listOf(
            Vector2(0.25, 0.48), Vector2(0.5, 0.5), Vector2(0.7, 0.55)
        )),
        KatakanaStroke(listOf(
            Vector2(0.2, 0.75), Vector2(0.45, 0.78), Vector2(0.65, 0.82)
        ))
    )),
    
    // ム (MU) - 2 strokes: angular shape
    KatakanaCharacter("MU", 'ム', listOf(
        KatakanaStroke(listOf(
            Vector2(0.5, 0.1), Vector2(0.45, 0.35), Vector2(0.2, 0.65),
            Vector2(0.35, 0.75), Vector2(0.75, 0.85)
        )),
        KatakanaStroke(listOf(
            Vector2(0.35, 0.55), Vector2(0.55, 0.6), Vector2(0.7, 0.7)
        ))
    )),
    
    // メ (ME) - 2 strokes: X-like crossing
    KatakanaCharacter("ME", 'メ', listOf(
        KatakanaStroke(listOf(
            Vector2(0.7, 0.15), Vector2(0.45, 0.5), Vector2(0.2, 0.88)
        )),
        KatakanaStroke(listOf(
            Vector2(0.3, 0.35), Vector2(0.55, 0.6), Vector2(0.8, 0.85)
        ))
    )),
    
    // モ (MO) - 3 strokes: horizontal lines with vertical
    KatakanaCharacter("MO", 'モ', listOf(
        KatakanaStroke(listOf(
            Vector2(0.2, 0.25), Vector2(0.5, 0.22), Vector2(0.8, 0.28)
        )),
        KatakanaStroke(listOf(
            Vector2(0.25, 0.55), Vector2(0.5, 0.52), Vector2(0.75, 0.58)
        )),
        KatakanaStroke(listOf(
            Vector2(0.5, 0.1), Vector2(0.48, 0.55), Vector2(0.5, 0.9)
        ))
    )),
    
    // ヤ (YA) - 2 strokes: angular shape
    KatakanaCharacter("YA", 'ヤ', listOf(
        KatakanaStroke(listOf(
            Vector2(0.25, 0.3), Vector2(0.5, 0.25), Vector2(0.75, 0.35),
            Vector2(0.5, 0.6), Vector2(0.25, 0.88)
        )),
        KatakanaStroke(listOf(
            Vector2(0.55, 0.1), Vector2(0.52, 0.4), Vector2(0.55, 0.75)
        ))
    )),
    
    // ユ (YU) - 2 strokes: angular box-like
    KatakanaCharacter("YU", 'ユ', listOf(
        KatakanaStroke(listOf(
            Vector2(0.2, 0.25), Vector2(0.22, 0.55), Vector2(0.2, 0.75)
        )),
        KatakanaStroke(listOf(
            Vector2(0.2, 0.75), Vector2(0.5, 0.73), Vector2(0.8, 0.78)
        ))
    )),
    
    // ヨ (YO) - 3 strokes: three horizontal-ish lines connected
    KatakanaCharacter("YO", 'ヨ', listOf(
        KatakanaStroke(listOf(
            Vector2(0.3, 0.2), Vector2(0.55, 0.18), Vector2(0.75, 0.22)
        )),
        KatakanaStroke(listOf(
            Vector2(0.35, 0.5), Vector2(0.55, 0.48), Vector2(0.72, 0.52)
        )),
        KatakanaStroke(listOf(
            Vector2(0.75, 0.2), Vector2(0.73, 0.5), Vector2(0.75, 0.8),
            Vector2(0.55, 0.78), Vector2(0.3, 0.82)
        ))
    )),
    
    // ラ (RA) - 2 strokes: horizontal and diagonal
    KatakanaCharacter("RA", 'ラ', listOf(
        KatakanaStroke(listOf(
            Vector2(0.2, 0.2), Vector2(0.5, 0.18), Vector2(0.8, 0.22)
        )),
        KatakanaStroke(listOf(
            Vector2(0.55, 0.22), Vector2(0.52, 0.5), Vector2(0.35, 0.88)
        ))
    )),
    
    // リ (RI) - 2 strokes: two vertical strokes
    KatakanaCharacter("RI", 'リ', listOf(
        KatakanaStroke(listOf(
            Vector2(0.35, 0.15), Vector2(0.32, 0.45), Vector2(0.3, 0.65)
        )),
        KatakanaStroke(listOf(
            Vector2(0.68, 0.1), Vector2(0.65, 0.5), Vector2(0.6, 0.88)
        ))
    )),
    
    // ル (RU) - 2 strokes: vertical and curved bottom
    KatakanaCharacter("RU", 'ル', listOf(
        KatakanaStroke(listOf(
            Vector2(0.3, 0.1), Vector2(0.28, 0.5), Vector2(0.3, 0.88)
        )),
        KatakanaStroke(listOf(
            Vector2(0.6, 0.15), Vector2(0.58, 0.5), Vector2(0.55, 0.7),
            Vector2(0.7, 0.82), Vector2(0.85, 0.88)
        ))
    )),
    
    // レ (RE) - 1 stroke: vertical with flick
    KatakanaCharacter("RE", 'レ', listOf(
        KatakanaStroke(listOf(
            Vector2(0.35, 0.1), Vector2(0.32, 0.5), Vector2(0.35, 0.7),
            Vector2(0.55, 0.8), Vector2(0.8, 0.88)
        ))
    )),
    
    // ロ (RO) - 3 strokes: rectangle shape
    KatakanaCharacter("RO", 'ロ', listOf(
        KatakanaStroke(listOf(
            Vector2(0.2, 0.2), Vector2(0.5, 0.18), Vector2(0.8, 0.2)
        )),
        KatakanaStroke(listOf(
            Vector2(0.2, 0.2), Vector2(0.22, 0.5), Vector2(0.2, 0.8)
        )),
        KatakanaStroke(listOf(
            Vector2(0.8, 0.2), Vector2(0.78, 0.5), Vector2(0.8, 0.8),
            Vector2(0.5, 0.78), Vector2(0.2, 0.8)
        ))
    )),
    
    // ワ (WA) - 2 strokes: curved "wa" shape
    KatakanaCharacter("WA", 'ワ', listOf(
        KatakanaStroke(listOf(
            Vector2(0.2, 0.2), Vector2(0.5, 0.15), Vector2(0.8, 0.22),
            Vector2(0.75, 0.5), Vector2(0.6, 0.88)
        )),
        KatakanaStroke(listOf(
            Vector2(0.35, 0.35), Vector2(0.32, 0.55), Vector2(0.35, 0.75)
        ))
    )),
    
    // ヲ (WO) - 3 strokes: complex shape
    KatakanaCharacter("WO", 'ヲ', listOf(
        KatakanaStroke(listOf(
            Vector2(0.2, 0.2), Vector2(0.5, 0.18), Vector2(0.8, 0.22)
        )),
        KatakanaStroke(listOf(
            Vector2(0.25, 0.45), Vector2(0.5, 0.42), Vector2(0.75, 0.48)
        )),
        KatakanaStroke(listOf(
            Vector2(0.65, 0.22), Vector2(0.55, 0.55), Vector2(0.35, 0.88)
        ))
    )),
    
    // ン (N) - 2 strokes: curved and flick
    KatakanaCharacter("N", 'ン', listOf(
        KatakanaStroke(listOf(
            Vector2(0.2, 0.35), Vector2(0.32, 0.5)
        )),
        KatakanaStroke(listOf(
            Vector2(0.75, 0.15), Vector2(0.55, 0.45), Vector2(0.3, 0.85)
        ))
    ))
)

// ==========================================
// Default Parameters
// ==========================================

/**
 * Japanese calligraphy brush (書道筆/Shodo fude) characteristics:
 * - Soft pointed tip with dramatic width variation
 * - Sharp tapers at entry/exit (尖 - sen)
 * - Strong pressure response (press down = thick, lift = thin)
 * - Flying white/dry brush effects (飛白 - hiaku/kasure)
 * - Ink depletion along stroke (墨切れ - sumi-gire)
 */
fun defaultParams(seed: Long): CalligraphyParams = CalligraphyParams(
    seed = seed,
    tension = 1.15,             // Slightly higher tension for elegant curves
    pointCount = 16,            // More points for expressive gesture control
    strokeCount = 2,
    wMin = 1.5,                 // JAPANESE BRUSH: hairline tip minimum (pointed brush)
    wMax = 45.0,                // JAPANESE BRUSH: dramatic max when pressed
    curvGain = 0.6,             // Strong curvature→width response (thins on turns)
    washPasses = 3,             // Subtle ink bleeding (sumi-e style)
    paletteMode = 0,
    echoCount = 1,
    pressureFreq = 4.5,         // Higher frequency for expressive pressure variation
    taperAmount = 0.18          // JAPANESE BRUSH: dramatic pointed tapers
)

// ==========================================
// Palette Definitions
// ==========================================

data class CalligraphyPalette(
    val background: ColorRGBa,
    val inkPrimary: ColorRGBa,
    val inkSecondary: ColorRGBa,
    val inkWash: ColorRGBa
)

fun getPalette(mode: Int): CalligraphyPalette {
    return when (mode) {
        1 -> CalligraphyPalette(
            // Mode B: Indigo on cream
            background = ColorRGBa.fromHex("FDF8F0"),
            inkPrimary = ColorRGBa.fromHex("1A237E"),      // deep indigo
            inkSecondary = ColorRGBa.fromHex("0D1642"),    // near-black accent
            inkWash = ColorRGBa.fromHex("3949AB").opacify(0.15)
        )
        else -> CalligraphyPalette(
            // Mode A: Sumi ink wash (default)
            background = ColorRGBa.fromHex("FDF8F0"),      // cream paper
            inkPrimary = ColorRGBa.fromHex("1A1A1A"),      // near-black
            inkSecondary = ColorRGBa.fromHex("3A3A3A"),    // gray
            inkWash = ColorRGBa.fromHex("1A1A1A").opacify(0.08)
        )
    }
}

// ==========================================
// Katakana Stroke Generation (カタカナ筆画生成)
// ==========================================

/**
 * Transform normalized Katakana stroke points to canvas coordinates.
 * Applies slight hand-drawn jitter for organic feel while maintaining
 * character recognizability.
 */
fun generateKatakanaStrokePoints(
    stroke: KatakanaStroke,
    bounds: Rectangle,
    seed: Long,
    jitterAmount: Double = 2.0
): List<Vector2> {
    val rng = Random(seed)
    
    return stroke.points.map { normalizedPoint ->
        // Scale from [0,1] to canvas bounds with margin
        val x = bounds.x + normalizedPoint.x * bounds.width
        val y = bounds.y + normalizedPoint.y * bounds.height
        
        // Add subtle hand-drawn jitter (very small to maintain legibility)
        val jitter = Vector2(
            rng.nextDouble(-jitterAmount, jitterAmount),
            rng.nextDouble(-jitterAmount, jitterAmount)
        )
        
        Vector2(x, y) + jitter
    }
}

/**
 * Get the current Katakana character based on params
 */
fun getCurrentKatakana(params: CalligraphyParams): KatakanaCharacter {
    val index = params.katakanaIndex.coerceIn(0, KATAKANA_CHARACTERS.lastIndex)
    return KATAKANA_CHARACTERS[index]
}

// ==========================================
// Gesture Walk Point Generator (Legacy - for abstract mode)
// ==========================================

/**
 * Generate control points for Japanese calligraphy (書道) style gestures:
 * - Deliberate entry stroke (入り - iri) with approach angle
 * - Rhythmic pressure points (押さえ - osae) creating thickness variation
 * - Elegant curves with proper brush lift areas (撥ね - hane)
 * - Exit stroke (終わり - owari) with graceful taper direction
 * 
 * Japanese calligraphy emphasizes intentional movement, not random walking.
 * Each stroke has a beginning, middle development, and ending character.
 */
fun generateGesturePoints(params: CalligraphyParams, bounds: Rectangle, strokeIndex: Int): List<Vector2> {
    val rng = Random(params.seed + strokeIndex * 1000)
    val points = mutableListOf<Vector2>()
    
    val numPoints = params.pointCount
    
    // Japanese calligraphy: determine stroke type for this gesture
    // 0 = horizontal (横画), 1 = vertical (縦画), 2 = diagonal sweep (払い), 3 = hook/turn (折れ)
    val strokeType = rng.nextInt(4)
    
    // Entry point: Japanese calligraphy starts with intentional brush placement
    // The entry angle matters - brush approaches the paper at specific angles
    val startPos: Vector2
    var direction: Vector2
    
    when (strokeType) {
        0 -> { // Horizontal stroke (横画) - typically left to right
            startPos = Vector2(
                bounds.x + bounds.width * rng.nextDouble(0.05, 0.2),
                bounds.y + bounds.height * rng.nextDouble(0.2, 0.8)
            )
            // Entry angle: slightly downward then horizontal
            direction = Vector2(1.0, rng.nextDouble(-0.15, 0.1)).normalized
        }
        1 -> { // Vertical stroke (縦画) - typically top to bottom
            startPos = Vector2(
                bounds.x + bounds.width * rng.nextDouble(0.2, 0.8),
                bounds.y + bounds.height * rng.nextDouble(0.05, 0.2)
            )
            // Entry angle: slight right lean is traditional
            direction = Vector2(rng.nextDouble(-0.1, 0.15), 1.0).normalized
        }
        2 -> { // Diagonal sweep (払い) - dramatic sweeping motion
            val fromTop = rng.nextBoolean()
            startPos = if (fromTop) {
                Vector2(
                    bounds.x + bounds.width * rng.nextDouble(0.6, 0.9),
                    bounds.y + bounds.height * rng.nextDouble(0.05, 0.25)
                )
            } else {
                Vector2(
                    bounds.x + bounds.width * rng.nextDouble(0.1, 0.4),
                    bounds.y + bounds.height * rng.nextDouble(0.05, 0.3)
                )
            }
            direction = Vector2(
                if (fromTop) -1.0 else 1.0,
                rng.nextDouble(0.4, 0.9)
            ).normalized
        }
        else -> { // Hook/turn stroke (折れ) - changes direction mid-stroke
            startPos = Vector2(
                bounds.x + bounds.width * rng.nextDouble(0.15, 0.4),
                bounds.y + bounds.height * rng.nextDouble(0.1, 0.35)
            )
            direction = Vector2(rng.nextDouble(0.5, 1.0), rng.nextDouble(-0.3, 0.5)).normalized
        }
    }
    
    points.add(startPos)
    
    // Japanese calligraphy: step sizes vary based on brush speed
    // Slower at pressure points (thick), faster through transitions (thin)
    val baseStepSize = min(bounds.width, bounds.height) / (numPoints * 0.32)
    
    // Medium-high inertia for smooth, controlled strokes
    val baseInertia = 0.72 + rng.nextDouble() * 0.12
    
    // Pressure points: where the brush presses down (creates thickness)
    // These are intentional, not random - typically 1-3 per stroke
    val pressureCount = 1 + rng.nextInt(3)
    val pressurePositions = mutableSetOf<Int>()
    // First pressure point often near start (after entry)
    pressurePositions.add(2 + rng.nextInt(2))
    // Additional pressure points distributed through stroke
    for (p in 1 until pressureCount) {
        pressurePositions.add(3 + rng.nextInt(numPoints - 5))
    }
    
    // Turn point for hook strokes (折れ)
    val turnPoint = if (strokeType == 3) numPoints / 2 + rng.nextInt(3) - 1 else -1
    
    var current = startPos
    
    for (i in 1 until numPoints) {
        val progress = i.toDouble() / numPoints
        
        // Japanese calligraphy: speed varies with intent
        // Slower at pressure points, accelerating through thin sections
        val isPressurePoint = i in pressurePositions
        val nearPressure = pressurePositions.any { abs(it - i) <= 1 }
        
        val speedFactor = when {
            isPressurePoint -> 0.6  // Slow down at pressure points
            nearPressure -> 0.75
            progress > 0.85 -> 1.3  // Accelerate toward exit (tapering stroke)
            else -> 1.0
        }
        
        val stepSize = baseStepSize * speedFactor * rng.nextDouble(0.9, 1.1)
        
        // Direction changes: mostly smooth, with intentional turns
        var inertia = baseInertia
        var turnInfluence = Vector2.ZERO
        
        // Hook stroke: deliberate direction change at turn point
        if (strokeType == 3 && i == turnPoint) {
            // Sharp turn characteristic of 折れ strokes
            val turnAngle = rng.nextDouble(70.0, 110.0) * if (rng.nextBoolean()) 1 else -1
            direction = direction.rotate(turnAngle)
            inertia = 0.3  // Allow sharper turn
        }
        
        // Gentle organic curve through stroke body
        val curveBias = when (strokeType) {
            2 -> Vector2(0.0, 0.15)  // Diagonal sweeps curve downward
            else -> Vector2(rng.nextDouble(-0.08, 0.08), rng.nextDouble(-0.05, 0.08))
        }
        
        // Very subtle perturbation - Japanese calligraphy is controlled, not chaotic
        val perturbation = Vector2(
            rng.nextDouble(-0.06, 0.06),
            rng.nextDouble(-0.06, 0.06)
        )
        
        direction = (direction * inertia + perturbation + curveBias + turnInfluence).normalized
        
        var next = current + direction * stepSize
        
        // Boundary handling with padding for stroke width
        val padding = params.wMax * 0.5
        if (next.x < bounds.x + padding || next.x > bounds.x + bounds.width - padding ||
            next.y < bounds.y + padding || next.y > bounds.y + bounds.height - padding) {
            // Gentle curve away from boundary rather than hard reflection
            val toCenter = (bounds.center - next).normalized * 0.3
            direction = (direction + toCenter).normalized
            next = Vector2(
                next.x.coerceIn(bounds.x + padding, bounds.x + bounds.width - padding),
                next.y.coerceIn(bounds.y + padding, bounds.y + bounds.height - padding)
            )
        }
        
        // Minimal jitter - Japanese brush control is precise
        val jitter = if (i > 1 && i < numPoints - 1) {
            Vector2(rng.nextDouble(-1.0, 1.0), rng.nextDouble(-1.0, 1.0))
        } else Vector2.ZERO
        
        points.add(next + jitter)
        current = next
    }
    
    return points
}

// ==========================================
// Hobby Curve Generation
// ==========================================

/**
 * Fit an open Hobby curve through points with given tension
 */
fun hobbyCurve(points: List<Vector2>, tension: Double): ShapeContour {
    return hobbyCurve(points, false, tension)
}

// ==========================================
// Arc-Length Sampling with Curvature
// ==========================================

/**
 * Sample the curve densely (uniform in arc length) to obtain position, tangent, normal, and curvature
 */
fun sampleContourArcLength(contour: ShapeContour, numSamples: Int): List<CalligraphySample> {
    val positions = contour.equidistantPositions(numSamples)
    if (positions.size < 3) return emptyList()
    
    val samples = mutableListOf<CalligraphySample>()
    
    for (i in positions.indices) {
        val pos = positions[i]
        val s = i.toDouble() / (positions.size - 1) // normalized arc-length parameter
        
        // Compute tangent from finite differences
        val tangent = when (i) {
            0 -> (positions[1] - positions[0]).normalized
            positions.lastIndex -> (positions[i] - positions[i - 1]).normalized
            else -> (positions[i + 1] - positions[i - 1]).normalized
        }
        
        // Normal is perpendicular to tangent (rotated 90° counter-clockwise)
        val normal = tangent.perpendicular()
        
        samples.add(CalligraphySample(pos, tangent, normal, s, 0.0)) // curvature computed next
    }
    
    return samples
}

/**
 * Estimate curvature κ(s) for each sample using change in tangent direction
 * Curvature relates to bending energy; Hobby produces a low-bending-energy spline.
 */
fun estimateCurvature(samples: List<CalligraphySample>): List<CalligraphySample> {
    if (samples.size < 3) return samples
    
    val curvatures = DoubleArray(samples.size)
    
    for (i in samples.indices) {
        curvatures[i] = when (i) {
            0 -> {
                val t0 = samples[0].tangent
                val t1 = samples[1].tangent
                val ds = (samples[1].pos - samples[0].pos).length
                if (ds > 0.001) angleChange(t0, t1) / ds else 0.0
            }
            samples.lastIndex -> {
                val t0 = samples[i - 1].tangent
                val t1 = samples[i].tangent
                val ds = (samples[i].pos - samples[i - 1].pos).length
                if (ds > 0.001) angleChange(t0, t1) / ds else 0.0
            }
            else -> {
                val t0 = samples[i - 1].tangent
                val t1 = samples[i + 1].tangent
                val ds = (samples[i + 1].pos - samples[i - 1].pos).length
                if (ds > 0.001) angleChange(t0, t1) / ds else 0.0
            }
        }
    }
    
    return samples.mapIndexed { i, sample ->
        sample.copy(curvature = curvatures[i])
    }
}

/**
 * Compute absolute angle change between two unit vectors
 */
private fun angleChange(t0: Vector2, t1: Vector2): Double {
    val dot = (t0.x * t1.x + t0.y * t1.y).coerceIn(-1.0, 1.0)
    return acos(dot)
}

// ==========================================
// Width Function
// ==========================================

/**
 * Japanese calligraphy brush width function (書道筆の筆圧):
 * 
 * Authentic characteristics modeled:
 * - Sharp pointed entry (入筆 - nyuuhitsu): brush tip touches first
 * - Pressure build-up (押さえ - osae): intentional thick sections
 * - Curvature response: brush naturally thins on tight curves (brush lifts slightly)
 * - Exit taper (収筆 - shuuhitsu): graceful pointed release
 * - Ink depletion simulation: affects width slightly as ink runs out
 * 
 * The width function uses:
 * w(s) = base_width * taper_envelope * curvature_factor * pressure_gesture * ink_amount
 */
fun widthAt(s: Double, kappa: Double, params: CalligraphyParams, seedOffset: Long): Double {
    val rng = Random(params.seed + seedOffset)
    
    // === CURVATURE RESPONSE ===
    // Japanese brush naturally thins on curves as the tip lifts
    // Strong response - tight curves = thin strokes (brush physics)
    val epsilon = 0.02  // Small epsilon = high sensitivity to curvature
    val curvInfluence = 1.0 / (epsilon + abs(kappa) * params.curvGain * 8.0)
    val curvFactor = curvInfluence.coerceIn(0.15, 1.0)  // Allow dramatic thinning
    
    // === ENTRY/EXIT TAPER (尖り - togari) ===
    // Japanese brush has very sharp pointed tips
    // Entry taper: quick transition from point to full width
    val entryLength = params.taperAmount * 0.7  // Entry is quicker than exit
    val exitLength = params.taperAmount * 1.2   // Exit is longer, more graceful
    
    // Entry: starts from near-zero (pointed tip)
    val entryTaper = if (s < entryLength) {
        // Ease-in-out for natural brush touch
        val t = s / entryLength
        t * t * (3.0 - 2.0 * t)  // smoothstep
    } else 1.0
    
    // Exit: tapers to very fine point (brush lifting off)
    val exitTaper = if (s > 1.0 - exitLength) {
        val t = (1.0 - s) / exitLength
        // Slightly different curve for exit - more gradual at first, then quick release
        sqrt(t * t * (3.0 - 2.0 * t))
    } else 1.0
    
    val taper = entryTaper * exitTaper
    
    // === PRESSURE GESTURE (押さえの呼吸 - osae no kokyuu) ===
    // Japanese calligraphy has intentional pressure points
    // These are rhythmic, not random - creates "breathing" in the stroke
    
    // Primary pressure wave: 2-3 intentional press-downs per stroke
    val primaryWave = sin(s * PI * (2.0 + rng.nextDouble())) * 0.5 + 0.5
    val pressurePrimary = 0.5 + primaryWave * 0.5
    
    // Secondary wave: subtle organic variation
    val secondaryWave = simplex1D(s * params.pressureFreq + seedOffset * 0.1, params.seed)
    val pressureSecondary = 0.85 + secondaryWave * 0.15
    
    // Combine pressure influences
    val pressure = pressurePrimary * pressureSecondary
    
    // === INK AMOUNT / DEPLETION (墨量 - sumiryo) ===
    // Stroke width slightly decreases as ink depletes
    // This is subtle - more visible in opacity than width
    val inkDepletion = 1.0 - s * 0.08  // 8% reduction by end of stroke
    
    // === FINAL WIDTH CALCULATION ===
    // Base width range from hairline to full brush width
    val baseWidth = params.wMin + (params.wMax - params.wMin) * curvFactor
    
    return baseWidth * taper * pressure * inkDepletion
}

/**
 * Simple 1D noise approximation for pressure variation
 */
@Suppress("UNUSED_VARIABLE")
private fun simplex1D(x: Double, seed: Long): Double {
    val xi = x.toInt()
    val xf = x - xi
    
    val a = Random(seed + xi).nextDouble() * 2 - 1
    val b = Random(seed + xi + 1).nextDouble() * 2 - 1
    
    // Smoothstep interpolation
    val t = xf * xf * (3 - 2 * xf)
    return a * (1 - t) + b * t
}

// ==========================================
// Ribbon Polygon Building
// ==========================================

/**
 * Build a filled ribbon polygon from samples and widths.
 * left(s) = p(s) + n(s) * w(s)/2
 * right(s) = p(s) - n(s) * w(s)/2
 * Join left + reverse(right) into a closed polygon
 */
fun buildRibbonPolygon(samples: List<CalligraphySample>, params: CalligraphyParams, seedOffset: Long): ShapeContour {
    val leftPoints = mutableListOf<Vector2>()
    val rightPoints = mutableListOf<Vector2>()
    
    for (sample in samples) {
        val w = widthAt(sample.s, sample.curvature, params, seedOffset)
        leftPoints.add(sample.pos + sample.normal * (w / 2.0))
        rightPoints.add(sample.pos - sample.normal * (w / 2.0))
    }
    
    // Join left + reverse(right) into closed polygon
    val polygonPoints = leftPoints + rightPoints.reversed()
    return ShapeContour.fromPoints(polygonPoints, closed = true)
}

// ==========================================
// Paper Rendering
// ==========================================

/**
 * Render paper-like background with procedural grain and subtle fibers
 */
fun renderPaper(drawer: Drawer, params: CalligraphyParams) {
    val palette = getPalette(params.paletteMode)
    val rng = Random(params.seed)
    
    // Base color
    drawer.clear(palette.background)
    
    // Vignette and grain via shade style
    drawer.isolated {
        drawer.shadeStyle = shadeStyle {
            fragmentTransform = """
                vec2 uv = c_boundsPosition.xy;
                
                // Pseudo-random noise
                float noise = fract(sin(dot(uv * 500.0, vec2(12.9898, 78.233))) * 43758.5453);
                
                // Subtle vignette
                float vign = 1.0 - length(uv - 0.5) * 0.25;
                
                vec3 color = x_fill.rgb;
                
                // Add very subtle grain
                color += (noise - 0.5) * 0.025;
                
                // Apply vignette
                color *= vign;
                
                x_fill.rgb = clamp(color, 0.0, 1.0);
            """.trimIndent()
        }
        drawer.fill = palette.background
        drawer.stroke = null
        drawer.rectangle(0.0, 0.0, drawer.width.toDouble(), drawer.height.toDouble())
    }
    
    // Sparse fiber streaks (long, low-alpha lines)
    drawer.isolated {
        drawer.stroke = ColorRGBa.fromHex("8B7355").opacify(0.03)
        drawer.strokeWeight = 0.5
        
        val fiberCount = 30 + rng.nextInt(20)
        for (i in 0 until fiberCount) {
            val x = rng.nextDouble() * drawer.width
            val y = rng.nextDouble() * drawer.height
            val length = 20 + rng.nextDouble() * 60
            val angle = rng.nextDouble() * PI * 2
            
            val start = Vector2(x, y)
            val end = start + Vector2(cos(angle), sin(angle)) * length
            
            drawer.lineSegment(start, end)
        }
    }
}

// ==========================================
// Ink Stroke Rendering
// ==========================================

/**
 * Japanese calligraphy ink stroke rendering (墨跡描画):
 * 
 * Authentic effects implemented:
 * - Sumi ink density variation (濃淡 - notan): wet to dry transition
 * - Flying white / dry brush (飛白/掠れ - hiaku/kasure): paper showing through
 * - Ink bleeding (にじみ - nijimi): subtle edge softness where ink is wet
 * - Bristle texture (毛先 - kesaki): individual hair marks on fast strokes
 * - Ink pooling (溜まり - tamari): darker areas where ink collects
 * - Curvature-driven transparency: brush lifts slightly on curves
 */
fun renderInkStroke(
    drawer: Drawer,
    ribbon: ShapeContour,
    samples: List<CalligraphySample>,
    params: CalligraphyParams,
    isEcho: Boolean = false,
    seedOffset: Long = 0
) {
    val palette = getPalette(params.paletteMode)
    val rng = Random(params.seed + seedOffset)
    
    val baseAlpha = if (isEcho) 0.15 else 1.0
    
    // === INK BLEEDING (にじみ - nijimi) ===
    // Subtle edge softness - more pronounced where stroke is thick (more ink)
    // Japanese sumi ink bleeds subtly into washi paper
    for (pass in 0 until params.washPasses) {
        drawer.isolated {
            // Bleeding is subtle and follows brush direction
            val bleedAmount = 0.8 + pass * 0.5
            drawer.translate(
                rng.nextDouble(-bleedAmount, bleedAmount),
                rng.nextDouble(-bleedAmount, bleedAmount)
            )
            // Very subtle opacity - bleeding is delicate in real sumi
            drawer.fill = palette.inkWash.opacify(0.02 + pass * 0.008)
            drawer.stroke = null
            drawer.contour(ribbon)
        }
    }
    
    // === MAIN INK BODY ===
    // Rich, deep black with full opacity
    drawer.fill = palette.inkPrimary.opacify(baseAlpha)
    drawer.stroke = null
    drawer.contour(ribbon)
    
    // === INK POOLING (溜まり - tamari) ===
    // Ink collects and darkens where brush pauses or presses
    // Most visible at pressure points and stroke beginnings
    if (!isEcho) {
        drawer.isolated {
            for (i in samples.indices step 4) {
                val sample = samples[i]
                val w = widthAt(sample.s, sample.curvature, params, seedOffset)
                
                // Pooling is stronger at start (more ink) and at thick sections
                val poolIntensity = (1.0 - sample.s * 0.7) * (w / params.wMax)
                
                if (poolIntensity > 0.3 && w > params.wMin * 3) {
                    // Dark center pool
                    drawer.fill = palette.inkPrimary.opacify(0.12 * poolIntensity)
                    drawer.stroke = null
                    val poolSize = w * 0.35 * rng.nextDouble(0.6, 1.0)
                    drawer.circle(sample.pos, poolSize)
                }
            }
        }
    }
    
    // === INK DEPLETION / DRY BRUSH TRANSITION (墨切れ - sumi-gire) ===
    // As ink depletes, stroke becomes lighter and shows "flying white"
    if (!isEcho) {
        drawer.isolated {
            // Ink level decreases along stroke
            for (i in samples.indices step 2) {
                val sample = samples[i]
                val w = widthAt(sample.s, sample.curvature, params, seedOffset)
                
                // Depletion increases toward end of stroke
                val depletionFactor = sample.s * sample.s  // Quadratic - accelerates at end
                
                // Create subtle lightening effect
                if (depletionFactor > 0.3) {
                    val lightness = depletionFactor * 0.15 * rng.nextDouble(0.7, 1.3)
                    drawer.fill = palette.background.opacify(lightness)
                    drawer.stroke = null
                    drawer.circle(sample.pos, w * 0.3 * rng.nextDouble(0.5, 1.0))
                }
            }
        }
    }
    
    // === FLYING WHITE / KASURE (飛白/掠れ) ===
    // Paper showing through stroke - characteristic of fast brush movement
    // More prominent at end of stroke where ink is depleted and on fast sections
    if (!isEcho) {
        drawer.isolated {
            drawer.fill = palette.background
            drawer.stroke = null
            
            // Flying white appears as thin gaps in the stroke
            for (i in samples.indices) {
                val sample = samples[i]
                val w = widthAt(sample.s, sample.curvature, params, seedOffset)
                
                // Flying white probability increases with:
                // 1. Progress through stroke (ink depletion)
                // 2. Thin sections (less ink)
                // 3. High curvature (brush lifts)
                val depletionChance = sample.s * 0.5
                val widthChance = (1.0 - w / params.wMax) * 0.3
                val curvatureChance = abs(sample.curvature) * 2.0
                
                val flyingWhiteChance = (depletionChance + widthChance + curvatureChance).coerceIn(0.0, 0.6)
                
                // Skip if width is too small to avoid empty random range
                if (w > 0.1 && rng.nextDouble() < flyingWhiteChance * 0.15) {
                    // Create thin paper-colored gap (bristle mark)
                    val gapWidth = rng.nextDouble(0.5, 2.0)
                    val gapLength = rng.nextDouble(3.0, 12.0) * (1.0 + sample.s)
                    
                    // Position within stroke width
                    val halfRange = w * 0.4
                    val offset = sample.normal * rng.nextDouble(-halfRange, halfRange)
                    val startPos = sample.pos + offset
                    val endPos = startPos + sample.tangent * gapLength
                    
                    drawer.strokeWeight = gapWidth
                    drawer.stroke = palette.background.opacify(0.7 + rng.nextDouble() * 0.3)
                    drawer.fill = null
                    drawer.lineSegment(startPos, endPos)
                }
            }
        }
    }
    
    // === BRISTLE TEXTURE (毛先 - kesaki) ===
    // Individual brush hair marks visible at edges, especially on curved/fast sections
    if (!isEcho) {
        drawer.isolated {
            val maxKappa = samples.maxOfOrNull { abs(it.curvature) } ?: 1.0
            
            // Bristle marks at stroke edges
            for (i in samples.indices step 3) {
                val sample = samples[i]
                val w = widthAt(sample.s, sample.curvature, params, seedOffset)
                val kappaRatio = abs(sample.curvature) / (maxKappa + 0.001)
                
                // More bristle marks on curves and toward end of stroke
                val bristleChance = (kappaRatio * 0.5 + sample.s * 0.3).coerceIn(0.0, 0.5)
                
                if (rng.nextDouble() < bristleChance) {
                    // Bristle mark: thin line extending from edge
                    val side = if (rng.nextBoolean()) 1.0 else -1.0
                    val edgeOffset = w / 2.0 * side * rng.nextDouble(0.8, 0.98)
                    val bristleStart = sample.pos + sample.normal * edgeOffset
                    
                    // Bristle extends slightly beyond stroke
                    val bristleLength = rng.nextDouble(1.5, 5.0)
                    val bristleEnd = bristleStart + sample.normal * (side * bristleLength)
                    
                    drawer.strokeWeight = rng.nextDouble(0.3, 0.8)
                    drawer.stroke = palette.inkPrimary.opacify(rng.nextDouble(0.3, 0.6))
                    drawer.fill = null
                    drawer.lineSegment(bristleStart, bristleEnd)
                }
            }
        }
    }
    
    // === CURVATURE TRANSPARENCY (カーブの透け) ===
    // Brush naturally lifts slightly on tight curves, creating lighter areas
    if (!isEcho) {
        drawer.isolated {
            val maxKappa = samples.maxOfOrNull { abs(it.curvature) } ?: 1.0
            
            for (sample in samples) {
                val kappaRatio = abs(sample.curvature) / (maxKappa + 0.001)
                
                // Significant lightening only on pronounced curves
                if (kappaRatio > 0.35) {
                    val liftAlpha = kappaRatio * 0.25
                    drawer.fill = palette.background.opacify(liftAlpha)
                    drawer.stroke = null
                    val w = widthAt(sample.s, sample.curvature, params, seedOffset)
                    drawer.circle(sample.pos, w * 0.25)
                }
            }
        }
    }
    
    // === ENTRY POINT EMPHASIS (入筆の強調) ===
    // Slight ink concentration at brush entry point
    if (!isEcho && samples.isNotEmpty()) {
        drawer.isolated {
            val firstSamples = samples.take(5)
            for ((idx, sample) in firstSamples.withIndex()) {
                val w = widthAt(sample.s, sample.curvature, params, seedOffset)
                val emphasis = (1.0 - idx / 5.0) * 0.1
                drawer.fill = palette.inkPrimary.opacify(emphasis)
                drawer.stroke = null
                drawer.circle(sample.pos, w * 0.4 * rng.nextDouble(0.6, 1.0))
            }
        }
    }
}

// ==========================================
// Composition Rendering
// ==========================================

/**
 * Generate stroke data for all strokes of the current Katakana character.
 * Returns the list of (ribbon contour, samples) pairs for further rendering or simulation.
 */
fun generateStrokeData(drawer: Drawer, params: CalligraphyParams): List<Pair<ShapeContour, List<CalligraphySample>>> {
    val margin = min(drawer.width, drawer.height) * 0.08
    val safeArea = Rectangle(margin, margin, drawer.width - 2 * margin, drawer.height - 2 * margin)
    
    val strokeData = mutableListOf<Pair<ShapeContour, List<CalligraphySample>>>()
    
    // Get the current Katakana character
    val katakana = getCurrentKatakana(params)
    
    // Calculate character bounds - center the character in the safe area
    // with proper proportions for Japanese calligraphy
    val charSize = min(safeArea.width, safeArea.height) * 0.85
    val charBounds = Rectangle(
        safeArea.x + (safeArea.width - charSize) / 2,
        safeArea.y + (safeArea.height - charSize) / 2,
        charSize,
        charSize
    )
    
    // Generate data for each stroke of the Katakana character in order (筆順)
    for ((strokeIdx, stroke) in katakana.strokes.withIndex()) {
        // Generate control points from Katakana stroke definition
        val points = generateKatakanaStrokePoints(
            stroke,
            charBounds,
            params.seed + strokeIdx * 1000,
            jitterAmount = 1.5  // Subtle hand-drawn feel
        )
        
        // Need at least 2 points for a curve
        if (points.size < 2) continue
        
        // Build Hobby curve through the stroke points
        val contour = hobbyCurve(points, params.tension)
        
        // Sample with curvature estimation
        val numSamples = (contour.length / 2.0).toInt().coerceIn(30, 300)
        var samples = sampleContourArcLength(contour, numSamples)
        samples = estimateCurvature(samples)
        
        // Skip if no valid samples
        if (samples.isEmpty()) continue
        
        // Build ribbon polygon with brush width
        val ribbon = buildRibbonPolygon(samples, params, strokeIdx.toLong())
        
        strokeData.add(Pair(ribbon, samples))
    }
    
    return strokeData
}

/**
 * Render using the physics-based ink simulation.
 * This creates realistic sumi-e ink effects: bleed, kasure, pooling, and sheen.
 */
fun renderWithSimulation(
    drawer: Drawer,
    inkSim: InkSimulation,
    params: CalligraphyParams,
    strokeData: List<Pair<ShapeContour, List<CalligraphySample>>>
) {
    // Clear simulation buffers for fresh render
    inkSim.clear(drawer)
    
    // Deposit each stroke into the simulation
    for ((strokeIdx, data) in strokeData.withIndex()) {
        val (_, samples) = data
        
        // Deposit main stroke
        inkSim.depositStroke(drawer, samples, params, strokeIdx.toLong())
        
        // Add splashes at high curvature points
        inkSim.addSplashes(drawer, samples, params, strokeIdx.toLong())
        
        // Run partial simulation between strokes (let ink start to settle)
        inkSim.simulate(drawer, params.copy(simIterations = params.simIterations / 3))
    }
    
    // Run final simulation iterations for full ink settling
    inkSim.simulate(drawer, params)
    
    // Composite final image
    inkSim.composite(drawer, params)
}

/**
 * Render the full calligraphic composition with Katakana character(s).
 * Draws the selected Katakana character using traditional brush stroke order.
 * 
 * @param inkSim Optional ink simulation - if provided and params.useInkSim is true,
 *               uses physics-based rendering; otherwise uses classic ribbon rendering.
 */
@Suppress("UNUSED_PARAMETER")
fun renderComposition(
    drawer: Drawer, 
    params: CalligraphyParams, 
    debugMode: Boolean = false,  // Reserved for future debug rendering within composition
    inkSim: InkSimulation? = null
): List<Pair<ShapeContour, List<CalligraphySample>>> {
    val strokeData = generateStrokeData(drawer, params)
    
    // Use ink simulation if available and enabled
    if (params.useInkSim && inkSim != null) {
        renderWithSimulation(drawer, inkSim, params, strokeData)
    } else {
        // Classic ribbon rendering
        for ((strokeIdx, data) in strokeData.withIndex()) {
            val (ribbon, samples) = data
            
            // Render main stroke with all ink effects
            renderInkStroke(drawer, ribbon, samples, params, isEcho = false, seedOffset = strokeIdx.toLong())
            
            // Render subtle echo strokes for depth (optional ink shadow)
            for (echoIdx in 0 until params.echoCount) {
                val echoRng = Random(params.seed + strokeIdx * 100 + echoIdx)
                val echoOffset = Vector2(
                    echoRng.nextDouble(-4.0, 4.0),
                    echoRng.nextDouble(-4.0, 4.0)
                )
                
                val echoSamples = samples.map { it.copy(pos = it.pos + echoOffset) }
                
                // Lighter, smaller echo
                val echoParams = params.copy(
                    wMin = params.wMin * 0.5,
                    wMax = params.wMax * 0.4
                )
                val echoRibbon = buildRibbonPolygon(echoSamples, echoParams, strokeIdx.toLong() + echoIdx + 1000)
                
                renderInkStroke(drawer, echoRibbon, echoSamples, echoParams, isEcho = true, seedOffset = strokeIdx.toLong() + echoIdx)
            }
        }
    }
    
    // Render traditional seal stamp (落款 - rakkan) regardless of render mode
    val margin = min(drawer.width, drawer.height) * 0.08
    
    // Traditional seal stamp (落款 - rakkan) - bottom right
    val rng = Random(params.seed)
    if (rng.nextDouble() < 0.7) {
        drawer.isolated {
            val sealX = drawer.width - margin * 2.0
            val sealY = drawer.height - margin * 2.5
            val sealSize = margin * 0.8
            
            val palette = getPalette(params.paletteMode)
            // Red seal color (朱肉 - shuniku) or ink colored
            val sealColor = if (params.paletteMode == 0) {
                ColorRGBa.fromHex("B22222").opacify(0.4)  // Traditional vermillion red
            } else {
                palette.inkPrimary.opacify(0.2)
            }
            
            drawer.fill = sealColor
            drawer.stroke = sealColor.opacify(0.6)
            drawer.strokeWeight = 1.5
            
            // Square seal is traditional
            drawer.rectangle(sealX, sealY, sealSize, sealSize)
            
            // Add simple mark inside seal
            drawer.strokeWeight = 2.0
            drawer.stroke = sealColor.opacify(0.8)
            drawer.fill = null
            val inset = sealSize * 0.2
            drawer.lineSegment(
                Vector2(sealX + inset, sealY + sealSize / 2),
                Vector2(sealX + sealSize - inset, sealY + sealSize / 2)
            )
            drawer.lineSegment(
                Vector2(sealX + sealSize / 2, sealY + inset),
                Vector2(sealX + sealSize / 2, sealY + sealSize - inset)
            )
        }
    }
    
    return strokeData
}

// ==========================================
// Debug Overlay
// ==========================================

/**
 * Render debug overlay showing control points, tangents, and curvature heat
 */
fun renderDebugOverlay(
    drawer: Drawer,
    params: CalligraphyParams,
    strokeData: List<Pair<ShapeContour, List<CalligraphySample>>>
) {
    val margin = min(drawer.width, drawer.height) * 0.08
    val safeArea = Rectangle(margin, margin, drawer.width - 2 * margin, drawer.height - 2 * margin)
    
    // Get current Katakana for debug display
    val katakana = getCurrentKatakana(params)
    val charSize = min(safeArea.width, safeArea.height) * 0.85
    val charBounds = Rectangle(
        safeArea.x + (safeArea.width - charSize) / 2,
        safeArea.y + (safeArea.height - charSize) / 2,
        charSize,
        charSize
    )
    
    for ((strokeIdx, data) in strokeData.withIndex()) {
        val (_, samples) = data
        
        // Get the Katakana stroke points for this stroke
        val points = if (strokeIdx < katakana.strokes.size) {
            generateKatakanaStrokePoints(
                katakana.strokes[strokeIdx],
                charBounds,
                params.seed + strokeIdx * 1000,
                jitterAmount = 1.5
            )
        } else {
            emptyList()
        }
        
        // Draw control points
        drawer.isolated {
            drawer.fill = ColorRGBa.MAGENTA
            drawer.stroke = ColorRGBa.MAGENTA.opacify(0.5)
            drawer.strokeWeight = 1.0
            
            for (pt in points) {
                drawer.circle(pt, 4.0)
            }
            drawer.lineStrip(points)
        }
        
        // Draw curvature heat along stroke
        val maxKappa = samples.maxOfOrNull { abs(it.curvature) } ?: 1.0
        
        drawer.isolated {
            for (sample in samples) {
                val kappaRatio = abs(sample.curvature) / (maxKappa + 0.001)
                // Heat color: blue (low curvature) to red (high curvature)
                val heatColor = ColorRGBa(kappaRatio, 0.2, 1.0 - kappaRatio, 0.6)
                drawer.fill = heatColor
                drawer.stroke = null
                drawer.circle(sample.pos, 2.0 + kappaRatio * 4.0)
            }
        }
        
        // Draw tangent vectors (sparse)
        drawer.isolated {
            drawer.stroke = ColorRGBa.CYAN.opacify(0.5)
            drawer.strokeWeight = 1.0
            
            for (i in samples.indices step 10) {
                val sample = samples[i]
                drawer.lineSegment(sample.pos, sample.pos + sample.tangent * 15.0)
            }
        }
        
        // Draw normal vectors (sparse)
        drawer.isolated {
            drawer.stroke = ColorRGBa.YELLOW.opacify(0.5)
            drawer.strokeWeight = 1.0
            
            for (i in samples.indices step 10) {
                val sample = samples[i]
                drawer.lineSegment(sample.pos, sample.pos + sample.normal * 10.0)
            }
        }
    }
    
    // Debug legend
    drawer.isolated {
        drawer.fill = ColorRGBa.WHITE.opacify(0.8)
        drawer.stroke = null
        drawer.rectangle(10.0, 10.0, 200.0, 80.0)
        
        drawer.fill = ColorRGBa.BLACK
        drawer.text("Debug Mode", 20.0, 30.0)
        drawer.text("Magenta: Control points", 20.0, 45.0)
        drawer.text("Heat: Curvature (blue=low, red=high)", 20.0, 60.0)
        drawer.text("Cyan: Tangents, Yellow: Normals", 20.0, 75.0)
    }
}

// ==========================================
// Export Function
// ==========================================

/**
 * Export high-resolution PNG with Katakana character info in filename
 */
@Suppress("UNUSED_PARAMETER")
fun exportPNG(drawer: Drawer, params: CalligraphyParams, renderTarget: RenderTarget) {
    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val katakana = getCurrentKatakana(params)
    val filename = "katakana_${katakana.name}_${katakana.unicode}_s${params.seed}_t${String.format("%.1f", params.tension)}_$timestamp.png"
    
    // Ensure images directory exists
    File("images").mkdirs()
    
    // Save the color buffer
    renderTarget.colorBuffer(0).saveToFile(File("images/$filename"))
    
    println("Exported: images/$filename")
}

// ==========================================
// Main Program
// ==========================================

fun main() = application {
    configure {
        width = 600
        height = 800
        title = "Katakana Calligraphy (カタカナ書道) - Ink Simulation"
    }
    
    program {
        // State
        var seed = Random.nextLong()
        var params = defaultParams(seed)
        var debugMode = false
        var strokeData = emptyList<Pair<ShapeContour, List<CalligraphySample>>>()
        
        // Ink simulation system for physics-based rendering
        var inkSim: InkSimulation? = null
        var inkSimSeed = seed  // Track when simulation needs to be recreated
        
        // Render target for high-quality export
        val renderTarget = renderTarget(width, height) {
            colorBuffer()
            depthBuffer(DepthFormat.DEPTH24_STENCIL8)
        }
        
        // Font for UI
        val font = loadFont("data/fonts/default.otf", 12.0)
        val largeFont = loadFont("data/fonts/default.otf", 24.0)
        
        fun regenerate() {
            params = params.copy(seed = seed)
        }
        
        fun ensureInkSim() {
            // Create or recreate ink simulation if seed changed
            if (inkSim == null || inkSimSeed != seed) {
                inkSim?.destroy()
                inkSim = InkSimulation(width, height, seed)
                inkSimSeed = seed
            }
        }
        
        fun render(drawer: Drawer) {
            // Render paper background
            renderPaper(drawer, params)
            
            // Ensure ink simulation is ready if enabled
            if (params.useInkSim) {
                ensureInkSim()
            }
            
            // Render Katakana composition
            strokeData = renderComposition(drawer, params, debugMode, if (params.useInkSim) inkSim else null)
            
            // Debug overlay if enabled
            if (debugMode) {
                renderDebugOverlay(drawer, params, strokeData)
            }
            
            // Get current Katakana for display
            val katakana = getCurrentKatakana(params)
            
            // Display current character info at top
            drawer.isolated {
                drawer.fontMap = largeFont
                drawer.fill = ColorRGBa.fromHex("4A4A4A").opacify(0.6)
                
                // Character name and Unicode at top-left
                drawer.text("${katakana.unicode} (${katakana.name})", 20.0, 40.0)
                
                // Character index + simulation mode indicator
                drawer.fontMap = font
                val simMode = if (params.useInkSim) "[SIM]" else "[CLASSIC]"
                drawer.text("[${params.katakanaIndex + 1}/${KATAKANA_CHARACTERS.size}] $simMode", 20.0, 58.0)
            }
            
            // UI Legend at bottom
            drawer.isolated {
                drawer.fontMap = font
                drawer.fill = ColorRGBa.fromHex("4A4A4A").opacify(0.8)
                
                val legendY = height - 100.0
                val mode = if (params.paletteMode == 0) "Sumi" else "Indigo"
                val simStatus = if (params.useInkSim) "ON" else "OFF"
                
                drawer.text("カタカナ書道 (Katakana Calligraphy) | Seed: ${params.seed} | Mode: $mode", 15.0, legendY)
                drawer.text("Tension: ${String.format("%.2f", params.tension)} | CurvGain: ${String.format("%.2f", params.curvGain)} | Sim: $simStatus", 15.0, legendY + 15.0)
                drawer.text("Bleed: ${String.format("%.2f", params.wetDiffusion)} | Absorb: ${String.format("%.2f", params.absorbRate)} | Iter: ${params.simIterations}", 15.0, legendY + 30.0)
                drawer.text("←/→ = char | ↑/↓ = ±10 | R=reseed | 1/2=palette | I=sim toggle", 15.0, legendY + 45.0)
                drawer.text("-/+=tension K/J=curv B/V=bleed N/M=absorb D=debug E=export", 15.0, legendY + 60.0)
            }
        }
        
        // Keyboard controls
        keyboard.keyDown.listen { event ->
            when (event.name) {
                "r" -> {
                    seed = Random.nextLong()
                    regenerate()
                }
                "1" -> {
                    params = params.copy(paletteMode = 0)
                }
                "2" -> {
                    params = params.copy(paletteMode = 1)
                }
                "[" -> {
                    params = params.copy(pointCount = (params.pointCount - 2).coerceIn(8, 40))
                }
                "]" -> {
                    params = params.copy(pointCount = (params.pointCount + 2).coerceIn(8, 40))
                }
                "-" -> {
                    params = params.copy(tension = (params.tension - 0.1).coerceIn(0.5, 2.5))
                }
                "=" -> {
                    params = params.copy(tension = (params.tension + 0.1).coerceIn(0.5, 2.5))
                }
                "k" -> {
                    params = params.copy(curvGain = (params.curvGain + 0.1).coerceIn(0.0, 2.0))
                }
                "j" -> {
                    params = params.copy(curvGain = (params.curvGain - 0.1).coerceIn(0.0, 2.0))
                }
                "w" -> {
                    params = params.copy(washPasses = (params.washPasses + 1).coerceIn(0, 10))
                }
                "s" -> {
                    params = params.copy(washPasses = (params.washPasses - 1).coerceIn(0, 10))
                }
                "d" -> {
                    debugMode = !debugMode
                }
                "e" -> {
                    // Render to target and export
                    drawer.isolatedWithTarget(renderTarget) {
                        render(drawer)
                    }
                    exportPNG(drawer, params, renderTarget)
                }
                // Ink simulation toggle
                "i" -> {
                    params = params.copy(useInkSim = !params.useInkSim)
                    println("Ink simulation: ${if (params.useInkSim) "ON" else "OFF"}")
                }
                // Ink simulation parameters
                "b" -> {
                    // Increase bleed/wetness diffusion
                    params = params.copy(wetDiffusion = (params.wetDiffusion + 0.05).coerceIn(0.0, 1.0))
                }
                "v" -> {
                    // Decrease bleed/wetness diffusion
                    params = params.copy(wetDiffusion = (params.wetDiffusion - 0.05).coerceIn(0.0, 1.0))
                }
                "n" -> {
                    // Increase absorption rate
                    params = params.copy(absorbRate = (params.absorbRate + 0.02).coerceIn(0.0, 0.5))
                }
                "m" -> {
                    // Decrease absorption rate
                    params = params.copy(absorbRate = (params.absorbRate - 0.02).coerceIn(0.0, 0.5))
                }
                "," -> {
                    // Decrease simulation iterations
                    params = params.copy(simIterations = (params.simIterations - 2).coerceIn(2, 30))
                }
                "." -> {
                    // Increase simulation iterations
                    params = params.copy(simIterations = (params.simIterations + 2).coerceIn(2, 30))
                }
                ";" -> {
                    // Decrease sheen
                    params = params.copy(sheenStrength = (params.sheenStrength - 0.05).coerceIn(0.0, 1.0))
                }
                "'" -> {
                    // Increase sheen
                    params = params.copy(sheenStrength = (params.sheenStrength + 0.05).coerceIn(0.0, 1.0))
                }
                // Katakana navigation - arrow keys
                "arrow-right" -> {
                    // Next character
                    val newIndex = (params.katakanaIndex + 1) % KATAKANA_CHARACTERS.size
                    params = params.copy(katakanaIndex = newIndex)
                }
                "arrow-left" -> {
                    // Previous character
                    val newIndex = if (params.katakanaIndex <= 0) 
                        KATAKANA_CHARACTERS.lastIndex 
                    else 
                        params.katakanaIndex - 1
                    params = params.copy(katakanaIndex = newIndex)
                }
                "arrow-up" -> {
                    // Jump forward 10 characters
                    val newIndex = (params.katakanaIndex + 10) % KATAKANA_CHARACTERS.size
                    params = params.copy(katakanaIndex = newIndex)
                }
                "arrow-down" -> {
                    // Jump backward 10 characters
                    val newIndex = if (params.katakanaIndex < 10) 
                        KATAKANA_CHARACTERS.size - (10 - params.katakanaIndex)
                    else 
                        params.katakanaIndex - 10
                    params = params.copy(katakanaIndex = newIndex.coerceIn(0, KATAKANA_CHARACTERS.lastIndex))
                }
                " " -> {
                    // Random character
                    val rng = Random(System.currentTimeMillis())
                    params = params.copy(katakanaIndex = rng.nextInt(KATAKANA_CHARACTERS.size))
                    seed = Random.nextLong()  // Also reseed for variation
                    regenerate()
                }
            }
        }
        
        extend {
            render(drawer)
        }
    }
}
