package com.coderarchitech.jobassistant.core.config

import com.coderarchitech.jobassistant.core.network.NetworkJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * A config document: the header every item carries (`kind`, `name`, `version`, `minAppVersion`) and the rest
 * of its [body]. Only parsed after its signature checks out, or when it comes from the APK.
 */
class ConfigDocument private constructor(
    val kind: String,
    val name: String,
    /** Increases with every release; an older one never replaces a newer one. */
    val version: Int,
    /** The lowest app `versionCode` that can use it. */
    val minAppVersion: Int,
    val body: JsonObject,
) {
    /** True when this document is the one published for [item]. */
    fun isFor(item: ConfigItem): Boolean = kind == item.kind && name == item.name

    /** Reading documents. */
    companion object {
        /** The document in [bytes], or null when it is not one. */
        fun parse(bytes: ByteArray): ConfigDocument? {
            val json =
                try {
                    NetworkJson.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)) as? JsonObject
                } catch (_: SerializationException) {
                    null
                } catch (_: CharacterCodingException) {
                    null
                }
            return json?.let(::header)
        }

        private fun header(json: JsonObject): ConfigDocument? {
            val kind = json.text("kind")
            val name = json.text("name")
            val version = json.number("version")?.takeIf { it >= 1 }
            val minAppVersion = json.number("minAppVersion")?.takeIf { it >= 0 }
            val complete = kind != null && name != null && version != null && minAppVersion != null
            return if (complete) ConfigDocument(kind, name, version, minAppVersion, json) else null
        }

        private fun JsonObject.text(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }

        private fun JsonObject.number(key: String): Int? =
            (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
    }
}
