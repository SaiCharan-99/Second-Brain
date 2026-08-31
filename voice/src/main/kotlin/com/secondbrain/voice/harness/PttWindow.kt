package com.secondbrain.voice.harness

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities

/**
 * The Step 1 push-to-talk front-end.
 *
 * Why Swing and not a console loop: hold-to-talk needs key-DOWN and key-UP as
 * separate events, and a JVM console gets line-buffered stdin with no key-release
 * event at all -- `./gradlew :voice:run` with "hold Space" is not expressible in
 * a terminal (E5). Swing ships with the JDK, so this costs no dependency and,
 * critically, does not pull Compose into Step 1: the real UI is Step 4 and is
 * specified against the design board (voice screen 1a), not against this.
 *
 * This is a test rig. It borrows the design board's colour tokens so the states
 * read the same way they will in the real UI, and nothing more.
 *
 * Key repeat is the trap here: holding a key on Windows fires keyPressed
 * repeatedly, so a naive listener restarts capture ~30 times a second. The
 * [down] flag is what makes hold-to-talk actually work.
 */
class PttWindow(
    private val onTalkStart: () -> Unit,
    private val onTalkEnd: () -> Unit,
    /** EC-V3 / E3: any keypress during playback cuts the audio. */
    private val onBargeIn: () -> Unit,
    private val onQuit: () -> Unit,
) {

    // Design board tokens (artifacts/Second Brain UI.html, option 1a).
    private companion object {
        val CANVAS = Color(0xF7, 0xF7, 0xF5)
        val INK = Color(0x17, 0x17, 0x1A)
        val MUTED = Color(0x8A, 0x8A, 0x90)
        val BLUE = Color(0x2E, 0x6B, 0xE6)
        val GREEN = Color(0x3E, 0xA7, 0x6B)
        val AMBER = Color(0xD6, 0x9A, 0x2B)
    }

    private val stateLabel = JLabel("Idle")
    private val hintLabel = JLabel("Hold SPACE to talk - release to send.  ESC quits.")
    private val transcript = JTextArea()
    private val statusLabel = JLabel(" ")

    @Volatile
    private var down = false

    @Volatile
    private var playing = false

    private val frame = JFrame("Second Brain - voice harness (Step 1)")

    fun show() {
        SwingUtilities.invokeAndWait {
            stateLabel.font = Font(Font.SANS_SERIF, Font.BOLD, 26)
            stateLabel.foreground = INK
            stateLabel.alignmentX = JPanel.CENTER_ALIGNMENT

            hintLabel.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
            hintLabel.foreground = MUTED
            hintLabel.alignmentX = JPanel.CENTER_ALIGNMENT

            val header = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                background = CANVAS
                border = BorderFactory.createEmptyBorder(28, 24, 20, 24)
                add(stateLabel)
                add(JPanel().apply { background = CANVAS; preferredSize = Dimension(1, 8) })
                add(hintLabel)
            }

            transcript.apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                font = Font(Font.MONOSPACED, Font.PLAIN, 12)
                background = Color.WHITE
                foreground = INK
                border = BorderFactory.createEmptyBorder(12, 14, 12, 14)
            }

            statusLabel.font = Font(Font.MONOSPACED, Font.PLAIN, 11)
            statusLabel.foreground = MUTED
            statusLabel.border = BorderFactory.createEmptyBorder(6, 14, 8, 14)

            frame.contentPane.apply {
                background = CANVAS
                layout = BorderLayout()
                add(header, BorderLayout.NORTH)
                add(JScrollPane(transcript), BorderLayout.CENTER)
                add(statusLabel, BorderLayout.SOUTH)
            }

            val listener = object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    when (e.keyCode) {
                        KeyEvent.VK_ESCAPE -> onQuit()
                        KeyEvent.VK_SPACE -> {
                            // EC-V3: cut playback before starting to listen, so the
                            // user talking over the reply is heard from the first word.
                            if (playing) onBargeIn()
                            if (!down) {
                                down = true
                                onTalkStart()
                            }
                        }
                        else -> if (playing) onBargeIn()
                    }
                }

                override fun keyReleased(e: KeyEvent) {
                    if (e.keyCode == KeyEvent.VK_SPACE && down) {
                        down = false
                        onTalkEnd()
                    }
                }
            }

            frame.addKeyListener(listener)
            transcript.addKeyListener(listener)
            frame.addWindowListener(object : WindowAdapter() {
                override fun windowClosing(e: WindowEvent) = onQuit()
            })

            frame.defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
            frame.size = Dimension(780, 520)
            frame.setLocationRelativeTo(null)
            frame.isFocusable = true
            frame.isVisible = true
            frame.requestFocus()
        }
    }

    /** Idle / Listening / Thinking / Speaking, per the design board's state indicator. */
    fun setState(state: String) = SwingUtilities.invokeLater {
        stateLabel.text = state
        stateLabel.foreground = when (state) {
            "Listening" -> BLUE
            "Thinking" -> AMBER
            "Speaking" -> GREEN
            else -> INK
        }
        playing = state == "Speaking"
    }

    fun append(line: String) = SwingUtilities.invokeLater {
        transcript.append(line + "\n")
        transcript.caretPosition = transcript.document.length
    }

    fun setStatus(text: String) = SwingUtilities.invokeLater { statusLabel.text = text }

    fun close() = SwingUtilities.invokeLater { frame.dispose() }
}
