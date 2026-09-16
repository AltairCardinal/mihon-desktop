package mihon.buildlogic

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.writeText

class AndroidLicensesPluginFunctionalTest {
    @TempDir
    lateinit var projectDir: Path

    @Test
    fun `release notices retain an artifact without a pom and current Tink dependencies`() {
        writeFixture(includeUnknownArtifact = false)

        val result = runner("verifyReleaseNotices").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":resolveReleaseArtifacts")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":prepareLibraryDefinitionsRelease")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyReleaseNotices")?.outcome)
        assertTrue(result.output.contains("Verified all release artifact coordinates"), result.output)
    }

    @Test
    fun `an unknown artifact without a pom cannot silently disappear from release notices`() {
        writeFixture(includeUnknownArtifact = true)

        val result = runner("verifyReleaseNotices").buildAndFail()

        assertEquals(TaskOutcome.SUCCESS, result.task(":resolveReleaseArtifacts")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":prepareLibraryDefinitionsRelease")?.outcome)
        assertTrue(result.output.contains("Missing release notice: com.example:undocumented:1.0"), result.output)
    }

    private fun writeFixture(includeUnknownArtifact: Boolean) {
        writeArtifactOnlyModule("com.github.arkon.FlexibleAdapter", "flexible-adapter", "c8013533")
        if (includeUnknownArtifact) writeArtifactOnlyModule("com.example", "undocumented", "1.0")
        projectDir.resolve("settings.gradle").writeText("rootProject.name = 'android-license-fixture'\n")
        projectDir.resolve("gradle.properties").writeText("android.useAndroidX=true\n")
        val localProperties = Properties().apply {
            val path = repositoryRoot().resolve("local.properties")
            if (path.isRegularFile()) Files.newInputStream(path).use(::load)
        }
        val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
            ?: localProperties.getProperty("sdk.dir")
            ?: error("Android SDK is required for the release license fixture")
        projectDir.resolve("local.properties").writeText("sdk.dir=${sdk.replace('\\', '/')}\n")
        val unknownDependency = if (includeUnknownArtifact) {
            "implementation 'com.example:undocumented:1.0'"
        } else {
            ""
        }
        projectDir.resolve("build.gradle").writeText(
            """
            import groovy.json.JsonSlurper
            import org.gradle.api.artifacts.component.ModuleComponentIdentifier

            plugins {
                id 'com.android.application'
                id 'com.mikepenz.aboutlibraries.plugin.android'
            }
            repositories {
                maven {
                    url = uri('repo')
                    metadataSources { gradleMetadata(); artifact() }
                    content {
                        includeGroup 'com.github.arkon.FlexibleAdapter'
                        includeGroup 'com.example'
                    }
                }
                mavenCentral()
                google()
            }
            android {
                namespace = 'mihon.licenses.fixture'
                compileSdk = 36
                defaultConfig { minSdk = 26 }
            }
            dependencies {
                implementation 'com.github.arkon.FlexibleAdapter:flexible-adapter:c8013533'
                implementation 'com.google.crypto.tink:tink-android:1.23.0'
                implementation 'androidx.annotation:annotation-jvm:1.9.1'
                implementation 'org.jetbrains.kotlin:kotlin-stdlib:2.3.10'
                implementation 'org.jetbrains:annotations:23.0.0'
                $unknownDependency
            }
            aboutLibraries {
                collect.configPath.set(file(providers.gradleProperty('licenseMetadataRoot').get()))
                offlineMode.set(true)
            }
            def resolvedArtifacts = tasks.register('resolveReleaseArtifacts') {
                doLast {
                    def artifacts = configurations.releaseRuntimeClasspath.incoming.artifacts.artifacts
                    assert artifacts.any {
                        it.id.componentIdentifier.displayName ==
                            'com.github.arkon.FlexibleAdapter:flexible-adapter:c8013533'
                    }
                }
            }
            tasks.matching { it.name == 'prepareLibraryDefinitionsRelease' }.configureEach {
                dependsOn(resolvedArtifacts)
            }
            tasks.register('verifyReleaseNotices') {
                dependsOn('prepareLibraryDefinitionsRelease')
                doLast {
                    def noticesFile = file('build/generated/aboutLibraries/release/res/raw/aboutlibraries.json')
                    def notices = new JsonSlurper().parse(noticesFile, 'UTF-8')
                    def libraries = notices.libraries.groupBy { it.uniqueId }
                    // Artifact-backed modules exclude projects and BOMs without masking unknown missing POMs.
                    def coordinates = configurations.releaseRuntimeClasspath.incoming.artifacts.artifacts
                        .collect { it.id.componentIdentifier }
                        .findAll { it instanceof ModuleComponentIdentifier }
                        .toSet()
                    coordinates.each { coordinate ->
                        def id = coordinate.group + ':' + coordinate.module
                        assert libraries[id] : 'Missing release notice: ' + coordinate.displayName
                        assert libraries[id].any { it.artifactVersion == coordinate.version } :
                            'Wrong release notice version: ' + coordinate.displayName
                    }
                    assert libraries['com.github.arkon.FlexibleAdapter:flexible-adapter'].size() == 1
                    def flexible = libraries['com.github.arkon.FlexibleAdapter:flexible-adapter'].first()
                    assert flexible.artifactVersion == 'c8013533'
                    assert flexible.licenses == ['Apache-2.0']
                    assert notices.licenses['Apache-2.0'].content.contains('TERMS AND CONDITIONS')
                    assert libraries['com.google.crypto.tink:tink-android'].size() == 1
                    assert libraries['com.google.crypto.tink:tink-android'].first().artifactVersion == '1.23.0'
                    assert libraries['com.google.crypto.tink:tink-android'].first().licenses
                    println 'Verified all release artifact coordinates'
                }
            }
            """.trimIndent(),
        )
    }

    private fun writeArtifactOnlyModule(group: String, name: String, version: String) {
        val directory = projectDir.resolve("repo/${group.replace('.', '/')}/$name/$version").createDirectories()
        val artifact = "$name-$version.aar"
        // A controlled AAR fixture exercises Gradle's real artifact resolution without creating any Maven POM.
        ZipOutputStream(Files.newOutputStream(directory.resolve(artifact))).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write("""<manifest package="mihon.licenses.fixture"/>""".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        directory.resolve("$name-$version.module").writeText(
            """
            {
              "formatVersion": "1.1",
              "component": { "group": "$group", "module": "$name", "version": "$version" },
              "variants": [{
                "name": "releaseRuntimeElements",
                "attributes": { "org.gradle.category": "library", "org.gradle.usage": "java-runtime" },
                "files": [{ "name": "$artifact", "url": "$artifact" }]
              }]
            }
            """.trimIndent(),
        )
    }

    private fun runner(vararg tasks: String) = GradleRunner.create()
        .withProjectDir(projectDir.toFile())
        .withTestKitDir(gradleUserHome().toFile())
        .withArguments(
            *tasks,
            "--offline",
            "--stacktrace",
            "-PlicenseMetadataRoot=${repositoryRoot().resolve("app/license-metadata")}",
        )
        .withPluginClasspath()

    private fun gradleUserHome() =
        System.getProperty("gradle.user.home")?.let(Path::of)
            ?: System.getenv("GRADLE_USER_HOME")?.let(Path::of)
            ?: Path.of(System.getProperty("user.home"), ".gradle")

    private fun repositoryRoot() = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { it.resolve("app/build.gradle.kts").isRegularFile() }
}
