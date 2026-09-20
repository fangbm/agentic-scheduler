package dev.agenticscheduler.application.sync

import java.security.MessageDigest

actual fun pairingSha256(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)
