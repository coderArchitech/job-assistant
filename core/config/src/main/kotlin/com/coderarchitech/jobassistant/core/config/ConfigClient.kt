package com.coderarchitech.jobassistant.core.config

import com.coderarchitech.jobassistant.core.model.BuildInfo
import com.coderarchitech.jobassistant.core.network.await
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Base64

/** Where the config in use came from. */
enum class ConfigOrigin {
    /** Downloaded from the config host and verified, now or on an earlier run. */
    DOWNLOADED,

    /** Shipped in the APK. */
    BUNDLED,
}

/** The document in use for an item, and where it came from. */
class ActiveConfig(
    val document: ConfigDocument,
    val origin: ConfigOrigin,
)

/** What a [ConfigClient.refresh] did. In every case but [Updated] the config in use is unchanged. */
sealed interface RefreshResult {
    /** A newer verified release is now in use. */
    data class Updated(
        val version: Int,
    ) : RefreshResult

    /** The host has nothing newer than what is in use. */
    data class UpToDate(
        val version: Int,
    ) : RefreshResult

    /** The host's release was refused. */
    data class Rejected(
        val reason: Rejection,
    ) : RefreshResult

    /** The host could not be asked. */
    data class Unavailable(
        val reason: Unavailability,
    ) : RefreshResult
}

/** Why a release was refused. */
enum class Rejection {
    /** Not signed by either of the app's keys, or tampered with after signing. */
    BAD_SIGNATURE,

    /** Signed, but not a valid document for its kind. */
    MALFORMED,

    /** Signed for another item, such as one portal's bundle served as another's. */
    WRONG_ITEM,

    /** Older than the newest release ever accepted: a rollback. */
    OLDER,

    /** Needs a newer app (`minAppVersion`). */
    NEEDS_NEWER_APP,
}

/** Why the host could not be asked. */
enum class Unavailability {
    /** No config host is set for this build (task T3.10). */
    HOST_NOT_SET,
    NETWORK,
    HTTP_ERROR,
    TOO_LARGE,

    /** The release was good but could not be saved (storage full or failing); nothing changed. */
    STORAGE,
}

/**
 * Fetches signed config from the static config host and decides what the app uses (design, Portal adapters;
 * task T3.09).
 *
 * A release is used only if its Ed25519 signature checks out against one of the app's keys, it names the item
 * asked for, its body is valid for its kind, this app is new enough for it, and it is newer than every release
 * accepted before (a floor kept apart from the saved copy, so losing that copy never lets an older release
 * back in). Anything else leaves the config in use as it was. The last good release is kept on disk and
 * checked again on every read, and the version bundled in the APK is used when nothing newer is good, so a
 * tampered or missing download never takes config away. Refreshes run one at a time.
 *
 * Limit: a host that keeps serving the current release can hold back a newer one; nothing signed says how
 * fresh a release must be (task T3.10 may add an expiry).
 *
 * @param http the `NetworkGroup.CONFIG` client, which reaches only the config host.
 */
