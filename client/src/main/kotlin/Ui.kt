package org.lain.qbupdater

import com.formdev.flatlaf.FlatDarkLaf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.*
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
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
    frame.iconImage = loadImage("lain.png")

    val image = loadImage("pc.png")

    // margin
    val margin = 15
    val gap = 10
    val root = object : JPanel(BorderLayout(gap, gap)) {
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            g as Graphics2D
            g.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR
            )
            g.setRenderingHint(
                RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY
            )
            g.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON
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

    val backupCheckbox = JCheckBox("Создавать резервную копию config и options.txt перед применением обновления")
    backupCheckbox.isSelected = restoreBackupCheckbox()

    val pathPanel = JPanel(BorderLayout(8, 0))
    pathPanel.add(pathField, BorderLayout.CENTER)
    pathPanel.add(browseButton, BorderLayout.EAST)

    val controlsPanel = JPanel()
    controlsPanel.layout = BoxLayout(
        controlsPanel, BoxLayout.Y_AXIS
    )

    controlsPanel.add(pathPanel)
    controlsPanel.add(Box.createVerticalStrut(10))
    controlsPanel.add(buttonPanel)
    controlsPanel.add(Box.createVerticalStrut(10))
    controlsPanel.add(progressBar)


    root.add(controlsPanel, BorderLayout.NORTH)
    root.add(scrollPane, BorderLayout.CENTER)
    root.add(backupCheckbox, BorderLayout.SOUTH)

    controlsPanel.isOpaque = false
    pathPanel.isOpaque = false
    buttonPanel.isOpaque = false
    scrollPane.isOpaque = false
    scrollPane.viewport.isOpaque = false
    console.isOpaque = false

    var updateProcess: Job? = null

    fun setButtonStateBeforeDownload() {
        progressBar.isIndeterminate = false
        progressBar.string = null

        buttonPanel.remove(cancelButton)
        buttonPanel.add(updateButton)
        buttonPanel.revalidate()
        buttonPanel.repaint()
    }

    updateButton.addActionListener {
        val windowScope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
        updateProcess = windowScope.launch {
            val gamePathStr = pathField.text.trim()
            if (gamePathStr.isBlank()) {
                JOptionPane.showMessageDialog(
                    frame, "Выберите папку игры"
                )
                return@launch
            }

            val gamePath = runCatching { File(gamePathStr) }.onFailure {
                    JOptionPane.showMessageDialog(
                        frame,
                        "Введён неправильный путь"
                    )
                }.getOrNull() ?: return@launch

            val (version, gameNotInstalled) = withContext(Dispatchers.IO) {
                val version = fetchVersion(gamePath) ?: runCatching { fetchEngineVersion(gamePath) }.getOrNull()
                version to showGameFolderWarn(gamePath)
            }

            if (version == null) {
                val result = if (gameNotInstalled) {
                    val options = arrayOf("Продолжить", "Выбрать другую папку")

                    JOptionPane.showOptionDialog(
                        frame,
                        "В указанной директории не установлен Minecraft. Если вы уверены, что выбрали правильную папку, нажмите 'Продолжить'",
                        "Предупреждение",
                        JOptionPane.DEFAULT_OPTION,
                        JOptionPane.WARNING_MESSAGE,
                        null,
                        options,
                        options[0]
                    )
                } else {
                    JOptionPane.showConfirmDialog(
                        frame,
                        "Не удалось определить версию сборки qbrp, вследствие чего будет установлена последняя её версия. Некоторые файлы могут быть перезписаны",
                        "Установка с нуля",
                        JOptionPane.DEFAULT_OPTION,
                        JOptionPane.WARNING_MESSAGE,
                    )
                }
                if (result == 1 || result == JOptionPane.CLOSED_OPTION) {
                    return@launch
                }
            }
            val resolvedVersion = version ?: "none"

            saveGamePath(gamePathStr)

            if (isMinecraftRunning()) {
                JOptionPane.showMessageDialog(
                    frame, "Перед обновлением закройте Minecraft"
                )
                return@launch
            }

            progressBar.isIndeterminate = true

            buttonPanel.remove(updateButton)
            buttonPanel.add(cancelButton)
            buttonPanel.revalidate()
            buttonPanel.repaint()

            try {
                val updates = withContext(Dispatchers.IO) { requestUpdates(resolvedVersion) } ?: run {
                    JOptionPane.showMessageDialog(frame, "Обновление не требуется")
                    return@launch
                }

                if (backupCheckbox.isSelected) {
                    println("Резервное копирование файлов")
                    withContext(Dispatchers.IO) { backupOptions(gamePath) }
                }
                progressBar.isIndeterminate = false
                progressBar.value = 0
                val hasErrors = withContext(Dispatchers.IO) {
                    requestDownloadUpdate(
                        gamePath,
                        updates,
                        progressListener = { progress ->
                            SwingUtilities.invokeLater {
                                when (progress) {
                                    is UpdateProgress.Determinate -> {
                                        progressBar.isIndeterminate = false
                                        progressBar.value = progress.percent
                                        progressBar.string = null
                                    }

                                    UpdateProgress.ApplyingFiles -> {
                                        progressBar.isIndeterminate = true
                                        progressBar.string = "Установка..."
                                    }
                                }
                            }
                        },
                        conflictDecision = { conflict ->
                            withContext(Dispatchers.Swing) {
                                when (conflict) {
                                    UpdateConflict.OptionsFile -> {
                                        val replace = JOptionPane.showConfirmDialog(
                                            frame,
                                            "Обновление содержит options.txt. Перезаписать текущие настройки Minecraft?",
                                            "Замена настроек",
                                            JOptionPane.YES_NO_OPTION,
                                            JOptionPane.WARNING_MESSAGE,
                                        ) == JOptionPane.YES_OPTION
                                        if (replace) ReplacementDecision.REPLACE else ReplacementDecision.KEEP
                                    }

                                    is UpdateConflict.Mod -> {
                                        val installed = conflict.installedFiles.zip(conflict.installedVersions)
                                            .joinToString("\n") { (file, version) ->
                                                "• $file — ${version ?: "версия неизвестна"}"
                                            }
                                        val updated = conflict.updateFiles.joinToString("\n") { file ->
                                            "• $file — ${conflict.updateVersion ?: "версия неизвестна"}"
                                        }
                                        val options = arrayOf("Заменить", "Заменить все конфликтующие", "Оставить")
                                        when (JOptionPane.showOptionDialog(
                                            frame,
                                            """
                                                |В обновлении найден мод «${conflict.displayName}» (id: ${conflict.identifier}).
                                                |Установлено:
                                                |$installed
                                                |
                                                |В обновлении:
                                                |$updated
                                                |
                                                |Удалить установленный мод и поставить версию из обновления?
                                                """.trimMargin(),
                                            "Замена мода",
                                            JOptionPane.DEFAULT_OPTION,
                                            JOptionPane.WARNING_MESSAGE,
                                            null,
                                            options,
                                            options[0],
                                        )) {
                                            0 -> ReplacementDecision.REPLACE
                                            1 -> ReplacementDecision.REPLACE_ALL_MODS
                                            else -> ReplacementDecision.KEEP
                                        }
                                    }
                                }
                            }
                        },
                    )
                }
                if (hasErrors) {
                    JOptionPane.showMessageDialog(
                        frame,
                        "Во время установки некоторые файлы были пропущены или возникли ошибки удаления. Проверьте журнал"
                    )
                }
            } catch (e: CancellationException) {

            } catch (e: Exception) {
                JOptionPane.showMessageDialog(frame, e.message, "Ошибка", JOptionPane.ERROR_MESSAGE)
            } finally {
                progressBar.isIndeterminate = false
                setButtonStateBeforeDownload()
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

        chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY

        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            pathField.text = chooser.selectedFile.absolutePath
        }
    }

    val stream = PrintStream(
        JTextAreaOutputStream(console), true, Charsets.UTF_8
    )
    System.setOut(stream)
    System.setErr(stream)

    frame.addWindowListener(object : WindowAdapter() {
        override fun windowClosing(e: WindowEvent) {
            saveBackupCheckbox(backupCheckbox.isSelected)
        }
    })

    frame.isVisible = true
}
