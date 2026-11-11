package mihon.desktop.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.theme.colorscheme.TachiyomiColorScheme
import kotlinx.coroutines.Dispatchers
import mihon.domain.reader.ReaderScrollMode
import mihon.domain.reader.ReaderSettings
import mihon.domain.reader.ReaderSettingsLayout
import mihon.domain.reader.ReaderSettingsMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Locale

/** Actual production controls, pointer input, layout bounds and Skia pixels; no HTML or model replica. */
@OptIn(ExperimentalComposeUiApi::class)
class ReaderModePrototypeVisualTest {
    @Test
    fun `horizontal controls keep prototype grouping card heights icons and subdued selection`() = fixture { ui ->
        val mode = ui.option("横向阅读").boundsInRoot
        val direction = ui.option("从右到左").boundsInRoot
        assertTrue(mode.height >= 82f, "Mode cards retain their icon, copy and radio layout")
        assertTrue(direction.height >= 74f, "Direction cards retain an arrow above their label")
        assertEquals(ui.option("从左到右").boundsInRoot.width, direction.width, 1f)
        val single = ui.option("单页").boundsInRoot
        val dual = ui.option("双页").boundsInRoot
        assertEquals(single.right, dual.left, 1f, "Segments share one continuous track")
        assertTrue(ui.hasText("下方设置仅对当前作品生效"))
        assertTrue(ui.hasText("在双页显示时，自动调整页面配对"))
        ui.frame().use { image ->
            val bitmap = image.toComposeImageBitmap().asSkiaBitmap()
            val pixel = bitmap.getColor(mode.left.toInt() + 4, mode.center.y.toInt())
            val channels = listOf((pixel shr 16) and 255, (pixel shr 8) and 255, pixel and 255)
            assertTrue(channels.max() - channels.min() < 100, "Selection uses the prototype's muted fill")
            val beside = bitmap.getColor(direction.center.x.toInt() + 20, direction.top.toInt() + 20)
            val painted = (12..33).sumOf { y ->
                (-10..10).count { x ->
                    bitmap.getColor(direction.center.x.toInt() + x, direction.top.toInt() + y) !=
                        beside
                }
            }
            assertTrue(painted > 12, "The direction arrow is actually painted")
        }
        ui.capture("horizontal-double")
        ui.click("单页")
        assertFalse(ui.hasText("双页自动匹配"))
        ui.capture("horizontal-single")
        ui.click("自动")
        assertTrue(ui.hasText("根据阅读区域宽高比自动切换单/双页"))
        ui.capture("horizontal-auto")
    }

    @Test
    fun `default summary uses separate visible rows and custom settings restore on click`() = fixture { ui ->
        ui.click("跟随默认")
        assertTrue(ui.hasText("跟随全局阅读偏好"))
        assertTrue(ui.hasText("默认设置变更后，当前作品同步采用。"))
        val mode = ui.text("横向阅读").boundsInRoot
        val layout = ui.text("双页").boundsInRoot
        val direction = ui.text("从右到左").boundsInRoot
        assertTrue(mode.bottom <= layout.top && layout.bottom <= direction.top)
        ui.capture("default-summary")
        ui.click("单独设置")
        assertTrue(ui.option("双页").config[SemanticsProperties.Selected])
        ui.click("从左到右")
        ui.click("跟随默认")
        ui.click("单独设置")
        assertTrue(ui.option("从左到右").config[SemanticsProperties.Selected])
    }

    @Test
    fun `vertical segmented controls show explanatory gap card only during continuous scrolling`() = fixture { ui ->
        ui.click("纵向阅读")
        assertTrue(ui.hasText("在相邻页面之间留出间隔"))
        val continuous = ui.option("连续滚动").boundsInRoot
        val paged = ui.option("逐页翻动").boundsInRoot
        assertEquals(continuous.right, paged.left, 1f)
        ui.click("页面有空隙")
        assertTrue(ui.settings.value.gap)
        ui.capture("vertical-continuous")
        ui.click("逐页翻动")
        assertEquals(ReaderScrollMode.PAGED, ui.settings.value.scroll)
        assertFalse(ui.hasText("页面有空隙"))
        ui.capture("vertical-paged")
        ui.click("跟随默认")
        assertTrue(ui.hasText("逐页翻动"))
        assertFalse(ui.hasText("页面有空隙") || ui.hasText("单双页") || ui.hasText("翻页方向"))
        ui.capture("vertical-default-paged")
        ui.click("单独设置")
        ui.click("连续滚动")
        assertTrue(ui.settings.value.gap)
        assertFalse(ui.hasText("是") || ui.hasText("否"))
    }

