plugins {
    kotlin("jvm") version "2.3.10" apply false
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    repositories { mavenCentral() }
    tasks.withType<Jar>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}

listOf("old" to "1.10.2", "new" to "1.11.0").forEach { (name, version) ->
    project(":$name") {
        dependencies {
            "compileOnly"("org.jetbrains.kotlinx:kotlinx-coroutines-core:$version")
        }
    }
}

project(":future") {
    val upstreamApi = file("../../../../app-desktop/tmp/aex00-external-v16/build/classes/kotlin/main")
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        doFirst {
            require(upstreamApi.resolve("eu/kanade/tachiyomi/source/Source.class").isFile) {
                "Missing fixed external v1.6 API compilation. Follow extension-coroutines/README.md prerequisites."
            }
        }
    }
    dependencies {
        // The API is the separately compiled, fixed upstream v1.6 snapshot from AEX-00.
        // No host production output, Kotlin/coroutines, API, or stub classes enter this JAR.
        "compileOnly"(files(upstreamApi))
        "compileOnly"(project(":stubs"))
        "compileOnly"("com.squareup.okhttp3:okhttp:5.3.2")
    }
    tasks.named<Jar>("jar") {
        from(zipTree("../../../../app-desktop/src/test/resources/extensions/real/aex00-external-v16-controlled-sample.jar")) {
            include("aex00/external/v16/**")
        }
    }
}
