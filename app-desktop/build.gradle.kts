import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.io.ByteArrayOutputStream

plugins {
    kotlin("multiplatform")
    alias(libs.plugins.compose.multiplatform)
    id("com.mikepenz.aboutlibraries.plugin")
    id("mihon.desktop.licenses")
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

val appVersionFile = project.file("src/main/kotlin/mihon/desktop/AppVersion.kt")

fun readAppVersionConstant(name: String): Int {
    val regex = Regex("""const val $name = (\d+)""")
    val text = appVersionFile.readText()
    return regex.find(text)?.groupValues?.get(1)?.toInt()
        ?: error("Unable to read AppVersion.$name from ${appVersionFile.path}")
}

val desktopNativePackageVersion: String by lazy {
    "${readAppVersionConstant("STAGE")}." +
        "${readAppVersionConstant("FEATURE")}." +
        readAppVersionConstant("BUILD")
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
                implementation(projects.presentationTheme)

                // Network
                implementation(libs.okhttp.core)
                implementation(libs.okhttp.logging)
                implementation(libs.okhttp.brotli)
                implementation(libs.okhttp.dnsoverhttps)

                // Database
                implementation(libs.sqldelight.jvm.driver)

                // DI
                implementation(libs.injekt)
                implementation(libs.jna)
                implementation(libs.jna.platform)

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

                // Drag-and-drop reordering for LazyColumn (mirrors Android's ItemTouchHelper pattern)
                implementation(libs.reorderable)

                // JavaScript engine (Rhino) for extensions that use JS evaluation
                implementation(libs.rhino)

                // DEX to JVM bytecode conversion (for loading Android extension APKs on desktop)
                implementation(libs.dex.tools)

                // Verify APK signer certificates before converting repository artifacts.
                implementation("com.android.tools.build:apksig:8.13.2")

                // Test HTTP Server (Ktor)
                implementation("io.ktor:ktor-server-core:2.3.12")
                implementation("io.ktor:ktor-server-netty:2.3.12")
                implementation("io.ktor:ktor-server-cio:2.3.12")
                implementation("io.ktor:ktor-client-core:2.3.12")
                implementation("io.ktor:ktor-client-okhttp:2.3.12")
            }
        }
        val jvmTest by getting {
            kotlin.srcDirs("src/test/kotlin")
            resources.srcDir("src/test/resources")
            dependencies {
                implementation(libs.bundles.test)
                implementation(kotlinx.coroutines.test)
                implementation(libs.okhttp.core)
                implementation(libs.okhttp.mockwebserver)
                runtimeOnly(libs.junit.platform.launcher)
            }
        }
    }
}

tasks.named("compileKotlinJvm") { dependsOn(generateBuildInfo) }
tasks.named("compileTestKotlinJvm") { dependsOn(generateBuildInfo) }

tasks.withType<Test> {
    val testTempDir = layout.buildDirectory.dir("test-tmp").get().asFile
    val includeLiveNetworkTests = providers.gradleProperty("includeLiveNetworkTests")
        .map(String::toBoolean)
        .getOrElse(false)
    doFirst {
        testTempDir.mkdirs()
    }
    systemProperty("java.io.tmpdir", testTempDir.absolutePath)
    if (includeLiveNetworkTests) {
        val proxyHost = providers.environmentVariable("MIHON_LIVE_PROXY_HOST").getOrElse("127.0.0.1")
        val proxyPort = providers.environmentVariable("MIHON_LIVE_PROXY_PORT").getOrElse("10808")
        systemProperty("http.proxyHost", proxyHost)
        systemProperty("http.proxyPort", proxyPort)
        systemProperty("https.proxyHost", proxyHost)
        systemProperty("https.proxyPort", proxyPort)
        systemProperty("http.nonProxyHosts", "localhost|127.*|[::1]")
    }
    useJUnitPlatform {
        val includeIntegrationTests = providers.gradleProperty("includeIntegrationTests")
            .map(String::toBoolean)
            .getOrElse(false)
        if (!includeIntegrationTests) {
            excludeTags("integration")
        }
        if (!includeLiveNetworkTests) {
            excludeTags("live-network")
        }
        val includeNetworkSurveyTests = providers.gradleProperty("includeNetworkSurveyTests")
            .map(String::toBoolean)
            .getOrElse(false)
        if (!includeNetworkSurveyTests) {
            excludeTags("network-survey")
        }
    }
}

val jvmTestTask = tasks.named<Test>("jvmTest")
jvmTestTask {
    useJUnitPlatform {
        excludeTags("final-parity-audit")
    }
}
tasks.register<Test>("finalParityAudit") {
    group = "verification"
    description = "Runs the explicit 64-capability final parity closure gate."
    dependsOn(tasks.named("jvmTestClasses"))
    testClassesDirs = jvmTestTask.get().testClassesDirs
    classpath = jvmTestTask.get().classpath
    useJUnitPlatform {
        includeTags("final-parity-audit")
    }
    testLogging {
        events("failed", "standardOut")
        showStandardStreams = true
    }
}

compose.desktop {
    application {
        mainClass = "mihon.desktop.MainKt"

        nativeDistributions {
            // Redirect output to local APFS volume — codesign fails on exFAT
            outputBaseDir.set(project.file("/tmp/mihon-dist"))
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi)
            packageName = "Mihon Desktop"
            packageVersion = desktopNativePackageVersion

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
                infoPlist {
                    extraKeysRawXml = project.file(
                        "src/main/resources/platform/macos/tachiyomi-url-types.plist",
                    ).readText()
                }
            }

            windows {
                menuGroup = "Mihon"
                upgradeUuid = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
            }
        }
    }
}
