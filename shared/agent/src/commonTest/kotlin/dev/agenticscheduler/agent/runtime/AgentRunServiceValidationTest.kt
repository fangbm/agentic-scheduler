package dev.agenticscheduler.agent.runtime

import dev.agenticscheduler.agent.provider.ProviderFunctionCall
import dev.agenticscheduler.agent.provider.ProviderToolCall
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentRunServiceValidationTest {
    @Test fun `runtime distinguishes multiple calls from malformed call fields`() {
        val valid = ProviderToolCall("call-1", function = ProviderFunctionCall("task.list", "{}"))

        assertEquals(
            "MULTIPLE_TOOL_CALLS_UNSUPPORTED",
            invalidProviderToolCallCode(listOf(valid, valid.copy(id = "call-2"))),
        )
        listOf(
            valid.copy(id = ""),
            valid.copy(function = valid.function.copy(name = "")),
            valid.copy(function = valid.function.copy(arguments = "")),
        ).forEach { invalid ->
            assertEquals("INVALID_TOOL_CALL_FIELDS", invalidProviderToolCallCode(listOf(invalid)))
        }
        assertNull(invalidProviderToolCallCode(listOf(valid)))
        assertNull(invalidProviderToolCallCode(emptyList()))
    }
}
