package com.ideatic.sunwatt.core.store

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Named blobs of app-private storage. The app module implements it on `Context.filesDir` ([DirectoryFileStorage] does
 * it for any folder). Names are plain file names: no folders.
 */
interface FileStorage {
    /** The bytes of [name], or null when it does not exist or cannot be read. */
    fun read(name: String): ByteArray?

    /** Replaces [name] with [bytes] all at once: a reader sees the old or the new content, never a mix. Throws [IOException]. */
    fun writeAtomically(name: String, bytes: ByteArray)

    /** Removes [name]; a missing file is fine. */
    fun delete(name: String)
}

/**
 * Encrypts the secrets (kiosk link, API keys) before they reach the disk. The app implements it with an Android
 * Keystore AES-GCM key. Texts are opaque to the store: [decrypt] gets back what [encrypt] returned.
 */
interface SecretCipher {
    fun encrypt(plain: String): String

    /** Throws when [cipher] cannot be read (key lost or changed, damaged text); the store then treats the secret as empty. */
    fun decrypt(cipher: String): String
}

/** [FileStorage] on a folder: temp file in the same folder, `fsync`, then an atomic rename over the target. */
class DirectoryFileStorage(private val directory: File) : FileStorage {
    private fun file(name: String): File {
        require(name.isNotEmpty() && '/' !in name && '\\' !in name && name != "." && name != "..") { "Invalid file name" }
        return File(directory, name)
    }

    override fun read(name: String): ByteArray? = try {
        file(name).takeIf { it.isFile }?.readBytes()
    } catch (e: IOException) {
        null
    }

    override fun writeAtomically(name: String, bytes: ByteArray) {
        val target = file(name)
        Files.createDirectories(directory.toPath())
        val temp = File(directory, ".$name.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temp).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
        }
    }

    override fun delete(name: String) {
        file(name).delete()
    }
}
