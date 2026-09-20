package dev.agenticscheduler.application.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class ContentKeyIdentityTest {
    @Test
    fun `SYN-005A content key identity is canonical SHA-256 base64url`() {
        assertEquals(
            ContentKeyIdentity("Yw3NKWbEM2aRElRIu7JbT_QSpJxzLbLIq8G4WBvXEN0"),
            ContentKeyIdentity.fromRawAes256Key(ByteArray(32) { it.toByte() }),
        )
    }

    @Test
    fun `content key identity rejects non AES-256 material`() {
        assertFails { ContentKeyIdentity.fromRawAes256Key(ByteArray(31)) }
    }
}
