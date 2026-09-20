package dev.agenticscheduler.application.sync

import dev.agenticscheduler.sync.AccountId
import dev.agenticscheduler.sync.DeviceId
import dev.agenticscheduler.sync.EnrollmentRequestId
import dev.agenticscheduler.sync.HpkePublicKeyBase64Url
import dev.agenticscheduler.sync.PendingEnrollmentRequestV1
import dev.agenticscheduler.sync.SyncSpaceId
import dev.agenticscheduler.sync.encodeCanonicalBase64Url
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PairingApprovalServiceTest {
    private val account = AccountId("acct-1")
    private val space = SyncSpaceId("personal-space")
    private val active = LocalEnrollmentState.Active(
        accountId = account,
        deviceId = DeviceId("approver-device"),
        enrollmentRequestId = EnrollmentRequestId("approver-request"),
        hpkePublicKey = publicKey(0),
        hpkePrivateKeyReference = SecretReference("secure://approver-private/1"),
        syncSpaceId = space,
        accountMasterKeyReference = SecretReference("secure://amk/1"),
        deviceCredentialReference = SecretReference("secure://credential/1"),
    )
    private val remote = PendingEnrollmentRequestV1(
        account,
        EnrollmentRequestId("remote-request"),
        DeviceId("remote-device"),
        publicKey(1),
    )

    @Test
    fun `active approver with matching SAS returns only encrypted relay package`() = runBlocking {
        val recordingHpke = RecordingHpke()
        val exporter = Exporter()
        val service = service(active, exporter, recordingHpke)

        val result = service.approve(account, remote, sas(remote))

        val approved = assertIs<PairingApprovalResult.Approved>(result)
        assertEquals(remote.accountId, approved.envelope.accountId)
        assertEquals(remote.requestId, approved.envelope.requestId)
        assertEquals(remote.targetDeviceId, approved.envelope.targetDeviceId)
        assertEquals(remote.hpkePublicKeyBase64Url, recordingHpke.recipient)
        assertEquals(1, recordingHpke.encryptions)
    }

    @Test
    fun `SAS mismatch exports no secrets and performs no encryption`() = runBlocking {
        val exporter = Exporter()
        val hpke = RecordingHpke()

        assertEquals(PairingApprovalResult.SasMismatch, service(active, exporter, hpke).approve(account, remote, "00000000"))
        assertEquals(0, exporter.exports)
        assertEquals(0, hpke.encryptions)
    }

    @Test
    fun `pending local device cannot approve a remote request`() = runBlocking {
        val pending = LocalEnrollmentState.Pending(
            account,
            DeviceId("new-device"),
            EnrollmentRequestId("new-request"),
            publicKey(2),
            SecretReference("secure://new-private/1"),
        )
        val hpke = RecordingHpke()

        assertEquals(PairingApprovalResult.NotActive, service(pending, Exporter(), hpke).approve(account, remote, sas(remote)))
        assertEquals(0, hpke.encryptions)
    }

    @Test
    fun `request account mismatch is rejected before key export`() = runBlocking {
        val mismatch = remote.copy(accountId = AccountId("other-account"))
        val exporter = Exporter()
        val hpke = RecordingHpke()

        assertEquals(PairingApprovalResult.AccountMismatch, service(active, exporter, hpke).approve(account, mismatch, sas(mismatch)))
        assertEquals(0, exporter.exports)
        assertEquals(0, hpke.encryptions)
    }

    @Test
    fun `remote public key and request ID changes invalidate the displayed SAS`() = runBlocking {
        val displayed = sas(remote)
        val publicKeyChanged = remote.copy(hpkePublicKeyBase64Url = publicKey(3))
        val requestIdChanged = remote.copy(requestId = EnrollmentRequestId("other-request"))
        val hpke = RecordingHpke()

        assertEquals(PairingApprovalResult.SasMismatch, service(active, Exporter(), hpke).approve(account, publicKeyChanged, displayed))
        assertEquals(PairingApprovalResult.SasMismatch, service(active, Exporter(), hpke).approve(account, requestIdChanged, displayed))
        assertEquals(0, hpke.encryptions)
    }

    @Test
    fun `package build failure leaves approver state unchanged and emits no envelope`() = runBlocking {
        val enrollments = MemoryEnrollments(active)
        val hpke = RecordingHpke()
        val service = PairingApprovalService(
            enrollments,
            PairingKeyPackageBuilder(KeyRing, Exporter(activeKeyAvailable = false), hpke),
        )

        assertEquals(
            PairingApprovalResult.PackageBuildFailed(PairingKeyPackageBuildResult.MissingActiveContentKey),
            service.approve(account, remote, sas(remote)),
        )
        assertEquals(active, enrollments.state(account))
        assertEquals(0, hpke.encryptions)
    }

    private fun service(
        local: LocalEnrollmentState,
        exporter: Exporter,
        hpke: RecordingHpke,
    ) = PairingApprovalService(MemoryEnrollments(local), PairingKeyPackageBuilder(KeyRing, exporter, hpke))

    private fun sas(request: PendingEnrollmentRequestV1): String = PairingSas.calculate(
        request.accountId,
        request.requestId,
        request.targetDeviceId,
        request.hpkePublicKeyBase64Url,
    )

    private fun publicKey(seed: Int): HpkePublicKeyBase64Url =
        HpkePublicKeyBase64Url(encodeCanonicalBase64Url(ByteArray(32) { (it + seed).toByte() }))

    private class MemoryEnrollments(
        private val local: LocalEnrollmentState,
    ) : LocalEnrollmentRepository {
        override suspend fun state(accountId: AccountId): LocalEnrollmentState? = local.takeIf { it.accountId == accountId }
        override suspend fun savePending(value: LocalEnrollmentState.Pending) = error("Approval never writes local enrollment")
        override suspend fun saveActive(value: LocalEnrollmentState.Active) = error("Approval never writes local enrollment")
    }

    private class Exporter(
        private val activeKeyAvailable: Boolean = true,
    ) : PlatformPairingKeyMaterialExporter {
        var exports = 0
        override suspend fun exportAccountMasterKeyForPairing(reference: SecretReference): PairingEphemeralKeyMaterial? = RawMaterial(0).also { exports++ }
        override suspend fun exportContentKeyForPairing(reference: SecretReference): ExportedPairingContentKey? {
            exports++
            return if (activeKeyAvailable) ExportedPairingContentKey(RawMaterial(32), KeyRing.active.contentKeyIdentity) else null
        }
    }

    private class RawMaterial(start: Int) : PairingEphemeralKeyMaterial {
        private val raw = ByteArray(32) { (start + it).toByte() }
        override fun copyRawKeyBytesForPairing(): ByteArray = raw.copyOf()
    }

    private class RecordingHpke : PairingHpke {
        var recipient: HpkePublicKeyBase64Url? = null
        var encryptions = 0
        override fun generateDeviceKeyPair(): PairingDeviceKeyPair = error("Not used")
        override fun encrypt(publicKey: HpkePublicKeyBase64Url, plaintext: ByteArray, contextInfo: ByteArray): HpkeCiphertextComponents {
            recipient = publicKey
            encryptions++
            return HpkeCiphertextComponents(publicKey.value, "AQI")
        }
        override fun decrypt(privateKey: PairingPrivateKeyMaterial, encapsulatedKeyBase64Url: String, ciphertextBase64Url: String, contextInfo: ByteArray): ByteArray = error("Not used")
    }

    private object KeyRing : SyncKeyRingRepository {
        val active = SyncSpaceContentKeyMetadata(
            SyncSpaceId("personal-space"),
            8,
            SecretReference("secure://content/8"),
            ContentKeyIdentity("identity-8"),
            SyncSpaceContentKeyUsage.ACTIVE,
        )
        override suspend fun state(syncSpaceId: SyncSpaceId): SyncSpaceKeyState? = SyncSpaceKeyState(active.syncSpaceId, active.keyEpoch)
        override suspend fun currentEncryptionKey(syncSpaceId: SyncSpaceId): SyncSpaceContentKeyMetadata? = active.takeIf { it.syncSpaceId == syncSpaceId }
        override suspend fun decryptionKey(syncSpaceId: SyncSpaceId, keyEpoch: Long): SyncSpaceContentKeyMetadata? = null
        override suspend fun historicalDecryptKeys(syncSpaceId: SyncSpaceId): List<SyncSpaceContentKeyMetadata> = emptyList()
        override suspend fun installNewEpoch(syncSpaceId: SyncSpaceId, keyEpoch: Long, contentKeyReference: SecretReference, contentKeyIdentity: ContentKeyIdentity): InstallSyncSpaceKeyEpochResult = error("Not used")
        override suspend fun installKeyPackage(syncSpaceId: SyncSpaceId, activeKey: SyncKeyPackageKeyReference, historicalReferences: List<SyncKeyPackageKeyReference>): InstallSyncKeyPackageResult = error("Not used")
    }
}
