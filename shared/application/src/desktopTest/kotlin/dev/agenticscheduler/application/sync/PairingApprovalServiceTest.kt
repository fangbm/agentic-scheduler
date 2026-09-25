package dev.agenticscheduler.application.sync

import dev.agenticscheduler.sync.AccountId
import dev.agenticscheduler.sync.DeviceId
import dev.agenticscheduler.sync.EnrollmentRequestId
import dev.agenticscheduler.sync.HpkePublicKeyBase64Url
import dev.agenticscheduler.sync.PendingEnrollmentRequestV1
import dev.agenticscheduler.sync.SyncSpaceId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class PairingApprovalServiceTest {
    private val account = AccountId("acct-1")
    private val syncSpace = SyncSpaceId("personal-space")
    private val active = LocalEnrollmentState.Active(
        accountId = account,
        deviceId = DeviceId("existing-device"),
        enrollmentRequestId = EnrollmentRequestId("existing-request"),
        hpkePublicKey = publicKey(0),
        hpkePrivateKeyReference = SecretReference("secure://private/existing"),
        syncSpaceId = syncSpace,
        accountMasterKeyReference = SecretReference("secure://amk"),
        deviceCredentialReference = SecretReference("secure://credential"),
    )
    private val remoteRequest = PendingEnrollmentRequestV1(
        accountId = account,
        requestId = EnrollmentRequestId("remote-request"),
        targetDeviceId = DeviceId("new-device"),
        hpkePublicKeyBase64Url = publicKey(32),
    )

    @Test
    fun `active device with confirmed remote SAS returns only an encrypted key package`() = runBlocking {
        val hpke = RecordingHpke()
        val service = service(active, hpke)

        val result = service.approve(account, remoteRequest, sas(remoteRequest))

        val approved = assertIs<PairingApprovalResult.Approved>(result)
        assertEquals(remoteRequest.accountId, approved.envelope.accountId)
        assertEquals(remoteRequest.requestId, approved.envelope.requestId)
        assertEquals(remoteRequest.targetDeviceId, approved.envelope.targetDeviceId)
        assertEquals(remoteRequest.hpkePublicKeyBase64Url, hpke.recipient)
        assertEquals(1, hpke.encryptions)
    }

    @Test
    fun `SAS or remote request identity mismatch never exports or encrypts key material`() = runBlocking {
        val hpke = RecordingHpke()
        val service = service(active, hpke)

        assertEquals(PairingApprovalResult.SasMismatch, service.approve(account, remoteRequest, "00000000"))
        assertEquals(PairingApprovalResult.SasMismatch, service.approve(account, remoteRequest.copy(requestId = EnrollmentRequestId("changed")), sas(remoteRequest)))
        assertEquals(PairingApprovalResult.SasMismatch, service.approve(account, remoteRequest.copy(hpkePublicKeyBase64Url = publicKey(64)), sas(remoteRequest)))
        assertEquals(0, hpke.encryptions)
    }

    @Test
    fun `pending local device and mismatched account cannot approve a remote enrollment`() = runBlocking {
        val hpke = RecordingHpke()
        val pending = LocalEnrollmentState.Pending(
            account,
            DeviceId("pending-device"),
            EnrollmentRequestId("pending-request"),
            publicKey(0),
            SecretReference("secure://private/pending"),
            SecretReference("secure://credential/pending"),
        )

        assertEquals(PairingApprovalResult.NotActive, service(pending, hpke).approve(account, remoteRequest, sas(remoteRequest)))
        assertEquals(
            PairingApprovalResult.AccountMismatch,
            PairingApprovalService(
                enrollments = FixedEnrollments(active, ignoreRequestedAccount = true),
                packageBuilder = PairingKeyPackageBuilder(KeyRing, Exporter, hpke),
            ).approve(AccountId("other-account"), remoteRequest.copy(accountId = AccountId("other-account")), sas(remoteRequest.copy(accountId = AccountId("other-account")))),
        )
        assertEquals(0, hpke.encryptions)
    }

    @Test
    fun `package build failure publishes no envelope`() = runBlocking {
        val hpke = RecordingHpke()
        val service = PairingApprovalService(
            enrollments = FixedEnrollments(active),
            packageBuilder = PairingKeyPackageBuilder(
                keyRing = EmptyKeyRing,
                keyMaterial = Exporter,
                hpke = hpke,
            ),
        )

        assertEquals(
            PairingApprovalResult.PackageUnavailable(PairingKeyPackageBuildResult.MissingActiveContentKey),
            service.approve(account, remoteRequest, sas(remoteRequest)),
        )
        assertNull(hpke.recipient)
    }

    private fun service(state: LocalEnrollmentState, hpke: RecordingHpke): PairingApprovalService =
        PairingApprovalService(
            enrollments = FixedEnrollments(state),
            packageBuilder = PairingKeyPackageBuilder(
                keyRing = KeyRing,
                keyMaterial = Exporter,
                hpke = hpke,
            ),
        )

    private fun sas(request: PendingEnrollmentRequestV1): String = PairingSas.calculate(
        request.accountId,
        request.requestId,
        request.targetDeviceId,
        request.hpkePublicKeyBase64Url,
    )

    private fun publicKey(offset: Int) = HpkePublicKeyBase64Url(
        dev.agenticscheduler.sync.encodeCanonicalBase64Url(ByteArray(32) { (offset + it).toByte() }),
    )

    private class FixedEnrollments(
        private val value: LocalEnrollmentState,
        private val ignoreRequestedAccount: Boolean = false,
    ) : LocalEnrollmentRepository {
        override suspend fun state(accountId: AccountId): LocalEnrollmentState? =
            if (ignoreRequestedAccount || value.accountId == accountId) value else null
        override suspend fun states(): List<LocalEnrollmentState> = listOf(value)
        override suspend fun savePending(value: LocalEnrollmentState.Pending) = error("Not used")
        override suspend fun saveActive(value: LocalEnrollmentState.Active) = error("Not used")
    }

    private object KeyRing : SyncKeyRingRepository {
        private val active = SyncSpaceContentKeyMetadata(
            SyncSpaceId("personal-space"),
            8,
            SecretReference("secure://content/8"),
            ContentKeyIdentity.fromRawAes256Key(raw(32)),
            SyncSpaceContentKeyUsage.ACTIVE,
        )
        override suspend fun state(syncSpaceId: SyncSpaceId) = SyncSpaceKeyState(syncSpaceId, 8)
        override suspend fun currentEncryptionKey(syncSpaceId: SyncSpaceId) = active.takeIf { it.syncSpaceId == syncSpaceId }
        override suspend fun decryptionKey(syncSpaceId: SyncSpaceId, keyEpoch: Long) = null
        override suspend fun historicalDecryptKeys(syncSpaceId: SyncSpaceId) = emptyList<SyncSpaceContentKeyMetadata>()
        override suspend fun installNewEpoch(syncSpaceId: SyncSpaceId, keyEpoch: Long, contentKeyReference: SecretReference, contentKeyIdentity: ContentKeyIdentity) = error("Not used")
        override suspend fun installKeyPackage(syncSpaceId: SyncSpaceId, activeKey: SyncKeyPackageKeyReference, historicalReferences: List<SyncKeyPackageKeyReference>) = error("Not used")
    }

    private object EmptyKeyRing : SyncKeyRingRepository by KeyRing {
        override suspend fun currentEncryptionKey(syncSpaceId: SyncSpaceId): SyncSpaceContentKeyMetadata? = null
    }

    private object Exporter : PlatformPairingKeyMaterialExporter {
        override suspend fun exportAccountMasterKeyForPairing(reference: SecretReference): PairingEphemeralKeyMaterial = RawMaterial(raw(0))
        override suspend fun exportContentKeyForPairing(reference: SecretReference): ExportedPairingContentKey =
            ExportedPairingContentKey(RawMaterial(raw(32)), ContentKeyIdentity.fromRawAes256Key(raw(32)))
    }

    private class RecordingHpke : PairingHpke {
        var recipient: HpkePublicKeyBase64Url? = null
        var encryptions = 0
        override fun generateDeviceKeyPair(): PairingDeviceKeyPair = error("Not used")
        override fun encrypt(publicKey: HpkePublicKeyBase64Url, plaintext: ByteArray, contextInfo: ByteArray): HpkeCiphertextComponents {
            recipient = publicKey
            encryptions += 1
            return HpkeCiphertextComponents(publicKey.value, "AQI")
        }
        override fun decrypt(privateKey: PairingPrivateKeyMaterial, encapsulatedKeyBase64Url: String, ciphertextBase64Url: String, contextInfo: ByteArray): ByteArray = error("Not used")
    }

    private class RawMaterial(private val bytes: ByteArray) : PairingEphemeralKeyMaterial {
        override fun copyRawKeyBytesForPairing(): ByteArray = bytes.copyOf()
    }

    private companion object {
        fun raw(offset: Int) = ByteArray(32) { (offset + it).toByte() }
    }
}
