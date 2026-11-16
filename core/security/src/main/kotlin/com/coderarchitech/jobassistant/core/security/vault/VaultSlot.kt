package com.coderarchitech.jobassistant.core.security.vault

import com.coderarchitech.jobassistant.core.model.AiProvider
import java.util.Locale

/**
 * The name a secret is stored under in the [Vault], such as `portal/12` or `ai/anthropic`.
 *
 * A name is 1 to 128 characters from `a-z`, `0-9`, `_`, `/` and `-`. Each ciphertext is bound to
 * its slot name, so a value copied to another slot cannot be decrypted there.
 */
@JvmInline
value class VaultSlot(
    val name: String,
) {
    init {
        require(NAME_PATTERN.matches(name)) { "Invalid vault slot name" }
    }

    override fun toString(): String = "VaultSlot($name)"

    /** Builders for the slots the app uses. */
    companion object {
        private val NAME_PATTERN = Regex("[a-z0-9_/-]{1,128}")

        /** Login credentials for a portal; equal to `Portal.vaultKey` for the same id. */
        fun portal(id: Long): VaultSlot = VaultSlot("portal/$id")

        /** The user's API key for an AI provider, for example `ai/anthropic`. */
        fun aiKey(provider: AiProvider): VaultSlot = VaultSlot("ai/" + provider.name.lowercase(Locale.ROOT))

        /** The slot for [name], or null when [name] is not a valid slot name. */
        fun fromNameOrNull(name: String): VaultSlot? = if (NAME_PATTERN.matches(name)) VaultSlot(name) else null
    }
}
