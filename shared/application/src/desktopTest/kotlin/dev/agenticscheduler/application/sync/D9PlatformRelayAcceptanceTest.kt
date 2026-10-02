package dev.agenticscheduler.application.sync

import dev.agenticscheduler.acceptance.EnrolledPlatformReplica
import dev.agenticscheduler.acceptance.acceptanceTrustManager
import dev.agenticscheduler.database.openDesktopDatabase
import dev.agenticscheduler.sync.AccountId
import dev.agenticscheduler.sync.SyncSpaceId
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class D9PlatformRelayAcceptanceTest {
    @Test fun platformPhase() = runBlocking {
        val phase = System.getenv("D9_PLATFORM_PHASE")
        assumeTrue("Cross-platform harness not requested: SKIPPED, not E2E PASS.", phase != null)
        val root = File(requireNotNull(System.getenv("D9_PLATFORM_DIRECTORY")))
        val client = HttpClient(CIO) { engine { https { trustManager = acceptanceTrustManager(File(root, "cert.pem").readText()) } } }
        val replica = EnrolledPlatformReplica(client, "https://localhost:18445", AccountId(File(root, "account.txt").readText().trim()),
            SyncSpaceId(File(root, "space.txt").readText().trim()), "desktop", DesktopPlatformSecureStore(), { openDesktopDatabase(File(root, "desktop.db").absolutePath) })
        try {
            replica.enrollOrRestore()
            when (phase) {
                "seed" -> replica.seedDesktopOffline()
                "resume" -> replica.resumeDesktopAfterServerRestart()
                "verify" -> replica.verifyDesktop()
                else -> error("Unknown acceptance phase")
            }
        } finally { replica.close(); client.close() }
    }
}
