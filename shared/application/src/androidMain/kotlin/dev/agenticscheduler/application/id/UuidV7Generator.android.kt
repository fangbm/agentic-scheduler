package dev.agenticscheduler.application.id

import java.security.SecureRandom
import kotlin.time.Clock

actual fun productionUuidV7Generator(): UuidV7Generator = RfcUuidV7Generator(
    EpochMillisecondsClock { Clock.System.now().toEpochMilliseconds() },
    RandomBytes { size -> ByteArray(size).also(SecureRandom()::nextBytes) },
)
