package eu.kanade.presentation.more.settings.screen.about

import android.app.Activity
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.app.ActivityOptionsCompat
import eu.kanade.tachiyomi.network.NetworkHelper
import io.mockk.every
import io.mockk.mockk
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar
import java.io.File
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "en")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidFeedbackScreenshotPickerWiringTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun `default About launches image document picker and displays final private JPEG`() {
        val previous = Injekt
        val locale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        Injekt = InjektScope(DefaultRegistrar())
        val configServer = mockwebserver3.MockWebServer().also {
            it.start()
            it.enqueue(
                mockwebserver3.MockResponse(
                    body = """
{"accepting":true,
"siteKey":"public",
"capabilities":{"nativeV2":true,
"screenshots":true},
"limits":{"maxScreenshots":2,
"maxScreenshotBytes":524288,
"maxTotalScreenshotBytes":1048576,
"maxScreenshotSide":1920,
"maxBodyBytes":2097152,
"maxLogBytes":262144}}
""",
                ),
            )
        }
        val configClient = OkHttpClient.Builder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().url(configServer.url(chain.request().url.encodedPath)).build())
        }.build()
        Injekt.addSingleton(mockk<NetworkHelper> { every { nonCloudflareClient } returns configClient })
        val diagnosticScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
        )
        Injekt.addSingleton(
            mihon.feedback.createAndroidDiagnosticStore(
                org.robolectric.RuntimeEnvironment.getApplication(),
                diagnosticScope,
            ),
        )
        Injekt.addSingleton(tachiyomi.domain.feedback.FeedbackDraftMemory())
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val source = File(activity.get().cacheDir, "fictional-private-screenshot.png")
        val bitmap = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888)
        source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        var opened = 0
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun query(
                uri: Uri,
                projection: Array<out String>?,
                selection: String?,
                args: Array<out String>?,
                sort: String?,
            ): Cursor =
                MatrixCursor(arrayOf(OpenableColumns.SIZE)).apply { addRow(arrayOf(source.length())) }
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
                opened++
                return ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
            }
            override fun getType(uri: Uri) = "image/png"
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
        }
        provider.attachInfo(
            activity.get(),
            android.content.pm.ProviderInfo().apply {
                authority = "feedback-fixture"
                exported = false
                grantUriPermissions = true
            },
        )
        ShadowContentResolver.registerProviderInternal("feedback-fixture", provider)
        var launches = 0
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                code: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?,
            ) {
                assertEquals(listOf("image/png", "image/jpeg"), (input as Array<*>).toList())
                launches++
                dispatchResult(
                    code,
                    if (launches == 1) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                    Intent().setData(Uri.parse("content://feedback-fixture/selected")),
                )
            }
        }
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = registry
        }
        val screenshot = SemanticsMatcher("Final screenshot") {
            it.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith("feedback-screenshot-")
        }
        try {
            activity.get().setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    MaterialTheme { AboutFeedbackPreference() }
                }
            }
            compose.onNodeWithText("Report a problem").performClick()
            compose.onNodeWithTag("feedback-add-screenshot").performScrollTo()
            compose.waitUntil(10_000) {
                !compose.onNodeWithTag(
                    "feedback-add-screenshot",
                ).fetchSemanticsNode().config.contains(SemanticsProperties.Disabled)
            }
            compose.onNodeWithTag("feedback-add-screenshot").performClick()
            compose.waitUntil(15_000) { compose.onAllNodes(screenshot).fetchSemanticsNodes().size == 1 }
            assertEquals(1, opened)
            compose.onAllNodes(screenshot)[0].performScrollTo().performClick()
            compose.onNodeWithTag("feedback-final-preview").assertIsDisplayed()
            compose.onNodeWithTag("feedback-preview-close").performClick()
            val id = compose.onAllNodes(screenshot).fetchSemanticsNodes().single().config[SemanticsProperties.TestTag]
                .removePrefix("feedback-screenshot-")
            compose.onNodeWithTag("feedback-remove-screenshot-$id").performScrollTo().performClick()
            compose.onNodeWithTag("feedback-add-screenshot").performScrollTo().performClick()
            compose.runOnIdle {
                assertEquals(2, launches)
                assertEquals(1, opened)
            }
            assertEquals(0, compose.onAllNodes(screenshot).fetchSemanticsNodes().size)
            compose.onNodeWithText("fictional-private-screenshot.png").assertDoesNotExist()
        } finally {
            diagnosticScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            configServer.close()
            activity.pause().stop().destroy()
            source.delete()
            Injekt = previous
            Locale.setDefault(locale)
        }
    }
}
