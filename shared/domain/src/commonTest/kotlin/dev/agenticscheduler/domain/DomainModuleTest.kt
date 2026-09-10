package dev.agenticscheduler.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class DomainModuleTest {
    @Test
    fun exposesStableModuleIdentity() {
        assertEquals("agentic-scheduler-domain", DomainModule.name)
    }
}

