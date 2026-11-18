package com.coderarchitech.jobassistant.core.data.settings

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/** File name of the auto-send consent marker inside the app's no-backup directory. */
internal const val AUTO_SEND_CONSENT_FILE_NAME = "auto_send_emails_consent"

/**
 * The user's "auto-send emails" consent for this device (task T1.08, decision D03): the consent is
 * on exactly while the file from [produceFile] exists. In the app that file lives in
 * `Context.noBackupFilesDir` (resolved lazily, since the getter creates the directory), which the
 * platform never puts in a cloud backup or a device transfer, so a restored or transferred install
 * always starts with auto-send off. The file holds no data.
 *
 * Every method blocks on disk I/O; call them off the main thread. Only [DataStoreSettingsRepository]
 * changes the marker.
 */
internal class AutoSendConsentMarker(
    produceFile: () -> File,
) {
    private val file: File by lazy(produceFile)

    /** True when the consent marker exists. */
    fun exists(): Boolean = file.isFile

    /** Creates the marker and syncs its directory, so the consent survives a power loss. */
    fun create() {
        val dir = file.absoluteFile.parentFile
        if (dir == null || (!dir.isDirectory && !dir.mkdirs())) throw IOException("No directory for ${file.name}")
        if (!file.createNewFile() && !file.isFile) throw IOException("Cannot create ${file.name}")
        syncDirectory(dir)
    }

    /** Deletes the marker and syncs its directory, so a crash cannot bring the consent back. */
    fun delete() {
        if (!file.delete() && file.exists()) throw IOException("Cannot delete ${file.name}")
        file.absoluteFile.parentFile
            ?.takeIf { it.isDirectory }
            ?.let(::syncDirectory)
    }

    private fun syncDirectory(dir: File) {
        FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }
}
