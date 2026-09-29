package dev.agenticscheduler.desktop

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class PlannerDogfoodItemTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun preview_survives_calendar_item_insertion_above_the_panel() {
        compose.setContent {
            val calendarRows = remember { mutableStateOf(emptyList<Int>()) }
            LazyColumn {
                item(key = "insert-calendar-source") {
                    Button(
                        onClick = { calendarRows.value = calendarRows.value + calendarRows.value.size },
                        modifier = Modifier.testTag("insert-calendar-source"),
                    ) { Text("Insert calendar source") }
                }
                items(calendarRows.value, key = { it }) { Text("Calendar source $it") }
                plannerDogfoodItem {
                    val preview = remember { mutableStateOf(false) }
                    Button(
                        onClick = { preview.value = true },
                        modifier = Modifier.testTag("planner-full-replan"),
                    ) { Text("Full Replan preview") }
                    if (preview.value) Text("PlanBranch preview", modifier = Modifier.testTag("planner-preview"))
                }
            }
        }

        compose.onAllNodesWithTag("planner-preview").assertCountEquals(0)
        compose.onNodeWithTag("planner-full-replan").performClick()
        compose.onAllNodesWithTag("planner-preview").assertCountEquals(1)

        compose.onNodeWithTag("insert-calendar-source").performClick()

        compose.onAllNodesWithTag("planner-preview").assertCountEquals(1)
    }
}
