import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.extra.color.presets.ORANGE
import org.openrndr.extra.color.spaces.toOKHSLa
import org.openrndr.extra.compositor.compose
import org.openrndr.extra.compositor.draw
import org.openrndr.extra.compositor.layer
import org.openrndr.extra.compositor.post
import org.openrndr.extra.envelopes.ADSRTracker
import org.openrndr.extra.fx.distort.Fisheye
import org.openrndr.extra.fx.distort.Lenses
import org.openrndr.extra.gui.GUI
import org.openrndr.extra.gui.addTo
import org.openrndr.extra.midi.MidiConsole
import org.openrndr.extra.midi.openMidiDevice
import org.openrndr.extra.noise.uniform
import org.openrndr.extra.olive.oliveProgram
import org.openrndr.math.IntVector2
import org.openrndr.math.Vector2

fun main() {

    application {
        configure {
            width = 600
            height = 480
            windowAlwaysOnTop = true
            position = IntVector2(100, 100)
        }

        oliveProgram {
            val midi = openMidiDevice("Live")

            val gui = GUI().apply {
                name = "Settings"
            }

            extend(MidiConsole()) {
                register(midi)
                historySize = 10
            }

            val tracker = ADSRTracker(this)
            tracker.addTo(gui)

            midi.noteOn.listen {
               tracker.triggerOn(it.note) { time, value, position ->
                   val normalizedNote = (it.note % 12) + 1
                   val screenSection = width / 12.0
                   val x = normalizedNote * screenSection - screenSection / 2

                   val noteColors = mapOf(
                       0 to ColorRGBa.RED,
                       1 to ColorRGBa.ORANGE,
                       2 to ColorRGBa.YELLOW,
                       3 to ColorRGBa.GREEN,
                       4 to ColorRGBa.CYAN,
                       5 to ColorRGBa.BLUE,
                       6 to ColorRGBa.MAGENTA,
                       7 to ColorRGBa.PINK,
                       8 to ColorRGBa.WHITE,
                       9 to ColorRGBa.fromHex("#800080"), // Purple
                       10 to ColorRGBa.fromHex("#A0522D"), // Sienna
                       11 to ColorRGBa.fromHex("#4B0082") // Indigo
                   )
                   val color = noteColors[it.note % 12] ?: ColorRGBa.WHITE
                   drawer.fill = color
                   println("note on $it $time $value $position")

                   drawer.circle(Vector2(x, height / 2.0), value * 50.0)
               }
            }

            midi.noteOff.listen {
                tracker.triggerOff(it.note)
            }

            extend(gui)
            extend {
                gui.visible = mouse.position.x < 200.0
                drawer.stroke = null
//                drawer.circle(drawer.bounds.center, tracker.value() * 200)
                for(v in tracker.values()) {
                    v.draw()
                }
            }
        }

    }
}