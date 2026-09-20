package dev.agenticscheduler.application.sync

import io.ktor.client.HttpClient

/** Platform engine boundary; callers own and close the returned client. */
expect fun createSyncHttpClient(): HttpClient

fun createPlatformSyncTransport(
    baseUrl: String,
    deviceCredential: suspend () -> DeviceCredential?,
): KtorSyncTransport = KtorSyncTransport(createSyncHttpClient(), baseUrl, deviceCredential)
