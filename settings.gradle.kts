rootProject.name = "RoyTools"

include(":app")
include(":refresh")
include(":core")
include(":service")
include(":design")
include(":common")
include(":hideapi")

pluginManagement {
    repositories {
        google()
        mavenLocal()
        mavenCentral()
        gradlePluginPortal()
    }
}

