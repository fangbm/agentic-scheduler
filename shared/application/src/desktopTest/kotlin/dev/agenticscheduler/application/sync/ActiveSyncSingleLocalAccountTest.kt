package dev.agenticscheduler.application.sync

import dev.agenticscheduler.database.openDesktopDatabase
import dev.agenticscheduler.database.record.LocalPairingEnrollmentRecord
import dev.agenticscheduler.database.repository.RoomLocalEnrollmentRepository
import dev.agenticscheduler.database.repository.RoomD8RuntimeComposition
import dev.agenticscheduler.application.history.MutationWallClock
import dev.agenticscheduler.application.id.productionUuidV7Generator
import dev.agenticscheduler.sync.AccountId
import dev.agenticscheduler.sync.DeviceId
import dev.agenticscheduler.sync.EnrollmentRequestId
import dev.agenticscheduler.sync.HpkePublicKeyBase64Url
import dev.agenticscheduler.sync.SyncSpaceId
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ActiveSyncSingleLocalAccountTest {
    @Test
    fun `configured runtime rejects multiple active accounts regardless of configured account`() {
        runBlocking<Unit> {
            val databaseFile = File.createTempFile("agentic-d8-multiple-active-", ".db")
            val database = openDesktopDatabase(databaseFile.absolutePath)
            val store = DesktopPlatformSecureStore(MemoryBackend(), TinkPairingHpke())
            try {
                database.localPairingEnrollmentDao().save(record("account-z"))
                database.localPairingEnrollmentDao().save(record("account-a"))
                val runtime = RoomD8RuntimeComposition(
                    database,
                    store,
                    TinkPairingHpke(),
                    productionUuidV7Generator(),
                    MutationWallClock { 1L },
                )

                val expectedAccounts = listOf(AccountId("account-a"), AccountId("account-z"))
                for (configuredAccount in expectedAccounts) {
                    val result = assertIs<ActiveSyncRuntimeCreation.MultipleActiveEnrollments>(
                        runtime.activate(ActiveSyncRuntimeConfiguration(configuredAccount, "https://sync.example")),
                    )
                    assertEquals(expectedAccounts, result.activeAccountIds)
                }
            } finally {
                databaseFile.delete()
            }
        }
    }

    @Test
    fun `room repository atomically refuses a second active account`() {
        runBlocking<Unit> {
            val databaseFile = File.createTempFile("agentic-d8-single-active-", ".db")
            val database = openDesktopDatabase(databaseFile.absolutePath)
            val enrollments = RoomLocalEnrollmentRepository(database)
            try {
                enrollments.saveActive(active("account-a"))

                assertFailsWith<IllegalStateException> {
                    enrollments.saveActive(active("account-b"))
                }

                assertEquals(listOf(AccountId("account-a")), enrollments.states()
                    .filterIsInstance<LocalEnrollmentState.Active>()
                    .map(LocalEnrollmentState.Active::accountId))
            } finally {
                databaseFile.delete()
            }
        }
    }

    private fun active(accountId: String) = LocalEnrollmentState.Active(
        accountId = AccountId(accountId),
        deviceId = DeviceId("device-$accountId"),
        enrollmentRequestId = EnrollmentRequestId("request-$accountId"),
        hpkePublicKey = HpkePublicKeyBase64Url("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"),
        hpkePrivateKeyReference = SecretReference("private-$accountId"),
        syncSpaceId = SyncSpaceId("space-$accountId"),
        accountMasterKeyReference = SecretReference("amk-$accountId"),
        deviceCredentialReference = SecretReference("credential-$accountId"),
    )

    private fun record(accountId: String) = LocalPairingEnrollmentRecord(
        accountId = accountId,
        deviceId = "device-$accountId",
        enrollmentRequestId = "request-$accountId",
        hpkePublicKeyBase64Url = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
        hpkePrivateKeySecretRef = "private-$accountId",
        status = "ACTIVE",
        syncSpaceId = "space-$accountId",
        accountMasterKeySecretRef = "amk-$accountId",
        deviceCredentialSecretRef = "credential-$accountId",
    )

    private class MemoryBackend : DesktopSecureBackend {
        override val referencePrefix = "test-single-active://"
        override fun store(id: String, value: ByteArray) = Unit
        override fun read(id: String): ByteArray? = null
        override fun delete(id: String) = Unit
    }
}
