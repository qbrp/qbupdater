package org.lain.qbupdater

import com.formdev.flatlaf.FlatDarkLaf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.PrintStream
import javax.imageio.ImageIO
import javax.swing.*
import kotlin.coroutines.cancellation.CancellationException


val FONT = "Segoe UI"

class JTextAreaOutputStream(
    private val textArea: JTextArea
) : OutputStream() {

    override fun write(b: Int) {
        write(byteArrayOf(b.toByte()))
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        val text = String(b, off, len, Charsets.UTF_8)

        SwingUtilities.invokeLater {
            textArea.append(text)
            textArea.caretPosition = textArea.document.length
        }
    }
}

fun CenteredButton(text: String) = JButton(text).apply {
    font = Font("Segoe UI", Font.BOLD, 18)
    alignmentX = JPanel.LEFT_ALIGNMENT
    preferredSize = Dimension(Int.MAX_VALUE, 45)
}

fun setupFlatDarkLafStyle() {
    FlatDarkLaf.setup()
    UIManager.put("Button.arc", 20)
    UIManager.put("Component.arc", 20)
    UIManager.put("TextComponent.arc", 20)
    UIManager.put("ProgressBar.arc", 20)
}

fun loadImage(path: String): BufferedImage? {
    try {
        Thread.currentThread().contextClassLoader.getResourceAsStream(path).use { im ->
            requireNotNull(im) { "Image file not found in resources!" }
            return ImageIO.read(im)
        }
    } catch (e: IOException) {
        e.printStackTrace()
        return null
    }
}

fun setupWindow() {
    setupFlatDarkLafStyle()

    val frame = JFrame("qbupdater")
    frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
    frame.setSize(600, 500)
    frame.isResizable = false
    frame.setLocationRelativeTo(null)
    frame.iconImage = loadImage("lain.ico")

    val image = loadImage("pc.png")

    // margin
    val margin = 15
    val gap = 10
    val root = object : JPanel(BorderLayout(gap, gap)) {
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            g as Graphics2D
            g.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR
            )
            g.setRenderingHint(
                RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY
            )
            g.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON
            )
            g.drawImage(image, 0, 0, getWidth(), getHeight(), this)
        }
    }

    root.border = BorderFactory.createEmptyBorder(margin, margin, margin, margin)
    frame.contentPane = root

    val pathField = JTextField()
    pathField.font = Font(FONT, Font.PLAIN, 16)
    pathField.preferredSize = Dimension(0, 30)
    pathField.text = restoreGamePath() ?: ""

    val browseButton = JButton("📁")
    browseButton.font = Font("Segoe UI Emoji", Font.PLAIN, 18)
    browseButton.preferredSize = Dimension(55, 30)

    val buttonPanel = JPanel(BorderLayout())

    val updateButton = CenteredButton("Запуск")
    val cancelButton = CenteredButton("Отмена")

    buttonPanel.add(updateButton, BorderLayout.CENTER)

    val progressBar = JProgressBar()
    progressBar.minimum = 0
    progressBar.maximum = 100
    progressBar.isStringPainted = true
    progressBar.value = 0
    progressBar.preferredSize = Dimension(0, 30)

    val console = JTextArea()
    console.isEditable = false
    console.font = Font("Consolas", Font.PLAIN, 14)
    console.lineWrap = true
    console.wrapStyleWord = true

    val scrollPane = JScrollPane(console)
    scrollPane.border = BorderFactory.createTitledBorder(
        "Журнал"
    )

    val pathPanel = JPanel(BorderLayout(8, 0))
    pathPanel.add(pathField, BorderLayout.CENTER)
    pathPanel.add(browseButton, BorderLayout.EAST)

    val controlsPanel = JPanel()
    controlsPanel.layout = BoxLayout(
        controlsPanel,
        BoxLayout.Y_AXIS
    )

    controlsPanel.add(pathPanel)
    controlsPanel.add(Box.createVerticalStrut(10))
    controlsPanel.add(buttonPanel)
    controlsPanel.add(Box.createVerticalStrut(10))
    controlsPanel.add(progressBar)


    root.add(controlsPanel, BorderLayout.NORTH)
    root.add(scrollPane, BorderLayout.CENTER)

    controlsPanel.isOpaque = false
    pathPanel.isOpaque = false
    buttonPanel.isOpaque = false
    scrollPane.isOpaque = false
    scrollPane.viewport.isOpaque = false
    console.isOpaque = false

    var updateProcess: Job? = null

    fun setButtonStateBeforeDownload() {
        progressBar.isIndeterminate = false

        buttonPanel.remove(cancelButton)
        buttonPanel.add(updateButton)
        buttonPanel.revalidate()
        buttonPanel.repaint()
    }

    updateButton.addActionListener {
        val gamePathStr = pathField.text.trim()
        if (gamePathStr.isBlank()) {
            JOptionPane.showMessageDialog(
                frame,
                "Выберите папку игры"
            )
            return@addActionListener
        }
        val gamePath = runCatching { File(gamePathStr) }
            .onFailure { JOptionPane.showMessageDialog(frame, "Введён неправильный путь") }
            .getOrNull() ?: return@addActionListener
        saveGamePath(gamePathStr)

        progressBar.isIndeterminate = true

        buttonPanel.remove(updateButton)
        buttonPanel.add(cancelButton)
        buttonPanel.revalidate()
        buttonPanel.repaint()

        updateProcess = CoroutineScope(Dispatchers.IO).launch {
            try {
                val version = fetchVersion(gamePath)
                val updates = requestUpdates(version) ?: run {
                    JOptionPane.showMessageDialog(frame, "Обновление не требуется")
                    return@launch
                }
                val hasErrors = requestDownloadUpdate(gamePath, updates) { percent ->
                    progressBar.value = percent
                }
                if (hasErrors) {
                    SwingUtilities.invokeLater {
                        JOptionPane.showMessageDialog(
                            frame,
                            "Во время установки некоторые файлы были пропущены или возникли ошибки удаления. Проверьте журнал"
                        )
                    }
                }
            } catch (e: CancellationException) {

            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    JOptionPane.showMessageDialog(frame, e.message)
                }
            } finally {
                SwingUtilities.invokeLater {
                    progressBar.isIndeterminate = false
                    setButtonStateBeforeDownload()
                }
            }
        }
    }


    cancelButton.addActionListener {
        updateProcess?.cancel() ?: return@addActionListener
        progressBar.value = 0
        setButtonStateBeforeDownload()
    }

    browseButton.addActionListener {
        val chooser = JFileChooser()

        chooser.fileSelectionMode =
            JFileChooser.DIRECTORIES_ONLY

        if (chooser.showOpenDialog(frame) ==
            JFileChooser.APPROVE_OPTION
        ) {
            pathField.text =
                chooser.selectedFile.absolutePath
        }
    }

    val stream = PrintStream(
        JTextAreaOutputStream(console),
        true,
        Charsets.UTF_8
    )
    System.setOut(stream)
    System.setErr(stream)

    frame.isVisible = true
}