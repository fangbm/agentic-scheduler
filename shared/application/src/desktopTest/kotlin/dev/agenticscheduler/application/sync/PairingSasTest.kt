package dev.agenticscheduler.application.sync

import dev.agenticscheduler.sync.AccountId
import dev.agenticscheduler.sync.DeviceId
import dev.agenticscheduler.sync.EnrollmentRequestId
import dev.agenticscheduler.sync.HpkePublicKeyBase64Url
import kotlin.test.Test
import kotlin.test.assertEquals

class PairingSasTest {
    @Test
    fun `SYN-006A SAS fixture is stable and preserves leading zeroes`() {
        assertEquals(
            "20345109",
            PairingSas.calculate(
                accountId = AccountId("acct-1"),
                requestId = EnrollmentRequestId("req-1"),
                deviceId = DeviceId("device-1"),
                hpkePublicKey = HpkePublicKeyBase64Url("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"),
            ),
        )
    }
}
