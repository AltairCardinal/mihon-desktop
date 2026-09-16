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
        commonMain.dependencies {
            api(projects.data)
            implementation(projects.domain)
            implementation(projects.i18n)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.foundation)
            implementation(compose.runtime)
            implementation(compose.ui)
            implementation(project.dependencies.platform(kotlinx.coroutines.bom))
            implementation(kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            implementation(project.dependencies.platform(androidCompose.bom))
        }
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.bundles.test)
            implementation(kotlinx.coroutines.test)
            runtimeOnly(libs.junit.platform.launcher)
        }
    }
}

android { namespace = "mihon.presentation.sync" }

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    if (providers.gradleProperty("syncVisuals").orNull == "true") {
        systemProperty("mihon.sync.visualDir", layout.buildDirectory.dir("sync-visual").get().asFile.absolutePath)
    }
}
