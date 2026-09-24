package dev.agenticscheduler.application.sync

import dev.agenticscheduler.sync.HpkePublicKeyBase64Url
import dev.agenticscheduler.sync.SyncSpaceId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BootstrapEnrollmentServiceTest {
    private val publicKey = HpkePublicKeyBase64Url("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8")

    @Test
    fun `bootstrap registers public HPKE identity and returns secure references only`() = runBlocking {
        val credentials = MemoryCredentialStore()
        val privateKeys = MemoryPrivateKeyStore(publicKey)
        var sentPublicKey: String? = null
        val result = BootstrapEnrollmentService(
            bootstrap = { _, _, hpke ->
                sentPublicKey = hpke
                response()
            },
            credentials = credentials,
            privateKeys = privateKeys,
        ).enroll("invite", "device")

        val stored = assertIs<BootstrapEnrollmentResult.Stored>(result)
        assertEquals(SyncSpaceId("space"), stored.syncSpaceId)
        assertEquals(SecretReference("secure://credential/1"), stored.credentialReference)
        assertEquals(publicKey, stored.hpkePublicKey)
        assertEquals(SecretReference("secure://pairing-private/bootstrap"), stored.hpkePrivateKeyReference)
        assertEquals(publicKey.value, sentPublicKey)
        assertEquals(DeviceCredential("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"), credentials.value)
        assertEquals(1, privateKeys.generated)
    }

    @Test
    fun `invalid credential never reaches credential store and deletes staged private key`() = runBlocking {
        val credentials = MemoryCredentialStore()
        val privateKeys = MemoryPrivateKeyStore(publicKey)
        val result = BootstrapEnrollmentService(
            bootstrap = { _, _, _ -> response().copy(deviceCredential = "invalid") },
            credentials = credentials,
            privateKeys = privateKeys,
        ).enroll("invite", "device")

        assertEquals(BootstrapEnrollmentResult.InvalidCredential, result)
        assertEquals(null, credentials.value)
        assertTrue(privateKeys.deleted)
    }

    private fun response() = ClientBootstrapResponse(
        accountId = "account",
        syncSpaceId = "space",
        deviceId = "device",
        deviceCredential = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
    )

    private class MemoryCredentialStore : PlatformDeviceCredentialStore {
        var value: DeviceCredential? = null
        override suspend fun generate(): GeneratedDeviceCredential = error("Not used")
        override suspend fun store(value: DeviceCredential): SecretReference {
            this.value = value
            return SecretReference("secure://credential/1")
        }
        override suspend fun load(reference: SecretReference): DeviceCredential? = value
        override suspend fun delete(reference: SecretReference) { value = null }
    }

    private class MemoryPrivateKeyStore(
        private val publicKey: HpkePublicKeyBase64Url,
    ) : PlatformPairingPrivateKeyStore {
        var generated = 0
        var deleted = false
        private val reference = SecretReference("secure://pairing-private/bootstrap")

        override suspend fun generatePairingDeviceKey(): PersistedPairingDeviceKey {
            generated++
            return PersistedPairingDeviceKey(publicKey, reference)
        }

        override suspend fun privateKey(reference: SecretReference): PairingPrivateKeyMaterial? = null

        override suspend fun delete(reference: SecretReference) {
            if (reference == this.reference) deleted = true
        }
    }
}
