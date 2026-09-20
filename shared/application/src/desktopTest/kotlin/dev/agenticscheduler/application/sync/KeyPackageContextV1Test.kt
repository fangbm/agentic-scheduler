package dev.agenticscheduler.application.sync

import dev.agenticscheduler.sync.AccountId
import dev.agenticscheduler.sync.DeviceId
import dev.agenticscheduler.sync.EnrollmentRequestId
import kotlin.test.Test
import kotlin.test.assertEquals

class KeyPackageContextV1Test {
    @Test
    fun `SYN-006A contextInfo byte fixture is length prefixed and stable`() {
        val bytes = KeyPackageContextV1.bytes(
            accountId = AccountId("acct-1"),
            requestId = EnrollmentRequestId("req-1"),
            targetDeviceId = DeviceId("device-1"),
            keyPackageVersion = 1,
            keyEpoch = 8,
        )

        assertEquals(
            "6167656e7469632d7363686564756c65722d6b65792d7061636b61676500" +
                "00000006616363742d31" +
                "000000057265712d31" +
                "000000086465766963652d31" +
                "00000001" +
                "0000000000000008",
            bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) },
        )
    }
}
