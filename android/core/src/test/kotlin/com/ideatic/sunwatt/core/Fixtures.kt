package com.ideatic.sunwatt.core

import java.io.File

/** Reads the shared fixtures of the Apple package (never copied). The Gradle test task sets `sunwatt.fixtures`. */
object Fixtures {
    private val directory: File by lazy {
        val path = System.getProperty("sunwatt.fixtures")
            ?: error("System property sunwatt.fixtures is not set: run the tests through Gradle (:core:test).")
        File(path).also { check(it.isDirectory) { "Fixtures folder not found: $path" } }
    }

    fun bytes(name: String): ByteArray {
        val file = File(directory, name)
        check(file.isFile) { "Fixture not found: $name" }
        return file.readBytes()
    }

    fun text(name: String): String = bytes(name).toString(Charsets.UTF_8)
}
