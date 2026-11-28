package com.coderarchitech.jobassistant.portals.adapter

import com.coderarchitech.jobassistant.core.config.ConfigClient
import com.coderarchitech.jobassistant.core.config.ConfigDocument
import com.coderarchitech.jobassistant.core.config.ConfigItem
import com.coderarchitech.jobassistant.core.config.RefreshResult
import com.coderarchitech.jobassistant.portals.web.PortalSession

/**
 * Signed adapter manifests from the config host, or the ones bundled under `assets/config/v1/adapters/` (task
 * T3.11). A release is usable only if its manifest is valid and stays on the portal's own origins; anything else
 * is refused like a bad signature, so it never replaces a working one.
 */
class AdapterBundles(
    private val client: ConfigClient,
) {
    /** The manifest in use for [portal], or null when there is none this app can use. */
    suspend fun manifest(portal: PortalSession): AdapterManifest? =
        client
            .current(ConfigItem.Adapter(portal.id)) { usable(it, portal) != null }
            ?.document
            ?.let { usable(it, portal) }

    /** Fetches a newer release for [portal] and keeps it if it is good. */
    suspend fun refresh(portal: PortalSession): RefreshResult =
        client.refresh(ConfigItem.Adapter(portal.id)) { usable(it, portal) != null }

    private fun usable(
        document: ConfigDocument,
        portal: PortalSession,
    ) = AdapterManifest.from(document.body)?.takeIf { it.isFor(portal) }
}
