package mihon.buildlogic

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText

class DesktopLicensesPluginFunctionalTest {

    @TempDir
    lateinit var projectDir: Path

    @Test
    fun `real resolved dependencies produce stable packaged metadata`() {
        writeSettings()
        val dependencies = listOf(
            "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2",
            "com.squareup.okio:okio-jvm:3.16.1",
        )
        writeBuild(dependencies)

        val first = runBuild("clean", "jvmProcessResources")
        assertEquals(TaskOutcome.SUCCESS, first.task(":jvmProcessResources")?.outcome)
        val resource = packagedResource()
        assertTrue(resource.isRegularFile(), "missing packaged resource: $resource")
        val firstBytes = resource.readBytes()
        val firstIds = uniqueIds(resource.readText())
        assertTrue(firstIds.any { "kotlinx-coroutines-core" in it }, "resolved dependency missing: $firstIds")
        assertEquals(firstIds.sorted(), firstIds)

        writeBuild(dependencies.reversed())
        runBuild("clean", "jvmProcessResources")

        assertArrayEquals(firstBytes, packagedResource().readBytes())
    }

    @Test
    fun `malformed pom emits a diagnostic instead of silently producing an empty resource`() {
        writeSettings()
        val module = projectDir.resolve("repo/com/example/broken/1.0").createDirectories()
        module.resolve("broken-1.0.pom").writeText("<project><broken>")
        ZipOutputStream(Files.newOutputStream(module.resolve("broken-1.0.jar"))).use { }
        writeBuild(listOf("com.example:broken:1.0"), includeLocalRepository = true)

        val result = runner("exportLibraryDefinitionsJvm").build()

        assertTrue(
            result.output.contains("broken-1.0.pom") ||
                result.output.contains("Could not parse POM"),
            result.output,
        )
    }

    private fun writeSettings() {
        projectDir.resolve("gradle.properties").writeText(
            "kotlin.stdlib.default.dependency=false",
        )
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            pluginManagement {
                repositories {
                    gradlePluginPortal()
                    mavenCentral()
                    google()
                }
            }
            rootProject.name = "desktop-license-fixture"
            """.trimIndent(),
        )
    }

    private fun writeBuild(
        dependencies: List<String>,
        includeLocalRepository: Boolean = false,
    ) {
        val localRepository = if (includeLocalRepository) """maven { url = uri("repo") }""" else ""
        val declarations = dependencies.joinToString("\n") { """implementation("$it")""" }
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("org.jetbrains.kotlin.multiplatform")
                id("com.mikepenz.aboutlibraries.plugin")
                id("mihon.desktop.licenses")
            }

            repositories {
                $localRepository
                mavenCentral()
            }

            kotlin {
                jvm()
                sourceSets {
                    jvmMain.dependencies {
                        $declarations
                    }
                }
            }
            """.trimIndent(),
        )
    }

    private fun runBuild(vararg tasks: String) = runner(*tasks).build()

    private fun runner(vararg tasks: String) = GradleRunner.create()
        .withProjectDir(projectDir.toFile())
        .withTestKitDir(gradleUserHome().toFile())
        .withArguments(*tasks, "--offline", "--stacktrace")
        .withPluginClasspath()

    private fun gradleUserHome() =
        System.getProperty("gradle.user.home")?.let(Path::of)
            ?: System.getenv("GRADLE_USER_HOME")?.let(Path::of)
            ?: Path.of(System.getProperty("user.home"), ".gradle")

    private fun packagedResource() =
        projectDir.resolve("build/processedResources/jvm/main/META-INF/mihon/dependencies.json")

    private fun uniqueIds(json: String) =
        Regex(""""uniqueId":"([^"]+)"""")
            .findAll(json)
            .map { it.groupValues[1] }
            .toList()
}
