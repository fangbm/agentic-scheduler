package dev.agenticscheduler.application.sync

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

actual fun hmacSha256(key: ByteArray, value: ByteArray): ByteArray =
    Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(value)
