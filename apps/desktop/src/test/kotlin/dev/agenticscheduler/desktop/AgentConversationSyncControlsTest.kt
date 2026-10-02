package dev.agenticscheduler.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.agenticscheduler.application.sync.*
import dev.agenticscheduler.database.openDesktopDatabase
import dev.agenticscheduler.database.repository.*
import dev.agenticscheduler.sync.*
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import java.io.File

class AgentConversationSyncControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun explicitAcknowledgementAndOffPersistSeparatelyFromV2() = runBlocking {
        val file = File.createTempFile("conversation-ui-", ".db")
        val db = openDesktopDatabase(file.absolutePath)
        val space = SyncSpaceId("ui-acceptance")
        try {
            val enrollments = RoomLocalEnrollmentRepository(db)
            enrollments.saveActive(LocalEnrollmentState.Active(AccountId("ui-account"), DeviceId("ui-device"), EnrollmentRequestId("ui-enrollment"),
                HpkePublicKeyBase64Url(encodeCanonicalBase64Url(ByteArray(32) { 1 })), SecretReference("ui-only://hpke"), space, SecretReference("ui-only://amk"), SecretReference("ui-only://credential")))
            val state = RoomAgentSyncTransportPersistence(db)
            val history = RoomAgentSyncPersistence(db)
            history.advanceBackfill(space, AgentSyncBackfillState(0, AgentSyncBackfillRecoveryState.INCOMPLETE, 1, 1))
            val settings = AgentConversationSyncSettings(enrollments, state, state, history)
            compose.setContent { MaterialTheme { DesktopConversationSyncControls(settings) } }
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
            db.close()
            val reopened = openDesktopDatabase(file.absolutePath)
            try { check(!RoomAgentSyncTransportPersistence(reopened).conversationConsent(space)) } finally { reopened.close() }
        } finally { db.close(); file.delete() }
    }
}
