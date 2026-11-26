package com.coderarchitech.jobassistant.core.config

import com.coderarchitech.jobassistant.core.model.BuildInfo
import com.google.crypto.tink.subtle.Ed25519Sign
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

/** Task T3.09 Done when: a tampered bundle is rejected and the bundled version is used. */
class ConfigClientTest {
    @get:Rule val folder = TemporaryFolder()

    private val server = MockWebServer()
    private val current = Ed25519Sign.KeyPair.newKeyPair()
    private val next = Ed25519Sign.KeyPair.newKeyPair()
    private val stranger = Ed25519Sign.KeyPair.newKeyPair()
    private val naukri = ConfigItem.Adapter("naukri")
    private val bundledFiles = mutableMapOf<String, ByteArray>()
    private lateinit var storeDir: File

    @Before
    fun start() {
        server.start()
        storeDir = folder.newFolder("config")
        bundledFiles[naukri.path] = document("adapter", "naukri", version = 3)
    }

    @After
    fun stop() = server.close()

    @Test
    fun aTamperedBundleIsRejectedAndTheBundledVersionIsUsed() =
        runTest {
            val signed = document("adapter", "naukri", version = 4)
            val tampered = signed.decodeToString().replace("\"script\":\"ok\"", "\"script\":\"evil\"").toByteArray()
            serve(tampered, sign(current, signed))

            assertEquals(RefreshResult.Rejected(Rejection.BAD_SIGNATURE), client().refresh(naukri))

            val inUse = client().current(naukri)
            assertEquals(ConfigOrigin.BUNDLED, inUse?.origin)
            assertEquals(3, inUse?.document?.version)
        }

    @Test
    fun aGoodNewerReleaseIsUsedAndKeptAcrossRestarts() =
        runTest {
            val release = document("adapter", "naukri", version = 4)
            serve(release, sign(current, release))

            assertEquals(RefreshResult.Updated(4), client().refresh(naukri))

            // A new client, as after a restart, reads the saved copy without the network.
            val inUse = client().current(naukri)
            assertEquals(ConfigOrigin.DOWNLOADED, inUse?.origin)
            assertEquals(4, inUse?.document?.version)
        }

    @Test
    fun theNextKeyIsAcceptedAndAnyOtherKeyIsNot() =
        runTest {
            val byNext = document("adapter", "naukri", version = 4)
            serve(byNext, sign(next, byNext))
            assertEquals(RefreshResult.Updated(4), client().refresh(naukri))

            val byStranger = document("adapter", "naukri", version = 5)
            serve(byStranger, sign(stranger, byStranger))
            assertEquals(RefreshResult.Rejected(Rejection.BAD_SIGNATURE), client().refresh(naukri))
            assertEquals(4, client().current(naukri)?.document?.version)
        }

    @Test
    fun anotherPortalsSignedBundleIsRefused() =
        runTest {
            val instahyre = document("adapter", "instahyre", version = 9)
            serve(instahyre, sign(current, instahyre))

            assertEquals(RefreshResult.Rejected(Rejection.WRONG_ITEM), client().refresh(naukri))
        }

    @Test
    fun anOlderSignedReleaseCannotRollBack() =
        runTest {
            val old = document("adapter", "naukri", version = 2)
            serve(old, sign(current, old))
            assertEquals(RefreshResult.Rejected(Rejection.OLDER), client().refresh(naukri))

            val same = document("adapter", "naukri", version = 3)
            serve(same, sign(current, same))
            assertEquals(RefreshResult.UpToDate(3), client().refresh(naukri))
            assertEquals(ConfigOrigin.BUNDLED, client().current(naukri)?.origin)
        }

    @Test
    fun losingTheSavedCopyDoesNotLetAnOlderReleaseBackIn() =
        runTest {
            val v5 = document("adapter", "naukri", version = 5)
            serve(v5, sign(current, v5))
            assertEquals(RefreshResult.Updated(5), client().refresh(naukri))
            storeDir
                .listFiles()
                .orEmpty()
                .filterNot { it.name.endsWith(".floor") }
                .forEach { it.delete() }

            val v4 = document("adapter", "naukri", version = 4)
            serve(v4, sign(current, v4))
            assertEquals(RefreshResult.Rejected(Rejection.OLDER), client().refresh(naukri))
            assertEquals(ConfigOrigin.BUNDLED, client().current(naukri)?.origin)
        }

    @Test
    fun aHostWithAPathKeepsIt() =
        runTest {
            for (host in listOf(server.url("/config/").toString(), server.url("/config").toString())) {
                server.enqueue(MockResponse.Builder().code(HTTP_NOT_FOUND).build())
                client(host = host).refresh(naukri)
                assertEquals("/config/v1/adapters/naukri.json", server.takeRequest().url.encodedPath)
            }
        }

    @Test
    fun aGoodReleaseThatCannotBeSavedChangesNothing() =
        runTest {
            val release = document("adapter", "naukri", version = 4)
            serve(release, sign(current, release))
            val full =
                object : ConfigStore {
                    override fun load(item: ConfigItem): SignedBytes? = null

                    override fun save(
                        item: ConfigItem,
                        signed: SignedBytes,
                        version: Int,
                    ) = throw java.io.IOException("No space left on device")

                    override fun floor(item: ConfigItem) = 0
                }

            assertEquals(RefreshResult.Unavailable(Unavailability.STORAGE), client(store = full).refresh(naukri))
            assertEquals(3, client(store = full).current(naukri)?.document?.version)
        }

