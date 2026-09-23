package dev.agenticscheduler.application.sync

import dev.agenticscheduler.application.persistence.ApplicationTransactionRunner
import dev.agenticscheduler.sync.AccountId
import dev.agenticscheduler.sync.DeviceId
import dev.agenticscheduler.sync.EnrollmentRequestId
import dev.agenticscheduler.sync.HpkePublicKeyBase64Url
import dev.agenticscheduler.sync.KeyPackageEnvelopeV1
import dev.agenticscheduler.sync.KeyPackagePlaintextV1
import dev.agenticscheduler.sync.PairingWireCodec
import dev.agenticscheduler.sync.SyncSpaceId
import dev.agenticscheduler.sync.SyncSpaceKeyPackageV1
import dev.agenticscheduler.sync.encodeCanonicalBase64Url
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PairingRecipientAdmissionServiceTest {
    private val account = AccountId("acct-1")
    private val device = DeviceId("device-1")
    private val request = EnrollmentRequestId("req-1")
    private val space = SyncSpaceId("personal-space")
    private val publicKey = HpkePublicKeyBase64Url("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8")
    private val pending = LocalEnrollmentState.Pending(
        account, device, request, publicKey,
        SecretReference("secure://pairing-private/1"),
        SecretReference("secure://credential/1"),
    )
    private val raw = ByteArray(32) { it.toByte() }
    private val plaintext = KeyPackagePlaintextV1(
        accountId = account,
        requestId = request,
        targetDeviceId = device,
        keyEpoch = 8,
        accountMasterKeyBase64Url = encodeCanonicalBase64Url(raw),
        syncSpace = SyncSpaceKeyPackageV1(space, 8, encodeCanonicalBase64Url(raw), emptyList()),
    )

    @Test
    fun `authenticated package installs keys and transitions pending enrollment to active`() = runBlocking {
        val enrollments = MemoryEnrollments(pending)
        val service = service(enrollments, AccountStore(SecretReference("secure://amk/1")))

        val result = service.admit(envelope())

        val active = assertIs<PairingRecipientAdmissionResult.Activated>(result).state
        assertEquals(active, enrollments.value)
        assertEquals(space, active.syncSpaceId)
        assertEquals(SecretReference("secure://amk/1"), active.accountMasterKeyReference)
    }

    @Test
    fun `account key import failure leaves enrollment pending`() = runBlocking {
        val enrollments = MemoryEnrollments(pending)
        val service = service(enrollments, AccountStore(null))

        assertEquals(PairingRecipientAdmissionResult.AccountMasterKeyImportFailed, service.admit(envelope()))
        assertEquals(pending, enrollments.value)
    }

    @Test
    fun `legacy pending enrollment without a staged credential fails closed`() = runBlocking {
        val legacy = pending.copy(deviceCredentialReference = null)
        val enrollments = MemoryEnrollments(legacy)
        val accounts = AccountStore(SecretReference("secure://amk/1"))

        assertEquals(
            PairingRecipientAdmissionResult.MissingDeviceCredential,
            service(enrollments, accounts).admit(envelope()),
        )
        assertEquals(0, accounts.imports)
        assertEquals(legacy, enrollments.value)
    }

    @Test
    fun `identity mismatch is rejected before importing account material`() = runBlocking {
        val enrollments = MemoryEnrollments(pending)
        val accounts = AccountStore(SecretReference("secure://amk/1"))
        val service = service(enrollments, accounts)

        assertEquals(PairingRecipientAdmissionResult.IdentityMismatch, service.admit(envelope().copy(keyEpoch = 9)))
        assertEquals(0, accounts.imports)
        assertEquals(pending, enrollments.value)
    }

    @Test
    fun `replayed package cannot activate twice`() = runBlocking {
        val enrollments = MemoryEnrollments(pending)
        val accounts = AccountStore(SecretReference("secure://amk/1"))
        val service = service(enrollments, accounts)

        assertIs<PairingRecipientAdmissionResult.Activated>(service.admit(envelope()))
        assertEquals(PairingRecipientAdmissionResult.NotPending, service.admit(envelope()))
        assertEquals(1, accounts.imports)
    }

    private fun service(
        enrollments: MemoryEnrollments,
        accounts: AccountStore,
    ): PairingRecipientAdmissionService = PairingRecipientAdmissionService(
        enrollments = enrollments,
        privateKeys = PrivateKeys,
        hpke = PlaintextHpke(plaintext),
        accountMasterKeys = accounts,
        keyPackageInstaller = SyncKeyPackageInstaller(ContentKeys, SuccessfulRing),
        transactions = DirectTransactions,
    )

    private fun envelope() = KeyPackageEnvelopeV1(
        accountId = account,
        requestId = request,
        targetDeviceId = device,
        keyEpoch = 8,
        encapsulatedKeyBase64Url = publicKey.value,
        ciphertextBase64Url = "AQI",
    )

    private class MemoryEnrollments(initial: LocalEnrollmentState) : LocalEnrollmentRepository {
        var value: LocalEnrollmentState = initial
        override suspend fun state(accountId: AccountId): LocalEnrollmentState? = value.takeIf { it.accountId == accountId }
        override suspend fun savePending(value: LocalEnrollmentState.Pending) { this.value = value }
        override suspend fun saveActive(value: LocalEnrollmentState.Active) { this.value = value }
    }

    private class AccountStore(private val result: SecretReference?) : PlatformAccountMasterKeyStore {
        var imports = 0
        override suspend fun importAccountMasterKey(material: PairingEphemeralKeyMaterial): SecretReference? = result.also { imports++ }
        override suspend fun delete(reference: SecretReference) = Unit
    }

    private object PrivateKeys : PlatformPairingPrivateKeyStore {
        private val value = object : PairingPrivateKeyMaterial {}
        override suspend fun generatePairingDeviceKey(): PersistedPairingDeviceKey = error("Not used")
        override suspend fun privateKey(reference: SecretReference): PairingPrivateKeyMaterial? = value
        override suspend fun delete(reference: SecretReference) = Unit
    }

    private class PlaintextHpke(private val plaintext: KeyPackagePlaintextV1) : PairingHpke {
        override fun generateDeviceKeyPair(): PairingDeviceKeyPair = error("Not used")
        override fun encrypt(publicKey: HpkePublicKeyBase64Url, plaintext: ByteArray, contextInfo: ByteArray): HpkeCiphertextComponents = error("Not used")
        override fun decrypt(privateKey: PairingPrivateKeyMaterial, encapsulatedKeyBase64Url: String, ciphertextBase64Url: String, contextInfo: ByteArray): ByteArray =
            PairingWireCodec.encodePlaintext(plaintext).encodeToByteArray()
    }

    private object ContentKeys : PlatformKeyMaterialStore {
        override suspend fun importContentKey(material: ImportedContentKeyMaterial): ImportedContentKey =
            ImportedContentKey(SecretReference("secure://content/8"), ContentKeyIdentity("identity-8"))
        override suspend fun contentAead(reference: SecretReference): SyncPayloadAead? = null
        override suspend fun delete(reference: SecretReference) = Unit
    }

    private object SuccessfulRing : SyncKeyRingRepository {
        override suspend fun state(syncSpaceId: SyncSpaceId): SyncSpaceKeyState? = null
        override suspend fun currentEncryptionKey(syncSpaceId: SyncSpaceId): SyncSpaceContentKeyMetadata? = null
        override suspend fun decryptionKey(syncSpaceId: SyncSpaceId, keyEpoch: Long): SyncSpaceContentKeyMetadata? = null
        override suspend fun historicalDecryptKeys(syncSpaceId: SyncSpaceId): List<SyncSpaceContentKeyMetadata> = emptyList()
        override suspend fun installNewEpoch(syncSpaceId: SyncSpaceId, keyEpoch: Long, contentKeyReference: SecretReference, contentKeyIdentity: ContentKeyIdentity): InstallSyncSpaceKeyEpochResult = error("Not used")
        override suspend fun installKeyPackage(syncSpaceId: SyncSpaceId, activeKey: SyncKeyPackageKeyReference, historicalReferences: List<SyncKeyPackageKeyReference>): InstallSyncKeyPackageResult =
            InstallSyncKeyPackageResult.Installed(SyncKeyPackageAdoption(setOf(activeKey.keyEpoch), emptySet()))
    }

    private object DirectTransactions : ApplicationTransactionRunner {
        override suspend fun <T> inWriteTransaction(block: suspend () -> T): T = block()
    }
}
