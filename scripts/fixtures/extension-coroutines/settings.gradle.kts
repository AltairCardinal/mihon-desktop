pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
rootProject.name = "extension-coroutines-abi"
include("old", "new", "future", "stubs")
