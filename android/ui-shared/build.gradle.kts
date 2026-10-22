plugins {
    alias(libs.plugins.android.library)
}

// Code the phone app and the watch app share: secret cipher, colours, chart models, layout and renderers (DECISIONS D38).
android {
    namespace = "com.ideatic.sunwatt.shared"
    compileSdk = 36

    defaultConfig {
        // As the phone app; the watch (30) is above it.
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // The shared types expose :core models and Compose colours, so both are part of the API.
    api(project(":core"))
    api(platform(libs.compose.bom))
    api(libs.compose.ui)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
