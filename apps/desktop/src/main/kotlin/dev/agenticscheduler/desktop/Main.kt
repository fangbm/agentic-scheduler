package dev.agenticscheduler.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.agenticscheduler.domain.DomainModule

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Agentic Scheduler",
    ) {
        SchedulerDesktopShell()
    }
}

@Composable
private fun SchedulerDesktopShell() {
    MaterialTheme {
        Surface {
            Text("Agentic Scheduler — ${DomainModule.name}")
        }
    }
}