    @Test
    fun aSavedReleaseThisAppCannotReadLosesToTheBundledOne() =
        runTest {
            val release = document("adapter", "naukri", version = 4)
            serve(release, sign(current, release))
            client().refresh(naukri)

            val inUse = client().current(naukri) { it.version != 4 }

            assertEquals(ConfigOrigin.BUNDLED, inUse?.origin)
        }

    @Test
    fun aReleaseForANewerAppIsNotUsed() =
        runTest {
            val future = document("adapter", "naukri", version = 4, minAppVersion = APP_VERSION + 1)
            serve(future, sign(current, future))

            assertEquals(RefreshResult.Rejected(Rejection.NEEDS_NEWER_APP), client().refresh(naukri))
            assertEquals(3, client().current(naukri)?.document?.version)
        }

    @Test
    fun aSavedCopyTamperedOnDiskIsIgnored() =
        runTest {
            val release = document("adapter", "naukri", version = 4)
            serve(release, sign(current, release))
            client().refresh(naukri)
            val file = storeDir.listFiles().orEmpty().single { !it.name.endsWith(".floor") }
            file.writeBytes(
                file
                    .readBytes()
                    .decodeToString()
                    .replace("ok", "no")
                    .toByteArray(),
            )

            val inUse = client().current(naukri)
            assertEquals(ConfigOrigin.BUNDLED, inUse?.origin)
        }

    @Test
    fun withoutKeysNothingDownloadedIsUsed() =
        runTest {
            val release = document("adapter", "naukri", version = 4)
            serve(release, sign(current, release))

            val result = client(keys = emptyList()).refresh(naukri)

            assertEquals(RefreshResult.Rejected(Rejection.BAD_SIGNATURE), result)
            assertEquals(ConfigOrigin.BUNDLED, client(keys = emptyList()).current(naukri)?.origin)
        }

    @Test
    fun aMissingHostOrAFailedFetchLeavesConfigAsItWas() =
        runTest {
            assertEquals(RefreshResult.Unavailable(Unavailability.HOST_NOT_SET), client(host = "").refresh(naukri))
            assertEquals(0, server.requestCount)

            server.enqueue(MockResponse.Builder().code(HTTP_NOT_FOUND).build())
            assertEquals(RefreshResult.Unavailable(Unavailability.HTTP_ERROR), client().refresh(naukri))

            val release = document("adapter", "naukri", version = 4)
            server.enqueue(MockResponse.Builder().body(release.decodeToString()).build())
            server.enqueue(MockResponse.Builder().code(HTTP_SERVER_ERROR).build())
            assertEquals(RefreshResult.Unavailable(Unavailability.HTTP_ERROR), client().refresh(naukri))

            serve(release, "not base64!".toByteArray(), encode = false)
            assertEquals(RefreshResult.Rejected(Rejection.BAD_SIGNATURE), client().refresh(naukri))

            assertEquals(3, client().current(naukri)?.document?.version)
        }

    @Test
    fun anOversizedReleaseIsNotRead() =
        runTest {
            server.enqueue(MockResponse.Builder().body("x".repeat(1_000_001)).build())

            assertEquals(RefreshResult.Unavailable(Unavailability.TOO_LARGE), client().refresh(naukri))
        }

    @Test
    fun aSignedReleaseWithABadBodyIsMalformed() =
        runTest {
            val release = document("adapter", "naukri", version = 4)
            serve(release, sign(current, release))

            assertEquals(RefreshResult.Rejected(Rejection.MALFORMED), client().refresh(naukri) { false })

            val notADocument = "[1, 2, 3]".toByteArray()
            serve(notADocument, sign(current, notADocument))
            assertEquals(RefreshResult.Rejected(Rejection.BAD_SIGNATURE), client().refresh(naukri))
        }

    @Test
    fun nothingAtAllMeansNoConfig() =
        runTest {
            assertNull(client().current(ConfigItem.Adapter("instahyre")))
        }

    private fun client(
        host: String = server.url("/").toString(),
        keys: List<ByteArray> = listOf(current.publicKey, next.publicKey),
        store: ConfigStore = FileConfigStore(storeDir),
    ) = ConfigClient(
        http = OkHttpClient(),
        buildInfo =
            BuildInfo(
                versionName = "1.0",
                flavor = "dev",
                configHost = host,
                versionCode = APP_VERSION,
                configKeys = keys.map { Base64.getEncoder().encodeToString(it) },
            ),
        signatures = ConfigSignatures(keys.map { Base64.getEncoder().encodeToString(it) }),
        store = store,
        bundled = { bundledFiles[it] },
        io = Dispatchers.Unconfined,
    )

    private fun serve(
        document: ByteArray,
        signature: ByteArray,
        encode: Boolean = true,
    ) {
        server.enqueue(MockResponse.Builder().body(document.decodeToString()).build())
        val sig = if (encode) Base64.getEncoder().encodeToString(signature) else signature.decodeToString()
        server.enqueue(MockResponse.Builder().body(sig).build())
    }

    private fun sign(
        keys: Ed25519Sign.KeyPair,
        data: ByteArray,
    ): ByteArray = Ed25519Sign(keys.privateKey).sign(data)

    private companion object {
        const val APP_VERSION = 5
        const val HTTP_NOT_FOUND = 404
        const val HTTP_SERVER_ERROR = 500

        fun document(
            kind: String,
            name: String,
            version: Int,
            minAppVersion: Int = 1,
        ): ByteArray =
            """{"kind":"$kind","name":"$name","version":$version,"minAppVersion":$minAppVersion,"script":"ok"}"""
                .toByteArray()
    }
}
