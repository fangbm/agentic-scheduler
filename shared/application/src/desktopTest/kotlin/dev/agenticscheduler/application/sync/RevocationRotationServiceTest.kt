package dev.agenticscheduler.application.sync

import dev.agenticscheduler.application.persistence.ApplicationTransactionRunner
import dev.agenticscheduler.sync.AccountId
import dev.agenticscheduler.sync.DeviceId
import dev.agenticscheduler.sync.EnrollmentRequestId
import dev.agenticscheduler.sync.HpkePublicKeyBase64Url
import dev.agenticscheduler.sync.SyncSpaceId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RevocationRotationServiceTest {
    private val account = AccountId("acct-1")
    private val space = SyncSpaceId("space-1")
    private val self = DeviceId("device-a")
    private val revoked = DeviceId("device-b")
    private val public = HpkePublicKeyBase64Url("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8")

    @Test fun `initiator publishes only after server accepts complete staged package set`() = runBlocking {
        val fixture = Fixture()
        val prepared = assertIs<RevocationRotationResult.Prepared>(fixture.service.prepare(account, revoked, "rotation-1", fixture.secret)).value
        assertEquals(0, fixture.transport.submissions)
        val committed = assertIs<RevocationRotationResult.Committed>(fixture.service.submit(prepared))
        assertEquals(1, fixture.transport.submissions)
        assertEquals(8L, fixture.ring.installed?.keyEpoch)
        assertEquals(setOf(7L), fixture.ring.historical.map { it.keyEpoch }.toSet())
        assertEquals(SecretReference("secure://amk/new"), committed.state.accountMasterKeyReference)
        assertEquals(SecretReference("secure://amk/new"), (fixture.enrollments.value as LocalEnrollmentState.Active).accountMasterKeyReference)
    }

    @Test fun `invalid directory target never generates rotation secrets`() = runBlocking {
        val fixture = Fixture(devices = listOf(ClientActiveDeviceDirectoryEntry(self.value, public.value)))
        assertEquals(RevocationRotationResult.DirectoryInvalid, fixture.service.prepare(account, revoked, "rotation-1", fixture.secret))
        assertEquals(0, fixture.amk.generated)
        assertEquals(0, fixture.content.generated)
        assertEquals(0, fixture.transport.submissions)
    }

    private inner class Fixture(devices: List<ClientActiveDeviceDirectoryEntry> = listOf(ClientActiveDeviceDirectoryEntry(self.value, public.value), ClientActiveDeviceDirectoryEntry(revoked.value, public.value))) {
        val secret = RecoverySecret("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8")
        val enrollments = Enrollments(active())
        val ring = Ring()
        val material = Material()
        val amk = Amk()
        val content = Content()
        val transport = Transport(devices)
        val service = RevocationRotationService(transport, enrollments, ring, material, amk, content, material, RotationKeyPackageBuilder(ring, material, Hpke), material, DirectTransactions)
    }

    private fun active() = LocalEnrollmentState.Active(account, self, EnrollmentRequestId("req-1"), public, SecretReference("secure://hpke/a"), space, SecretReference("secure://amk/old"), SecretReference("secure://credential/a"))
    private inner class Enrollments(var value: LocalEnrollmentState) : LocalEnrollmentRepository {
        override suspend fun state(accountId: AccountId) = value.takeIf { it.accountId == account }
        override suspend fun savePending(value: LocalEnrollmentState.Pending) = error("unused")
        override suspend fun saveActive(value: LocalEnrollmentState.Active) { this.value = value }
    }
    private inner class Ring : SyncKeyRingRepository {
        val old = SyncSpaceContentKeyMetadata(space, 7, SecretReference("secure://key/old"), ContentKeyIdentity.fromRawAes256Key(ByteArray(32) { 1 }), SyncSpaceContentKeyUsage.ACTIVE)
        var installed: SyncKeyPackageKeyReference? = null; var historical = emptyList<SyncKeyPackageKeyReference>()
        override suspend fun state(syncSpaceId: SyncSpaceId) = SyncSpaceKeyState(space, 7)
        override suspend fun currentEncryptionKey(syncSpaceId: SyncSpaceId) = old
        override suspend fun decryptionKey(syncSpaceId: SyncSpaceId, keyEpoch: Long) = null
        override suspend fun historicalDecryptKeys(syncSpaceId: SyncSpaceId) = emptyList<SyncSpaceContentKeyMetadata>()
        override suspend fun installNewEpoch(syncSpaceId: SyncSpaceId, keyEpoch: Long, contentKeyReference: SecretReference, contentKeyIdentity: ContentKeyIdentity) = error("unused")
        override suspend fun installKeyPackage(syncSpaceId: SyncSpaceId, activeKey: SyncKeyPackageKeyReference, historicalReferences: List<SyncKeyPackageKeyReference>): InstallSyncKeyPackageResult { installed = activeKey; historical = historicalReferences; return InstallSyncKeyPackageResult.Advanced(SyncKeyPackageAdoption(setOf(activeKey.keyEpoch), historicalReferences.map { it.keyEpoch }.toSet())) }
    }
    private class Amk : PlatformAccountMasterKeyGenerator { var generated = 0; override suspend fun generateAccountMasterKey() = SecretReference("secure://amk/new").also { generated++ } }
    private class Content : PlatformContentKeyGenerator { var generated = 0; override suspend fun generateContentKey() = ImportedContentKey(SecretReference("secure://key/new").also { generated++ }, ContentKeyIdentity.fromRawAes256Key(ByteArray(32) { 2 })) }
    private class Material : PlatformAccountMasterKeyStore, PlatformPairingKeyMaterialExporter, PlatformKeyMaterialStore {
        private val rawOld = ByteArray(32) { 1 }; private val rawNew = ByteArray(32) { 2 }; private val rawAmkOld = ByteArray(32) { 3 }; private val rawAmkNew = ByteArray(32) { 4 }
        override suspend fun importAccountMasterKey(material: PairingEphemeralKeyMaterial) = null
        override suspend fun delete(reference: SecretReference) = Unit
        override suspend fun exportAccountMasterKeyForPairing(reference: SecretReference) = Raw(if (reference.value.endsWith("new")) rawAmkNew else rawAmkOld)
        override suspend fun exportContentKeyForPairing(reference: SecretReference): ExportedPairingContentKey { val raw = if (reference.value.endsWith("new")) rawNew else rawOld; return ExportedPairingContentKey(Raw(raw), ContentKeyIdentity.fromRawAes256Key(raw)) }
        override suspend fun importContentKey(material: ImportedContentKeyMaterial) = error("unused")
        override suspend fun contentAead(reference: SecretReference) = null
    }
    private class Raw(private val value: ByteArray) : PairingEphemeralKeyMaterial { override fun copyRawKeyBytesForPairing() = value.copyOf() }
    private class Transport(private val devices: List<ClientActiveDeviceDirectoryEntry>) : RevocationRotationTransport { var submissions = 0; override suspend fun activeDevices() = devices; override suspend fun revokeDeviceAndRotate(deviceId: String, request: ClientAtomicRevocationRequest) { submissions++ } }
    private object Hpke : PairingHpke { override fun generateDeviceKeyPair() = error("unused"); override fun encrypt(publicKey: HpkePublicKeyBase64Url, plaintext: ByteArray, contextInfo: ByteArray) = HpkeCiphertextComponents(publicKey.value, "AQI"); override fun decrypt(privateKey: PairingPrivateKeyMaterial, encapsulatedKeyBase64Url: String, ciphertextBase64Url: String, contextInfo: ByteArray) = error("unused") }
    private object DirectTransactions : ApplicationTransactionRunner { override suspend fun <T> inWriteTransaction(block: suspend () -> T) = block() }
}
