pluginManagement {
    resolutionStrategy {
        eachPlugin {
            val regex = "com.android.(library|application)".toRegex()
            if (regex matches requested.id.id) {
                useModule("com.android.tools.build:gradle:${requested.version}")
            }
        }
    }
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        maven(url = "https://www.jitpack.io")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// Preserve the released binary when JitPack no longer serves this immutable revision.
val pinnedAdapterDirectory = file(
    "gradle/pinned-maven/com/github/arkon/FlexibleAdapter/flexible-adapter/c8013533",
)
mapOf(
    "flexible-adapter-c8013533.aar" to "41929c785c249e0395faf89fd6bb253aafd65d44d88dbeaa46ecd9658d706cc4",
    "flexible-adapter-c8013533.pom" to "a5add124c5026173a759e73dd62bbae8943330cd401421983fd506a19b7f4edb",
).forEach { (name, expected) ->
    val artifact = pinnedAdapterDirectory.resolve(name)
    check(artifact.isFile) { "Missing pinned FlexibleAdapter artifact: $artifact" }
    val actual = java.security.MessageDigest.getInstance("SHA-256").digest(artifact.readBytes())
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    check(actual == expected) { "Pinned FlexibleAdapter artifact digest mismatch: $artifact" }
}

dependencyResolutionManagement {
    versionCatalogs {
        create("kotlinx") {
            from(files("gradle/kotlinx.versions.toml"))
        }
        create("androidx") {
            from(files("gradle/androidx.versions.toml"))
        }
        create("androidCompose") {
            from(files("gradle/compose.versions.toml"))
        }
    }
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository {
                maven {
                    name = "PinnedFlexibleAdapter"
                    url = uri(file("gradle/pinned-maven"))
                }
            }
            filter {
                includeModule("com.github.arkon.FlexibleAdapter", "flexible-adapter")
            }
        }
        mavenCentral()
        google()
        maven(url = "https://www.jitpack.io")
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "Mihon"
include(":app")
include(":core-metadata")
include(":core:archive")
include(":core:common")
include(":data")
include(":domain")
include(":i18n")
include(":macrobenchmark")
include(":presentation-core")
include(":presentation-theme")
include(":presentation-sync")
include(":presentation-history")
include(":presentation-widget")
include(":source-api")
include(":source-local")
include(":telemetry")
include(":app-desktop")
include(":test-desktop")
