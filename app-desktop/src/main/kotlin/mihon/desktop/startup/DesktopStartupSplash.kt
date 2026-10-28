package mihon.desktop.startup

import java.awt.AWTEvent
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.WindowEvent
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import javax.imageio.ImageIO
import javax.swing.ImageIcon
import javax.swing.JLabel
import javax.swing.JWindow

internal interface StartupSplashSurface : AutoCloseable {
    val size: Dimension
    fun locate(x: Int, y: Int)
    fun show()
}

internal fun showStartupSplash(surface: StartupSplashSurface, workArea: Rectangle): AutoCloseable {
    try {
        surface.locate(
            workArea.x + (workArea.width - surface.size.width) / 2,
            workArea.y + (workArea.height - surface.size.height) / 2,
        )
        surface.show()
        return surface
    } catch (failure: Throwable) {
        runCatching { surface.close() }.exceptionOrNull()?.let(failure::addSuppressed)
        throw failure
    }
}

internal fun showDesktopStartupSplash(
    args: Array<String>,
    windows: Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true),
    headless: Boolean = GraphicsEnvironment.isHeadless(),
    imagePath: String? = System.getProperty("mihon.desktop.startupSplash"),
    open: (String) -> AutoCloseable = ::openAwtStartupSplash,
): AutoCloseable? {
    if (!windows || headless || "--headless" in args || imagePath.isNullOrBlank()) return null
    return runCatching { open(imagePath) }.onFailure {
        System.err.println("Desktop startup splash unavailable: ${it.message}")
    }.getOrNull()
}

private fun openAwtStartupSplash(imagePath: String): AutoCloseable = onSplashEdt {
    // Select the device before constructing the native peer, including on mixed-DPI desktops.
    val configuration = MouseInfo.getPointerInfo()?.device?.defaultConfiguration
        ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
    val image = requireNotNull(ImageIO.read(File(imagePath))) { "Invalid startup splash image" }
    val toolkit = Toolkit.getDefaultToolkit()
    val workArea = Rectangle(configuration.bounds)
    val insets = toolkit.getScreenInsets(configuration)
    workArea.apply {
        x += insets.left
        y += insets.top
        width -= insets.left + insets.right
        height -= insets.top + insets.bottom
    }
    val window = JWindow(configuration).apply {
        name = "MihonStartupSplash"
        focusableWindowState = false
        isAutoRequestFocus = false
        contentPane.add(JLabel(ImageIcon(image)))
        pack()
    }
    val surface = object : StartupSplashSurface {
        private var closed = false
        private val opening: AWTEventListener = AWTEventListener { event ->
            if (event is WindowEvent && event.id == WindowEvent.WINDOW_OPENED && event.window !== window) {
                // Let the main window's opening/geometry callbacks reveal its first frame first.
                EventQueue.invokeLater { EventQueue.invokeLater { close() } }
            }
        }

        override val size: Dimension get() = window.size

        override fun locate(x: Int, y: Int) = window.setLocation(x, y)

        override fun show() {
            toolkit.addAWTEventListener(opening, AWTEvent.WINDOW_EVENT_MASK)
            window.isVisible = true
        }

        override fun close(): Unit = onSplashEdt {
            if (!closed) {
                closed = true
                toolkit.removeAWTEventListener(opening)
                window.dispose()
            }
        }
    }
    showStartupSplash(surface, workArea)
}

private fun <T> onSplashEdt(block: () -> T): T {
    if (EventQueue.isDispatchThread()) return block()
    val task = FutureTask(Callable(block))
    EventQueue.invokeAndWait(task)
    return task.get()
}
