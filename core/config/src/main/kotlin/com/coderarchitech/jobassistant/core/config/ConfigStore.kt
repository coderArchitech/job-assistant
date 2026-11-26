package com.coderarchitech.jobassistant.core.config

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** A signed document and its signature, as downloaded. */
class SignedBytes(
    val document: ByteArray,
    val signature: ByteArray,
)

/**
 * Keeps the last good download of each item, so the app has it offline and after a failed refresh, and the
 * highest version ever accepted, so a lost or damaged copy never opens the door to an older release.
 */
interface ConfigStore {
    /** The saved copy of [item], or null. It is checked again before use, like a download. */
    fun load(item: ConfigItem): SignedBytes?

    /** Replaces the saved copy of [item] and raises its floor to [version]. Throws [IOException] on failure. */
    fun save(
        item: ConfigItem,
        signed: SignedBytes,
        version: Int,
    )

    /** The highest version of [item] ever saved; 0 when none was. */
    fun floor(item: ConfigItem): Int
}

/**
 * Saves each item as one file in app-private storage: the 64-byte signature, then the document. One file, so a
 * crash mid-write never pairs a document with another's signature; written to a unique temporary file, synced
 * and moved into place. The floor is a second small file, written the same way after the document.
 */
class FileConfigStore(
    private val dir: File,
) : ConfigStore {
    override fun load(item: ConfigItem): SignedBytes? {
        val bytes =
            try {
                fileOf(item).takeIf { it.isFile }?.readBytes()
            } catch (_: IOException) {
                null
            }
        return bytes?.takeIf { it.size > ConfigSignatures.SIGNATURE_BYTES }?.let {
            SignedBytes(
                document = it.copyOfRange(ConfigSignatures.SIGNATURE_BYTES, it.size),
                signature = it.copyOfRange(0, ConfigSignatures.SIGNATURE_BYTES),
            )
        }
    }

    override fun save(
        item: ConfigItem,
        signed: SignedBytes,
        version: Int,
    ) {
        require(signed.signature.size == ConfigSignatures.SIGNATURE_BYTES) { "An Ed25519 signature is 64 bytes" }
        replace(fileOf(item), signed.signature + signed.document)
        if (version > floor(item)) replace(floorOf(item), version.toString().toByteArray())
    }

    override fun floor(item: ConfigItem): Int =
        try {
            floorOf(item)
                .takeIf { it.isFile }
                ?.readText()
                ?.trim()
                ?.toIntOrNull() ?: 0
        } catch (_: IOException) {
            0
        }

    private fun replace(
        target: File,
        bytes: ByteArray,
    ) {
        dir.mkdirs()
        val temp = File.createTempFile("${target.name}.", ".tmp", dir)
        try {
            FileOutputStream(temp).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } finally {
            temp.delete()
        }
    }

    private fun fileOf(item: ConfigItem) = File(dir, item.path.replace('/', '_'))

    private fun floorOf(item: ConfigItem) = File(dir, item.path.replace('/', '_') + ".floor")
}
