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
    namespace = "com.ideatic.sunwatt"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ideatic.sunwatt"
        minSdk = 29
        targetSdk = 36
        // Phone and watch share one Play listing, so each needs its own code: phone 2n - 1, watch 2n (DECISIONS D39).
        versionCode = 2 * providers.gradleProperty("sunwatt.versionCode").get().toInt() - 1
        versionName = providers.gradleProperty("sunwatt.versionName").get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.startup.runtime)
    // Wear OS: settings sync to the watch (wearsync/).
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.play.services)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.reflect)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.junit4)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    // Espresso 3.5 (the default) crashes on Android 17: InputManager.getInstance is gone.
    androidTestImplementation(libs.androidx.test.espresso.core)
    debugImplementation(libs.compose.ui.test.manifest)
}

// Debug only (DECISIONS D7): copies the git-ignored private/kiosk.local into the debug app's assets, or an empty file
// when it is absent, for DebugKioskPrefill. Release builds never get it. The content is never printed.
abstract class DebugKioskAsset : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val source: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val target = outputDir.get().file("kiosk.local").asFile
        target.parentFile.mkdirs()
        val file = source.files.firstOrNull { it.isFile }
        target.writeBytes(file?.readBytes() ?: ByteArray(0))
    }
}

val debugKioskAsset = tasks.register<DebugKioskAsset>("debugKioskAsset") {
    source.from(rootProject.layout.projectDirectory.file("../private/kiosk.local"))
    outputDir.set(layout.buildDirectory.dir("generated/debugKiosk/assets"))
}

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(debugKioskAsset, DebugKioskAsset::outputDir)
    }
}
