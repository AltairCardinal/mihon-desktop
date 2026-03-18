plugins {
    id("mihon.library")
    id("mihon.library.compose")
    kotlin("android")
}

android {
    namespace = "tachiyomi.presentation.core"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.animation.ExperimentalAnimationApi",
            "-opt-in=androidx.compose.animation.graphics.ExperimentalAnimationGraphicsApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi",
            "-opt-in=kotlinx.coroutines.FlowPreview",
        )
    }
}

dependencies {
    api(projects.core.common)
    api(projects.i18n)

    // Compose
    implementation(androidCompose.activity)
    implementation(androidCompose.foundation)
    implementation(androidCompose.material3.core)
    implementation(androidCompose.material.icons)
    implementation(androidCompose.animation)
    implementation(androidCompose.animation.graphics)
    debugImplementation(androidCompose.ui.tooling)
    implementation(androidCompose.ui.tooling.preview)
    implementation(androidCompose.ui.util)

    implementation(androidx.paging.runtime)
    implementation(androidx.paging.compose)
    implementation(kotlinx.immutables)
}
