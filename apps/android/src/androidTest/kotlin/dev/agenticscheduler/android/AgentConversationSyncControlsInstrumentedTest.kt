package dev.agenticscheduler.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.content.ContextWrapper
import dev.agenticscheduler.application.sync.*
import dev.agenticscheduler.database.openAndroidDatabase
import dev.agenticscheduler.database.repository.*
import dev.agenticscheduler.sync.*
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AgentConversationSyncControlsInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun explicitAcknowledgementAndOffPersistSeparatelyFromV2() = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(target.cacheDir, "d9-consent-ui-${UUID.randomUUID()}.db")
        val context = object : ContextWrapper(target) {
            override fun getApplicationContext() = this
            override fun getDatabasePath(name: String) = file
        }
        val db = openAndroidDatabase(context)
        val space = SyncSpaceId("ui-acceptance")
        try {
            val enrollments = RoomLocalEnrollmentRepository(db)
            enrollments.saveActive(LocalEnrollmentState.Active(AccountId("ui-account"), DeviceId("ui-device"), EnrollmentRequestId("ui-enrollment"),
                HpkePublicKeyBase64Url(encodeCanonicalBase64Url(ByteArray(32) { 1 })), SecretReference("ui-only://hpke"), space, SecretReference("ui-only://amk"), SecretReference("ui-only://credential")))
            val state = RoomAgentSyncTransportPersistence(db)
            state.setConversationConsent(space, false, false)
            val history = RoomAgentSyncPersistence(db)
            history.advanceBackfill(space, AgentSyncBackfillState(0, AgentSyncBackfillRecoveryState.INCOMPLETE, 1, 1))
            val settings = AgentConversationSyncSettings(enrollments, state, state, history)
            compose.setContent { MaterialTheme { AndroidConversationSyncControls(settings) } }
            compose.waitUntil(10000) { compose.onAllNodesWithTag("agent-conversation-toggle").fetchSemanticsNodes().size == 1 }
            compose.onNodeWithTag("agent-conversation-toggle").assertIsNotEnabled()
            compose.onNodeWithText("Conversation history is incomplete until retained history and keys are recovered.").assertExists()
            compose.onNodeWithTag("agent-conversation-v3-ack").performClick()
            compose.onNodeWithTag("agent-conversation-toggle").assertIsEnabled().performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("Turn conversation sync OFF").fetchSemanticsNodes().size == 1 }
            check(state.conversationConsent(space)); check(!RoomAgentStateRepository(db).syncAgentOriginEnabled(space))
            check(state.outboundRecords(space).isEmpty())
            compose.onNodeWithTag("agent-conversation-toggle").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("Explicitly enable conversation sync").fetchSemanticsNodes().size == 1 }
            check(!state.conversationConsent(space))
        } finally { db.close() }
        val reopened = openAndroidDatabase(context)
        try { check(!RoomAgentSyncTransportPersistence(reopened).conversationConsent(space)) } finally { reopened.close(); file.delete() }
    }
}
