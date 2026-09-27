package dev.agenticscheduler.application.sync

import dev.agenticscheduler.sync.AccountId
import dev.agenticscheduler.sync.DeviceId
import dev.agenticscheduler.sync.EnrollmentRequestId
import dev.agenticscheduler.sync.HpkePublicKeyBase64Url
import dev.agenticscheduler.sync.SyncSpaceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

class LocalEnrollmentRequestServiceTest {
    private val account = AccountId("acct-1")
    private val device = DeviceId("device-1")
    private val request = EnrollmentRequestId("req-1")
    private val privateRef = SecretReference("secure://pairing-private-key/1")
    private val credentialRef = SecretReference("secure://credential/1")

    @Test
    fun `pending enrollment persists a private key reference and resolves after restart`() = runBlocking {
        val enrollments = MemoryEnrollments()
        val privateKeys = MemoryPrivateKeys(privateRef)
        val credentials = MemoryCredentials(credentialRef)
        val service = LocalEnrollmentRequestService(enrollments, privateKeys, credentials)

        val createdResult = assertIs<StartLocalEnrollmentResult.Created>(service.startPending(account, device, request))
        val created = createdResult.pending
        assertEquals(created, enrollments.state(account))
        assertEquals(privateKeys.private, privateKeys.privateKey(created.hpkePrivateKeyReference))
        assertEquals(created.asRemoteEnrollmentRequest().hpkePublicKeyBase64Url, created.hpkePublicKey)
        assertEquals(credentialRef, created.deviceCredentialReference)
        assertEquals(credentials.hash, createdResult.credentialHashBase64Url)
    }

    @Test
    fun `failed pending metadata save deletes the unreferenced private key`() = runBlocking {
        val privateKeys = MemoryPrivateKeys(privateRef)
        val credentials = MemoryCredentials(credentialRef)
        val service = LocalEnrollmentRequestService(FailingEnrollments, privateKeys, credentials)

        kotlin.test.assertFails { service.startPending(account, device, request) }

        assertEquals(listOf(privateRef), privateKeys.deleted)
        assertNull(privateKeys.privateKey(privateRef))
        assertEquals(listOf(credentialRef), credentials.deleted)
    }

    @Test
    fun `another active account rejects pending before generating secure material or writing enrollment`() = runBlocking {
        val activeAccount = AccountId("acct-active")
        val enrollments = MemoryEnrollments().apply {
            saveActive(
                LocalEnrollmentState.Active(
                    accountId = activeAccount,
                    deviceId = DeviceId("active-device"),
                    enrollmentRequestId = EnrollmentRequestId("active-request"),
                    hpkePublicKey = HpkePublicKeyBase64Url("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"),
                    hpkePrivateKeyReference = SecretReference("secure://active-private"),
                    syncSpaceId = SyncSpaceId("personal-active"),
                    accountMasterKeyReference = SecretReference("secure://active-amk"),
                    deviceCredentialReference = SecretReference("secure://active-credential"),
                ),
            )
        }
        val privateKeys = MemoryPrivateKeys(privateRef)
        val credentials = MemoryCredentials(credentialRef)
        val service = LocalEnrollmentRequestService(enrollments, privateKeys, credentials)

        val result = assertIs<StartLocalEnrollmentResult.RejectedActiveAccount>(
            service.startPending(account, device, request),
        )

        assertEquals(listOf(activeAccount), result.activeAccountIds)
        assertEquals(0, privateKeys.generationCount)
        assertEquals(0, credentials.generationCount)
        assertEquals(0, enrollments.pendingWriteCount)
        assertNull(enrollments.state(account))
    }

