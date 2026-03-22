import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.io.ByteArrayOutputStream

plugins {
    kotlin("multiplatform")
    alias(libs.plugins.compose.multiplatform)
}

pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")

// Generate BuildInfo.kt with git commit hash at compile time
val gitHash: String by lazy {
    val stdout = ByteArrayOutputStream()
    exec {
        commandLine("git", "rev-parse", "--short=7", "HEAD")
        standardOutput = stdout
    }
    stdout.toString().trim()
}

val generateBuildInfo = tasks.register("generateBuildInfo") {
    val outputDir = layout.buildDirectory.dir("generated/src/main/kotlin")
    outputs.dir(outputDir)
    // Always re-run so the hash stays fresh
    outputs.upToDateWhen { false }
    doLast {
        val file = outputDir.get().file("mihon/desktop/BuildInfo.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package mihon.desktop
            |
            |object BuildInfo {
            |    const val GIT_HASH = "$gitHash"
            |}
            """.trimMargin(),
        )
    }
}

kotlin {
    jvm()

    sourceSets {
        val jvmMain by getting {
            kotlin.srcDirs("src/main/kotlin", layout.buildDirectory.dir("generated/src/main/kotlin"))
            resources.srcDirs("src/main/resources")
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(compose.components.uiToolingPreview)

                implementation(project.dependencies.platform(kotlinx.coroutines.bom))
                implementation(kotlinx.coroutines.core)
                implementation(kotlinx.coroutines.swing)

                // Mihon modules
                implementation(projects.core.common)
                implementation(projects.sourceApi)
                implementation(projects.domain)
                implementation(projects.data)
                implementation(projects.coreMetadata)
                implementation(projects.i18n)

                // Network
                implementation(libs.okhttp.core)
                implementation(libs.okhttp.logging)
                implementation(libs.okhttp.brotli)
                implementation(libs.okhttp.dnsoverhttps)

                // Database
                implementation(libs.sqldelight.jvm.driver)

                // DI
                implementation(libs.injekt)

                // Serialization
                implementation(kotlinx.serialization.json)
                implementation(kotlinx.serialization.protobuf)

                // Navigation
                implementation(libs.bundles.voyager)

                // Image loading
                implementation(project.dependencies.platform(libs.coil.bom))
                implementation(libs.coil.core)
                implementation(libs.coil.compose)
                implementation(libs.coil.network.okhttp)

                // RAR/CBR archive support (RAR4 + RAR5) via 7-Zip JNI bindings
                // sevenzipjbinding = Java API; sevenzipjbinding-all-platforms = native libs
                implementation("net.sf.sevenzipjbinding:sevenzipjbinding:16.02-2.01")
                implementation("net.sf.sevenzipjbinding:sevenzipjbinding-all-platforms:16.02-2.01")

                // File system watching (FSEvents on macOS, inotify on Linux)
                implementation("io.methvin:directory-watcher:0.18.0")
            }
        }
        val jvmTest by getting {
            kotlin.srcDirs("src/test/kotlin")
            dependencies {
                implementation(libs.bundles.test)
                implementation(kotlinx.coroutines.test)
                implementation(libs.okhttp.core) // for mockwebserver
                implementation("com.squareup.okhttp3:mockwebserver3-junit5:5.3.2")
                runtimeOnly(libs.junit.platform.launcher)
            }
        }
    }
}

tasks.named("compileKotlinJvm") { dependsOn(generateBuildInfo) }
tasks.named("compileTestKotlinJvm") { dependsOn(generateBuildInfo) }

tasks.withType<Test> {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "mihon.desktop.MainKt"

        nativeDistributions {
            // Redirect output to local APFS volume — codesign fails on exFAT
            outputBaseDir.set(project.file("/tmp/mihon-dist"))
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi)
            packageName = "Mihon Desktop"
            packageVersion = "1.0.0"

            modules(
                "java.sql",
                "java.naming",
                "java.management",
                "java.instrument",
                "java.security.jgss",
                "java.net.http",
            )

            macOS {
                bundleID = "mihon.desktop"
            }

            windows {
                menuGroup = "Mihon"
                upgradeUuid = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
            }
        }
    }
}