    @Test
    fun `prototype cards and switches retain focus and activate exactly once with keyboard`() = fixture { ui ->
        ui.activate("从左到右", Key.Enter)
        assertTrue(ui.option("从左到右").config[SemanticsProperties.Selected])
        ui.activate("纵向阅读", Key.Spacebar)
        assertEquals(ReaderSettingsMode.VERTICAL, ui.settings.value.mode)
        ui.activate("页面有空隙", Key.Enter)
        assertTrue(ui.settings.value.gap)
        ui.activate("页面有空隙", Key.Spacebar)
        assertFalse(ui.settings.value.gap)
        ui.activate("跟随默认", Key.Enter)
        assertTrue(ui.follows.value)
    }

    @Test
    fun `narrow and enlarged text remains within production controls in light dark and AMOLED`() {
        val schemes = listOf(
            "dark" to TachiyomiColorScheme.darkScheme,
            "light" to TachiyomiColorScheme.lightScheme,
            "amoled" to TachiyomiColorScheme.getColorScheme(true, true, true),
        )
        for ((name, scheme) in schemes) {
            fixture(width = 360, fontScale = 1.6f, scheme = scheme) { ui ->
                for (label in listOf("跟随默认", "单独设置", "横向阅读", "纵向阅读", "自动", "单页", "双页")) {
                    val option = ui.option(label)
                    assertTrue(option.boundsInRoot.left >= 0 && option.boundsInRoot.right <= 360)
                    for (text in ui.flatten(option).filter { it.config.contains(SemanticsProperties.Text) }) {
                        assertTrue(text.boundsInRoot.left >= option.boundsInRoot.left - 1)
                        assertTrue(text.boundsInRoot.right <= option.boundsInRoot.right + 1)
                    }
                }
                ui.capture("narrow-$name")
                ui.click("纵向阅读")
                assertEquals(ReaderSettingsMode.VERTICAL, ui.settings.value.mode)
                ui.capture("narrow-$name-vertical")
            }
        }
    }

    private fun fixture(
        width: Int = 560,
        fontScale: Float = 1f,
        scheme: ColorScheme = TachiyomiColorScheme.darkScheme,
        test: (Fixture) -> Unit,
    ) {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        val ui = Fixture()
        try {
            ui.scene = ImageComposeScene(width, 1400, coroutineContext = Dispatchers.Unconfined) {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                    MaterialTheme(colorScheme = scheme) {
                        Column(
                            Modifier.fillMaxSize().background(scheme.surfaceContainerHigh)
                                .verticalScroll(rememberScrollState()).padding(24.dp),
                        ) {
                            ReaderModeSettingsContent(
                                ui.settings.value,
                                ui.follows.value,
                                { ui.follows.value = true },
                                { ui.follows.value = false },
                                { ui.settings.value = it },
                                actualDualPage = true,
                            )
                        }
                    }
                }
            }
            repeat(3) { ui.frame().close() }
            test(ui)
        } finally {
            ui.scene.close()
            Locale.setDefault(previous)
        }
    }

    private class Fixture {
        val settings = mutableStateOf(ReaderSettings(layout = ReaderSettingsLayout.DOUBLE))
        val follows = mutableStateOf(false)
        lateinit var scene: ImageComposeScene
        private var time = 0L
        fun frame(): org.jetbrains.skia.Image = scene.render((time + 1_000_000_000L).also { time = it })
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        fun nodes() = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
        fun hasText(label: String) = nodes().any { n ->
            n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true
        }
        fun text(label: String) = nodes().first { n ->
            n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true
        }
        fun option(label: String) = nodes().first { n ->
            n.config.contains(SemanticsActions.OnClick) && flatten(n).any { child ->
                child.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true ||
                    child.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true
            }
        }
        fun click(label: String) {
            val position = option(label).boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Press, position)
            scene.sendPointerEvent(PointerEventType.Release, position)
            repeat(3) { frame().close() }
        }
        fun activate(label: String, key: Key) {
            assertTrue(requireNotNull(option(label).config[SemanticsActions.RequestFocus].action).invoke())
            frame().close()
            val events = Class.forName("androidx.compose.ui.input.key.KeyEvent_desktopKt")
            val factory = events.declaredMethods.single {
                it.name.startsWith("KeyEvent-") && !it.name.endsWith("\$default")
            }
            for (phase in listOf("Down", "Up")) {
                val type = Class.forName("androidx.compose.ui.input.key.KeyEventType")
                    .getMethod("access\$getKey$phase\$cp").invoke(null)
                val native = factory.invoke(null, key.keyCode, type, 0, false, false, false, false, null)
                scene.sendKeyEvent(androidx.compose.ui.input.key.KeyEvent(native))
                repeat(2) { frame().close() }
            }
        }
        fun capture(name: String) {
            val directory = File("../.gradle-coordinator/reader-settings-visual").apply { mkdirs() }
            frame().use { image ->
                requireNotNull(image.encodeToData()).use { File(directory, "$name.png").writeBytes(it.bytes) }
            }
        }
    }
}
