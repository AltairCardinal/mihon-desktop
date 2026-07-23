plugins {
    id("mihon.library")
    kotlin("multiplatform")
    alias(libs.plugins.compose.multiplatform)
}

pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

kotlin {
    androidTarget()
    jvm()

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain {
            dependencies {
                implementation(compose.material3)
                implementation(compose.runtime)
                implementation(compose.ui)
                api(projects.i18n)
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

android {
    namespace = "tachiyomi.presentation.theme"
}
