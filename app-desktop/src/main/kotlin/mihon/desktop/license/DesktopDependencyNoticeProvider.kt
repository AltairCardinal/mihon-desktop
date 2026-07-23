package mihon.desktop.license

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mihon.domain.license.model.DependencyNoticeMetadata
import mihon.domain.license.model.LicenseNoticeResult
import mihon.domain.license.service.LicenseNoticePolicy

fun interface DependencyNoticeProvider {
    fun getNotices(): LicenseNoticeResult
}

class ClasspathDependencyNoticeProvider internal constructor(
    private val resourceReader: () -> String? = {
        ClasspathDependencyNoticeProvider::class.java.classLoader
            .getResourceAsStream(RESOURCE_PATH)
            ?.bufferedReader()
            ?.use { it.readText() }
    },
) : DependencyNoticeProvider {

    private val cachedNotices: LicenseNoticeResult by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        LicenseNoticePolicy.create(
            runCatching {
                val resource = requireNotNull(resourceReader()) {
                    "Missing packaged dependency metadata: $RESOURCE_PATH"
                }
                val metadata = json.decodeFromString<AboutLibrariesMetadata>(resource)
                metadata.libraries.map { library ->
                    val firstLicenseContent = library.licenses
                        .firstOrNull()
                        ?.let(metadata.licenses::get)
                        ?.content
                        ?.takeUnless(String::isBlank)
                    DependencyNoticeMetadata(
                        name = library.name,
                        website = library.website,
                        licenses = listOfNotNull(firstLicenseContent),
                    )
                }
            },
        )
    }

    override fun getNotices(): LicenseNoticeResult = cachedNotices

    private companion object {
        const val RESOURCE_PATH = "META-INF/mihon/dependencies.json"

        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }
}

@Serializable
private data class AboutLibrariesMetadata(
    val libraries: List<AboutLibrary>,
    val licenses: Map<String, AboutLicense>,
)

@Serializable
private data class AboutLibrary(
    val name: String,
    val website: String? = null,
    val licenses: List<String> = emptyList(),
)

@Serializable
private data class AboutLicense(
    val content: String? = null,
)
