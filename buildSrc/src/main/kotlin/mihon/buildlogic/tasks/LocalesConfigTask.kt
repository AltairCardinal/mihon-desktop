package mihon.buildlogic.tasks

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider
import java.io.File

private val emptyResourcesElement = "<resources>\\s*</resources>|<resources\\s*/>".toRegex()

fun Project.getLocalesConfigTask(outputResourceDir: File): TaskProvider<Task> {
    return tasks.register("generateLocalesConfig") {
        val locales = fileTree("$projectDir/src/commonMain/moko-resources/")
            .matching { include("**/strings.xml") }
            .filterNot { it.readText().contains(emptyResourcesElement) }
            .map {
                it.parentFile.name
                    .replace("base", "en")
                    .replace("-r", "-")
                    .replace("+", "-")
            }
            .sorted()
            .joinToString("\n") { "|   <locale android:name=\"$it\"/>" }

        val content = """
        |<?xml version="1.0" encoding="utf-8"?>
        |<locale-config xmlns:android="http://schemas.android.com/apk/res/android">
        $locales
        |</locale-config>
        """.trimMargin()

        outputResourceDir.resolve("xml/locales_config.xml").apply {
            parentFile.mkdirs()
            writeText(content)
        }
    }
}

/** The compiled catalog uses the same nonempty moko source files as Android locales_config. */
fun Project.getLocaleCatalogTask(outputDir: File): TaskProvider<Task> {
    val sources = fileTree("$projectDir/src/commonMain/moko-resources/").matching { include("**/strings.xml") }
    return tasks.register("generateLocaleCatalog") {
        inputs.files(sources)
        val output = outputDir.resolve("tachiyomi/i18n/ApplicationLocales.kt")
        outputs.file(output)
        doLast {
            val tags = sources.filterNot { it.readText(Charsets.UTF_8).contains(emptyResourcesElement) }
                .map { file ->
                    val tag = file.parentFile.name.replace("base", "en").replace("-r", "-").replace("+", "-")
                    java.util.Locale.forLanguageTag(tag).toLanguageTag()
                }.distinct().sorted()
            output.parentFile.mkdirs()
            output.writeText(
                "package tachiyomi.i18n\n\nobject ApplicationLocales {\n    val languageTags: List<String> = listOf(\n" +
                    tags.joinToString("\n") { "        \"$it\"," } + "\n    )\n}\n",
                Charsets.UTF_8,
            )
        }
    }
}
