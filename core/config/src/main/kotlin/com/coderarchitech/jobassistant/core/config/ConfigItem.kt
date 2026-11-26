package com.coderarchitech.jobassistant.core.config

/**
 * Something the config host publishes (design, Portal adapters; task T3.09). Each is a JSON document at
 * [path] with a detached Ed25519 signature at `path.sig`, and names its own [kind] and [name], so a document
 * signed for one item is never accepted as another.
 */
sealed class ConfigItem(
    /** The document's `kind`. */
    val kind: String,
    /** The document's `name`. */
    val name: String,
    /** Where it lives, relative to the config host and to the bundled `config/` assets. */
    val path: String,
) {
    /** The model price table. */
    data object Prices : ConfigItem(kind = "prices", name = "prices", path = "v1/prices.json")

    /** A portal adapter bundle, such as `naukri`. */
    class Adapter(
        portal: String,
    ) : ConfigItem(kind = "adapter", name = portal, path = "v1/adapters/$portal.json") {
        init {
            require(ID.matches(portal)) { "A portal name is lower-case letters, digits and '-'" }
        }

        override fun equals(other: Any?) = other is Adapter && other.name == name

        override fun hashCode() = name.hashCode()

        override fun toString() = "Adapter($name)"

        private companion object {
            val ID = Regex("[a-z0-9][a-z0-9-]{0,40}")
        }
    }
}
