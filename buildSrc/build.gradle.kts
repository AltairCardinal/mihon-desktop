plugins {
    `kotlin-dsl`
}

val functionalTestPlugins by configurations.creating

dependencies {
    implementation(androidx.gradle)
    implementation(kotlinx.gradle)
    implementation(kotlinx.compose.compiler.gradle)
    implementation(libs.spotless.gradle)
    implementation(gradleApi())

    implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))
    implementation(files(androidx.javaClass.superclass.protectionDomain.codeSource.location))
    implementation(files(androidCompose.javaClass.superclass.protectionDomain.codeSource.location))
    implementation(files(kotlinx.javaClass.superclass.protectionDomain.codeSource.location))

    testImplementation(gradleTestKit())
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

    functionalTestPlugins(
        "com.mikepenz.aboutlibraries.plugin:aboutlibraries-plugin:" +
            libs.plugins.aboutLibraries.get().version.requiredVersion,
    )
}

repositories {
    gradlePluginPortal()
    mavenCentral()
    google()
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.named<PluginUnderTestMetadata>("pluginUnderTestMetadata") {
    pluginClasspath.from(functionalTestPlugins)
}
