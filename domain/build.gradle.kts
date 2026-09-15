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
        commonTest {
            resources.srcDir("../app-desktop/src/test/resources/extensions/index")
        }
        commonMain {
            dependencies {
                implementation(projects.sourceApi)
                implementation(projects.core.common)
                implementation(libs.okio)

                implementation(project.dependencies.platform(kotlinx.coroutines.bom))
                implementation(kotlinx.bundles.coroutines)
                implementation(kotlinx.bundles.serialization)
                implementation(libs.stringSimilarity)
            }
        }
        androidMain {
            dependencies {
                implementation(libs.unifile)
                api(libs.sqldelight.android.paging)
            }
        }
        jvmMain {
            dependencies {
                implementation(project.dependencies.platform(kotlinx.coroutines.bom))
                implementation(kotlinx.coroutines.core)
            }
        }
        val androidUnitTest by getting {
            dependencies {
                implementation(libs.bundles.test)
                implementation(kotlinx.coroutines.test)
                runtimeOnly(libs.junit.platform.launcher)
            }
        }
        jvmTest {
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
        freeCompilerArgs.add("-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi")
    }
}

android {
    namespace = "tachiyomi.domain"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    sourceSets["test"].resources.srcDir("src/commonTest/resources")
    sourceSets["test"].resources.srcDir("../app-desktop/src/test/resources/extensions/index")
}
