package dev.agenticscheduler.application.sync

import com.sun.jna.platform.win32.Crypt32Util
import com.sun.jna.platform.win32.WinCrypt.CRYPTPROTECT_UI_FORBIDDEN
import java.util.Base64
import java.util.UUID
import java.util.prefs.Preferences

/**
 * SYN-009 desktop store. Windows persists DPAPI ciphertext only; Linux uses
 * the freedesktop Secret Service through `secret-tool`. There is deliberately
 * no plaintext file or in-memory production fallback.
 */
class DesktopPlatformSecureStore private constructor(
    private val backend: DesktopSecureBackend,
    private val pairingHpke: TinkPairingHpke,
    @Suppress("UNUSED_PARAMETER") private val constructorMarker: Unit,
) : PlatformSecretStore,
    PlatformKeyMaterialStore,
    PlatformPairingPrivateKeyStore,
    PlatformPairingKeyMaterialExporter,
    PlatformAccountMasterKeyStore {
    constructor(pairingHpke: TinkPairingHpke = TinkPairingHpke()) : this(DesktopSecureBackend.system(), pairingHpke, Unit)

    internal constructor(backend: DesktopSecureBackend, pairingHpke: TinkPairingHpke) : this(backend, pairingHpke, Unit)

    override suspend fun importSecret(material: PlatformSecretMaterial): SecretReference =
        store(SecretKind.GENERIC, material.copyRawSecretBytesForSecureStore())

    override suspend fun readSecret(reference: SecretReference): PlatformSecretMaterial? =
        read(reference, SecretKind.GENERIC)?.let(::StoredSecret)

    override suspend fun importContentKey(material: ImportedContentKeyMaterial): ImportedContentKey {
        val raw = material.copyRawSecretBytesForSecureStore()
        require(raw.size == CONTENT_KEY_BYTES) { "SyncSpace content key must be exactly 32 bytes." }
        return ImportedContentKey(store(SecretKind.CONTENT_KEY, raw), ContentKeyIdentity.fromRawAes256Key(raw))
    }

    override suspend fun contentAead(reference: SecretReference): SyncPayloadAead? =
        read(reference, SecretKind.CONTENT_KEY)
            ?.takeIf { it.size == CONTENT_KEY_BYTES }
            ?.let(TinkSyncPayloadAead::fromRawContentKey)

    override suspend fun generatePairingDeviceKey(): PersistedPairingDeviceKey {
        val generated = pairingHpke.generateDeviceKeyPair()
        return PersistedPairingDeviceKey(
            generated.publicKey,
            store(SecretKind.PAIRING_PRIVATE_KEY, pairingHpke.serializeForSecureStore(generated.privateKey)),
        )
    }

    override suspend fun privateKey(reference: SecretReference): PairingPrivateKeyMaterial? =
        try {
            read(reference, SecretKind.PAIRING_PRIVATE_KEY)?.let(pairingHpke::restoreFromSecureStore)
        } catch (_: Throwable) {
            null
        }

    override suspend fun exportAccountMasterKeyForPairing(reference: SecretReference): PairingEphemeralKeyMaterial? =
        read(reference, SecretKind.ACCOUNT_MASTER_KEY)?.let(::StoredSecret)

    override suspend fun exportContentKeyForPairing(reference: SecretReference): ExportedPairingContentKey? =
        read(reference, SecretKind.CONTENT_KEY)
            ?.takeIf { it.size == CONTENT_KEY_BYTES }
            ?.let { raw -> ExportedPairingContentKey(StoredSecret(raw), ContentKeyIdentity.fromRawAes256Key(raw)) }

    override suspend fun importAccountMasterKeyForPairing(material: PairingEphemeralKeyMaterial): SecretReference {
        val raw = material.copyRawKeyBytesForPairing()
        require(raw.size == CONTENT_KEY_BYTES) { "Account master key must be exactly 32 bytes." }
        return store(SecretKind.ACCOUNT_MASTER_KEY, raw)
    }

    override suspend fun delete(reference: SecretReference) {
        val id = referenceId(reference) ?: return
        backend.delete(id)
    }

    private fun store(kind: SecretKind, raw: ByteArray): SecretReference {
        val id = UUID.randomUUID().toString()
        backend.store(id, byteArrayOf(kind.tag) + raw)
        return SecretReference(backend.referencePrefix + id)
    }

    private fun read(reference: SecretReference, expected: SecretKind): ByteArray? {
        val id = referenceId(reference) ?: return null
        val stored = try { backend.read(id) } catch (_: Throwable) { null } ?: return null
        return if (stored.isNotEmpty() && stored[0] == expected.tag) stored.copyOfRange(1, stored.size) else null
    }

    private fun referenceId(reference: SecretReference): String? =
        reference.value.removePrefix(backend.referencePrefix)
            .takeIf { reference.value.startsWith(backend.referencePrefix) && UUID_PATTERN.matches(it) }

    private class StoredSecret(private val raw: ByteArray) : PairingEphemeralKeyMaterial, ImportedContentKeyMaterial {
        override fun copyRawKeyBytesForPairing(): ByteArray = raw.copyOf()
    }

    private enum class SecretKind(val tag: Byte) {
        GENERIC(1), CONTENT_KEY(2), ACCOUNT_MASTER_KEY(3), PAIRING_PRIVATE_KEY(4),
    }

    private companion object {
        const val CONTENT_KEY_BYTES = 32
        val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}

/** Internal native boundary; production code has no map or file fallback. */
internal interface DesktopSecureBackend {
    val referencePrefix: String
    fun store(id: String, value: ByteArray)
    fun read(id: String): ByteArray?
    fun delete(id: String)

    companion object {
        fun system(): DesktopSecureBackend = when {
            System.getProperty("os.name").startsWith("Windows", ignoreCase = true) -> WindowsDpapiSecureBackend()
            System.getProperty("os.name").startsWith("Linux", ignoreCase = true) -> LinuxSecretServiceSecureBackend()
            else -> throw UnsupportedOperationException("No SYN-009 secure-store backend is available for this desktop platform.")
        }
    }
}

/** DPAPI protects every blob before it reaches Windows user preferences. */
internal class WindowsDpapiSecureBackend(
    private val preferences: Preferences = Preferences.userRoot().node("dev/agenticscheduler/d8/secure-store/v1"),
) : DesktopSecureBackend {
    override val referencePrefix = "windows-dpapi://"

    override fun store(id: String, value: ByteArray) {
        val protected = Crypt32Util.cryptProtectData(value, CRYPTPROTECT_UI_FORBIDDEN)
        preferences.put(id, Base64.getUrlEncoder().withoutPadding().encodeToString(protected))
        preferences.flush()
    }

    override fun read(id: String): ByteArray? = try {
        preferences.get(id, null)
            ?.let(Base64.getUrlDecoder()::decode)
            ?.let { Crypt32Util.cryptUnprotectData(it, CRYPTPROTECT_UI_FORBIDDEN) }
    } catch (_: Throwable) {
        null
    }

    override fun delete(id: String) {
        preferences.remove(id)
        preferences.flush()
    }
}

/** `secret-tool` is the supported libsecret CLI for the Secret Service/keyring API. */
internal class LinuxSecretServiceSecureBackend(
    private val command: String = "secret-tool",
) : DesktopSecureBackend {
    override val referencePrefix = "linux-secret-service://"

    override fun store(id: String, value: ByteArray) {
        try {
            val process = ProcessBuilder(command, "store", "--label=Agentic Scheduler D8 Secret", "service", SERVICE, "reference", id)
                .redirectErrorStream(true)
                .start()
            process.outputStream.bufferedWriter().use { writer ->
                writer.write(Base64.getUrlEncoder().withoutPadding().encodeToString(value))
                writer.newLine()
            }
            process.inputStream.readBytes()
            check(process.waitFor() == 0) { "Secret Service store command failed." }
        } catch (failure: Throwable) {
            throw SecureStoreUnavailableException(UNAVAILABLE_MESSAGE, failure)
        }
    }

    override fun read(id: String): ByteArray? = try {
        val process = ProcessBuilder(command, "lookup", "service", SERVICE, "reference", id)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.readBytes()
        if (process.waitFor() != 0) null else Base64.getUrlDecoder().decode(output.decodeToString().trim())
    } catch (_: Throwable) {
        null
    }

    override fun delete(id: String) {
        try {
            val process = ProcessBuilder(command, "clear", "service", SERVICE, "reference", id)
                .redirectErrorStream(true)
                .start()
            process.inputStream.readBytes()
            check(process.waitFor() == 0) { "Secret Service clear command failed." }
        } catch (failure: Throwable) {
            throw SecureStoreUnavailableException(UNAVAILABLE_MESSAGE, failure)
        }
    }

    private companion object {
        const val SERVICE = "agentic-scheduler"
        const val UNAVAILABLE_MESSAGE =
            "Linux Secret Service is unavailable or locked. Start and unlock a Secret Service keyring, then retry sync or pairing."
    }
}