    @Test
    fun `another active account rejects reuse of an existing pending enrollment`() = runBlocking {
        val activeAccount = AccountId("acct-active")
        val pending = LocalEnrollmentState.Pending(
            accountId = account,
            deviceId = device,
            enrollmentRequestId = request,
            hpkePublicKey = HpkePublicKeyBase64Url("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"),
            hpkePrivateKeyReference = privateRef,
            deviceCredentialReference = credentialRef,
        )
        val enrollments = MemoryEnrollments().apply {
            savePending(pending)
            saveActive(
                LocalEnrollmentState.Active(
                    accountId = activeAccount,
                    deviceId = DeviceId("active-device"),
                    enrollmentRequestId = EnrollmentRequestId("active-request"),
                    hpkePublicKey = HpkePublicKeyBase64Url("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"),
                    hpkePrivateKeyReference = SecretReference("secure://active-private"),
                    syncSpaceId = SyncSpaceId("personal-active"),
                    accountMasterKeyReference = SecretReference("secure://active-amk"),
                    deviceCredentialReference = SecretReference("secure://active-credential"),
                ),
            )
        }
        val privateKeys = MemoryPrivateKeys(privateRef)
        val credentials = MemoryCredentials(credentialRef)
        val service = LocalEnrollmentRequestService(enrollments, privateKeys, credentials)

        val result = assertIs<StartLocalEnrollmentResult.RejectedActiveAccount>(
            service.startPending(account, device, request),
        )

        assertEquals(listOf(activeAccount), result.activeAccountIds)
        assertEquals(pending, enrollments.state(account))
        assertEquals(0, privateKeys.generationCount)
        assertEquals(0, credentials.generationCount)
        assertEquals(1, enrollments.pendingWriteCount)
    }

    @Test
    fun `existing pending enrollment is revalidated through durable save before reuse`() = runBlocking {
        val pending = LocalEnrollmentState.Pending(
            accountId = account,
            deviceId = device,
            enrollmentRequestId = request,
            hpkePublicKey = HpkePublicKeyBase64Url("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"),
            hpkePrivateKeyReference = privateRef,
            deviceCredentialReference = credentialRef,
        )
        val enrollments = MemoryEnrollments().apply { savePending(pending) }
        val privateKeys = MemoryPrivateKeys(privateRef)
        val credentials = MemoryCredentials(credentialRef)
        val service = LocalEnrollmentRequestService(enrollments, privateKeys, credentials)

        assertEquals(StartLocalEnrollmentResult.Existing(pending), service.startPending(account, device, request))

        assertEquals(2, enrollments.pendingWriteCount)
        assertEquals(0, privateKeys.generationCount)
        assertEquals(0, credentials.generationCount)
    }

    private class MemoryEnrollments : LocalEnrollmentRepository {
        private val values = mutableMapOf<AccountId, LocalEnrollmentState>()
        var pendingWriteCount = 0
            private set
        override suspend fun state(accountId: AccountId): LocalEnrollmentState? = values[accountId]
        override suspend fun states(): List<LocalEnrollmentState> = values.values.toList()
        override suspend fun savePending(value: LocalEnrollmentState.Pending) { pendingWriteCount++; values[value.accountId] = value }
        override suspend fun saveActive(value: LocalEnrollmentState.Active) { values[value.accountId] = value }
    }

    private object FailingEnrollments : LocalEnrollmentRepository {
        override suspend fun state(accountId: AccountId): LocalEnrollmentState? = null
        override suspend fun states(): List<LocalEnrollmentState> = emptyList()
        override suspend fun savePending(value: LocalEnrollmentState.Pending): Nothing = error("Room unavailable")
        override suspend fun saveActive(value: LocalEnrollmentState.Active): Nothing = error("Not used")
    }

    private class MemoryPrivateKeys(
        private val reference: SecretReference,
    ) : PlatformPairingPrivateKeyStore {
        val private = object : PairingPrivateKeyMaterial {}
        val deleted = mutableListOf<SecretReference>()
        var generationCount = 0
            private set
        private var present = true
        override suspend fun generatePairingDeviceKey(): PersistedPairingDeviceKey {
            generationCount++
            return PersistedPairingDeviceKey(
                HpkePublicKeyBase64Url("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"),
                reference,
            )
        }
        override suspend fun privateKey(reference: SecretReference): PairingPrivateKeyMaterial? = private.takeIf { present && reference == this.reference }
        override suspend fun delete(reference: SecretReference) {
            deleted += reference
            if (reference == this.reference) present = false
        }
    }

    private class MemoryCredentials(private val reference: SecretReference) : PlatformDeviceCredentialStore {
        val hash = "credential-hash"
        val deleted = mutableListOf<SecretReference>()
        var generationCount = 0
            private set
        override suspend fun generate(): GeneratedDeviceCredential {
            generationCount++
            return GeneratedDeviceCredential(reference, hash)
        }
        override suspend fun store(value: DeviceCredential) = reference
        override suspend fun load(reference: SecretReference): DeviceCredential? = null
        override suspend fun delete(reference: SecretReference) { deleted += reference }
    }
}
