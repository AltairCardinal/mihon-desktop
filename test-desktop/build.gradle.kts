plugins {
    kotlin("jvm")
}

group = "mihon.test"
version = "1.0-SNAPSHOT"

kotlin {
    jvmToolchain(21)

    sourceSets {
        main {
            dependencies {
                // HTTP Client
                implementation("io.ktor:ktor-client-core:3.0.2")
                implementation("io.ktor:ktor-client-okhttp:3.0.2")
                implementation("io.ktor:ktor-client-content-negotiation:3.0.2")
                implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.2")

                // Serialization
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

                // Logging
                implementation("org.slf4j:slf4j-api:2.0.9")

                // Coroutines
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")

                // Annotations
                implementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
            }
        }
        test {
            dependencies {
                implementation("org.junit.jupiter:junit-jupiter:5.10.2")
                implementation("org.assertj:assertj-core:3.25.3")
                implementation("org.junit.jupiter:junit-jupiter-params:5.10.2")
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
