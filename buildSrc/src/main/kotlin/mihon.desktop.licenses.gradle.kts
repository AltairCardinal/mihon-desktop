import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property

val generatedLicenseResources = layout.buildDirectory.dir("generated/aboutLibraries/jvmMain")

pluginManager.withPlugin("com.mikepenz.aboutlibraries.plugin") {
    val aboutLibraries = extensions.getByName("aboutLibraries")
    val exportConfig = aboutLibraries.javaClass.getMethod("getExport").invoke(aboutLibraries)
    val outputFile = exportConfig.javaClass.getMethod("getOutputFile").invoke(exportConfig) as RegularFileProperty
    @Suppress("UNCHECKED_CAST")
    val prettyPrint = exportConfig.javaClass.getMethod("getPrettyPrint").invoke(exportConfig) as Property<Boolean>
    outputFile.set(
        generatedLicenseResources.map {
            it.file("META-INF/mihon/dependencies.json")
        },
    )
    prettyPrint.set(false)
}

extensions.configure<KotlinMultiplatformExtension> {
    sourceSets.matching { it.name == "jvmMain" }.configureEach {
        resources.srcDir(generatedLicenseResources)
    }
}

tasks.matching { it.name == "jvmProcessResources" }.configureEach {
    dependsOn("exportLibraryDefinitionsJvm")
}
