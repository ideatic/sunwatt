import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("sunwatt.xcstrings")
}

// Release signing: the upload key in the top-level private/ (git-ignored, see private/android_upload.properties).
// Without that file the release build stays unsigned, as on CI.
val uploadKey = rootProject.file("../private/android_upload.properties").takeIf { it.isFile }?.let { file ->
    Properties().apply { file.inputStream().use { load(it) } }
}


android {
    // The R class is `com.ideatic.sunwatt.wear.R`; the application ID is the phone's, so the Data Layer pairs the two apps.
    namespace = "com.ideatic.sunwatt.wear"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ideatic.sunwatt"
        // Wear OS 3 (API 30): the oldest release that still gets Google Play services updates (the Data Layer the settings
        // sync uses) and the one the Jetpack complication and Compose for Wear OS 1.5 libraries are tested on.
        minSdk = 30
        targetSdk = 36
        // The phone and watch builds share one Play listing, so their version codes must differ.
        // Phone and watch share one Play listing, so each needs its own code: phone 2n - 1, watch 2n (DECISIONS D39).
        versionCode = 2 * providers.gradleProperty("sunwatt.versionCode").get().toInt()
        versionName = providers.gradleProperty("sunwatt.versionName").get()
    }

    signingConfigs {
        if (uploadKey != null) {
            create("upload") {
                storeFile = rootProject.file("../private/${uploadKey.getProperty("storeFile")}")
                storePassword = uploadKey.getProperty("storePassword")
                keyAlias = uploadKey.getProperty("keyAlias")
                keyPassword = uploadKey.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("upload")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":ui-shared"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.complications.data.source.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.play.services)

    // Debug only: draws the complication data the way a watch face does (ComplicationPreviewActivity).
    debugImplementation(libs.wear.complications.rendering)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
