package eu.kanade.tachiyomi.ui.browse.extension.details

import android.app.Application
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.PreferenceScreen
import eu.kanade.tachiyomi.source.SourcePreferenceScreenSetup
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidSourcePreferencesAdapterTest {

    private lateinit var previousInjekt: InjektScope

    @Before
    fun setUp() {
        previousInjekt = Injekt
        Injekt = InjektScope(DefaultRegistrar())
    }

    @After
    fun tearDown() {
        Injekt = previousInjekt
    }

    @Test
    fun `Fragment caller renders verified MangaDex schema from immutable artifact provenance`() {
        val fixture = checkNotNull(
            javaClass.classLoader?.getResourceAsStream(
                "extensions/real/keiyoushi-mangadex-1.4.211.schema.json",
            ),
        ).bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        val artifactPath = locateArtifact(fixture.getValue("artifactPath").jsonPrimitive.content)
        assertEquals(fixture.getValue("artifactSizeBytes").jsonPrimitive.content.toLong(), Files.size(artifactPath))
        assertEquals(
            fixture.getValue("artifactSha256").jsonPrimitive.content,
            MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(artifactPath))
                .joinToString("") { "%02x".format(it) },
        )

        val application = RuntimeEnvironment.getApplication() as Application
        val source = schemaSource(fixture)
        Injekt.addSingleton<Application>(application)
        Injekt.addSingleton<SourceManager>(
            mockk {
                every { getOrStub(source.id) } returns source
            },
        )
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val container = FrameLayout(activity).apply {
            id = View.generateViewId()
        }
        activity.setContentView(container)
        val fragment = HeadlessSourcePreferencesFragment().apply {
            arguments = SourcePreferencesFragment.getInstance(source.id).arguments
        }

        activity.supportFragmentManager.beginTransaction()
            .add(container.id, fragment)
            .commitNow()

        val screen = fragment.preferenceScreen
        assertEquals(2, screen.preferenceCount)
        assertEquals("Cover quality", screen.findPreference<ListPreference>("thumbnailQuality_en")?.title)
        assertEquals("Block groups by UUID", screen.findPreference<EditTextPreference>("blockedGroups_en")?.title)
        assertEquals(
            "main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8",
            fixture.getValue("authorityRef").jsonPrimitive.content,
        )
        assertEquals(12, fixture.getValue("schemaPreferenceCount").jsonPrimitive.content.toInt())
    }

    @Test
    fun `production adapter executes configurable schema and preserves missing and failure boundaries`() {
        val context = RuntimeEnvironment.getApplication()
        val preferenceManager = PreferenceManager(context)
        val screen = preferenceManager.createPreferenceScreen(context)
        var setupCalls = 0
        val source = configurableSource { target ->
            setupCalls += 1
            target.addPreference(
                SwitchPreferenceCompat(context).apply {
                    key = "data_saver"
                    title = "Data saver"
                },
            )
        }

        val success = AndroidSourcePreferencesAdapter.setupPreferenceScreen(source, screen)

        assertTrue(success is SourcePreferenceScreenSetup.Success)
        assertEquals(1, setupCalls)
        assertEquals("data_saver", screen.getPreference(0).key)
        assertTrue(
            AndroidSourcePreferencesAdapter.setupPreferenceScreen(
                null,
                preferenceManager.createPreferenceScreen(context),
            ) is SourcePreferenceScreenSetup.Missing,
        )

        val setupFailure = IllegalStateException("setup failed")
        val failure = AndroidSourcePreferencesAdapter.setupPreferenceScreen(
            configurableSource { throw setupFailure },
            preferenceManager.createPreferenceScreen(context),
        )
        assertSame(setupFailure, (failure as SourcePreferenceScreenSetup.Failure).error)
    }

    private fun configurableSource(setup: (PreferenceScreen) -> Unit) = object : ConfigurableSource {
        override val id = 1L
        override val name = "Fixture source"

        override fun setupPreferenceScreen(screen: PreferenceScreen) {
            setup(screen)
        }
    }

    private fun schemaSource(fixture: kotlinx.serialization.json.JsonObject) = object : ConfigurableSource {
        override val id = 2499283573021220255L
        override val name = fixture.getValue("sourceName").jsonPrimitive.content

        override fun setupPreferenceScreen(screen: PreferenceScreen) {
            fixture.getValue("verifiedPreferences").jsonArray.forEach { element ->
                val preference = element.jsonObject
                val key = preference.getValue("key").jsonPrimitive.content
                val title = preference.getValue("title").jsonPrimitive.content
                when (preference.getValue("type").jsonPrimitive.content) {
                    "list" -> ListPreference(screen.context).apply {
                        this.key = key
                        this.title = title
                        entries = arrayOf("Original", "Compressed")
                        entryValues = arrayOf("original", "compressed")
                    }
                    "editText" -> EditTextPreference(screen.context).apply {
                        this.key = key
                        this.title = title
                    }
                    else -> error("Unsupported verified preference type")
                }.also(screen::addPreference)
            }
        }
    }

    private fun locateArtifact(relativePath: String): Path {
        return sequenceOf(Path.of(relativePath), Path.of("..").resolve(relativePath))
            .map(Path::toAbsolutePath)
            .first(Files::isRegularFile)
    }

    class HeadlessSourcePreferencesFragment : SourcePreferencesFragment() {
        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?,
        ): View {
            val context = inflater.context
            val list = RecyclerView(context).apply {
                id = androidx.preference.R.id.recycler_view
                layoutManager = LinearLayoutManager(context)
            }
            androidx.preference.PreferenceFragmentCompat::class.java
                .getDeclaredField("mList")
                .apply { isAccessible = true }
                .set(this, list)
            return FrameLayout(context).apply {
                addView(
                    list,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
        }
    }
}
