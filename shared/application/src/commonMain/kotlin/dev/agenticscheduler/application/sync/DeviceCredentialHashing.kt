package dev.agenticscheduler.application.sync

import dev.agenticscheduler.sync.decodeCanonicalBase64Url
import dev.agenticscheduler.sync.encodeCanonicalBase64Url

/** The server stores this digest; the bearer credential itself never enters the enrollment wire object. */
object DeviceCredentialHashing {
    fun sha256Base64Url(value: DeviceCredential): String =
        encodeCanonicalBase64Url(pairingSha256(decodeCanonicalBase64Url(value.value, 32, "DeviceCredential")))
}
