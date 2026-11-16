package com.coderarchitech.jobassistant.core.data.settings

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import com.coderarchitech.jobassistant.core.data.settings.proto.SettingsProto
import com.coderarchitech.jobassistant.core.model.AppSettings
import com.google.protobuf.InvalidProtocolBufferException
import kotlinx.coroutines.CoroutineScope
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** File name of the settings store inside the app's DataStore directory. */
internal const val SETTINGS_FILE_NAME = "settings.pb"

/** Reads and writes [SettingsProto]; a missing file reads as the complete default settings. */
internal object SettingsSerializer : Serializer<SettingsProto> {
    override val defaultValue: SettingsProto = AppSettings().toProto()

    override suspend fun readFrom(input: InputStream): SettingsProto =
        try {
            SettingsProto.parseFrom(input)
        } catch (e: InvalidProtocolBufferException) {
            throw CorruptionException("Cannot read settings", e)
        }

    override suspend fun writeTo(
        t: SettingsProto,
        output: OutputStream,
    ) {
        t.writeTo(output)
    }
}

/**
 * Creates the settings DataStore over [produceFile]. A corrupt file is replaced with the defaults.
 * Create only one instance per file for the whole process.
 */
internal fun createSettingsDataStore(
    scope: CoroutineScope,
    produceFile: () -> File,
): DataStore<SettingsProto> =
    DataStoreFactory.create(
        serializer = SettingsSerializer,
        corruptionHandler = ReplaceFileCorruptionHandler { SettingsSerializer.defaultValue },
        scope = scope,
        produceFile = produceFile,
    )
