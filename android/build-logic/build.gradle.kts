plugins {
    `java-gradle-plugin`
    kotlin("jvm") version "2.2.20"
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // Gradle 9.8 puts Kotlin 2.4 classes on the compile classpath; the 2.2 compiler can still read them.
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

dependencies {
    compileOnly("com.android.tools.build:gradle-api:9.4.1")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

gradlePlugin {
    plugins {
        create("xcstrings") {
            id = "sunwatt.xcstrings"
            implementationClass = "sunwatt.xcstrings.XcstringsPlugin"
        }
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        showStandardStreams = false
    }
}
