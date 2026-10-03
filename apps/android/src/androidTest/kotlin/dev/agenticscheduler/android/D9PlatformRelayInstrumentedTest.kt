package dev.agenticscheduler.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.agenticscheduler.acceptance.EnrolledPlatformReplica
import dev.agenticscheduler.acceptance.acceptanceTrustManager
import dev.agenticscheduler.application.sync.AndroidKeystoreSecureStore
import dev.agenticscheduler.database.openAndroidDatabase
import dev.agenticscheduler.sync.AccountId
import dev.agenticscheduler.sync.SyncSpaceId
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64
import javax.net.ssl.SSLContext

@RunWith(AndroidJUnit4::class)
class D9PlatformRelayInstrumentedTest {
    @Test fun desktopToAndroidToDesktopWithDurableRetry() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val certificate = arguments.getString("d9AcceptanceCertificate")
        assumeTrue("Requires real enrolled Desktop/relay fixture: SKIPPED, never PASS.", certificate != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(acceptanceTrustManager(String(Base64.getDecoder().decode(certificate)))), null) }
        val client = HttpClient(Android) { engine { sslManager = { connection -> connection.sslSocketFactory = ssl.socketFactory } } }
        val replica = EnrolledPlatformReplica(client, "https://localhost:18445", AccountId(requireNotNull(arguments.getString("d9AcceptanceAccount"))),
            SyncSpaceId(requireNotNull(arguments.getString("d9AcceptanceSpace"))), "android", AndroidKeystoreSecureStore(context), { openAndroidDatabase(context) })
        val phase = arguments.getString("d9AcceptancePhase") ?: error("Explicit platform phase required")
        try {
            replica.enrollOrRestore()
            when (phase) {
                "seed" -> replica.androidSeedOffline()
                "resume" -> replica.androidResumeAfterProcessRestart()
                else -> error("Unknown Android platform phase")
            }
        } finally {
            if (phase == "resume") replica.destroyFixtureSecrets()
            replica.close(); client.close()
        }
    }
}
