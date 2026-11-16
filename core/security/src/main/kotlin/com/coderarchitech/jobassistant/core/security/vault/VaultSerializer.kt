package com.coderarchitech.jobassistant.core.security.vault

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import com.coderarchitech.jobassistant.core.security.vault.proto.VaultProto
import com.google.protobuf.InvalidProtocolBufferException
import kotlinx.coroutines.CoroutineScope
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** File name of the vault inside the app's DataStore directory. */
internal const val VAULT_FILE_NAME = "vault.pb"

/** Reads and writes [VaultProto]; a missing file reads as an empty vault. */
internal object VaultSerializer : Serializer<VaultProto> {
    override val defaultValue: VaultProto = VaultProto.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): VaultProto =
        try {
            VaultProto.parseFrom(input)
        } catch (e: InvalidProtocolBufferException) {
            throw CorruptionException("Cannot read the vault", e)
        }

    override suspend fun writeTo(
        t: VaultProto,
        output: OutputStream,
    ) {
        t.writeTo(output)
    }
}

/**
 * Creates the vault DataStore over [produceFile]. A corrupt file is replaced with an empty vault:
 * its secrets are lost and the user enters them again. Create only one instance per file for the
 * whole process.
 */
internal fun createVaultDataStore(
    scope: CoroutineScope,
    produceFile: () -> File,
): DataStore<VaultProto> =
    DataStoreFactory.create(
        serializer = VaultSerializer,
        corruptionHandler = ReplaceFileCorruptionHandler { VaultSerializer.defaultValue },
        scope = scope,
        produceFile = produceFile,
    )
