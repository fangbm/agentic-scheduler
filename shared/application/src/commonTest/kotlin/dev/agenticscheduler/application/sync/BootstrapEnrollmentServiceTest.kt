package dev.agenticscheduler.application.sync

import dev.agenticscheduler.sync.SyncSpaceId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BootstrapEnrollmentServiceTest {
    @Test
    fun `bootstrap stores only secure reference`() = runBlocking {
        val store = MemoryCredentialStore()
        val result = BootstrapEnrollmentService(
            bootstrap = { _, _ -> response() },
            credentials = store,
        ).enroll("invite", "device")
        val stored = assertIs<BootstrapEnrollmentResult.Stored>(result)
        assertEquals(SyncSpaceId("space"), stored.syncSpaceId)
        assertEquals(SecretReference("secure://credential/1"), stored.credentialReference)
        assertEquals(DeviceCredential("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"), store.value)
    }

    @Test
    fun `invalid credential never reaches secure store`() = runBlocking {
        val store = MemoryCredentialStore()
        val result = BootstrapEnrollmentService(
            bootstrap = { _, _ -> response().copy(deviceCredential = "invalid") },
            credentials = store,
        ).enroll("invite", "device")
        assertEquals(BootstrapEnrollmentResult.InvalidCredential, result)
        assertEquals(null, store.value)
    }

    private fun response() = ClientBootstrapResponse(
        accountId = "account",
        syncSpaceId = "space",
        deviceId = "device",
        deviceCredential = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
    )

    private class MemoryCredentialStore : PlatformDeviceCredentialStore {
        var value: DeviceCredential? = null
        override suspend fun store(value: DeviceCredential): SecretReference {
            this.value = value
            return SecretReference("secure://credential/1")
        }
        override suspend fun load(reference: SecretReference): DeviceCredential? = value
        override suspend fun delete(reference: SecretReference) { value = null }
    }
}
