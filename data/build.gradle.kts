import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    id("mihon.library")
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    alias(libs.plugins.sqldelight)
}

kotlin {
    androidTarget()
    jvm()

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain {
            dependencies {
                implementation(projects.sourceApi)
                implementation(projects.domain)
                implementation(projects.core.common)

                api(libs.sqldelight.coroutines.kmp)

                implementation(project.dependencies.platform(kotlinx.coroutines.bom))
                implementation(kotlinx.coroutines.core)
                implementation(kotlinx.serialization.json)
                implementation(kotlinx.serialization.protobuf)
            }
        }
        commonTest {
            dependencies {
                implementation(libs.bundles.test)
                implementation(kotlinx.coroutines.test)
                implementation(libs.okhttp.mockwebserver)
            }
        }
        androidMain {
            dependencies {
                api(libs.sqldelight.android.driver)
                api(libs.sqldelight.android.paging)
                implementation(libs.tink.android)
            }
        }
        val androidUnitTest by getting {
            dependencies {
                implementation(libs.sqldelight.jvm.driver)
                runtimeOnly(libs.junit.platform.launcher)
            }
        }
        jvmMain {
            dependencies {
                api(libs.sqldelight.jvm.driver)
                implementation(project.dependencies.platform(kotlinx.coroutines.bom))
                implementation(kotlinx.coroutines.core)
                implementation(libs.tink.core)
            }
        }
        jvmTest {
            kotlin.srcDir("src/creatorEntryContract/kotlin")
            kotlin.srcDir("src/testFixtures/kotlin/tachiyomi/data")
            dependencies {
                implementation(libs.bundles.test)
                implementation(kotlinx.coroutines.test)
                implementation(libs.okhttp.mockwebserver)
                runtimeOnly(libs.junit.platform.launcher)
            }
        }
    }

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlinx.serialization.ExperimentalSerializationApi")
    }
}

android {
    namespace = "tachiyomi.data"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }

    sourceSets["test"].resources.srcDir("src/commonTest/resources")
}

sqldelight {
    databases {
        create("Database") {
            packageName.set("tachiyomi.data")
            dialect(libs.sqldelight.dialects.sql)
            schemaOutputDirectory.set(project.file("./src/commonMain/sqldelight"))
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

val verifySqlDelightAuthority by tasks.registering {
    group = "verification"
    description = "Ensures commonMain remains the only SQLDelight schema and migration authority."
    val commonAuthority = layout.projectDirectory.dir("src/commonMain/sqldelight")
    val legacyMirror = fileTree(layout.projectDirectory.dir("src/main/sqldelight")) {
        include("**/*.sq", "**/*.sqm")
    }
    inputs.dir(commonAuthority)
    inputs.files(legacyMirror)
    doLast {
        check(commonAuthority.asFile.resolve("tachiyomi/data").isDirectory) {
            "SQLDelight authority is missing: ${commonAuthority.asFile}"
        }
        check(legacyMirror.files.isEmpty()) {
            "Do not recreate data/src/main/sqldelight; edit data/src/commonMain/sqldelight only: " +
                legacyMirror.files.sorted().joinToString()
        }
    }
}

tasks.named("check") {
    dependsOn(verifySqlDelightAuthority)
}
// UI integration tests reuse the real storage/onboarding/Git fixture; never part of an app dependency.
val syncTestSupportJar by tasks.registering(Jar::class) {
    archiveClassifier.set("sync-test-support")
    dependsOn("jvmTestClasses")
    from(kotlin.targets.getByName("jvm").compilations.getByName("test").output.allOutputs)
}
configurations.create("syncTestSupport") {
    isCanBeConsumed = true
    isCanBeResolved = false
    outgoing.artifact(syncTestSupportJar)
}
