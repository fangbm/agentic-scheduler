package dev.agenticscheduler.application.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO

actual fun createSyncHttpClient(): HttpClient = HttpClient(CIO)