class ConfigClient(
    private val http: OkHttpClient,
    private val buildInfo: BuildInfo,
    private val signatures: ConfigSignatures,
    private val store: ConfigStore,
    private val bundled: BundledConfig,
    private val io: CoroutineDispatcher,
) {
    private val lock = Mutex()

    /**
     * Fetches [item]'s latest release and keeps it if it is good and newer. [isValidBody] checks the body for
     * the item's kind, here and whenever a saved or bundled copy is read.
     */
    suspend fun refresh(
        item: ConfigItem,
        isValidBody: (ConfigDocument) -> Boolean = { true },
    ): RefreshResult =
        withContext(io) {
            val base = buildInfo.configHost.takeIf { buildInfo.isConfigHostSet }?.toHttpUrlOrNull()
            if (base == null) return@withContext RefreshResult.Unavailable(Unavailability.HOST_NOT_SET)
            lock.withLock {
                when (val download = download(base, item)) {
                    is Download.Failed -> RefreshResult.Unavailable(download.reason)
                    is Download.Done -> accept(item, download.signed, isValidBody)
                }
            }
        }

    /**
     * The release of [item] in use: the last good download if it is newer than the bundled one, else that.
     * Only copies [isValidBody] accepts count.
     */
    suspend fun current(
        item: ConfigItem,
        isValidBody: (ConfigDocument) -> Boolean = { true },
    ): ActiveConfig? = withContext(io) { currentNow(item, isValidBody) }

    /** [current] on the calling thread; it reads a small file. */
    fun currentNow(
        item: ConfigItem,
        isValidBody: (ConfigDocument) -> Boolean = { true },
    ): ActiveConfig? {
        fun usable(document: ConfigDocument) = document.isFor(item) && document.fitsThisApp() && isValidBody(document)
        val saved =
            store
                .load(item)
                ?.let(::verified)
                ?.takeIf(::usable)
                ?.let { ActiveConfig(it, ConfigOrigin.DOWNLOADED) }
        val shipped =
            bundled
                .read(item.path)
                ?.let(ConfigDocument::parse)
                ?.takeIf(::usable)
                ?.let { ActiveConfig(it, ConfigOrigin.BUNDLED) }
        return listOfNotNull(saved, shipped).maxByOrNull { it.document.version }
    }

    private fun accept(
        item: ConfigItem,
        signed: SignedBytes,
        isValidBody: (ConfigDocument) -> Boolean,
    ): RefreshResult {
        val document = verified(signed) ?: return RefreshResult.Rejected(Rejection.BAD_SIGNATURE)
        val newest = maxOf(currentNow(item, isValidBody)?.document?.version ?: 0, store.floor(item))
        val rejection =
            when {
                !document.isFor(item) -> Rejection.WRONG_ITEM
                !isValidBody(document) -> Rejection.MALFORMED
                !document.fitsThisApp() -> Rejection.NEEDS_NEWER_APP
                document.version < newest -> Rejection.OLDER
                else -> null
            }
        return when {
            rejection != null -> RefreshResult.Rejected(rejection)
            document.version == newest -> RefreshResult.UpToDate(newest)
            else -> save(item, signed, document.version)
        }
    }

    private fun save(
        item: ConfigItem,
        signed: SignedBytes,
        version: Int,
    ): RefreshResult =
        try {
            store.save(item, signed, version)
            RefreshResult.Updated(version)
        } catch (_: IOException) {
            RefreshResult.Unavailable(Unavailability.STORAGE)
        } catch (_: SecurityException) {
            RefreshResult.Unavailable(Unavailability.STORAGE)
        }

    /** The document, if the signature checks out; MALFORMED documents parse to null and are refused the same. */
    private fun verified(signed: SignedBytes): ConfigDocument? =
        signed.takeIf { signatures.verify(it.document, it.signature) }?.let { ConfigDocument.parse(it.document) }

    private fun ConfigDocument.fitsThisApp() = minAppVersion <= buildInfo.versionCode

    private sealed interface Download {
        class Done(
            val signed: SignedBytes,
        ) : Download

        class Failed(
            val reason: Unavailability,
        ) : Download
    }

    private suspend fun download(
        base: HttpUrl,
        item: ConfigItem,
    ): Download {
        // Appended to the host's own path, so a host such as https://cdn.example/config keeps its /config.
        val document = fetch(base.newBuilder().addPathSegments(item.path).build())
        val signatureUrl = base.newBuilder().addPathSegments("${item.path}.sig").build()
        val signature = if (document is Fetch.Body) fetch(signatureUrl) else document
        return when {
            document is Fetch.Failed -> Download.Failed(document.reason)
            signature is Fetch.Failed -> Download.Failed(signature.reason)
            else -> {
                val decoded = decodeSignature((signature as Fetch.Body).bytes)
                Download.Done(SignedBytes((document as Fetch.Body).bytes, decoded))
            }
        }
    }

    private sealed interface Fetch {
        class Body(
            val bytes: ByteArray,
        ) : Fetch

        class Failed(
            val reason: Unavailability,
        ) : Fetch
    }

    private suspend fun fetch(url: HttpUrl): Fetch =
        try {
            http.newCall(Request.Builder().url(url).build()).await().use { response ->
                val body = response.body.source()
                when {
                    !response.isSuccessful -> Fetch.Failed(Unavailability.HTTP_ERROR)
                    !body.request(MAX_BYTES + 1) -> Fetch.Body(body.readByteArray())
                    else -> Fetch.Failed(Unavailability.TOO_LARGE)
                }
            }
        } catch (_: IOException) {
            Fetch.Failed(Unavailability.NETWORK)
        }

    /** A malformed signature becomes an empty one, which never verifies. */
    private fun decodeSignature(text: ByteArray): ByteArray =
        try {
            Base64.getDecoder().decode(text.decodeToString().trim())
        } catch (_: IllegalArgumentException) {
            ByteArray(0)
        }

    private companion object {
        /** Adapter bundles and the price table are small; anything larger is not a release. */
        const val MAX_BYTES = 1_000_000L
    }
}

/** Config shipped in the APK, read by path under `config/`; trusted because the APK is signed. */
fun interface BundledConfig {
    /** The bundled bytes at [path], or null when the APK has none. */
    fun read(path: String): ByteArray?
}
