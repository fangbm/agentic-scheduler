package dev.agenticscheduler.application.sync

import dev.agenticscheduler.sync.AccountId
import dev.agenticscheduler.sync.DeviceId
import dev.agenticscheduler.sync.EnrollmentRequestId
import dev.agenticscheduler.sync.HpkePublicKeyBase64Url
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

    private class MemoryEnrollments : LocalEnrollmentRepository {
        private var value: LocalEnrollmentState? = null
        override suspend fun state(accountId: AccountId): LocalEnrollmentState? = value?.takeIf { it.accountId == accountId }
        override suspend fun savePending(value: LocalEnrollmentState.Pending) { this.value = value }
        override suspend fun saveActive(value: LocalEnrollmentState.Active) { this.value = value }
    }

    private object FailingEnrollments : LocalEnrollmentRepository {
        override suspend fun state(accountId: AccountId): LocalEnrollmentState? = null
        override suspend fun savePending(value: LocalEnrollmentState.Pending): Nothing = error("Room unavailable")
        override suspend fun saveActive(value: LocalEnrollmentState.Active): Nothing = error("Not used")
    }

    private class MemoryPrivateKeys(
        private val reference: SecretReference,
    ) : PlatformPairingPrivateKeyStore {
        val private = object : PairingPrivateKeyMaterial {}
        val deleted = mutableListOf<SecretReference>()
        private var present = true
        override suspend fun generatePairingDeviceKey(): PersistedPairingDeviceKey =
            PersistedPairingDeviceKey(
                HpkePublicKeyBase64Url("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"),
                reference,
            )
        override suspend fun privateKey(reference: SecretReference): PairingPrivateKeyMaterial? = private.takeIf { present && reference == this.reference }
        override suspend fun delete(reference: SecretReference) {
            deleted += reference
            if (reference == this.reference) present = false
        }
    }

    private class MemoryCredentials(private val reference: SecretReference) : PlatformDeviceCredentialStore {
        val hash = "credential-hash"
        val deleted = mutableListOf<SecretReference>()
        override suspend fun generate() = GeneratedDeviceCredential(reference, hash)
        override suspend fun store(value: DeviceCredential) = reference
        override suspend fun load(reference: SecretReference): DeviceCredential? = null
        override suspend fun delete(reference: SecretReference) { deleted += reference }
    }
}
