package dev.agenticscheduler.application.sync

import dev.agenticscheduler.application.persistence.ApplicationTransactionRunner
import dev.agenticscheduler.sync.AccountId
import dev.agenticscheduler.sync.DeviceId
import dev.agenticscheduler.sync.EnrollmentRequestId
import dev.agenticscheduler.sync.HpkePublicKeyBase64Url
import dev.agenticscheduler.sync.RecoveryEnvelopePlaintextV1
import dev.agenticscheduler.sync.RecoveryHistoricalKeyV1
import dev.agenticscheduler.sync.RecoverySyncSpaceKeyRingV1
import dev.agenticscheduler.sync.RecoveryWireCodec
import dev.agenticscheduler.sync.SyncSpaceId
import dev.agenticscheduler.sync.encodeCanonicalBase64Url
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class RecoveryEnrollmentServiceTest {
    private val account = AccountId("account-recovery")
    private val device = DeviceId("recovered-device")
    private val request = EnrollmentRequestId("recovery-request")
    private val space = SyncSpaceId("personal-space")
    private val secret = RecoverySecret(encodeCanonicalBase64Url(ByteArray(32) { (it + 17).toByte() }))
    private val activeKey = ByteArray(32) { (it + 1).toByte() }
    private val historicalKey = ByteArray(32) { (it + 65).toByte() }
    private val accountMasterKey = ByteArray(32) { (it + 129).toByte() }

    @Test
    fun `fresh device restores envelope enrolls and atomically becomes active`() = runBlocking {
        val fixture = Fixture()

        val result = fixture.service.recover(account, secret, device, request)

        val active = assertIs<RecoveryEnrollmentServiceResult.Activated>(result).state
        assertEquals(active, fixture.enrollments.value)
        assertEquals(space, active.syncSpaceId)
        assertEquals(
            "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
            fixture.transport.request?.hpkePublicKeyBase64Url,
        )
        assertEquals(5L, fixture.transport.request?.counter)
        assertEquals(
            RecoveryRegistrationProof.calculate(secret, account.value, 5),
            fixture.transport.request?.proofBase64Url,
        )
        assertEquals(
            RecoveryRegistrationProof.hash(RecoveryRegistrationProof.calculate(secret, account.value, 6)),
            fixture.transport.request?.nextProofHashBase64Url,
        )
        assertEquals(setOf(4L, 5L), fixture.ring.installedEpochs)
    }

    @Test
    fun `wrong recovery secret never stages a local enrollment or contacts enrollment server`() = runBlocking {
        val fixture = Fixture()
        val wrong = RecoverySecret(encodeCanonicalBase64Url(ByteArray(32) { (it + 33).toByte() }))

        assertEquals(
            RecoveryEnrollmentServiceResult.EnvelopeAuthenticationFailed,
            fixture.service.recover(account, wrong, device, request),
        )
        assertNull(fixture.enrollments.value)
        assertEquals(0, fixture.credentials.generated)
        assertNull(fixture.transport.request)
    }

    @Test
    fun `server-created recovery can retry same pending identity after local key import failure`() = runBlocking {
        val fixture = Fixture(accountImport = null)

        assertEquals(
            RecoveryEnrollmentServiceResult.AccountMasterKeyImportFailed,
            fixture.service.recover(account, secret, device, request),
        )
        val pending = assertIs<LocalEnrollmentState.Pending>(fixture.enrollments.value)
        val firstCredentialHash = fixture.transport.request?.credentialHashBase64Url
        fixture.accounts.result = SecretReference("secure://amk/retry")

        assertIs<RecoveryEnrollmentServiceResult.Activated>(fixture.service.recover(account, secret, device, request))
        assertEquals(pending.deviceCredentialReference, (fixture.enrollments.value as LocalEnrollmentState.Active).deviceCredentialReference)
        assertEquals(firstCredentialHash, fixture.transport.request?.credentialHashBase64Url)
        assertEquals(1, fixture.credentials.generated)
        assertEquals(2, fixture.transport.enrollments)
    }

    private inner class Fixture(accountImport: SecretReference? = SecretReference("secure://amk/1")) {
        val enrollments = Enrollments()
        val credentials = Credentials()
        val accounts = Accounts(accountImport)
        val ring = Ring()
        val transport = Transport(envelopeBase64Url())
        val service = RecoveryEnrollmentService(
            transport = transport,
            pendingEnrollment = LocalEnrollmentRequestService(enrollments, PrivateKeys, credentials),
            enrollments = enrollments,
            credentials = credentials,
            accountMasterKeys = accounts,
            keyPackageInstaller = SyncKeyPackageInstaller(ContentKeys, ring),
            transactions = DirectTransactions,
        )
    }

    private fun envelopeBase64Url(): String {
        val plaintext = RecoveryEnvelopePlaintextV1(
            accountId = account,
            keyEpoch = 5,
            accountMasterKeyBase64Url = encodeCanonicalBase64Url(accountMasterKey),
            syncSpace = RecoverySyncSpaceKeyRingV1(
                syncSpaceId = space,
                activeEpoch = 5,
                activeKeyBase64Url = encodeCanonicalBase64Url(activeKey),
                historicalKeys = listOf(RecoveryHistoricalKeyV1(4, encodeCanonicalBase64Url(historicalKey))),
            ),
        )
        return encodeCanonicalBase64Url(RecoveryWireCodec.encodeEnvelope(RecoveryEnvelopeCodec.seal(secret, plaintext)).encodeToByteArray())
    }

    private class Enrollments : LocalEnrollmentRepository {
        var value: LocalEnrollmentState? = null
        override suspend fun state(accountId: AccountId): LocalEnrollmentState? = value?.takeIf { it.accountId == accountId }
        override suspend fun states(): List<LocalEnrollmentState> = listOfNotNull(value)
        override suspend fun savePending(value: LocalEnrollmentState.Pending) { this.value = value }
        override suspend fun saveActive(value: LocalEnrollmentState.Active) { this.value = value }
    }

    private class Credentials : PlatformDeviceCredentialStore {
        var generated = 0
        private val credential = DeviceCredential("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8")
        private val reference = SecretReference("secure://credential/recovery")
        override suspend fun generate(): GeneratedDeviceCredential {
            generated++
            return GeneratedDeviceCredential(reference, DeviceCredentialHashing.sha256Base64Url(credential))
        }
        override suspend fun store(value: DeviceCredential): SecretReference = reference
        override suspend fun load(reference: SecretReference): DeviceCredential? = credential.takeIf { reference == this.reference }
        override suspend fun delete(reference: SecretReference) = Unit
    }

    private class Accounts(var result: SecretReference?) : PlatformAccountMasterKeyStore {
        override suspend fun importAccountMasterKey(material: PairingEphemeralKeyMaterial): SecretReference? = result
        override suspend fun delete(reference: SecretReference) = Unit
    }

    private class Transport(private val envelope: String) : RecoveryEnrollmentTransport {
        var request: ClientRecoveryEnrollmentRequest? = null
        var enrollments = 0
        override suspend fun recoveryBootstrap(accountId: String): ClientRecoveryBootstrapResponse =
            ClientRecoveryBootstrapResponse(counter = 5, recoveryEnvelopeBase64Url = envelope)
        override suspend fun enrollWithRecovery(request: ClientRecoveryEnrollmentRequest): ClientRecoveryEnrollmentCreated {
            this.request = request
            enrollments++
            return ClientRecoveryEnrollmentCreated(request.accountId, request.targetDeviceId)
        }
    }

    private object PrivateKeys : PlatformPairingPrivateKeyStore {
        override suspend fun generatePairingDeviceKey() = PersistedPairingDeviceKey(
            HpkePublicKeyBase64Url("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"),
            SecretReference("secure://pairing-private/recovery"),
        )
        override suspend fun privateKey(reference: SecretReference): PairingPrivateKeyMaterial? = null
        override suspend fun delete(reference: SecretReference) = Unit
    }

    private object ContentKeys : PlatformKeyMaterialStore {
        override suspend fun importContentKey(material: ImportedContentKeyMaterial): ImportedContentKey {
            val identity = ContentKeyIdentity.fromRawAes256Key(material.copyRawSecretBytesForSecureStore())
            return ImportedContentKey(SecretReference("secure://content/$identity"), identity)
        }
        override suspend fun contentAead(reference: SecretReference): SyncPayloadAead? = null
        override suspend fun delete(reference: SecretReference) = Unit
    }

    private class Ring : SyncKeyRingRepository {
        var installedEpochs = emptySet<Long>()
        override suspend fun state(syncSpaceId: SyncSpaceId): SyncSpaceKeyState? = null
        override suspend fun currentEncryptionKey(syncSpaceId: SyncSpaceId): SyncSpaceContentKeyMetadata? = null
        override suspend fun decryptionKey(syncSpaceId: SyncSpaceId, keyEpoch: Long): SyncSpaceContentKeyMetadata? = null
        override suspend fun historicalDecryptKeys(syncSpaceId: SyncSpaceId): List<SyncSpaceContentKeyMetadata> = emptyList()
        override suspend fun installNewEpoch(syncSpaceId: SyncSpaceId, keyEpoch: Long, contentKeyReference: SecretReference, contentKeyIdentity: ContentKeyIdentity): InstallSyncSpaceKeyEpochResult = error("Not used")
        override suspend fun installKeyPackage(syncSpaceId: SyncSpaceId, activeKey: SyncKeyPackageKeyReference, historicalReferences: List<SyncKeyPackageKeyReference>): InstallSyncKeyPackageResult {
            installedEpochs = historicalReferences.map { it.keyEpoch }.toSet() + activeKey.keyEpoch
            return InstallSyncKeyPackageResult.Installed(SyncKeyPackageAdoption(installedEpochs, emptySet()))
        }
    }

    private object DirectTransactions : ApplicationTransactionRunner {
        override suspend fun <T> inWriteTransaction(block: suspend () -> T): T = block()
    }
}
