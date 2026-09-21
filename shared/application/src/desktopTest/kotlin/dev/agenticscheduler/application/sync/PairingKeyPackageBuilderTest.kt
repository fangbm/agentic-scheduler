package dev.agenticscheduler.application.sync

import dev.agenticscheduler.sync.AccountId
import dev.agenticscheduler.sync.DeviceId
import dev.agenticscheduler.sync.EnrollmentRequestId
import dev.agenticscheduler.sync.HpkePublicKeyBase64Url
import dev.agenticscheduler.sync.KeyPackagePlaintextV1
import dev.agenticscheduler.sync.PairingWireCodec
import dev.agenticscheduler.sync.PairingWireDecodeResult
import dev.agenticscheduler.sync.PendingEnrollmentRequestV1
import dev.agenticscheduler.sync.SyncSpaceId
import dev.agenticscheduler.sync.encodeCanonicalBase64Url
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class PairingKeyPackageBuilderTest {
    private val account = AccountId("acct-1")
    private val space = SyncSpaceId("personal-space")
    private val request = PendingEnrollmentRequestV1(
        account,
        EnrollmentRequestId("req-1"),
        DeviceId("device-1"),
        HpkePublicKeyBase64Url("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"),
    )
    private val active = metadata(8, "active", "identity-8", SyncSpaceContentKeyUsage.ACTIVE)
    private val epoch6 = metadata(6, "epoch-6", "identity-6", SyncSpaceContentKeyUsage.DECRYPT_ONLY)
    private val epoch7 = metadata(7, "epoch-7", "identity-7", SyncSpaceContentKeyUsage.DECRYPT_ONLY)

    @Test
    fun `sender package includes active key and complete sorted retained history`() = runBlocking {
        val hpke = RecordingHpke()
        val builder = PairingKeyPackageBuilder(
            keyRing = FixedKeyRing(active, listOf(epoch7, epoch6)),
            keyMaterial = Exporter(
                account = raw(0),
                keys = mapOf(
                    active.contentKeyReference to exported(raw(32), active.contentKeyIdentity),
                    epoch6.contentKeyReference to exported(raw(64), epoch6.contentKeyIdentity),
                    epoch7.contentKeyReference to exported(raw(96), epoch7.contentKeyIdentity),
                ),
            ),
            hpke = hpke,
        )

        val result = builder.build(PairingKeyPackageBuildRequest(account, SecretReference("secure://amk"), space, request))

        val envelope = assertIs<PairingKeyPackageBuildResult.Built>(result).envelope
        assertEquals(request.requestId, envelope.requestId)
        assertEquals(request.targetDeviceId, envelope.targetDeviceId)
        assertEquals(8L, envelope.keyEpoch)
        assertEquals(request.hpkePublicKeyBase64Url, hpke.recipient)

        val plaintext = assertIs<PairingWireDecodeResult.Supported<KeyPackagePlaintextV1>>(
            PairingWireCodec.decodePlaintext(hpke.plaintext!!.decodeToString()),
        ).value
        assertEquals(encodeCanonicalBase64Url(raw(0)), plaintext.accountMasterKeyBase64Url)
        assertEquals(encodeCanonicalBase64Url(raw(32)), plaintext.syncSpace.activeKeyBase64Url)
        assertEquals(listOf(6L, 7L), plaintext.syncSpace.historicalKeys.map { it.keyEpoch })
        assertEquals(
            listOf(encodeCanonicalBase64Url(raw(64)), encodeCanonicalBase64Url(raw(96))),
            plaintext.syncSpace.historicalKeys.map { it.keyBase64Url },
        )
    }

    @Test
    fun `identity mismatch fails closed before package encryption`() = runBlocking {
        val hpke = RecordingHpke()
        val builder = PairingKeyPackageBuilder(
            keyRing = FixedKeyRing(active, emptyList()),
            keyMaterial = Exporter(
                account = raw(0),
                keys = mapOf(active.contentKeyReference to exported(raw(32), ContentKeyIdentity("other-identity"))),
            ),
            hpke = hpke,
        )

        assertEquals(
            PairingKeyPackageBuildResult.ContentKeyIdentityMismatch(8),
            builder.build(PairingKeyPackageBuildRequest(account, SecretReference("secure://amk"), space, request)),
        )
        assertNull(hpke.plaintext)
    }

    private fun metadata(
        epoch: Long,
        reference: String,
        identity: String,
        usage: SyncSpaceContentKeyUsage,
    ) = SyncSpaceContentKeyMetadata(
        space,
        epoch,
        SecretReference("secure://$reference"),
        ContentKeyIdentity(identity),
        usage,
    )

    private fun raw(start: Int): ByteArray = ByteArray(32) { (start + it).toByte() }
    private fun exported(raw: ByteArray, identity: ContentKeyIdentity) = ExportedPairingContentKey(RawMaterial(raw), identity)

    private class RawMaterial(private val raw: ByteArray) : PairingEphemeralKeyMaterial {
        override fun copyRawKeyBytesForPairing(): ByteArray = raw.copyOf()
    }

    private class Exporter(
        private val account: ByteArray,
        private val keys: Map<SecretReference, ExportedPairingContentKey>,
    ) : PlatformPairingKeyMaterialExporter {
        override suspend fun exportAccountMasterKeyForPairing(reference: SecretReference): PairingEphemeralKeyMaterial? =
            RawMaterial(account)

        override suspend fun exportContentKeyForPairing(reference: SecretReference): ExportedPairingContentKey? = keys[reference]
    }

    private class FixedKeyRing(
        private val active: SyncSpaceContentKeyMetadata,
        private val historical: List<SyncSpaceContentKeyMetadata>,
    ) : SyncKeyRingRepository {
        override suspend fun state(syncSpaceId: SyncSpaceId): SyncSpaceKeyState? = SyncSpaceKeyState(active.syncSpaceId, active.keyEpoch)
        override suspend fun currentEncryptionKey(syncSpaceId: SyncSpaceId): SyncSpaceContentKeyMetadata? = active.takeIf { it.syncSpaceId == syncSpaceId }
        override suspend fun decryptionKey(syncSpaceId: SyncSpaceId, keyEpoch: Long): SyncSpaceContentKeyMetadata? = error("Not used")
        override suspend fun historicalDecryptKeys(syncSpaceId: SyncSpaceId): List<SyncSpaceContentKeyMetadata> = historical.takeIf { active.syncSpaceId == syncSpaceId }.orEmpty()
        override suspend fun installNewEpoch(syncSpaceId: SyncSpaceId, keyEpoch: Long, contentKeyReference: SecretReference, contentKeyIdentity: ContentKeyIdentity): InstallSyncSpaceKeyEpochResult = error("Not used")
        override suspend fun installKeyPackage(syncSpaceId: SyncSpaceId, activeKey: SyncKeyPackageKeyReference, historicalReferences: List<SyncKeyPackageKeyReference>): InstallSyncKeyPackageResult = error("Not used")
    }

    private class RecordingHpke : PairingHpke {
        var recipient: HpkePublicKeyBase64Url? = null
        var plaintext: ByteArray? = null
        override fun generateDeviceKeyPair(): PairingDeviceKeyPair = error("Not used")
        override fun encrypt(publicKey: HpkePublicKeyBase64Url, plaintext: ByteArray, contextInfo: ByteArray): HpkeCiphertextComponents {
            recipient = publicKey
            this.plaintext = plaintext.copyOf()
            return HpkeCiphertextComponents(
                encapsulatedKeyBase64Url = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
                ciphertextBase64Url = "AQI",
            )
        }
        override fun decrypt(privateKey: PairingPrivateKeyMaterial, encapsulatedKeyBase64Url: String, ciphertextBase64Url: String, contextInfo: ByteArray): ByteArray = error("Not used")
    }
}
