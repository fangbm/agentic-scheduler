package dev.agenticscheduler.application.sync

import dev.agenticscheduler.sync.AccountId
import dev.agenticscheduler.sync.RecoveryEnvelopePlaintextV1
import dev.agenticscheduler.sync.RecoveryEnvelopeV1
import dev.agenticscheduler.sync.RecoveryHistoricalKeyV1
import dev.agenticscheduler.sync.RecoverySyncSpaceKeyRingV1
import dev.agenticscheduler.sync.RecoveryWireCodec
import dev.agenticscheduler.sync.SyncSpaceId
import dev.agenticscheduler.sync.encodeCanonicalBase64Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class RecoveryEnvelopeCodecTest {
    private val secret = RecoverySecret("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8")
    private val account = AccountId("acct-1")
    private val space = SyncSpaceId("personal-space")

    @Test
    fun `frozen recovery KDF and AAD vectors are exact`() {
        assertEquals(
            "8yOwJWxoEpwPp8Apa4ZSuteJ9Eq2SxJhcfzl-GGqpt8",
            encodeCanonicalBase64Url(deriveRecoveryEnvelopeKey(secret, account, space, 7)),
        )
        assertEquals(
            "YWdlbnRpYy1zY2hlZHVsZXItcmVjb3ZlcnktZW52ZWxvcGUAAAAABmFjY3QtMQAAAA5wZXJzb25hbC1zcGFjZQAAAAEAAAAAAAAABw",
            encodeCanonicalBase64Url(recoveryEnvelopeAad(account, space, 7)),
        )
    }

    @Test
    fun `recovery envelope round trips complete active and historical ring`() {
        val plaintext = plaintext()
        val envelope = RecoveryEnvelopeCodec.seal(secret, plaintext)
        val opened = assertIs<RecoveryEnvelopeOpenResult.Decrypted>(RecoveryEnvelopeCodec.open(secret, envelope))
        assertEquals(plaintext, opened.plaintext)
        assertNotEquals(RecoveryWireCodec.encodePlaintext(plaintext), envelope.ciphertextBase64Url)
    }

    @Test
    fun `wrong secret and outer metadata tamper fail closed`() {
        val envelope = RecoveryEnvelopeCodec.seal(secret, plaintext())
        val wrong = RecoverySecret("ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8")
        assertEquals(RecoveryEnvelopeOpenResult.AuthenticationFailed, RecoveryEnvelopeCodec.open(wrong, envelope))
        assertEquals(RecoveryEnvelopeOpenResult.AuthenticationFailed, RecoveryEnvelopeCodec.open(secret, envelope.copy(keyEpoch = 8)))
        assertEquals(RecoveryEnvelopeOpenResult.AuthenticationFailed, RecoveryEnvelopeCodec.open(secret, envelope.copy(accountId = AccountId("acct-2"))))
    }

    @Test
    fun `plaintext and envelope identity mismatch is rejected after authentication`() {
        val mismatched = plaintext().copy(accountId = AccountId("acct-2"))
        val key = deriveRecoveryEnvelopeKey(secret, account, space, 7)
        val ciphertext = try {
            recoveryEnvelopeAeadEncrypt(
                key,
                RecoveryWireCodec.encodePlaintext(mismatched).encodeToByteArray(),
                recoveryEnvelopeAad(account, space, 7),
            )
        } finally {
            key.fill(0)
        }
        val envelope = RecoveryEnvelopeV1(
            accountId = account,
            syncSpaceId = space,
            keyEpoch = 7,
            ciphertextBase64Url = encodeCanonicalBase64Url(ciphertext),
        )
        assertEquals(RecoveryEnvelopeOpenResult.BindingMismatch, RecoveryEnvelopeCodec.open(secret, envelope))
    }

    @Test
    fun `authenticated recovery plaintext with invalid UTF-8 fails closed`() {
        val key = deriveRecoveryEnvelopeKey(secret, account, space, 7)
        val ciphertext = try {
            recoveryEnvelopeAeadEncrypt(
                key,
                byteArrayOf(0xc3.toByte(), 0x28),
                recoveryEnvelopeAad(account, space, 7),
            )
        } finally {
            key.fill(0)
        }
        val envelope = RecoveryEnvelopeV1(
            accountId = account,
            syncSpaceId = space,
            keyEpoch = 7,
            ciphertextBase64Url = encodeCanonicalBase64Url(ciphertext),
        )

        val result = assertIs<RecoveryEnvelopeOpenResult.InvalidPlaintext>(
            RecoveryEnvelopeCodec.open(secret, envelope),
        )
        assertEquals("Recovery plaintext is not valid UTF-8.", result.reason)
    }

    private fun plaintext() = RecoveryEnvelopePlaintextV1(
        accountId = account,
        keyEpoch = 7,
        accountMasterKeyBase64Url = encodeCanonicalBase64Url(ByteArray(32) { it.toByte() }),
        syncSpace = RecoverySyncSpaceKeyRingV1(
            syncSpaceId = space,
            activeEpoch = 7,
            activeKeyBase64Url = encodeCanonicalBase64Url(ByteArray(32) { (it + 32).toByte() }),
            historicalKeys = listOf(
                RecoveryHistoricalKeyV1(3, encodeCanonicalBase64Url(ByteArray(32) { (it + 64).toByte() })),
                RecoveryHistoricalKeyV1(6, encodeCanonicalBase64Url(ByteArray(32) { (it + 96).toByte() })),
            ),
        ),
    )
}
