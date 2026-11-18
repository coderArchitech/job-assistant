package com.coderarchitech.jobassistant

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.os.ParcelFileDescriptor
import com.coderarchitech.jobassistant.core.data.settings.SettingsRestore

/**
 * Auto Backup with one restore hook (task T1.08, decision D03). The manifest sets
 * `android:fullBackupOnly="true"`, so the system still runs Auto Backup with the rules in
 * `res/xml/data_extraction_rules.xml` and `res/xml/backup_rules.xml`; the key/value callbacks are
 * never called and do nothing.
 *
 * During a restore the process runs in restricted mode: no `JobAssistantApp`, no Hilt. So
 * [onRestoreFinished] edits `settings.pb` directly and synchronously. The vault is never in a backup,
 * so the user enters secrets again after a restore.
 */
class JobAssistantBackupAgent : BackupAgent() {
    override fun onBackup(
        oldState: ParcelFileDescriptor?,
        data: BackupDataOutput?,
        newState: ParcelFileDescriptor?,
    ) = Unit

    override fun onRestore(
        data: BackupDataInput?,
        appVersionCode: Int,
        newState: ParcelFileDescriptor?,
    ) = Unit

    /** Turns auto-send emails off in the restored settings, so a restored install never emails on its own. */
    override fun onRestoreFinished() {
        SettingsRestore.disableAutoSendAfterRestore(SettingsRestore.settingsFile(filesDir))
    }
}
