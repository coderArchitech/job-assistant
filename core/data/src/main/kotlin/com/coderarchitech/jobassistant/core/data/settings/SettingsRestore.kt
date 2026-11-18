package com.coderarchitech.jobassistant.core.data.settings

import com.coderarchitech.jobassistant.core.data.settings.proto.SettingsProto
import com.google.protobuf.InvalidProtocolBufferException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** What [SettingsRestore.disableAutoSendAfterRestore] did to `settings.pb`. */
enum class AutoSendReset {
    /** No settings file was restored; the defaults (auto-send off) apply. Nothing was written. */
    NO_FILE,

    /** The file does not parse. Left as is: the settings store replaces it with the defaults on first read. */
    UNREADABLE,

    /** Auto-send was already off or never set. Nothing was written. */
    ALREADY_OFF,

    /** Auto-send was on and is now off; every other setting is unchanged. */
    DISABLED,

    /** The file could not be read or rewritten, so it was deleted and every setting reverts to its default. */
    DELETED,

    /** The file could not be rewritten or deleted; auto-send may still be on. */
    FAILED,
}

/**
 * Edits `settings.pb` right after a backup restore (decision D03: a restored install must not send
 * emails on its own until the user turns auto-send on again on this device).
 *
 * It works on the file directly, without DataStore, because it runs from the backup agent: during a
 * restore the app process starts in restricted mode, without the app's `Application` or Hilt, so no
 * settings DataStore exists in that process, and the system kills the process after the restore, so
 * no running DataStore can hold a stale copy. Never call it while the app's settings store is open.
 * It blocks on disk I/O and never throws.
 */
object SettingsRestore {
    /** `settings.pb` inside the app's [filesDir], where the settings DataStore keeps it. */
    fun settingsFile(filesDir: File): File = File(File(filesDir, DATASTORE_DIR), SETTINGS_FILE_NAME)

    /**
     * Turns `toggles.auto_send_emails` off in [file], keeping every other field (unknown fields
     * included), and writes the result atomically (temporary file, then rename). If the file cannot
     * be read or rewritten it is deleted instead, so the app falls back to its defaults, which have
     * auto-send off.
     */
    fun disableAutoSendAfterRestore(file: File): AutoSendReset =
        when (val read = read(file)) {
            Read.Missing -> AutoSendReset.NO_FILE
            Read.Corrupt -> AutoSendReset.UNREADABLE
            Read.Failed -> deleteOrFail(file)
            is Read.Parsed ->
                if (read.settings.toggles.autoSendEmails) {
                    disableAutoSend(
                        file,
                        read.settings,
                    )
                } else {
                    AutoSendReset.ALREADY_OFF
                }
        }

    private sealed interface Read {
        data object Missing : Read

        data object Corrupt : Read

        data object Failed : Read

        data class Parsed(
            val settings: SettingsProto,
        ) : Read
    }

    private fun read(file: File): Read =
        if (!file.exists()) {
            Read.Missing
        } else {
            try {
                Read.Parsed(file.inputStream().use { SettingsProto.parseFrom(it) })
            } catch (_: InvalidProtocolBufferException) {
                Read.Corrupt
            } catch (_: IOException) {
                Read.Failed
            }
        }

    private fun disableAutoSend(
        file: File,
        stored: SettingsProto,
    ): AutoSendReset {
        val updated =
            stored
                .toBuilder()
                .setToggles(stored.toggles.toBuilder().setAutoSendEmails(false))
                .build()
        return try {
            writeAtomically(file, updated)
            AutoSendReset.DISABLED
        } catch (_: IOException) {
            deleteOrFail(file)
        }
    }

    private fun writeAtomically(
        file: File,
        settings: SettingsProto,
    ) {
        val temp = File(file.parentFile, file.name + RESTORE_TEMP_SUFFIX)
        try {
            FileOutputStream(temp).use { out ->
                settings.writeTo(out)
                out.fd.sync()
            }
            // rename(2) within one directory is atomic and replaces the target, as DataStore does it.
            if (!temp.renameTo(file)) throw IOException("Cannot replace ${file.name}")
        } finally {
            temp.delete()
        }
    }

    private fun deleteOrFail(file: File): AutoSendReset =
        if (file.delete() || !file.exists()) AutoSendReset.DELETED else AutoSendReset.FAILED
}

/** DataStore's directory under `filesDir` (see `androidx.datastore.dataStoreFile`). */
private const val DATASTORE_DIR = "datastore"

/** Suffix of the temporary file written next to `settings.pb` before the rename. */
private const val RESTORE_TEMP_SUFFIX = ".restore.tmp"
