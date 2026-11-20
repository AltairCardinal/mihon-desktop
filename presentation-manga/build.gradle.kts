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
                implementation(compose.animation)
                implementation(compose.foundation)
                implementation(compose.runtime)
                implementation(compose.ui)
                implementation(compose.materialIconsExtended)
                implementation(libs.reorderable)
                implementation(projects.domain)
                implementation(projects.core.common)
                api(projects.i18n)
            }
        }
        androidMain.dependencies {
            implementation(project.dependencies.platform(androidCompose.bom))
        }
    }
}

android {
    namespace = "tachiyomi.presentation.manga"
}
