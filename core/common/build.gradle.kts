import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    id("mihon.library")
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

kotlin {
    androidTarget()
    jvm()

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain {
            dependencies {
                implementation(project.dependencies.platform(kotlinx.coroutines.bom))
                api(libs.rxjava)
                api(libs.okhttp.core)
                api(libs.okhttp.logging)
                api(libs.okhttp.brotli)
                api(libs.okhttp.dnsoverhttps)
                api(libs.okio)
                api(kotlinx.coroutines.core)
                api(kotlinx.serialization.json)
                api(kotlinx.serialization.json.okio)
                implementation(libs.jsoup)
                implementation(libs.natural.comparator)
            }
        }
        androidMain {
            dependencies {
                api(libs.logcat)
                implementation(projects.i18n)
                implementation(libs.image.decoder)
                implementation(libs.unifile)
                implementation(libs.libarchive)
                api(libs.preferencektx)
                implementation(libs.bundles.js.engine)
            }
        }
        val androidUnitTest by getting {
            dependencies {
                implementation(libs.bundles.test)
                implementation(kotlinx.coroutines.test)
                implementation("junit:junit:4.13.2")
                implementation("org.robolectric:robolectric:4.16.1")
                runtimeOnly(libs.junit.platform.launcher)
                runtimeOnly("org.junit.vintage:junit-vintage-engine:6.0.3")
            }
        }
        jvmMain {
            dependencies {
                implementation(projects.i18n)
            }
        }
        jvmTest {
            dependencies {
                implementation(libs.bundles.test)
                implementation(kotlinx.coroutines.test)
                runtimeOnly(libs.junit.platform.launcher)
            }
        }
    }

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.serialization.ExperimentalSerializationApi",
        )
    }
}

android {
    namespace = "eu.kanade.tachiyomi.core.common"
}

tasks.withType<Test> {
    useJUnitPlatform()
}
