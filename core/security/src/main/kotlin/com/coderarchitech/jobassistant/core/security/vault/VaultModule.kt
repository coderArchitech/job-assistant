package com.coderarchitech.jobassistant.core.security.vault

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.dataStoreFile
import com.coderarchitech.jobassistant.core.model.IoDispatcher
import com.coderarchitech.jobassistant.core.security.vault.proto.VaultProto
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/** Binds [Vault] to the Tink vault in `vault.pb` with its Android Keystore key. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class VaultModule {
    @Binds
    abstract fun bindVault(impl: TinkVault): Vault

    companion object {
        /** One DataStore per file per process, so it is a singleton. */
        @Provides
        @Singleton
        fun provideVaultDataStore(
            @ApplicationContext context: Context,
            @IoDispatcher ioDispatcher: CoroutineDispatcher,
        ): DataStore<VaultProto> =
            createVaultDataStore(
                scope = CoroutineScope(ioDispatcher + SupervisorJob()),
                produceFile = { context.dataStoreFile(VAULT_FILE_NAME) },
            )

        @Provides
        @Singleton
        fun provideKeyEncryptionKey(): KeyEncryptionKey = AndroidKeystoreKeyEncryptionKey()
    }
}
