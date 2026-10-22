pluginManagement {
    // Strings generator: .xcstrings catalogs -> strings.xml (DECISIONS D35).
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "SunWatt"

include(":core")
include(":app")
include(":wear")
include(":ui-shared")
