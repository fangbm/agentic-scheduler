package dev.agenticscheduler.application.sync

import dev.agenticscheduler.application.persistence.ApplicationTransactionRunner
import dev.agenticscheduler.sync.AccountId
import dev.agenticscheduler.sync.DeviceId
import dev.agenticscheduler.sync.EnrollmentRequestId
import dev.agenticscheduler.sync.HpkePublicKeyBase64Url
import dev.agenticscheduler.sync.RotationKeyPackageEnvelopeV1
import dev.agenticscheduler.sync.RotationKeyPackagePlaintextV1
import dev.agenticscheduler.sync.RotationWireCodec
import dev.agenticscheduler.sync.SyncSpaceId
import dev.agenticscheduler.sync.SyncSpaceKeyPackageV1
import dev.agenticscheduler.sync.encodeCanonicalBase64Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class RotationKeyPackageTest {
    private val account = AccountId("acct-1")
    private val device = DeviceId("device-1")
    private val space = SyncSpaceId("personal-space")
    private val hpkePublic = HpkePublicKeyBase64Url("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8")
    private val active = LocalEnrollmentState.Active(
        accountId = account,
        deviceId = device,
        enrollmentRequestId = EnrollmentRequestId("req-1"),
        hpkePublicKey = hpkePublic,
        hpkePrivateKeyReference = SecretReference("secure://hpke/private"),
        syncSpaceId = space,
        accountMasterKeyReference = SecretReference("secure://amk/old"),
        deviceCredentialReference = SecretReference("secure://credential/1"),
    )

    @Test
    fun `SYN-007B context fixture is stable and domain separated from pairing`() {
        assertEquals(
            "YWdlbnRpYy1zY2hlZHVsZXItcm90YXRpb24ta2V5LXBhY2thZ2UAAAAABmFjY3QtMQAAAApyb3RhdGlvbi0xAAAACGRldmljZS0xAAAAAQAAAAAAAAAI",
            encodeCanonicalBase64Url(
                RotationKeyPackageContextV1.bytes(account, "rotation-1", device, 1, 8),
            ),
        )
    }

    @Test
    fun `ACTIVE recipient atomically advances AMK and ring without changing device identity`() = runBlocking {
        val enrollments = MemoryEnrollments(active)
        val accounts = AccountStore(SecretReference("secure://amk/new"))
        val ring = MemoryRing(
            state = SyncSpaceKeyState(space, 7),
            installResult = InstallSyncKeyPackageResult.Advanced(
                SyncKeyPackageAdoption(setOf(8), setOf(7)),
            ),
        )
        val service = service(enrollments, accounts, ring)

        val applied = assertIs<RotationKeyPackageApplyResult.Applied>(service.apply(envelope(8)))
        assertEquals(8L, ring.installedEpoch)
        assertEquals(SecretReference("secure://amk/new"), applied.state.accountMasterKeyReference)
        assertEquals(active.deviceId, applied.state.deviceId)
        assertEquals(active.enrollmentRequestId, applied.state.enrollmentRequestId)
        assertEquals(active.hpkePublicKey, applied.state.hpkePublicKey)
        assertEquals(active.hpkePrivateKeyReference, applied.state.hpkePrivateKeyReference)
        assertEquals(active.deviceCredentialReference, applied.state.deviceCredentialReference)
        assertEquals(listOf(SecretReference("secure://amk/old")), accounts.deleted)
    }

    @Test
    fun `same epoch replay cannot replace ACTIVE AMK`() = runBlocking {
        val enrollments = MemoryEnrollments(active)
        val accounts = AccountStore(SecretReference("secure://amk/replay"))
        val ring = MemoryRing(
            state = SyncSpaceKeyState(space, 8),
            installResult = InstallSyncKeyPackageResult.Idempotent(
                SyncKeyPackageAdoption(emptySet(), setOf(8)),
            ),
        )
        val service = service(enrollments, accounts, ring)

        assertIs<RotationKeyPackageApplyResult.AlreadyApplied>(service.apply(envelope(8)))
        assertEquals(active, enrollments.value)
        assertEquals(listOf(SecretReference("secure://amk/replay")), accounts.deleted)
    }

    @Test
    fun `remaining ACTIVE device fetches relay package and advances its rotation ring`() = runBlocking {
        val enrollments = MemoryEnrollments(active)
        val accounts = AccountStore(SecretReference("secure://amk/new"))
        val ring = MemoryRing(
            state = SyncSpaceKeyState(space, 7),
            installResult = InstallSyncKeyPackageResult.Advanced(
                SyncKeyPackageAdoption(setOf(8), setOf(7)),
            ),
        )
        val envelope = envelope(8)
        val result = RotationPackageCatchUpService(
            transport = object : RotationPackageTransport {
                override suspend fun rotationPackages() = listOf(
                    ClientRotationPackageResponse(
                        rotationId = envelope.rotationId,
                        targetDeviceId = envelope.targetDeviceId.value,
                        packageBase64Url = encodeCanonicalBase64Url(
                            RotationWireCodec.encodeEnvelope(envelope).encodeToByteArray(),
                        ),
                    ),
                )
            },
            recipient = service(enrollments, accounts, ring),
        ).catchUp()

        val completed = assertIs<RotationPackageCatchUpResult.Completed>(result)
        assertIs<RotationKeyPackageApplyResult.Applied>(completed.outcomes.single().result)
        assertEquals(8L, ring.installedEpoch)
        assertEquals(SecretReference("secure://amk/new"), (enrollments.value as LocalEnrollmentState.Active).accountMasterKeyReference)
    }

    @Test
    fun `rotation package fetch propagates coroutine cancellation`() = runBlocking {
        val cancellation = CancellationException("sync cancelled")
        val result = RotationPackageCatchUpService(
            transport = object : RotationPackageTransport {
                override suspend fun rotationPackages(): List<ClientRotationPackageResponse> {
                    throw cancellation
                }
            },
            recipient = service(
                MemoryEnrollments(active),
                AccountStore(null),
                MemoryRing(
                    SyncSpaceKeyState(space, 7),
                    InstallSyncKeyPackageResult.Advanced(SyncKeyPackageAdoption(setOf(8), setOf(7))),
                ),
            ),
        )

        assertEquals(cancellation, assertFailsWith<CancellationException> { result.catchUp() })
    }

    private fun service(
        enrollments: MemoryEnrollments,
        accounts: AccountStore,
        ring: MemoryRing,
    ) = RotationKeyPackageRecipientService(
        enrollments = enrollments,
        privateKeys = PrivateKeys,
        hpke = PlaintextHpke(plaintext(8)),
        accountMasterKeys = accounts,
        keyRing = ring,
        keyPackageInstaller = SyncKeyPackageInstaller(ContentKeys, ring),
        transactions = DirectTransactions,
    )

    private fun plaintext(epoch: Long) = RotationKeyPackagePlaintextV1(
        accountId = account,
        rotationId = "rotation-1",
        targetDeviceId = device,
        keyEpoch = epoch,
        accountMasterKeyBase64Url = encodeCanonicalBase64Url(ByteArray(32) { (it + 1).toByte() }),
        syncSpace = SyncSpaceKeyPackageV1(
            syncSpaceId = space,
            activeEpoch = epoch,
            activeKeyBase64Url = encodeCanonicalBase64Url(ByteArray(32) { (it + 33).toByte() }),
            historicalKeys = emptyList(),
        ),
    )

    private fun envelope(epoch: Long) = RotationKeyPackageEnvelopeV1(
        accountId = account,
        rotationId = "rotation-1",
        targetDeviceId = device,
        keyEpoch = epoch,
        encapsulatedKeyBase64Url = hpkePublic.value,
        ciphertextBase64Url = "AQI",
    )

    private class MemoryEnrollments(initial: LocalEnrollmentState.Active) : LocalEnrollmentRepository {
        var value: LocalEnrollmentState = initial
        override suspend fun state(accountId: AccountId): LocalEnrollmentState? =
            value.takeIf { it.accountId == accountId }
        override suspend fun states(): List<LocalEnrollmentState> = listOf(value)
        override suspend fun savePending(value: LocalEnrollmentState.Pending) { error("Not used") }
        override suspend fun saveActive(value: LocalEnrollmentState.Active) { this.value = value }
    }

    private class AccountStore(private val imported: SecretReference?) : PlatformAccountMasterKeyStore {
        val deleted = mutableListOf<SecretReference>()
        override suspend fun importAccountMasterKey(material: PairingEphemeralKeyMaterial): SecretReference? = imported
        override suspend fun delete(reference: SecretReference) { deleted += reference }
    }

    private object PrivateKeys : PlatformPairingPrivateKeyStore {
        private val privateKey = object : PairingPrivateKeyMaterial {}
        override suspend fun generatePairingDeviceKey(): PersistedPairingDeviceKey = error("Not used")
        override suspend fun privateKey(reference: SecretReference): PairingPrivateKeyMaterial? = privateKey
        override suspend fun delete(reference: SecretReference) = Unit
    }

    private class PlaintextHpke(private val plaintext: RotationKeyPackagePlaintextV1) : PairingHpke {
        override fun generateDeviceKeyPair(): PairingDeviceKeyPair = error("Not used")
        override fun encrypt(
            publicKey: HpkePublicKeyBase64Url,
            plaintext: ByteArray,
            contextInfo: ByteArray,
        ): HpkeCiphertextComponents = error("Not used")
        override fun decrypt(
            privateKey: PairingPrivateKeyMaterial,
            encapsulatedKeyBase64Url: String,
            ciphertextBase64Url: String,
            contextInfo: ByteArray,
        ): ByteArray = RotationWireCodec.encodePlaintext(plaintext).encodeToByteArray()
    }

    private object ContentKeys : PlatformKeyMaterialStore {
        private var next = 0
        override suspend fun importContentKey(material: ImportedContentKeyMaterial): ImportedContentKey {
            val raw = material.copyRawSecretBytesForSecureStore()
            val id = ContentKeyIdentity.fromRawAes256Key(raw)
            return ImportedContentKey(SecretReference("secure://rotation-content/" + next++), id)
        }
        override suspend fun contentAead(reference: SecretReference): SyncPayloadAead? = null
        override suspend fun delete(reference: SecretReference) = Unit
    }

    private class MemoryRing(
        private val state: SyncSpaceKeyState,
        private val installResult: InstallSyncKeyPackageResult,
    ) : SyncKeyRingRepository {
        var installedEpoch: Long? = null
        override suspend fun state(syncSpaceId: SyncSpaceId): SyncSpaceKeyState? = state.takeIf { it.syncSpaceId == syncSpaceId }
        override suspend fun currentEncryptionKey(syncSpaceId: SyncSpaceId): SyncSpaceContentKeyMetadata? = null
        override suspend fun decryptionKey(syncSpaceId: SyncSpaceId, keyEpoch: Long): SyncSpaceContentKeyMetadata? = null
        override suspend fun historicalDecryptKeys(syncSpaceId: SyncSpaceId): List<SyncSpaceContentKeyMetadata> = emptyList()
        override suspend fun installNewEpoch(
            syncSpaceId: SyncSpaceId,
            keyEpoch: Long,
            contentKeyReference: SecretReference,
            contentKeyIdentity: ContentKeyIdentity,
        ): InstallSyncSpaceKeyEpochResult = error("Not used")
        override suspend fun installKeyPackage(
            syncSpaceId: SyncSpaceId,
            activeKey: SyncKeyPackageKeyReference,
            historicalReferences: List<SyncKeyPackageKeyReference>,
        ): InstallSyncKeyPackageResult {
            installedEpoch = activeKey.keyEpoch
            return installResult
        }
    }

    private object DirectTransactions : ApplicationTransactionRunner {
        override suspend fun <T> inWriteTransaction(block: suspend () -> T): T = block()
    }
}
