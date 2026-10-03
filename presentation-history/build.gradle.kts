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
            api(projects.domain)
            implementation(projects.i18n)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.foundation)
            implementation(compose.runtime)
            implementation(compose.ui)
        }
        androidMain.dependencies { implementation(project.dependencies.platform(androidCompose.bom)) }
        androidUnitTest.dependencies {
            implementation(project.dependencies.platform(androidCompose.bom))
            implementation(androidCompose.activity)
            implementation(androidCompose.ui.test.junit4)
            implementation("junit:junit:4.13.2")
            implementation("org.robolectric:robolectric:4.16.1")
            runtimeOnly("org.junit.vintage:junit-vintage-engine:6.0.3")
            runtimeOnly("org.conscrypt:conscrypt-openjdk-uber:2.5.2")
            implementation(libs.bundles.test)
            implementation(kotlinx.coroutines.test)
            runtimeOnly(libs.junit.platform.launcher)
        }
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.bundles.test)
            implementation(kotlinx.coroutines.test)
            runtimeOnly(libs.junit.platform.launcher)
        }
    }
}

android {
    namespace = "mihon.presentation.history"
    testOptions.unitTests.isIncludeAndroidResources = true
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
