import java.util.Properties

plugins {
    id("mihon.benchmark")
}

/**
 * The benchmark build of this fork.
 *
 * The upstream benchmark measured `eu.kanade.tachiyomi.benchmark`, which is not an
 * application id of this fork, so the measurement never targeted the shipped app.
 * The identity is derived from the same release properties the app uses so the two
 * cannot drift apart.
 */
val releaseProperties = Properties().apply {
    rootProject.file("gradle/android-release.properties").inputStream().use(::load)
}
val benchmarkTargetPackage = "${releaseProperties.getProperty("applicationId")}.benchmark"

android {
    namespace = "tachiyomi.macrobenchmark"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["androidx.benchmark.enabledRules"] = "BaselineProfile"
        testInstrumentationRunnerArguments["mihon.benchmark.targetPackage"] = benchmarkTargetPackage
    }

    buildTypes {
        // This benchmark buildType is used for benchmarking, and should function like your
        // release build (for example, with minification on). It's signed with a debug key
        // for easy local/CI testing.
        create("benchmark") {
            isDebuggable = true
            signingConfig = getByName("debug").signingConfig
            matchingFallbacks.add("release")
        }
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

dependencies {
    implementation(androidx.test.ext)
    implementation(androidx.test.espresso.core)
    implementation(androidx.test.uiautomator)
    implementation(androidx.benchmark.macro)
}

androidComponents {
    beforeVariants(selector().all()) {
        it.enable = it.buildType == "benchmark"
    }
}
