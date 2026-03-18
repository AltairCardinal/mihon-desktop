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
                // ArchiveEntry is pure Kotlin, no deps needed
            }
        }
        androidMain {
            dependencies {
                implementation(libs.jsoup)
                implementation(libs.libarchive)
                implementation(libs.unifile)
            }
        }
        jvmMain {
            dependencies {
                // Desktop archive support will use java.util.zip
            }
        }
    }

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
}

android {
    namespace = "mihon.core.archive"
}
